package com.xiaozhangben.ledger.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * Transaction persistence.
 *
 * Deliberately no SQL aggregation: totals are computed in the domain layer over
 * loaded rows. A personal ledger holds thousands of rows, not millions, and doing
 * the maths in Kotlin keeps every timezone/rounding/refund decision in one place
 * where it can be unit-tested — instead of split across SQL date functions that
 * silently disagree with `java.time`.
 */
@Dao
interface TransactionDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAllIgnore(rows: List<TransactionEntity>): List<Long>

    /** Used by restore, where the ids come from the backup and must be preserved. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAllReplace(rows: List<TransactionEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(row: TransactionEntity): Long

    @Update
    suspend fun update(row: TransactionEntity)

    @Query("SELECT * FROM transactions WHERE id = :id")
    suspend fun findById(id: String): TransactionEntity?

    @Query("SELECT * FROM transactions WHERE dedupeKey = :key LIMIT 1")
    suspend fun findByDedupeKey(key: String): TransactionEntity?

    @Query("SELECT dedupeKey FROM transactions WHERE dedupeKey IN (:keys)")
    suspend fun findExistingDedupeKeys(keys: List<String>): List<String>

    @Query(
        """
        SELECT * FROM transactions
        WHERE occurredAt >= :fromMillis AND occurredAt < :toExclusive
          AND deletedAt IS NULL
        ORDER BY occurredAt DESC, createdAt DESC
        """
    )
    fun observeInRange(fromMillis: Long, toExclusive: Long): Flow<List<TransactionEntity>>

    @Query(
        """
        SELECT * FROM transactions
        WHERE occurredAt >= :fromMillis AND occurredAt < :toExclusive
          AND deletedAt IS NULL
        ORDER BY occurredAt DESC, createdAt DESC
        """
    )
    suspend fun loadInRange(fromMillis: Long, toExclusive: Long): List<TransactionEntity>

    @Query("SELECT * FROM transactions WHERE deletedAt IS NULL ORDER BY occurredAt DESC, createdAt DESC")
    fun observeAll(): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions WHERE deletedAt IS NULL ORDER BY occurredAt DESC, createdAt DESC")
    suspend fun loadAll(): List<TransactionEntity>

    /** Rows the engine could not explain. Must stay visible; never folded into "其他". */
    @Query(
        """
        SELECT * FROM transactions
        WHERE deletedAt IS NULL
          AND excluded = 0
          AND direction = 'EXPENSE'
          AND categoryKey = :unclassifiedKey
        ORDER BY occurredAt DESC
        """
    )
    fun observeUnclassified(unclassifiedKey: String): Flow<List<TransactionEntity>>

    @Query(
        """
        SELECT COUNT(*) FROM transactions
        WHERE deletedAt IS NULL AND excluded = 0 AND direction = 'EXPENSE' AND categoryKey = :unclassifiedKey
        """
    )
    fun observeUnclassifiedCount(unclassifiedKey: String): Flow<Int>

    /** Fuzzy duplicate search for cross-channel de-duplication. */
    @Query(
        """
        SELECT * FROM transactions
        WHERE deletedAt IS NULL
          AND amountCents = :amountCents
          AND occurredAt BETWEEN :fromMillis AND :toMillis
        LIMIT 5
        """
    )
    suspend fun findNearDuplicates(amountCents: Long, fromMillis: Long, toMillis: Long): List<TransactionEntity>

    /**
     * Finds the payment a refund reverses: same amount, an expense, no more recent
     * than the refund itself, within the look-back window.
     */
    @Query(
        """
        SELECT * FROM transactions
        WHERE deletedAt IS NULL
          AND direction = 'EXPENSE'
          AND excluded = 0
          AND amountCents = :amountCents
          AND occurredAt <= :beforeMillis
          AND occurredAt >= :afterMillis
        ORDER BY occurredAt DESC
        LIMIT 1
        """
    )
    suspend fun findRefundOriginal(amountCents: Long, beforeMillis: Long, afterMillis: Long): TransactionEntity?

    @Query(
        """
        UPDATE transactions
        SET categoryKey = :categoryKey, kind = :kind, excluded = :excluded, excludeReason = :excludeReason,
            matchedRuleId = :ruleId, matchedRuleLabel = :ruleLabel, confidence = :confidence,
            userCorrected = 1, updatedAt = :now
        WHERE id = :id
        """
    )
    suspend fun applyClassification(
        id: String,
        categoryKey: String,
        kind: String,
        excluded: Boolean,
        excludeReason: String?,
        ruleId: Long?,
        ruleLabel: String?,
        confidence: Float,
        now: Long,
    )

    @Query("UPDATE transactions SET note = :note, updatedAt = :now WHERE id = :id")
    suspend fun updateNote(id: String, note: String?, now: Long)

    @Query("UPDATE transactions SET amountCents = :amountCents, note = :note, updatedAt = :now WHERE id = :id")
    suspend fun updateAmountAndNote(id: String, amountCents: Long, note: String?, now: Long)

    /**
     * Fills in fields the row does not already have, from a second sighting of the same
     * payment.
     *
     * A notification can never carry the merchant or the official transaction id; the
     * monthly bill can. Enriching the existing row is what turns "captured live but
     * unclassified" into "captured live, and named once the bill was imported" —
     * without storing the payment twice.
     *
     * `COALESCE` means an existing value is never overwritten, and the fingerprint is
     * upgraded to the id-based one so a re-import of the same file matches exactly.
     */
    @Query(
        """
        UPDATE transactions
        SET sourceTxnId = COALESCE(sourceTxnId, :sourceTxnId),
            counterpartyRaw = COALESCE(counterpartyRaw, :counterpartyRaw),
            counterparty = COALESCE(counterparty, :counterparty),
            productDesc = COALESCE(productDesc, :productDesc),
            remark = COALESCE(remark, :remark),
            payMethod = COALESCE(payMethod, :payMethod),
            status = COALESCE(status, :status),
            dedupeKey = :dedupeKey,
            updatedAt = :now
        WHERE id = :id
        """
    )
    suspend fun enrichFromBill(
        id: String,
        sourceTxnId: String?,
        counterpartyRaw: String?,
        counterparty: String?,
        productDesc: String?,
        remark: String?,
        payMethod: String?,
        status: String?,
        dedupeKey: String,
        now: Long,
    )

    /**
     * Re-runs automatic classification after a row gained new information, e.g. a
     * merchant name from an imported bill.
     *
     * The `userCorrected = 0` guard is enforced in SQL as well as in the caller: a
     * category the user set by hand must survive any later enrichment.
     */
    @Query(
        """
        UPDATE transactions
        SET categoryKey = :categoryKey, kind = :kind, excluded = :excluded, excludeReason = :excludeReason,
            matchedRuleId = :ruleId, matchedRuleLabel = :ruleLabel, confidence = :confidence,
            updatedAt = :now
        WHERE id = :id AND userCorrected = 0
        """
    )
    suspend fun autoReclassify(
        id: String,
        categoryKey: String,
        kind: String,
        excluded: Boolean,
        excludeReason: String?,
        ruleId: Long?,
        ruleLabel: String?,
        confidence: Float,
        now: Long,
    )

    @Query("UPDATE transactions SET pocketId = :pocketId, pocketAssignedBy = :assignedBy, updatedAt = :now WHERE id = :id")
    suspend fun assignPocket(id: String, pocketId: String?, assignedBy: String?, now: Long)

    @Query("UPDATE transactions SET refundOfTxnId = :originalId, updatedAt = :now WHERE id = :id")
    suspend fun linkRefund(id: String, originalId: String, now: Long)

    @Query("UPDATE transactions SET deletedAt = :now, updatedAt = :now WHERE id = :id")
    suspend fun softDelete(id: String, now: Long)

    @Query("UPDATE transactions SET deletedAt = NULL, updatedAt = :now WHERE id = :id")
    suspend fun restore(id: String, now: Long)

    @Query("DELETE FROM transactions WHERE importBatchId = :batchId")
    suspend fun deleteByImportBatch(batchId: String): Int

    @Query("DELETE FROM transactions")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM transactions WHERE deletedAt IS NULL")
    fun observeCount(): Flow<Int>
}

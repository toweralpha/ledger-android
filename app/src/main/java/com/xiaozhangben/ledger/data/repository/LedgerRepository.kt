package com.xiaozhangben.ledger.data.repository

import com.xiaozhangben.ledger.core.DateX
import com.xiaozhangben.ledger.core.LogX
import com.xiaozhangben.ledger.core.Money
import com.xiaozhangben.ledger.data.local.CaptureStatsDao
import com.xiaozhangben.ledger.data.local.CaptureStatsEntity
import com.xiaozhangben.ledger.data.local.CategoryDao
import com.xiaozhangben.ledger.data.local.CategoryEntity
import com.xiaozhangben.ledger.data.local.ImportBatchDao
import com.xiaozhangben.ledger.data.local.ImportBatchEntity
import com.xiaozhangben.ledger.data.local.TransactionDao
import com.xiaozhangben.ledger.data.local.TransactionEntity
import com.xiaozhangben.ledger.data.local.toDirection
import com.xiaozhangben.ledger.domain.bill.Dedupe
import com.xiaozhangben.ledger.domain.categorize.ClassifyInput
import com.xiaozhangben.ledger.domain.categorize.ClassifyResult
import com.xiaozhangben.ledger.domain.categorize.MerchantNormalizer
import com.xiaozhangben.ledger.domain.model.CategoryKeys
import com.xiaozhangben.ledger.domain.model.CategoryKind
import com.xiaozhangben.ledger.domain.model.ExcludeReason
import com.xiaozhangben.ledger.domain.model.TxDirection
import com.xiaozhangben.ledger.domain.model.TxSource
import kotlinx.coroutines.flow.Flow
import java.util.UUID

/** A row on its way into the ledger, from any of the three capture channels. */
data class NewTransaction(
    val source: TxSource,
    val direction: TxDirection,
    val occurredAt: Long,
    val amountCents: Long,
    val rawType: String? = null,
    val status: String? = null,
    val counterpartyRaw: String? = null,
    val productDesc: String? = null,
    val payMethod: String? = null,
    val sourceTxnId: String? = null,
    val merchantOrderId: String? = null,
    val remark: String? = null,
    val note: String? = null,
    val notificationKey: String? = null,
    val importBatchId: String? = null,
    /** Explicit fingerprint; manual entries use their own UUID so two equal amounts never collide. */
    val dedupeKeyOverride: String? = null,
)

sealed interface InsertOutcome {
    data class Inserted(val id: String, val classification: ClassifyResult) : InsertOutcome

    /** Already present. Kept as data, never silently dropped by the caller. */
    data class Duplicate(val existingId: String?, val reason: ExcludeReason) : InsertOutcome

    data class Rejected(val reason: String) : InsertOutcome
}

/**
 * The ledger's write path and its read models.
 *
 * Every write funnels through [insert] so that classification, de-duplication and
 * refund linking happen in exactly one place regardless of whether the row came
 * from a notification, a bill import or the manual keypad.
 */
class LedgerRepository(
    private val txDao: TransactionDao,
    private val categoryDao: CategoryDao,
    private val importBatchDao: ImportBatchDao,
    private val captureStatsDao: CaptureStatsDao,
    private val classifier: Classifier,
) {

    /** A refund of a purchase older than this is not offset against this month. */
    private val refundLookbackMillis = 90L * 24 * 60 * 60 * 1000

    // ---- reads -------------------------------------------------------------------

    fun observeCategories(): Flow<List<CategoryEntity>> = categoryDao.observeAll()

    suspend fun loadCategories(): List<CategoryEntity> = categoryDao.loadAll()

    fun observeInRange(fromMillis: Long, toExclusive: Long): Flow<List<TransactionEntity>> =
        txDao.observeInRange(fromMillis, toExclusive)

    fun observeAllTransactions(): Flow<List<TransactionEntity>> = txDao.observeAll()

    suspend fun loadInRange(fromMillis: Long, toExclusive: Long): List<TransactionEntity> =
        txDao.loadInRange(fromMillis, toExclusive)

    fun observeUnclassified(): Flow<List<TransactionEntity>> =
        txDao.observeUnclassified(CategoryKeys.UNCLASSIFIED)

    fun observeUnclassifiedCount(): Flow<Int> =
        txDao.observeUnclassifiedCount(CategoryKeys.UNCLASSIFIED)

    fun observeImportBatches(): Flow<List<ImportBatchEntity>> = importBatchDao.observeAll()

    fun observeCount(): Flow<Int> = txDao.observeCount()

    fun observeCaptureStats(): Flow<CaptureStatsEntity?> = captureStatsDao.observe()

    suspend fun captureStats(): CaptureStatsEntity? = captureStatsDao.get()

    suspend fun findById(id: String): TransactionEntity? = txDao.findById(id)

    /**
     * Classify without writing. Used by the bill-import preview so the user sees what
     * would happen to each row before a single one is stored.
     */
    fun classify(input: ClassifyInput): ClassifyResult = classifier.classify(input)

    // ---- writes ------------------------------------------------------------------

    /**
     * Single entry point for every captured, imported or hand-typed row.
     *
     * Order matters:
     *  1. reject nonsense (non-positive amount) instead of storing a zero
     *  2. classify, so the stored row already carries its category and the reason
     *  3. de-duplicate exactly (unique key) and then fuzzily across channels
     *  4. link a refund to the payment it reverses, so it offsets the right category
     */
    suspend fun insert(input: NewTransaction, now: Long = System.currentTimeMillis()): InsertOutcome {
        if (input.amountCents <= 0L) {
            return InsertOutcome.Rejected("金额必须大于 0")
        }

        val classification = classifier.classify(
            ClassifyInput(
                source = input.source,
                direction = input.direction,
                rawType = input.rawType,
                status = input.status,
                counterpartyRaw = input.counterpartyRaw,
                productDesc = input.productDesc,
                payMethod = input.payMethod,
            )
        )

        val id = UUID.randomUUID().toString()

        // A manual entry gets a UUID fingerprint. Using a content hash here would make
        // two genuine ¥32 purchases in the same minute look like one row, and the
        // second would be dropped without a trace.
        val dedupeKey = input.dedupeKeyOverride
            ?: if (input.source == TxSource.MANUAL) Dedupe.manualKey(id) else forInput(input)

        txDao.findByDedupeKey(dedupeKey)?.let {
            return InsertOutcome.Duplicate(it.id, ExcludeReason.DUPLICATE)
        }

        // The same payment reaching us twice: once from a notification and once from the
        // monthly bill export the user imports later.
        //
        // Manual rows are excluded from both sides. They are typed deliberately, so a ¥32
        // cash entry must not swallow a ¥32 WeChat payment made minutes later.
        if (input.source != TxSource.MANUAL) {
            val near = txDao.findNearDuplicates(
                amountCents = input.amountCents,
                fromMillis = input.occurredAt - Dedupe.FUZZY_WINDOW_MILLIS,
                toMillis = input.occurredAt + Dedupe.FUZZY_WINDOW_MILLIS,
            ).firstOrNull { candidate ->
                val candidateSource = candidate.source.toSource()
                if (candidateSource == TxSource.MANUAL || candidateSource == TxSource.UNKNOWN) {
                    return@firstOrNull false
                }
                // One payment happens in one app, so a row from the other app is a
                // different payment even at the same amount and minute.
                if (candidateSource != input.source) return@firstOrNull false
                Dedupe.looksLikeSamePayment(
                    amountA = candidate.amountCents,
                    timeA = candidate.occurredAt,
                    txnIdA = candidate.sourceTxnId,
                    amountB = input.amountCents,
                    timeB = input.occurredAt,
                    txnIdB = input.sourceTxnId,
                )
            }
            if (near != null) {
                // The same payment, seen again with more detail. A notification cannot
                // carry the merchant or the official transaction id; the bill can. So
                // enrich the row we already have rather than storing the payment twice —
                // the user ends up with one entry that also knows where the money went.
                txDao.enrichFromBill(
                    id = near.id,
                    sourceTxnId = input.sourceTxnId,
                    counterpartyRaw = input.counterpartyRaw,
                    counterparty = MerchantNormalizer.normalize(input.counterpartyRaw).ifBlank { null },
                    productDesc = input.productDesc,
                    remark = input.remark,
                    payMethod = input.payMethod,
                    status = input.status,
                    dedupeKey = dedupeKey,
                    now = now,
                )
                reclassifyIfStillUnowned(near, input, now)
                return InsertOutcome.Duplicate(near.id, ExcludeReason.DUPLICATE)
            }
        }

        var entity = buildEntity(input, classification, dedupeKey, id, now)

        // A refund is not income: point it at the payment it reverses.
        if (classification.excludeReason == ExcludeReason.REFUND) {
            txDao.findRefundOriginal(
                amountCents = input.amountCents,
                beforeMillis = input.occurredAt,
                afterMillis = input.occurredAt - refundLookbackMillis,
            )?.let { original ->
                entity = entity.copy(refundOfTxnId = original.id)
            }
        }

        val rowId = txDao.insertIgnore(entity)
        if (rowId == -1L) {
            // Lost a race against a concurrent capture of the same payment.
            return InsertOutcome.Duplicate(null, ExcludeReason.DUPLICATE)
        }
        classification.matchedRuleId?.let { classifier.bumpHit(it, now) }
        return InsertOutcome.Inserted(id, classification)
    }

    /**
     * Re-runs classification for a row that just gained information — typically the
     * merchant name from an imported bill.
     *
     * Without this, a payment captured from a notification that carried no payee would
     * sit in the triage queue for ever, even though the bill import knows exactly where
     * the money went. A hand-set category is never touched: `userCorrected` is checked
     * here and again in the SQL statement.
     */
    private suspend fun reclassifyIfStillUnowned(
        existing: TransactionEntity,
        input: NewTransaction,
        now: Long,
    ) {
        if (existing.userCorrected) return
        if (existing.categoryKey != CategoryKeys.UNCLASSIFIED) return

        val better = classifier.classify(
            ClassifyInput(
                source = existing.source.toSource(),
                direction = existing.direction.toDirection(),
                rawType = input.rawType ?: existing.rawType,
                status = input.status ?: existing.status,
                counterpartyRaw = input.counterpartyRaw ?: existing.counterpartyRaw,
                productDesc = input.productDesc ?: existing.productDesc,
                payMethod = input.payMethod ?: existing.payMethod,
            )
        )
        if (better.needsTriage) return

        txDao.autoReclassify(
            id = existing.id,
            categoryKey = better.categoryKey,
            kind = better.kind.name,
            excluded = better.excludeReason != null,
            excludeReason = better.excludeReason?.name,
            ruleId = better.matchedRuleId,
            ruleLabel = better.matchedRuleLabel,
            confidence = better.confidence,
            now = now,
        )
        better.matchedRuleId?.let { classifier.bumpHit(it, now) }
    }

    private fun buildEntity(
        input: NewTransaction,
        classification: ClassifyResult,
        dedupeKey: String,
        id: String,
        now: Long,
    ): TransactionEntity = TransactionEntity(
        id = id,
        source = input.source.name,
        direction = input.direction.name,
        occurredAt = input.occurredAt,
        amountCents = input.amountCents,
        categoryKey = classification.categoryKey.ifEmpty { CategoryKeys.UNCLASSIFIED },
        kind = classification.kind.name,
        excluded = classification.excludeReason != null,
        excludeReason = classification.excludeReason?.name,
        rawType = input.rawType,
        status = input.status,
        counterpartyRaw = input.counterpartyRaw,
        counterparty = MerchantNormalizer.normalize(input.counterpartyRaw).ifBlank { null },
        productDesc = input.productDesc,
        payMethod = input.payMethod,
        sourceTxnId = input.sourceTxnId,
        merchantOrderId = input.merchantOrderId,
        remark = input.remark,
        note = input.note,
        matchedRuleId = classification.matchedRuleId,
        matchedRuleLabel = classification.matchedRuleLabel,
        confidence = classification.confidence,
        userCorrected = false,
        importBatchId = input.importBatchId,
        notificationKey = input.notificationKey,
        dedupeKey = dedupeKey,
        createdAt = now,
        updatedAt = now,
    )

    private fun forInput(input: NewTransaction): String = Dedupe.keyFor(
        source = input.source,
        sourceTxnId = input.sourceTxnId,
        occurredAt = input.occurredAt,
        amountCents = input.amountCents,
        counterpartyRaw = input.counterpartyRaw,
        productDesc = input.productDesc,
    )

    /** Fast path used by the bill importer to skip rows it already has. */
    suspend fun existingDedupeKeys(keys: List<String>): Set<String> =
        if (keys.isEmpty()) emptySet() else txDao.findExistingDedupeKeys(keys).toHashSet()

    /**
     * Places a transaction into a daily slot (早饭 / 午饭 / …), or clears the placement.
     *
     * `pocketAssignedBy = USER` is what makes the choice stick: [DailySlotAssigner]
     * returns an explicit assignment before consulting any rule, so a later change to a
     * slot's time window or category can never move a row the user placed by hand.
     */
    suspend fun assignDailySlot(
        txnId: String,
        slotId: String?,
        now: Long = System.currentTimeMillis(),
    ) {
        txDao.assignPocket(txnId, slotId, if (slotId != null) ASSIGNED_BY_USER else null, now)
    }

    fun dayRange(
        epochMillis: Long,
        zone: java.time.ZoneId = java.time.ZoneId.systemDefault(),
    ): Pair<Long, Long> = DateX.startOfDayMillis(epochMillis, zone) to
        DateX.endOfDayExclusiveMillis(epochMillis, zone)

    fun dedupeKeyFor(input: NewTransaction): String = forInput(input)

    /**
     * Applies a user's category choice and remembers it as a rule.
     *
     * Marking the row `userCorrected` is what stops a later re-classification pass
     * from undoing a decision the user made deliberately.
     */
    suspend fun reclassify(
        id: String,
        categoryKey: String,
        kind: CategoryKind = CategoryKind.VARIABLE,
        excluded: Boolean = false,
        excludeReason: ExcludeReason? = null,
        learn: Boolean = true,
        now: Long = System.currentTimeMillis(),
    ) {
        val row = txDao.findById(id) ?: return
        txDao.applyClassification(
            id = id,
            categoryKey = categoryKey,
            kind = kind.name,
            excluded = excluded || excludeReason != null,
            excludeReason = excludeReason?.name,
            ruleId = null,
            ruleLabel = "手动指定",
            confidence = 1f,
            now = now,
        )
        if (learn && !excluded && excludeReason == null) {
            classifier.learnFromCorrection(row.counterpartyRaw, categoryKey, kind, now)
        }
    }

    suspend fun updateNote(id: String, note: String?, now: Long = System.currentTimeMillis()) {
        txDao.updateNote(id, note, now)
    }

    suspend fun updateAmountAndNote(
        id: String,
        amountCents: Long,
        note: String?,
        now: Long = System.currentTimeMillis(),
    ) {
        if (amountCents <= 0L) return
        txDao.updateAmountAndNote(id, amountCents, note, now)
    }

    suspend fun softDelete(id: String, now: Long = System.currentTimeMillis()) {
        txDao.softDelete(id, now)
    }

    suspend fun restore(id: String, now: Long = System.currentTimeMillis()) {
        txDao.restore(id, now)
    }

    suspend fun deleteImportBatch(batchId: String): Int = txDao.deleteByImportBatch(batchId)

    suspend fun recordImportBatch(batch: ImportBatchEntity) = importBatchDao.insert(batch)

    suspend fun latestImportBatch(): ImportBatchEntity? = importBatchDao.latest()

    suspend fun clearAllData(now: Long = System.currentTimeMillis()) {
        txDao.deleteAll()
        importBatchDao.deleteAll()
        captureStatsDao.upsert(CaptureStatsEntity(id = 1, lastCaptureAt = null))
    }

    // ---- capture diagnostics -----------------------------------------------------

    suspend fun onListenerConnected(now: Long = System.currentTimeMillis()) {
        captureStatsDao.recordListenerConnected(now)
    }

    suspend fun onListenerDisconnected(now: Long = System.currentTimeMillis()) {
        captureStatsDao.recordListenerDisconnected(now)
        LogX.d("Capture") { "listener disconnected by the system" }
    }

    suspend fun recordCapture(now: Long) = captureStatsDao.recordCapture(now)

    /**
     * Persists why a notification was refused.
     *
     * Only the reason code is stored — never the notification text — so this stays
     * within the privacy policy while giving the diagnostics screen something concrete
     * to show when capture is producing nothing.
     */
    suspend fun recordSkipped(reason: String, now: Long = System.currentTimeMillis()) {
        captureStatsDao.recordSkipped(now, reason)
    }

    /** True when nothing has been captured for [days] despite capture being enabled. */
    suspend fun looksSilent(days: Int = 3, now: Long = System.currentTimeMillis()): Boolean {
        val stats = captureStatsDao.get() ?: return true
        val last = stats.lastCaptureAt ?: return true
        return now - last > days * 24L * 60 * 60 * 1000
    }

    fun monthRange(yearMonth: java.time.YearMonth, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()):
        Pair<Long, Long> = DateX.startOfMonthMillis(yearMonth, zone) to
        DateX.endOfMonthExclusiveMillis(yearMonth, zone)

    fun formatAmount(cents: Long): String = Money.format(cents)

    private fun String.toSource(): TxSource =
        runCatching { TxSource.valueOf(this) }.getOrDefault(TxSource.UNKNOWN)

    private fun String.toDirectionSafe(): TxDirection = toDirection()

    companion object {
        const val ASSIGNED_BY_USER = "USER"
        const val ASSIGNED_BY_AUTO = "AUTO"
    }
}

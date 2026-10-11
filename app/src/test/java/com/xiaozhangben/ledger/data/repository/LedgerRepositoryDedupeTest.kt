package com.xiaozhangben.ledger.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.xiaozhangben.ledger.data.local.LedgerDatabase
import com.xiaozhangben.ledger.data.local.Seed
import com.xiaozhangben.ledger.domain.model.CategoryKeys
import com.xiaozhangben.ledger.domain.model.ExcludeReason
import com.xiaozhangben.ledger.domain.model.TxDirection
import com.xiaozhangben.ledger.domain.model.TxSource
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * De-duplication against a real database.
 *
 * The acceptance criterion is "import the same file twice and the second run inserts
 * zero rows", and "one payment must never be counted twice across channels". Both are
 * properties of the SQLite unique index plus the fuzzy match, so they need a real
 * database rather than a mock.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LedgerRepositoryDedupeTest {

    private lateinit var database: LedgerDatabase
    private lateinit var repository: LedgerRepository

    private val noon = 1_700_000_000_000L

    @Before
    fun setUp() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, LedgerDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        database.categoryDao().upsertAll(Seed.categories(0L))
        database.ruleDao().insertAll(Seed.rules(0L))
        val classifier = Classifier(database.ruleDao()).also { it.refresh() }
        repository = LedgerRepository(
            txDao = database.transactionDao(),
            categoryDao = database.categoryDao(),
            importBatchDao = database.importBatchDao(),
            captureStatsDao = database.captureStatsDao(),
            classifier = classifier,
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    /**
     * A row exactly as the notification listener builds one.
     *
     * The [notificationKey] is not decoration: every real notification row carries one
     * (it is `sbn.key`), and the cross-channel dedupe rule reads it to decide which
     * channel the row was born in. An earlier version of this helper omitted it, which
     * made the "notification" rows indistinguishable from manual ones and quietly
     * disabled the very rule these tests exist to check.
     */
    private fun notification(
        amountCents: Long = 3200L,
        source: TxSource = TxSource.WECHAT,
        counterparty: String? = "星巴克",
        at: Long = noon,
    ) = NewTransaction(
        source = source,
        direction = TxDirection.EXPENSE,
        occurredAt = at,
        amountCents = amountCents,
        counterpartyRaw = counterparty,
        notificationKey = "0|${source.packageName()}|$at|null|10387",
    )

    /** A row exactly as the bill importer builds one: an official id and no notification. */
    private fun billRow(
        txnId: String,
        amountCents: Long = 3200L,
        source: TxSource = TxSource.WECHAT,
        counterparty: String? = "星巴克",
        at: Long = noon,
    ) = NewTransaction(
        source = source,
        direction = TxDirection.EXPENSE,
        occurredAt = at,
        amountCents = amountCents,
        counterpartyRaw = counterparty,
        sourceTxnId = txnId,
    )

    private fun TxSource.packageName(): String = when (this) {
        TxSource.WECHAT -> "com.tencent.mm"
        TxSource.ALIPAY -> "com.eg.android.AlipayGphone"
        else -> "unknown"
    }

    private fun manual(amountCents: Long, at: Long, note: String? = null) = NewTransaction(
        source = TxSource.MANUAL,
        direction = TxDirection.EXPENSE,
        occurredAt = at,
        amountCents = amountCents,
        note = note,
    )

    @Test
    fun `the same notification twice is recorded once`() = runTest {
        val first = repository.insert(notification(), now = noon)
        val second = repository.insert(notification(), now = noon + 1_000L)

        assertTrue(first is InsertOutcome.Inserted)
        assertTrue(second is InsertOutcome.Duplicate)
        assertEquals(1, repository.observeCount().first())
    }

    @Test
    fun `two genuinely equal manual entries are both kept`() = runTest {
        // Both are ¥32 at the same instant. A content-hash fingerprint would make the
        // second look like a duplicate and drop it without a trace.
        val first = repository.insert(manual(3200L, noon), now = noon)
        val second = repository.insert(manual(3200L, noon), now = noon)

        assertTrue(first is InsertOutcome.Inserted)
        assertTrue(second is InsertOutcome.Inserted)
        assertEquals(2, repository.observeCount().first())
    }

    @Test
    fun `a manual entry does not swallow a payment made minutes later`() = runTest {
        // Recorded a ¥32 cash purchase, then paid ¥32 by WeChat a minute later: two
        // different purchases, and fuzzy cross-channel matching must not merge them.
        repository.insert(manual(3200L, noon), now = noon)
        val wechat = repository.insert(notification(3200L, at = noon + 60_000L), now = noon + 60_000L)

        assertTrue(wechat is InsertOutcome.Inserted)
        assertEquals(2, repository.observeCount().first())
    }

    @Test
    fun `a payment captured live is not recorded again when the bill is imported`() = runTest {
        // THE case that matters: pay with WeChat (captured immediately), then import the
        // WeChat bill at the end of the month. The bill row carries an official
        // transaction id, the notification row carries a notification key, and because
        // they are two different channels looking at one event they must collapse.
        val captured = repository.insert(
            notification(source = TxSource.WECHAT, at = noon),
            now = noon,
        )
        val imported = repository.insert(
            billRow(txnId = "4200001234202506030001", at = noon + 30_000L),
            now = noon + 30_000L,
        )

        assertTrue(captured is InsertOutcome.Inserted)
        assertTrue(imported is InsertOutcome.Duplicate)
        assertEquals(1, repository.observeCount().first())
    }

    @Test
    fun `two notifications of the same amount are two payments`() = runTest {
        // The rule that used to be wrong, and the bug it caused: every notification lacks
        // an official transaction id, so "at least one side has no id" was true for any
        // pair of notifications and two ¥32 purchases minutes apart were merged into one.
        // The second payment disappeared with no trace — the same class of silent loss as
        // a listener that never ran.
        val first = repository.insert(notification(at = noon), now = noon)
        val second = repository.insert(notification(at = noon + 120_000L), now = noon + 120_000L)

        assertTrue(first is InsertOutcome.Inserted)
        assertTrue(second is InsertOutcome.Inserted)
        assertEquals(2, repository.observeCount().first())
    }

    @Test
    fun `two identical purchases minutes apart from one app are both kept`() = runTest {
        // Two 8.00 coffees. Same app, same amount, three minutes apart, distinct
        // notifications — genuinely two payments.
        val first = repository.insert(notification(amountCents = 800L, at = noon), now = noon)
        val second = repository.insert(notification(amountCents = 800L, at = noon + 180_000L), now = noon + 180_000L)

        assertTrue(first is InsertOutcome.Inserted)
        assertTrue(second is InsertOutcome.Inserted)
        assertEquals(2, repository.observeCount().first())
    }

    @Test
    fun `two distinct bill rows with the same amount are both kept`() = runTest {
        // Two genuine ¥32 purchases a minute apart, each with its own transaction id.
        val first = repository.insert(billRow(txnId = "4200001111", at = noon), now = noon)
        val second = repository.insert(billRow(txnId = "4200002222", at = noon + 60_000L), now = noon + 60_000L)

        assertTrue(first is InsertOutcome.Inserted)
        assertTrue(second is InsertOutcome.Inserted)
        assertEquals(2, repository.observeCount().first())
    }

    @Test
    fun `the same amount on a different day is not a duplicate`() = runTest {
        repository.insert(notification(at = noon), now = noon)
        val later = repository.insert(notification(at = noon + 6 * 60 * 60 * 1000L), now = noon)

        assertTrue(later is InsertOutcome.Inserted)
    }

    @Test
    fun `a transfer capture is recorded but excluded from spending`() = runTest {
        // This is the WeChat transfer path: captured, carrying the 转账 type, and
        // therefore kept out of the budget rather than distorting it.
        val outcome = repository.insert(
            NewTransaction(
                source = TxSource.WECHAT,
                direction = TxDirection.EXPENSE,
                occurredAt = noon,
                amountCents = 1L,
                rawType = "转账",
            ),
            now = noon,
        )

        val inserted = outcome as InsertOutcome.Inserted
        val row = repository.findById(inserted.id)
        assertNotNull(row)
        assertTrue(row!!.excluded)
        assertEquals(ExcludeReason.TRANSFER.name, row.excludeReason)
        // Recorded, so it is visible in the ledger...
        assertEquals(1, repository.observeCount().first())
    }

    @Test
    fun `a refund is linked to the payment it reverses`() = runTest {
        val purchase = repository.insert(notification(amountCents = 9900L, at = noon), now = noon)
        val purchaseId = (purchase as InsertOutcome.Inserted).id

        val refund = repository.insert(
            NewTransaction(
                source = TxSource.WECHAT,
                direction = TxDirection.INCOME,
                occurredAt = noon + 86_400_000L,
                amountCents = 9900L,
                status = "已全额退款",
            ),
            now = noon + 86_400_000L,
        )

        val refundRow = repository.findById((refund as InsertOutcome.Inserted).id)
        assertEquals(ExcludeReason.REFUND.name, refundRow?.excludeReason)
        assertEquals(purchaseId, refundRow?.refundOfTxnId)
    }

    @Test
    fun `a zero amount is rejected rather than stored`() = runTest {
        val outcome = repository.insert(notification(amountCents = 0L), now = noon)
        assertTrue(outcome is InsertOutcome.Rejected)
        assertEquals(0, repository.observeCount().first())
    }

    @Test
    fun `capture statistics survive the lifecycle that used to fail silently`() = runTest {
        repository.onListenerConnected(noon)
        repository.insert(notification(), now = noon)
        repository.recordCapture(noon)
        repository.recordSkipped("没有找到金额", noon)
        repository.onListenerDisconnected(noon + 1_000L)

        val stats = repository.captureStats()
        assertNotNull(stats)
        assertEquals(noon, stats?.lastListenerConnectedAt)
        assertEquals(1, stats?.capturedCount)
        assertEquals(1, stats?.disconnectedCount)
        assertEquals("没有找到金额", stats?.lastSkipReason)
    }

    @Test
    fun `an unclassified payment lands in the triage queue rather than 其他`() = runTest {
        val outcome = repository.insert(
            NewTransaction(
                source = TxSource.WECHAT,
                direction = TxDirection.EXPENSE,
                occurredAt = noon,
                amountCents = 1500L,
                counterpartyRaw = "老王小卖部",
            ),
            now = noon,
        )
        val row = repository.findById((outcome as InsertOutcome.Inserted).id)
        assertEquals(CategoryKeys.UNCLASSIFIED, row?.categoryKey)
        assertTrue(!row!!.excluded)
    }

    @Test
    fun `a correction is remembered as a rule`() = runTest {
        val outcome = repository.insert(
            NewTransaction(
                source = TxSource.MANUAL,
                direction = TxDirection.EXPENSE,
                occurredAt = noon,
                amountCents = 1500L,
                counterpartyRaw = "老王小卖部",
            ),
            now = noon,
        )
        repository.reclassify((outcome as InsertOutcome.Inserted).id, CategoryKeys.SHOPPING, learn = true)

        val again = repository.insert(
            NewTransaction(
                source = TxSource.MANUAL,
                direction = TxDirection.EXPENSE,
                occurredAt = noon + 60_000L,
                amountCents = 800L,
                counterpartyRaw = "老王小卖部",
            ),
            now = noon + 60_000L,
        )
        val row = repository.findById((again as InsertOutcome.Inserted).id)
        assertEquals(CategoryKeys.SHOPPING, row?.categoryKey)
    }
}

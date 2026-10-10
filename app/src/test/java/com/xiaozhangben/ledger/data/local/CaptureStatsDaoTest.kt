package com.xiaozhangben.ledger.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression tests for the capture-diagnostics table.
 *
 * The shipped bug these exist for: `capturedCount` and `disconnectedCount` are NOT NULL
 * with no SQL default, and the hand-written upserts omitted them. Every call raised a
 * NOT NULL constraint failure, the listener's catch-all swallowed it, and the result was
 * a diagnostics panel that silently said "never captured anything" — the single most
 * misleading thing it could have said.
 *
 * Room can only be tested against real SQLite, which is why Robolectric is here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CaptureStatsDaoTest {

    private lateinit var database: LedgerDatabase
    private lateinit var dao: CaptureStatsDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, LedgerDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.captureStatsDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `recording a listener connection works from an empty table`() = runTest {
        assertNull(dao.get())

        dao.recordListenerConnected(1_000L)

        val stats = dao.get()
        assertEquals(1_000L, stats?.lastListenerConnectedAt)
        assertEquals(0, stats?.capturedCount)
        assertEquals(0, stats?.disconnectedCount)
        assertEquals(1, stats?.id)
    }

    @Test
    fun `recording a capture works from an empty table`() = runTest {
        dao.recordCapture(2_000L)

        val stats = dao.get()
        assertEquals(2_000L, stats?.lastCaptureAt)
        assertEquals(1, stats?.capturedCount)
        assertEquals(0, stats?.disconnectedCount)
    }

    @Test
    fun `capture count accumulates and the timestamp advances`() = runTest {
        dao.recordCapture(1_000L)
        dao.recordCapture(2_000L)
        dao.recordCapture(3_000L)

        val stats = dao.get()
        assertEquals(3, stats?.capturedCount)
        assertEquals(3_000L, stats?.lastCaptureAt)
    }

    @Test
    fun `recording a disconnect works from an empty table and accumulates`() = runTest {
        dao.recordListenerDisconnected(1_000L)
        dao.recordListenerDisconnected(2_000L)

        val stats = dao.get()
        assertEquals(2, stats?.disconnectedCount)
        assertEquals(2_000L, stats?.lastDisconnectedAt)
        assertEquals(0, stats?.capturedCount)
    }

    @Test
    fun `a skip reason is stored without disturbing the counters`() = runTest {
        dao.recordCapture(1_000L)
        dao.recordSkipped(2_000L, "没有找到金额")

        val stats = dao.get()
        assertEquals(1, stats?.capturedCount)
        assertEquals("没有找到金额", stats?.lastSkipReason)
        assertEquals(2_000L, stats?.lastSkippedAt)
        assertEquals(1_000L, stats?.lastCaptureAt)
    }

    @Test
    fun `the full capture lifecycle never throws`() = runTest {
        // Exercises every hand-written upsert in sequence: the exact path that was broken.
        dao.recordListenerConnected(1_000L)
        dao.recordCapture(2_000L)
        dao.recordSkipped(2_500L, "不像支付通知（营销活动或提醒）")
        dao.recordCapture(3_000L)
        dao.recordListenerDisconnected(4_000L)
        dao.recordListenerConnected(5_000L)

        val stats = dao.get()
        assertEquals(2, stats?.capturedCount)
        assertEquals(1, stats?.disconnectedCount)
        assertEquals(5_000L, stats?.lastListenerConnectedAt)
        assertEquals(3_000L, stats?.lastCaptureAt)
    }

    @Test
    fun `upserting a whole entity still works for the backup path`() = runTest {
        dao.recordCapture(1_000L)
        dao.upsert(
            CaptureStatsEntity(
                id = 1,
                lastCaptureAt = 9_000L,
                capturedCount = 42,
                disconnectedCount = 3,
                lastSkipReason = "没有找到金额",
            )
        )

        val stats = dao.get()
        assertEquals(42, stats?.capturedCount)
        assertEquals(3, stats?.disconnectedCount)
        assertEquals(9_000L, stats?.lastCaptureAt)
    }
}

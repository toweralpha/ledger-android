package com.xiaozhangben.ledger.capture

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The health verdict behind the "capture has stopped" warning.
 *
 * The bug this guards against is subtle and expensive: the app asked only whether
 * notification access was *granted*, which stayed true while the system had stopped the
 * listener service. Purchases were silently not recorded for two days and nothing on
 * screen hinted at it.
 */
class CaptureHealthTest {

    private val now = 1_800_000_000_000L
    private val hour = 60 * 60 * 1000L

    private fun health(
        enabled: Boolean = true,
        authorised: Boolean = true,
        connected: Boolean = true,
        seen: Long? = now - hour,
        connectedAt: Long? = now - hour,
    ) = CaptureHealthEvaluator.evaluate(
        captureEnabled = enabled,
        authorised = authorised,
        connected = connected,
        lastNotificationSeenAt = seen,
        lastListenerConnectedAt = connectedAt,
        now = now,
    )

    @Test
    fun `granted but not bound is reported as not connected`() {
        // THE field failure: permission intact, service not running, nothing recorded.
        assertEquals(CaptureHealth.NOT_CONNECTED, health(authorised = true, connected = false))
    }

    @Test
    fun `not connected wins over every time-based heuristic`() {
        // Even with a fresh heartbeat and a fresh connection timestamp, an unbound
        // service is a fact, not a guess, and must take priority.
        assertEquals(
            CaptureHealth.NOT_CONNECTED,
            health(connected = false, seen = now - 1000L, connectedAt = now - 1000L),
        )
    }

    @Test
    fun `turning capture off is not a fault`() {
        assertEquals(CaptureHealth.OFF, health(enabled = false, connected = false))
    }

    @Test
    fun `revoked access is reported as unauthorised`() {
        assertEquals(CaptureHealth.NOT_AUTHORISED, health(authorised = false, connected = false))
    }

    @Test
    fun `a healthy listener is OK`() {
        assertEquals(CaptureHealth.OK, health())
        assertEquals(CaptureHealth.OK, health(seen = now - 47 * hour))
    }

    @Test
    fun `bound but silent for two days is worth mentioning`() {
        assertEquals(CaptureHealth.NOT_RECEIVING, health(seen = now - 49 * hour))
    }

    @Test
    fun `never having seen a notification falls back to when we connected`() {
        // A fresh install has no heartbeat yet. Once it has been bound for two days
        // without a single notification from WeChat, that is suspicious.
        assertEquals(CaptureHealth.OK, health(seen = null, connectedAt = now - hour))
        assertEquals(CaptureHealth.NOT_RECEIVING, health(seen = null, connectedAt = now - 49 * hour))
    }

    @Test
    fun `a brand new install with no history is not warned about`() {
        assertEquals(CaptureHealth.OK, health(seen = null, connectedAt = null))
    }

    @Test
    fun `the silence threshold is generous enough to survive a quiet weekend`() {
        // A false alarm trains the user to ignore the warning, which is the failure mode
        // this whole class exists to prevent.
        assertEquals(CaptureHealth.OK, health(seen = now - 47 * hour))
        assertEquals(CaptureHealth.NOT_RECEIVING, health(seen = now - 49 * hour))
    }
}

package com.xiaozhangben.ledger.domain.bill

import com.xiaozhangben.ledger.domain.model.TxSource
import java.security.MessageDigest

/**
 * Duplicate detection across the capture channels.
 *
 * Three ways the same real payment reaches the ledger:
 *
 *  1. a payment notification (no official transaction id)
 *  2. the monthly bill export (carries a transaction id)
 *  3. a hand-typed row
 *
 * Double counting is worse than missing a row: the user believes the total and cannot
 * see why it is wrong. Two levels of defence:
 *
 *  - [keyFor] is the exact fingerprint, enforced by a UNIQUE index in SQLite, so
 *    re-importing the same file is idempotent.
 *  - [looksLikeSamePayment] compares rows that necessarily have different fingerprints —
 *    a notification and a bill entry for the same purchase, or the two notifications
 *    one payment app may post.
 */
object Dedupe {

    /**
     * How far apart two captures of the same payment may be. A bill's timestamp and the
     * notification's timestamp for one payment differ by seconds; three minutes is
     * generous without being loose enough to merge two separate purchases.
     */
    const val FUZZY_WINDOW_MILLIS = 180_000L

    fun keyFor(
        source: TxSource,
        sourceTxnId: String?,
        occurredAt: Long,
        amountCents: Long,
        counterpartyRaw: String?,
        productDesc: String?,
    ): String {
        val id = sourceTxnId?.trim()
        if (!id.isNullOrEmpty()) return "${source.name}:id:$id"
        val fingerprint = listOf(
            source.name,
            occurredAt.toString(),
            amountCents.toString(),
            counterpartyRaw?.trim().orEmpty(),
            productDesc?.trim().orEmpty(),
        ).joinToString("|")
        return "${source.name}:h:${sha256Hex(fingerprint)}"
    }

    /** Fingerprint for a manually entered row: unique by construction. */
    fun manualKey(id: String): String = "MANUAL:id:$id"

    /**
     * Which channel a row was born in.
     *
     * The distinction that matters is *how the row came to exist*, not what it currently
     * carries: a row captured from a notification keeps its `notificationKey` even after
     * a bill import enriches it with a transaction id.
     */
    enum class CaptureChannel { NOTIFICATION, BILL, OTHER }

    fun channelOf(notificationKey: String?, sourceTxnId: String?): CaptureChannel = when {
        !notificationKey.isNullOrBlank() -> CaptureChannel.NOTIFICATION
        !sourceTxnId.isNullOrBlank() -> CaptureChannel.BILL
        else -> CaptureChannel.OTHER
    }

    /**
     * True when two rows describe one payment.
     *
     * Two conditions: the same amount, captured within [FUZZY_WINDOW_MILLIS]; **and the
     * two rows came from different channels**.
     *
     * The channel rule is the whole point, and getting it wrong has cost a real bug in
     * both directions:
     *
     *  - An earlier version required different *apps*, which excluded the most common
     *    duplicate of all — a payment captured live from WeChat and then appearing again
     *    in the WeChat bill — so a monthly import re-recorded everything captured live.
     *  - The next version keyed off "does this row have an official transaction id?",
     *    which is true for *every* notification (a notification cannot carry one). Two
     *    genuine ¥8.00 coffees three minutes apart were therefore merged into one, losing
     *    the second payment with no visible trace.
     *
     * A notification and a bill entry are two *views* of one payment, so they may be the
     * same event. Two notifications, or two bill rows, are two separate events — re-posts
     * of a single notification are handled upstream by `sbn.key`, not here.
     */
    fun looksLikeSamePayment(
        amountA: Long,
        timeA: Long,
        notificationKeyA: String?,
        txnIdA: String?,
        amountB: Long,
        timeB: Long,
        notificationKeyB: String?,
        txnIdB: String?,
    ): Boolean {
        if (amountA != amountB) return false
        if (kotlin.math.abs(timeA - timeB) > FUZZY_WINDOW_MILLIS) return false

        val channelA = channelOf(notificationKeyA, txnIdA)
        val channelB = channelOf(notificationKeyB, txnIdB)
        if (channelA == CaptureChannel.OTHER || channelB == CaptureChannel.OTHER) return false
        // Same channel means two distinct events. Do not merge: dropping a real payment
        // is invisible to the user, while a duplicate at least shows up in the list.
        return channelA != channelB
    }

    fun sha256Hex(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) {
            val v = b.toInt() and 0xFF
            sb.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
        }
        return sb.toString()
    }

    private val HEX = "0123456789abcdef".toCharArray()
}

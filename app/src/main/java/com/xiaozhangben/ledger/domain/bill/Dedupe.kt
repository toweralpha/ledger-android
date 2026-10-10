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
     * True when two rows describe one payment.
     *
     * All three conditions must hold:
     *
     *  1. the same amount;
     *  2. captured within [FUZZY_WINDOW_MILLIS] of each other;
     *  3. **at least one side has no official transaction id** — i.e. at least one of them
     *     came from a notification, which cannot carry one.
     *
     * Condition 3 is the whole point. An earlier version required the two rows to come
     * from *different* apps, which excluded the single most common duplicate: a payment
     * captured live from WeChat, then appearing again in the WeChat bill that the user
     * imports at the end of the month. That would have double-counted every
     * realtime-captured purchase. Two rows that each carry their own distinct bill
     * transaction id are, by definition, two different payments.
     */
    fun looksLikeSamePayment(
        amountA: Long,
        timeA: Long,
        txnIdA: String?,
        amountB: Long,
        timeB: Long,
        txnIdB: String?,
    ): Boolean {
        if (amountA != amountB) return false
        if (kotlin.math.abs(timeA - timeB) > FUZZY_WINDOW_MILLIS) return false
        val aHasId = !txnIdA.isNullOrBlank()
        val bHasId = !txnIdB.isNullOrBlank()
        return !(aHasId && bHasId)
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

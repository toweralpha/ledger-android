package com.xiaozhangben.ledger.core

/**
 * State machine behind the "记一笔" keypad.
 *
 * Pure and therefore testable: the amount keypad is the single most-used surface in
 * the app, and an off-by-one-cent bug there is both easy to introduce and hard to
 * notice. Supports the running total people actually want when entering a bill split
 * across several items ("12+8.5").
 */
data class AmountInput(
    /** Sum of the terms already confirmed with "+". */
    val committedCents: Long = 0L,
    /** The term currently being typed. Empty means "nothing typed yet". */
    val current: String = "",
    /** True after "+", so the display keeps showing the committed part. */
    val plusPending: Boolean = false,
) {

    val isEmpty: Boolean get() = current.isEmpty() && committedCents == 0L && !plusPending

    /** Value in cents, including the term in progress. */
    val valueCents: Long
        get() = committedCents + (Money.parseToCentsOrNull(current) ?: 0L)

    /** What the big number shows. */
    val display: String
        get() {
            val head = if (committedCents > 0L || plusPending) {
                Money.format(committedCents, withSymbol = false) + "+"
            } else {
                ""
            }
            return head + if (current.isEmpty()) (if (plusPending) "" else "0") else current
        }

    fun digit(d: Char): AmountInput {
        require(d.isDigit())
        if (current.length >= MAX_DIGITS) return this
        val next = when {
            current.isEmpty() -> d.toString()
            current == "0" -> d.toString()
            else -> current + d
        }
        return copy(current = next, plusPending = false)
    }

    fun dot(): AmountInput {
        if (current.contains('.')) return this
        val next = if (current.isEmpty()) "0." else "$current."
        // Refuse more than two decimal places being entered via dot spam.
        if (next.length > MAX_DIGITS + 3) return this
        return copy(current = next, plusPending = false)
    }

    fun plus(): AmountInput {
        val term = Money.parseToCentsOrNull(current)
        return if (term == null) {
            // "+" with nothing typed is a no-op rather than a reset.
            if (committedCents > 0L) copy(plusPending = true) else this
        } else {
            copy(committedCents = committedCents + term, current = "", plusPending = true)
        }
    }

    fun backspace(): AmountInput = when {
        current.isNotEmpty() -> copy(current = current.dropLast(1))
        // Stepping back past a "+" undoes the addition instead of clearing everything.
        plusPending || committedCents > 0L -> AmountInput()
        else -> this
    }

    fun clear(): AmountInput = AmountInput()

    /** Drops a trailing "." that would otherwise parse as a whole number. */
    fun normalized(): AmountInput = copy(current = current.trimEnd('.'))

    /**
     * Rejects values that cannot be stored. Guards against a fat-fingered entry
     * silently becoming the month's biggest expense.
     */
    fun validationError(): String? {
        val cents = valueCents
        return when {
            cents <= 0L -> "请输入金额"
            cents > Money.MAX_TRANSACTION_CENTS -> "单笔金额上限为 ¥1,000,000"
            else -> null
        }
    }

    companion object {
        const val MAX_DIGITS = 8
    }
}

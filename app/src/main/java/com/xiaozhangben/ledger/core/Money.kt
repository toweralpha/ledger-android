package com.xiaozhangben.ledger.core

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Money is always a [Long] count of **cents** (1 CNY = 100 cents).
 *
 * Why: binary floating point cannot represent 0.1 exactly, so `12.34 * 100`
 * evaluates to 1233.9999999999998. Every rounding error of that kind silently
 * corrupts a budget, and the error compounds over a month of rows.
 * `Double`/`Float` must therefore never participate in money arithmetic,
 * parsing or accumulation anywhere in this codebase.
 */
object Money {

    const val CENTS_PER_YUAN = 100L

    /**
     * Sanity bound on a single entry (¥1,000,000). A mistyped amount must not silently
     * become the month's largest expense and wreck every figure derived from it.
     */
    const val MAX_TRANSACTION_CENTS = 100_000_000L

    /** Keeps digits, the decimal point and a sign. Strips ¥ ￥ , spaces, 元, etc. */
    private val NON_NUMERIC = Regex("[^0-9.+-]")

    /**
     * Parses a bill amount such as `"¥1,234.50"`, `"12.34"`, `"12.34元"`, `"-8"`.
     *
     * Returns `null` when the value is malformed so callers can report the row as
     * unparseable. Returning 0 here would be worse than failing: a silent zero
     * looks like a legitimate row and disappears into the ledger.
     */
    fun parseToCentsOrNull(raw: String?): Long? {
        if (raw == null) return null
        var s = NON_NUMERIC.replace(raw, "").trim()
        if (s.isEmpty()) return null

        val negative = s.startsWith("-")
        s = s.removePrefix("-").removePrefix("+").trim()
        if (s.isEmpty() || s == ".") return null
        // "1.2.3" and "1,234" leftovers are malformed, not a guessable number.
        if (s.count { it == '.' } > 1) return null
        if (s.count { it == '-' || it == '+' } > 0) return null

        val value = try {
            BigDecimal(s)
        } catch (_: NumberFormatException) {
            return null
        }
        val cents = try {
            value.movePointRight(2).setScale(0, RoundingMode.HALF_UP)
        } catch (_: ArithmeticException) {
            return null
        }
        return try {
            (if (negative) cents.negate() else cents).longValueExact()
        } catch (_: ArithmeticException) {
            null
        }
    }

    fun parseToCents(raw: String?, fallback: Long = 0L): Long = parseToCentsOrNull(raw) ?: fallback

    fun yuanToCents(yuan: Long): Long = yuan * CENTS_PER_YUAN

    fun centsToYuan(cents: Long): BigDecimal = BigDecimal(cents).movePointLeft(2)

    /** `1234` -> `"¥12.34"`. Two decimals, always. */
    fun format(cents: Long, withSymbol: Boolean = true, grouping: Boolean = true): String {
        val negative = cents < 0
        val abs = if (negative) -cents else cents
        val yuan = abs / CENTS_PER_YUAN
        val frac = (abs % CENTS_PER_YUAN).toInt()
        val intPart = if (grouping) group(yuan) else yuan.toString()
        val body = "$intPart.${frac.toString().padStart(2, '0')}"
        val sign = if (negative) "-" else ""
        return if (withSymbol) "$sign¥$body" else "$sign$body"
    }

    /**
     * For headline numbers where a trailing `.00` is noise: `183200` -> `"1,832"`,
     * `183250` -> `"1,832.50"`.
     */
    fun formatCompact(cents: Long, withSymbol: Boolean = true): String {
        val negative = cents < 0
        val abs = if (negative) -cents else cents
        return if (abs % CENTS_PER_YUAN == 0L) {
            val sign = if (negative) "-" else ""
            val body = group(abs / CENTS_PER_YUAN)
            if (withSymbol) "$sign¥$body" else "$sign$body"
        } else {
            format(cents, withSymbol = withSymbol)
        }
    }

    /** Machine-readable form for CSV export: no symbol, no grouping, 2 decimals. */
    fun toPlainString(cents: Long): String = centsToYuan(cents).setScale(2, RoundingMode.UNNECESSARY).toPlainString()

    /** Parses user keyboard input such as `12`, `12.3`, `12.34`, `1,234.5` into cents. */
    fun parseUserInputToCents(input: String): Long? {
        val t = input.trim()
        if (t.isEmpty()) return null
        return parseToCentsOrNull(t)
    }

    private fun group(value: Long): String {
        val digits = value.toString()
        if (digits.length <= 3) return digits
        val sb = StringBuilder()
        var count = 0
        for (i in digits.lastIndex downTo 0) {
            sb.append(digits[i])
            count++
            if (count % 3 == 0 && i != 0) sb.append(',')
        }
        return sb.reverse().toString()
    }
}

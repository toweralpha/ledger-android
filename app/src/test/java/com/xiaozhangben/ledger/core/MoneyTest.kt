package com.xiaozhangben.ledger.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Money parsing is where a ledger quietly goes wrong, so the awkward inputs are
 * asserted explicitly rather than assumed.
 */
class MoneyTest {

    @Test
    fun `parses plain and decorated amounts`() {
        assertEquals(1234L, Money.parseToCentsOrNull("12.34"))
        assertEquals(1234L, Money.parseToCentsOrNull("¥12.34"))
        assertEquals(1234L, Money.parseToCentsOrNull("￥12.34"))
        assertEquals(123456L, Money.parseToCentsOrNull("¥1,234.56"))
        assertEquals(123456L, Money.parseToCentsOrNull("1,234.56"))
        assertEquals(1234L, Money.parseToCentsOrNull("12.34元"))
        assertEquals(1L, Money.parseToCentsOrNull("0.01"))
        assertEquals(100000000L, Money.parseToCentsOrNull("1000000.00"))
        assertEquals(3200L, Money.parseToCentsOrNull(" 32.00 "))
    }

    @Test
    fun `the classic float bug does not happen`() {
        // 12.34 * 100 in floating point is 1233.9999999999998.
        assertEquals(1234L, Money.parseToCentsOrNull("12.34"))
        // 1.005 rounds half up to 101 cents, not 100.
        assertEquals(101L, Money.parseToCentsOrNull("1.005"))
        assertEquals(29L, Money.parseToCentsOrNull("0.29"))
        assertEquals(7000L, Money.parseToCentsOrNull("70.00"))
    }

    @Test
    fun `rejects malformed input instead of guessing zero`() {
        assertNull(Money.parseToCentsOrNull(null))
        assertNull(Money.parseToCentsOrNull(""))
        assertNull(Money.parseToCentsOrNull("   "))
        assertNull(Money.parseToCentsOrNull("abc"))
        assertNull(Money.parseToCentsOrNull("1.2.3"))
        assertNull(Money.parseToCentsOrNull("."))
        assertNull(Money.parseToCentsOrNull("-"))
        assertNull(Money.parseToCentsOrNull("1-2"))
        assertNull(Money.parseToCentsOrNull("12..34"))
    }

    @Test
    fun `negative amounts keep their sign`() {
        assertEquals(-1234L, Money.parseToCentsOrNull("-12.34"))
        assertEquals(-1234L, Money.parseToCentsOrNull("-¥12.34"))
    }

    @Test
    fun `formats with grouping and two decimals`() {
        assertEquals("¥12.34", Money.format(1234L))
        assertEquals("¥1,234.56", Money.format(123456L))
        assertEquals("-¥1,234.56", Money.format(-123456L))
        assertEquals("¥0.01", Money.format(1L))
        assertEquals("¥0.00", Money.format(0L))
        assertEquals("1234.56", Money.toPlainString(123456L))
    }

    @Test
    fun `compact formatting drops a meaningless point zero zero`() {
        assertEquals("¥1,832", Money.formatCompact(183200L))
        assertEquals("¥1,832.50", Money.formatCompact(183250L))
        assertEquals("¥0", Money.formatCompact(0L))
    }
}

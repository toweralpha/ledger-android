package com.xiaozhangben.ledger.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The amount keypad is the most-used surface in the app, and an off-by-one-cent bug
 * here is both easy to introduce and easy to miss. These cases pin the behaviour down.
 */
class AmountInputTest {

    private fun type(text: String): AmountInput =
        text.fold(AmountInput()) { acc, c -> if (c == '.') acc.dot() else acc.digit(c) }

    @Test
    fun `typing digits builds an amount in yuan`() {
        // The keypad takes yuan: "1234" is ¥1234, i.e. 123400 cents.
        assertEquals(123400L, type("1234").valueCents)
        assertEquals(100L, type("1").valueCents)
        assertEquals(0L, AmountInput().valueCents)
    }

    @Test
    fun `a decimal point is optional and only one is accepted`() {
        assertEquals(1234L, type("12.34").valueCents)
        assertEquals(1200L, type("12.").valueCents)
        // A second dot must not corrupt the value.
        assertEquals(1234L, type("12.3.4").valueCents)
    }

    @Test
    fun `a leading dot becomes zero point`() {
        assertEquals(50L, type(".5").valueCents)
        assertEquals("0.5", type(".5").display)
    }

    @Test
    fun `a leading zero is replaced rather than concatenated`() {
        val result = AmountInput().digit('0').digit('5')
        assertEquals(500L, result.valueCents)
        assertEquals("5", result.display)
    }

    @Test
    fun `plus accumulates a running total`() {
        // 12 + 8.5 = 20.5
        var input = type("12")
        input = input.plus()
        input = input.digit('8').dot().digit('5')
        assertEquals(2050L, input.valueCents)
        assertEquals("12.00+8.5", input.display)
    }

    @Test
    fun `plus with nothing typed is a no-op`() {
        val input = AmountInput().plus()
        assertEquals(0L, input.valueCents)
    }

    @Test
    fun `repeated plus keeps the committed sum`() {
        var input = type("10").plus().let { it.digit('5') }.plus()
        input = input.digit('2').plus()
        assertEquals(1700L, input.valueCents)
    }

    @Test
    fun `backspace removes the last character`() {
        assertEquals(1200L, type("123").backspace().valueCents)
        assertEquals(0L, type("1").backspace().valueCents)
    }

    @Test
    fun `backspace past a plus undoes the whole addition`() {
        // Stepping back over "+" should clear, not leave the user stuck with a
        // committed subtotal they cannot see how to remove.
        val input = type("12").plus().backspace()
        assertEquals(0L, input.valueCents)
        assertEquals(0L, input.committedCents)
    }

    @Test
    fun `trailing dot is dropped before saving`() {
        assertEquals(1200L, type("12.").normalized().valueCents)
        assertNull(type("12.").normalized().validationError())
    }

    @Test
    fun `an empty or zero amount is rejected`() {
        assertNotNull(AmountInput().validationError())
        assertNotNull(type("0").validationError())
        assertNotNull(type("0.00").validationError())
        assertNull(type("0.01").validationError())
    }

    @Test
    fun `an absurd amount is rejected rather than becoming the month's biggest expense`() {
        val huge = type("99999999")
        assertNotNull(huge.validationError())
        assertEquals("单笔金额上限为 ¥1,000,000", huge.validationError())
    }

    @Test
    fun `digit count is capped`() {
        val long = type("12345678901234")
        assertEquals(AmountInput.MAX_DIGITS, long.current.length)
    }

    @Test
    fun `display shows zero when nothing has been entered`() {
        assertEquals("0", AmountInput().display)
    }
}

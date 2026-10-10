package com.xiaozhangben.ledger.domain.budget

import com.xiaozhangben.ledger.core.DateX
import com.xiaozhangben.ledger.domain.model.CategoryKeys
import com.xiaozhangben.ledger.domain.model.CategoryKind
import com.xiaozhangben.ledger.domain.model.TxDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Where a payment lands on the 每日 page.
 *
 * This is the logic that decides whether ¥15 spent at 12:30 was lunch or breakfast, so
 * the tests are mostly about *not* guessing: two slots claiming 餐饮 must fall through to
 * the time window, and anything still ambiguous has to come back as `null` so the user is
 * asked rather than quietly mis-filed.
 */
class DailySlotAssignerTest {

    private val zone: ZoneId = ZoneId.systemDefault()

    private fun at(hour: Int, minute: Int = 0): Long =
        LocalDateTime.of(2025, 6, 10, hour, minute).atZone(zone).toInstant().toEpochMilli()

    private fun slot(
        id: String,
        name: String,
        amount: Long = 2_000L,
        category: String? = CategoryKeys.FOOD,
        start: Int? = null,
        end: Int? = null,
        sortOrder: Int = 0,
    ) = PocketLite(
        id = id,
        name = name,
        dailyAmountCents = amount,
        categoryKey = category,
        startMinute = start,
        endMinute = end,
        sortOrder = sortOrder,
    )

    private fun txn(
        category: String = CategoryKeys.FOOD,
        occurredAt: Long = at(12, 30),
        pocketId: String? = null,
    ) = TxnLite(
        id = "t",
        occurredAt = occurredAt,
        amountCents = 1_500L,
        direction = TxDirection.EXPENSE,
        categoryKey = category,
        kind = CategoryKind.VARIABLE,
        pocketId = pocketId,
    )

    private val mealSlots = listOf(
        slot("breakfast", "早饭", start = 5 * 60, end = 10 * 60, sortOrder = 0),
        slot("lunch", "午饭", start = 10 * 60, end = 15 * 60, sortOrder = 1),
        slot("dinner", "晚饭", start = 15 * 60, end = 24 * 60, sortOrder = 2),
    )

    @Test
    fun `a payment at 12-30 goes to lunch, not to whichever meal sorts first`() {
        // All three slots are bound to 餐饮, which is exactly the meal case: the category
        // matches are ambiguous on purpose, so only the time window can decide.
        assertEquals("lunch", DailySlotAssigner.assign(txn(), mealSlots, zone))
    }

    @Test
    fun `the window picks the right meal across the day`() {
        assertEquals("breakfast", DailySlotAssigner.assign(txn(occurredAt = at(7, 40)), mealSlots, zone))
        assertEquals("lunch", DailySlotAssigner.assign(txn(occurredAt = at(12, 30)), mealSlots, zone))
        assertEquals("dinner", DailySlotAssigner.assign(txn(occurredAt = at(19, 20)), mealSlots, zone))
        // 23:59 must still be dinner, which is why the window ends at 24:00 not 23:59.
        assertEquals("dinner", DailySlotAssigner.assign(txn(occurredAt = at(23, 59)), mealSlots, zone))
    }

    @Test
    fun `a payment outside every window is left for the user`() {
        // 03:00 is nobody's meal; inventing one would make both meals wrong.
        assertNull(DailySlotAssigner.assign(txn(occurredAt = at(3, 0)), mealSlots, zone))
    }

    @Test
    fun `a window that wraps past midnight works`() {
        val lateNight = slot("night", "宵夜", start = 22 * 60, end = 2 * 60)
        val slots = mealSlots + lateNight
        assertEquals("night", DailySlotAssigner.assign(txn(occurredAt = at(23, 30)), slots, zone))
        assertEquals("night", DailySlotAssigner.assign(txn(occurredAt = at(1, 0)), slots, zone))
    }

    @Test
    fun `a user assignment always wins, even over a matching window`() {
        // The row was filed as dinner at 12:30 by hand. Recomputing must not "fix" it.
        assertEquals(
            "dinner",
            DailySlotAssigner.assign(txn(pocketId = "dinner"), mealSlots, zone),
        )
    }

    @Test
    fun `a unique category match does not need a time window`() {
        val slots = listOf(
            slot("food", "吃饭", category = CategoryKeys.FOOD),
            slot("bus", "通勤", category = CategoryKeys.TRANSPORT),
        )
        assertEquals("food", DailySlotAssigner.assign(txn(), slots, zone))
        assertEquals(
            "bus",
            DailySlotAssigner.assign(txn(category = CategoryKeys.TRANSPORT, occurredAt = at(8, 0)), slots, zone),
        )
    }

    @Test
    fun `a slot bound to another category is not chosen by time`() {
        val slots = listOf(
            slot("bus", "通勤", category = CategoryKeys.TRANSPORT, start = 7 * 60, end = 9 * 60),
        )
        // 08:00 is inside the commute window, but this is a food payment.
        assertNull(DailySlotAssigner.assign(txn(occurredAt = at(8, 0)), slots, zone))
    }

    @Test
    fun `an unbound slot can be picked by time`() {
        val slots = listOf(slot("any", "机动", category = null, start = 0, end = 24 * 60))
        assertEquals("any", DailySlotAssigner.assign(txn(), slots, zone))
    }

    @Test
    fun `overlapping windows resolve to the narrower one`() {
        // 宵夜 sits inside 晚饭. Adding it means "count late-night separately", so it must
        // win even though 晚饭 was created first.
        val slots = listOf(
            slot("dinner", "晚饭", start = 15 * 60, end = 24 * 60, sortOrder = 0),
            slot("night", "宵夜", start = 22 * 60, end = 2 * 60, sortOrder = 1),
        )
        assertEquals("night", DailySlotAssigner.assign(txn(occurredAt = at(23, 30)), slots, zone))
        assertEquals("night", DailySlotAssigner.assign(txn(occurredAt = at(1, 0)), slots, zone))
        assertEquals("dinner", DailySlotAssigner.assign(txn(occurredAt = at(19, 0)), slots, zone))
    }

    @Test
    fun `a single catch-all slot takes what nothing else claims`() {
        val slots = listOf(
            slot("breakfast", "早饭", start = 5 * 60, end = 10 * 60, sortOrder = 0),
            slot("lunch", "午饭", start = 10 * 60, end = 15 * 60, sortOrder = 1),
            slot("buffer", "机动", category = null, sortOrder = 2),
        )
        // Inside a window: the window decides.
        assertEquals("breakfast", DailySlotAssigner.assign(txn(occurredAt = at(8, 0)), slots, zone))
        assertEquals("lunch", DailySlotAssigner.assign(txn(occurredAt = at(12, 0)), slots, zone))
        // Outside every window: the one catch-all slot collects it.
        assertEquals("buffer", DailySlotAssigner.assign(txn(occurredAt = at(3, 0)), slots, zone))
    }

    @Test
    fun `a lone slot bound to the category takes everything in that category`() {
        // With only one food slot there is nothing to disambiguate, so the time window is
        // not consulted — filing all food there is what the user set up.
        val slots = listOf(slot("food", "吃饭", category = CategoryKeys.FOOD))
        assertEquals("food", DailySlotAssigner.assign(txn(occurredAt = at(3, 0)), slots, zone))
    }

    @Test
    fun `two catch-all slots leave the row unassigned rather than picking one`() {
        val slots = listOf(
            slot("a", "机动一", category = null),
            slot("b", "机动二", category = null),
        )
        assertNull(DailySlotAssigner.assign(txn(), slots, zone))
    }

    @Test
    fun `an empty slot list assigns nothing`() {
        assertNull(DailySlotAssigner.assign(txn(), emptyList(), zone))
    }

    @Test
    fun `an explicit assignment to a deleted slot is ignored`() {
        // The user filed it under a slot that no longer exists; fall back to the rules
        // rather than reporting a slot id the UI cannot resolve.
        assertEquals("lunch", DailySlotAssigner.assign(txn(pocketId = "gone"), mealSlots, zone))
    }
}

class MinuteOfDayTest {

    @Test
    fun `parses the shapes people actually type`() {
        assertEquals(0, DateX.parseMinuteOfDay("0:00"))
        assertEquals(5 * 60, DateX.parseMinuteOfDay("5:00"))
        assertEquals(5 * 60, DateX.parseMinuteOfDay("05:00"))
        assertEquals(5 * 60, DateX.parseMinuteOfDay("5"))
        assertEquals(12 * 60 + 30, DateX.parseMinuteOfDay("12:30"))
        assertEquals(5 * 60, DateX.parseMinuteOfDay(" 5：00 "))
    }

    @Test
    fun `accepts 24-00 so a dinner window can end at midnight`() {
        // Writing 23:59 would silently exclude a payment made at 23:59:30.
        assertEquals(1440, DateX.parseMinuteOfDay("24:00"))
    }

    @Test
    fun `rejects nonsense instead of guessing`() {
        assertNull(DateX.parseMinuteOfDay(null))
        assertNull(DateX.parseMinuteOfDay(""))
        assertNull(DateX.parseMinuteOfDay("  "))
        assertNull(DateX.parseMinuteOfDay("abc"))
        assertNull(DateX.parseMinuteOfDay("25:00"))
        assertNull(DateX.parseMinuteOfDay("12:60"))
        assertNull(DateX.parseMinuteOfDay("24:30"))
        assertNull(DateX.parseMinuteOfDay("12:30:45"))
    }

    @Test
    fun `formats back to a fixed width`() {
        assertEquals("05:00", DateX.formatMinuteOfDay(5 * 60))
        assertEquals("00:00", DateX.formatMinuteOfDay(0))
        assertEquals("23:59", DateX.formatMinuteOfDay(23 * 60 + 59))
    }
}

package com.xiaozhangben.ledger.domain.budget

import com.xiaozhangben.ledger.core.DateX
import java.time.ZoneId

/**
 * Decides which daily slot a payment belongs to.
 *
 * This is the piece that makes meal-level tracking possible at all. A payment
 * notification says "已支付¥15.00" and nothing about whether that was breakfast or
 * lunch, so the app has to either ask the user or infer — and inferring wrongly is worse
 * than not deciding, because a silently mis-filed ¥15 makes both slots wrong and the
 * user has no reason to suspect either.
 *
 * The rules, in order, and every one of them requires an unambiguous answer:
 *
 *  1. **Explicit.** The user assigned this row, so their choice wins permanently.
 *  2. **Unique category match.** Exactly one slot is bound to the row's category.
 *     Two slots both bound to 餐饮 is precisely the meal case, so it falls through
 *     rather than dumping everything into whichever happens to sort first.
 *  3. **Time window.** A slot whose window covers the moment of payment, and which is
 *     either unbound or bound to this category. This is what separates 午饭 from 晚饭.
 *  4. **Single catch-all.** If exactly one slot has neither category nor window (the
 *     user's "机动"), it takes the remainder.
 *  5. Otherwise **unassigned** — surfaced in the UI for the user to place, never guessed.
 */
object DailySlotAssigner {

    fun assign(
        txn: TxnLite,
        slots: List<PocketLite>,
        zone: ZoneId = ZoneId.systemDefault(),
    ): String? {
        if (slots.isEmpty()) return null

        // 1. A choice the user made is absolute, even if they later changed the slot's
        //    category. Losing a deliberate decision to a rule change would be infuriating.
        txn.pocketId?.let { explicit ->
            if (slots.any { it.id == explicit }) return explicit
        }

        // 2. Exactly one slot claims this category.
        val categoryMatches = slots.filter { it.categoryKey != null && it.categoryKey == txn.categoryKey }
        if (categoryMatches.size == 1) return categoryMatches.single().id

        // 3. Time of day. When windows overlap, the *narrower* one wins: a user who adds
        //    宵夜 22:00–02:00 alongside 晚饭 15:00–24:00 is explicitly asking for late-night
        //    spending to be counted separately, and creation order should not defeat that.
        val minute = DateX.minuteOfDay(txn.occurredAt, zone)
        slots.filter { slot ->
            slot.coversMinute(minute) && (slot.categoryKey == null || slot.categoryKey == txn.categoryKey)
        }.minWithOrNull(
            compareBy({ it.windowLengthMinutes ?: Int.MAX_VALUE }, { it.sortOrder }, { it.name })
        )?.let { return it.id }

        // 4. A single, explicit catch-all slot.
        val catchAlls = slots.filter { it.isCatchAll }
        if (catchAlls.size == 1) return catchAlls.single().id

        // 5. Leave it for the user.
        return null
    }
}

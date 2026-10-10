package com.xiaozhangben.ledger.core

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * All calendar work goes through java.time. Hand-rolled `+86400_000` arithmetic
 * breaks on DST transitions, which is exactly how a "monthly" budget silently
 * reports the wrong number of remaining days.
 */
object DateX {

    val zone: ZoneId get() = ZoneId.systemDefault()

    private val TIME_FMT = DateTimeFormatter.ofPattern("HH:mm")
    private val DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val DATE_TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    /** Bill timestamps appear in several shapes depending on export version. */
    private val PARSE_PATTERNS: List<DateTimeFormatter> = listOf(
        "yyyy-MM-dd HH:mm:ss",
        "yyyy-MM-dd HH:mm",
        "yyyy/MM/dd HH:mm:ss",
        "yyyy/MM/dd HH:mm",
        "yyyy-MM-dd'T'HH:mm:ss",
        "yyyy-MM-dd'T'HH:mm",
        "yyyy.MM.dd HH:mm:ss",
        "yyyyMMddHHmmss",
        "yyyy年MM月dd日 HH:mm:ss",
        "yyyy年MM月dd日 HH:mm",
    ).map { DateTimeFormatter.ofPattern(it) }

    private val DATE_ONLY_PATTERNS: List<DateTimeFormatter> = listOf(
        "yyyy-MM-dd",
        "yyyy/MM/dd",
        "yyyyMMdd",
        "yyyy年MM月dd日",
    ).map { DateTimeFormatter.ofPattern(it) }

    // ---- month / day boundaries -------------------------------------------------

    fun yearMonthOf(epochMillis: Long, z: ZoneId = zone): YearMonth =
        YearMonth.from(Instant.ofEpochMilli(epochMillis).atZone(z).toLocalDate())

    fun monthKey(ym: YearMonth): String = ym.toString()

    fun parseMonthKey(key: String): YearMonth? = try {
        YearMonth.parse(key)
    } catch (_: DateTimeParseException) {
        null
    }

    fun startOfMonthMillis(ym: YearMonth, z: ZoneId = zone): Long =
        ym.atDay(1).atStartOfDay(z).toInstant().toEpochMilli()

    fun endOfMonthExclusiveMillis(ym: YearMonth, z: ZoneId = zone): Long =
        ym.plusMonths(1).atDay(1).atStartOfDay(z).toInstant().toEpochMilli()

    fun startOfDayMillis(epochMillis: Long, z: ZoneId = zone): Long =
        Instant.ofEpochMilli(epochMillis).atZone(z).toLocalDate().atStartOfDay(z).toInstant().toEpochMilli()

    fun endOfDayExclusiveMillis(epochMillis: Long, z: ZoneId = zone): Long =
        Instant.ofEpochMilli(epochMillis).atZone(z).toLocalDate().plusDays(1).atStartOfDay(z)
            .toInstant().toEpochMilli()

    fun startOfTodayMillis(nowMillis: Long = System.currentTimeMillis(), z: ZoneId = zone): Long =
        startOfDayMillis(nowMillis, z)

    fun endOfTodayExclusiveMillis(nowMillis: Long = System.currentTimeMillis(), z: ZoneId = zone): Long =
        endOfDayExclusiveMillis(nowMillis, z)

    // ---- counts used by the daily allowance -------------------------------------

    /** Spendable days left in the month, counting today. Never below 1. */
    fun remainingDaysInMonth(nowMillis: Long, z: ZoneId = zone): Int {
        val today = Instant.ofEpochMilli(nowMillis).atZone(z).toLocalDate()
        return (today.lengthOfMonth() - today.dayOfMonth + 1).coerceAtLeast(1)
    }

    /** Days already passed in the month, counting today. Never below 1. */
    fun elapsedDaysInMonth(nowMillis: Long, z: ZoneId = zone): Int {
        val today = Instant.ofEpochMilli(nowMillis).atZone(z).toLocalDate()
        return today.dayOfMonth.coerceAtLeast(1)
    }

    fun daysInMonth(ym: YearMonth): Int = ym.lengthOfMonth()

    fun isWeekend(epochMillis: Long, z: ZoneId = zone): Boolean {
        val dow = Instant.ofEpochMilli(epochMillis).atZone(z).dayOfWeek.value
        return dow >= 6
    }

    // ---- formatting -------------------------------------------------------------

    fun formatTime(epochMillis: Long, z: ZoneId = zone): String =
        Instant.ofEpochMilli(epochMillis).atZone(z).format(TIME_FMT)

    fun formatDate(epochMillis: Long, z: ZoneId = zone): String =
        Instant.ofEpochMilli(epochMillis).atZone(z).format(DATE_FMT)

    fun formatDateTime(epochMillis: Long, z: ZoneId = zone): String =
        Instant.ofEpochMilli(epochMillis).atZone(z).format(DATE_TIME_FMT)

    private val WEEKDAYS = arrayOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

    fun weekdayLabel(epochMillis: Long, z: ZoneId = zone): String =
        WEEKDAYS[Instant.ofEpochMilli(epochMillis).atZone(z).dayOfWeek.value - 1]

    /** "今天" / "昨天" / "06-12 周三" — the way people actually describe a date. */
    fun formatDayLabel(epochMillis: Long, nowMillis: Long = System.currentTimeMillis(), z: ZoneId = zone): String {
        val day = Instant.ofEpochMilli(epochMillis).atZone(z).toLocalDate()
        val today = Instant.ofEpochMilli(nowMillis).atZone(z).toLocalDate()
        return when (day) {
            today -> "今天"
            today.minusDays(1) -> "昨天"
            today.minusDays(2) -> "前天"
            else -> if (day.year == today.year) {
                "${"%02d".format(day.monthValue)}-${"%02d".format(day.dayOfMonth)} ${weekdayLabel(epochMillis, z)}"
            } else {
                "${day.year}-${"%02d".format(day.monthValue)}-${"%02d".format(day.dayOfMonth)}"
            }
        }
    }

    fun formatMonthLabel(ym: YearMonth): String = "${ym.year} 年 ${ym.monthValue} 月"

    fun formatMonthShort(ym: YearMonth): String = "${ym.monthValue} 月"

    // ---- parsing ----------------------------------------------------------------

    /**
     * Parses the timestamp column of an imported bill.
     * Returns null when no pattern matches; the caller reports the row instead of
     * defaulting to "now", which would place a row in the wrong month.
     */
    fun parseBillTimestamp(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        val text = raw.trim().trim('"')
        for (fmt in PARSE_PATTERNS) {
            try {
                return LocalDateTime.parse(text, fmt).atZone(zone).toInstant().toEpochMilli()
            } catch (_: DateTimeParseException) {
                // try next
            }
        }
        for (fmt in DATE_ONLY_PATTERNS) {
            try {
                return LocalDate.parse(text, fmt).atStartOfDay(zone).toInstant().toEpochMilli()
            } catch (_: DateTimeParseException) {
                // try next
            }
        }
        return null
    }

    fun toLocalDate(epochMillis: Long, z: ZoneId = zone): LocalDate =
        Instant.ofEpochMilli(epochMillis).atZone(z).toLocalDate()

    /** Minutes since local midnight — used to place a payment inside a meal window. */
    fun minuteOfDay(epochMillis: Long, z: ZoneId = zone): Int {
        val time = Instant.ofEpochMilli(epochMillis).atZone(z).toLocalTime()
        return time.hour * 60 + time.minute
    }

    /** "07:30" from minutes since midnight. */
    fun formatMinuteOfDay(minute: Int): String =
        "%02d:%02d".format((minute % 1440 + 1440) % 1440 / 60, (minute % 1440 + 1440) % 1440 % 60)

    /**
     * Parses "5:00", "05:00", "24:00" or "7" into minutes since midnight.
     *
     * Accepts 24:00 as 1440 so a dinner window can end at midnight without the user
     * having to write 23:59 — which would then exclude a payment made at 23:59:30.
     */
    fun parseMinuteOfDay(raw: String?): Int? {
        if (raw.isNullOrBlank()) return null
        val text = raw.trim().replace('：', ':')
        val parts = text.split(':')
        if (parts.size > 2) return null
        val hour = parts[0].toIntOrNull() ?: return null
        val minute = if (parts.size == 2) (parts[1].toIntOrNull() ?: return null) else 0
        if (hour !in 0..24) return null
        if (minute !in 0..59) return null
        if (hour == 24 && minute != 0) return null
        return hour * 60 + minute
    }

    /**
     * Material's date picker reports the selection as UTC midnight of the chosen day.
     * Feeding that straight into a local-time timestamp shifts the date by a day for
     * anyone east or west of UTC, which silently files the expense in the wrong month
     * on the 1st. This converts the picked *calendar day* into local midnight.
     */
    fun pickedDayToLocalStartOfDay(pickerMillis: Long, z: ZoneId = zone): Long {
        val day = Instant.ofEpochMilli(pickerMillis).atZone(java.time.ZoneOffset.UTC).toLocalDate()
        return day.atStartOfDay(z).toInstant().toEpochMilli()
    }

    /** Converts a local timestamp into the UTC-midnight value the date picker expects. */
    fun localDateToPickerMillis(epochMillis: Long, z: ZoneId = zone): Long =
        toLocalDate(epochMillis, z).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
}

package com.dawnyew.core

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.daysUntil
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

enum class DateRangeKind { MONTH, YEAR, CUSTOM }
enum class DateRangePreset { CURRENT_MONTH, PREVIOUS_MONTH, CURRENT_YEAR, LAST_7_DAYS, LAST_30_DAYS }

/** Inclusive calendar dates in the same canonical format as QueryScope. */
data class DateRange(val startDate: String, val endDate: String) {
    init {
        calendarDate(startDate)
        calendarDate(endDate)
        if (startDate > endDate) invalid("开始日期不能晚于结束日期")
    }

    val kind: DateRangeKind
        get() = when {
            startDate.endsWith("-01-01") && endDate == startDate.take(4) + "-12-31" -> DateRangeKind.YEAR
            monthRange(startDate.take(7)) == (startDate to endDate) -> DateRangeKind.MONTH
            else -> DateRangeKind.CUSTOM
        }

    fun shift(delta: Int): DateRange = when (kind) {
        DateRangeKind.MONTH -> monthRange(shiftMonth(startDate.take(7), delta)).let { DateRange(it.first, it.second) }
        DateRangeKind.YEAR -> {
            val year = startDate.take(4).toLong() + delta
            if (year !in 1..9999) invalid("日期超出支持范围")
            val prefix = year.toString().padStart(4, '0')
            DateRange("$prefix-01-01", "$prefix-12-31")
        }
        DateRangeKind.CUSTOM -> {
            val start = calendarDate(startDate)
            val end = calendarDate(endDate)
            val offset = (start.daysUntil(end).toLong() + 1) * delta
            DateRange(shiftDays(start, offset).toString(), shiftDays(end, offset).toString())
        }
    }
}

/** One preference value keeps the inclusive start/end pair atomic across persistence and backup. */
fun encodeDateRangePreference(range: DateRange): String = "${range.startDate}/${range.endDate}"
fun decodeDateRangePreference(value: String): DateRange {
    val parts = value.split('/')
    if (parts.size != 2) invalid("日期范围偏好无效")
    return DateRange(parts[0], parts[1])
}

fun dateRangePreset(preset: DateRangePreset, today: String): DateRange {
    val date = calendarDate(today)
    return when (preset) {
        DateRangePreset.CURRENT_MONTH -> monthRange(today.take(7)).let { DateRange(it.first, it.second) }
        DateRangePreset.PREVIOUS_MONTH -> monthRange(shiftMonth(today.take(7), -1)).let { DateRange(it.first, it.second) }
        DateRangePreset.CURRENT_YEAR -> DateRange(today.take(4) + "-01-01", today.take(4) + "-12-31")
        DateRangePreset.LAST_7_DAYS -> DateRange(shiftDays(date, -6).toString(), today)
        DateRangePreset.LAST_30_DAYS -> DateRange(shiftDays(date, -29).toString(), today)
    }
}

/** Material date pickers encode calendar dates at UTC midnight, independent of the device zone. */
fun dateToUtcMillis(date: String): Long = calendarDate(date).atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds()

fun utcMillisToDate(millis: Long): String {
    val date = try {
        Instant.fromEpochMilliseconds(millis).toLocalDateTime(TimeZone.UTC).date
    } catch (_: Exception) {
        invalid("日期超出支持范围")
    }
    if (date.year !in 1..9999) invalid("日期超出支持范围")
    return date.toString()
}

private fun calendarDate(value: String): LocalDate {
    validateOccurrence(value + "T00:00:00")
    return LocalDate.parse(value)
}

private fun shiftDays(date: LocalDate, days: Long): LocalDate {
    val target = date.toEpochDays().toLong() + days
    if (target !in LocalDate(1, 1, 1).toEpochDays().toLong()..LocalDate(9999, 12, 31).toEpochDays().toLong()) {
        invalid("日期超出支持范围")
    }
    return date.plus(days.toInt(), DateTimeUnit.DAY)
}

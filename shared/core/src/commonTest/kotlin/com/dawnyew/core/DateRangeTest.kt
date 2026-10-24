package com.dawnyew.core

import kotlin.test.*

class DateRangeTest {
    @Test fun rangePreferenceIsOneValidatedAtomicPair() {
        val range = DateRange("2026-09-15", "2026-10-10")
        assertEquals("2026-09-15/2026-10-10", encodeDateRangePreference(range))
        assertEquals(range, decodeDateRangePreference(encodeDateRangePreference(range)))
        validatePreference(UserPreference("lastHomeRange", encodeDateRangePreference(range)))
        listOf("2026-09-15", "2026-10-10/2026-09-15", "2026-09-31/2026-10-10", "2026-09-15/2026-10-10/extra").forEach {
            assertFailsWith<DomainException> { validatePreference(UserPreference("lastHomeRange", it)) }
        }
        // Existing backups keep their original supported month field.
        validatePreference(UserPreference("lastPeriod", "2026-09"))
    }

    @Test fun acceptsInclusiveSingleDayAndSupportedYearBoundaries() {
        assertEquals("2024-02-29", DateRange("2024-02-29", "2024-02-29").endDate)
        assertEquals("0001-01-01", DateRange("0001-01-01", "9999-12-31").startDate)
    }

    @Test fun rejectsMalformedImpossibleAndReversedDates() {
        listOf("0000-01-01", "10000-01-01", "2024-2-01", "2023-02-29", "2024-04-31", " 2024-01-01").forEach { date ->
            assertFailsWith<DomainException> { DateRange(date, "9999-12-31") }
            assertFailsWith<DomainException> { DateRange("0001-01-01", date) }
        }
        assertFailsWith<DomainException> { DateRange("2026-10-19", "2026-10-18") }
    }

    @Test fun recognizesOnlyCompleteCalendarMonthsAndYears() {
        assertEquals(DateRangeKind.MONTH, DateRange("2024-02-01", "2024-02-29").kind)
        assertEquals(DateRangeKind.YEAR, DateRange("2024-01-01", "2024-12-31").kind)
        assertEquals(DateRangeKind.CUSTOM, DateRange("2024-02-01", "2024-02-28").kind)
        assertEquals(DateRangeKind.CUSTOM, DateRange("2024-01-01", "2025-12-31").kind)
    }

    @Test fun shiftsWholeMonthsByCalendarAcrossLeapDaysAndYears() {
        val january = DateRange("2024-01-01", "2024-01-31")
        assertEquals(DateRange("2024-02-01", "2024-02-29"), january.shift(1))
        assertEquals(DateRange("2023-12-01", "2023-12-31"), january.shift(-1))
        assertEquals(january, january.shift(1).shift(-1))
        assertEquals(DateRange("2025-02-01", "2025-02-28"), january.shift(13))
    }

    @Test fun shiftsWholeYearsByCalendarAcrossLeapYears() {
        val leapYear = DateRange("2024-01-01", "2024-12-31")
        assertEquals(DateRange("2023-01-01", "2023-12-31"), leapYear.shift(-1))
        assertEquals(DateRange("2025-01-01", "2025-12-31"), leapYear.shift(1))
    }

    @Test fun shiftsCustomRangesByTheirInclusiveDayCount() {
        val range = DateRange("2024-02-28", "2024-03-01")
        assertEquals(DateRange("2024-03-02", "2024-03-04"), range.shift(1))
        assertEquals(DateRange("2024-02-25", "2024-02-27"), range.shift(-1))
        assertEquals(DateRange("2024-03-05", "2024-03-07"), range.shift(2))
        assertEquals(range, range.shift(0))
        assertEquals(DateRange("2025-01-01", "2025-01-01"), DateRange("2024-12-31", "2024-12-31").shift(1))
    }

    @Test fun shiftsRejectUnsupportedYearsAndLargeDeltasWithoutWrapping() {
        listOf(
            DateRange("0001-01-01", "0001-01-01") to -1,
            DateRange("9999-12-31", "9999-12-31") to 1,
            DateRange("0001-01-01", "0001-01-31") to -1,
            DateRange("9999-12-01", "9999-12-31") to 1,
            DateRange("0001-01-01", "0001-12-31") to -1,
            DateRange("9999-01-01", "9999-12-31") to 1,
            DateRange("2024-02-28", "2024-03-01") to Int.MAX_VALUE,
            DateRange("2024-02-28", "2024-03-01") to Int.MIN_VALUE,
            DateRange("2024-01-01", "2024-12-31") to Int.MAX_VALUE,
        ).forEach { (range, delta) -> assertFailsWith<DomainException> { range.shift(delta) } }
    }

    @Test fun presetsUseTheSuppliedTodayAndIncludeTodayInRecentRanges() {
        val today = "2024-03-01"
        assertEquals(DateRange("2024-03-01", "2024-03-31"), dateRangePreset(DateRangePreset.CURRENT_MONTH, today))
        assertEquals(DateRange("2024-02-01", "2024-02-29"), dateRangePreset(DateRangePreset.PREVIOUS_MONTH, today))
        assertEquals(DateRange("2024-01-01", "2024-12-31"), dateRangePreset(DateRangePreset.CURRENT_YEAR, today))
        assertEquals(DateRange("2024-02-24", "2024-03-01"), dateRangePreset(DateRangePreset.LAST_7_DAYS, today))
        assertEquals(DateRange("2024-02-01", "2024-03-01"), dateRangePreset(DateRangePreset.LAST_30_DAYS, today))
        assertEquals(DateRange("2023-12-01", "2023-12-31"), dateRangePreset(DateRangePreset.PREVIOUS_MONTH, "2024-01-15"))
    }

    @Test fun presetsValidateTodayAndSupportedBoundaries() {
        assertFailsWith<DomainException> { dateRangePreset(DateRangePreset.CURRENT_YEAR, "0000-01-01") }
        assertFailsWith<DomainException> { dateRangePreset(DateRangePreset.LAST_7_DAYS, "0001-01-01") }
        assertFailsWith<DomainException> { dateRangePreset(DateRangePreset.PREVIOUS_MONTH, "0001-01-01") }
        assertEquals(DateRange("9999-12-01", "9999-12-31"), dateRangePreset(DateRangePreset.CURRENT_MONTH, "9999-12-31"))
    }

    @Test fun pickerMillisUseUtcMidnightAndRoundTripBeforeAndAfterEpoch() {
        assertEquals(0L, dateToUtcMillis("1970-01-01"))
        assertEquals(-86_400_000L, dateToUtcMillis("1969-12-31"))
        assertEquals(86_400_000L, dateToUtcMillis("1970-01-02"))
        listOf("0001-01-01", "1969-12-31", "2024-02-29", "2024-03-10", "2024-11-03", "9999-12-31").forEach { date ->
            val millis = dateToUtcMillis(date)
            assertEquals(0L, millis % 86_400_000L)
            assertEquals(date, utcMillisToDate(millis))
        }
        assertEquals("1969-12-31", utcMillisToDate(-1))
        assertEquals("1970-01-01", utcMillisToDate(86_399_999))
    }

    @Test fun pickerMillisRejectUnsupportedDates() {
        assertFailsWith<DomainException> { dateToUtcMillis("2023-02-29") }
        assertFailsWith<DomainException> { utcMillisToDate(dateToUtcMillis("0001-01-01") - 1) }
        assertFailsWith<DomainException> { utcMillisToDate(dateToUtcMillis("9999-12-31") + 86_400_000L) }
        assertFailsWith<DomainException> { utcMillisToDate(Long.MAX_VALUE) }
        assertFailsWith<DomainException> { utcMillisToDate(Long.MIN_VALUE) }
    }
}

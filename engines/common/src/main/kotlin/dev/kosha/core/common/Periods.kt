package dev.kosha.core.common

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Period math (spec G1):
 *  - Monthly periods use ONE global, configurable anchor day (1–28).
 *    Default 1 = calendar month. Anchor 5 → period runs 5th → 4th.
 *  - Weekly budgets run Mon–Sun.
 *  - Indian financial year: 1 April – 31 March.
 */
data class Period(val start: LocalDate, val endInclusive: LocalDate) {
    operator fun contains(date: LocalDate): Boolean = date in start..endInclusive

    fun startEpochMillis(zone: ZoneId): Long =
        start.atStartOfDay(zone).toInstant().toEpochMilli()

    /** Exclusive end boundary: first instant of the day after [endInclusive]. */
    fun endEpochMillisExclusive(zone: ZoneId): Long =
        endInclusive.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
}

object Periods {

    const val MIN_ANCHOR_DAY = 1
    const val MAX_ANCHOR_DAY = 28

    fun monthlyPeriodContaining(date: LocalDate, anchorDay: Int = 1): Period {
        require(anchorDay in MIN_ANCHOR_DAY..MAX_ANCHOR_DAY) {
            "anchorDay must be in $MIN_ANCHOR_DAY..$MAX_ANCHOR_DAY (got $anchorDay)"
        }
        val start = if (date.dayOfMonth >= anchorDay) {
            date.withDayOfMonth(anchorDay)
        } else {
            date.minusMonths(1).withDayOfMonth(anchorDay)
        }
        return Period(start, start.plusMonths(1).minusDays(1))
    }

    fun previousMonthlyPeriod(period: Period, anchorDay: Int = 1): Period =
        monthlyPeriodContaining(period.start.minusDays(1), anchorDay)

    fun nextMonthlyPeriod(period: Period, anchorDay: Int = 1): Period =
        monthlyPeriodContaining(period.endInclusive.plusDays(1), anchorDay)

    fun weeklyPeriodContaining(date: LocalDate): Period {
        val start = date.minusDays((date.dayOfWeek.value - DayOfWeek.MONDAY.value).toLong())
        return Period(start, start.plusDays(6))
    }

    /** Indian FY: 1 April – 31 March. FY label year = calendar year of April 1. */
    fun financialYearContaining(date: LocalDate): Period {
        val fyStartYear = if (date.monthValue >= 4) date.year else date.year - 1
        return Period(
            LocalDate.of(fyStartYear, 4, 1),
            LocalDate.of(fyStartYear + 1, 3, 31),
        )
    }

    fun localDateOf(epochMillis: Long, zone: ZoneId): LocalDate =
        Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate()
}

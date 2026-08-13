package dev.kosha.core.common

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PeriodsTest {

    @Test
    fun `default anchor is the calendar month`() {
        val p = Periods.monthlyPeriodContaining(LocalDate.of(2026, 8, 13))
        assertEquals(LocalDate.of(2026, 8, 1), p.start)
        assertEquals(LocalDate.of(2026, 8, 31), p.endInclusive)
    }

    @Test
    fun `salary-day anchor runs 5th to 4th`() {
        val onOrAfter = Periods.monthlyPeriodContaining(LocalDate.of(2026, 8, 5), anchorDay = 5)
        assertEquals(LocalDate.of(2026, 8, 5), onOrAfter.start)
        assertEquals(LocalDate.of(2026, 9, 4), onOrAfter.endInclusive)

        val before = Periods.monthlyPeriodContaining(LocalDate.of(2026, 8, 4), anchorDay = 5)
        assertEquals(LocalDate.of(2026, 7, 5), before.start)
        assertEquals(LocalDate.of(2026, 8, 4), before.endInclusive)
    }

    @Test
    fun `february boundaries are handled`() {
        val p = Periods.monthlyPeriodContaining(LocalDate.of(2026, 2, 10), anchorDay = 28)
        assertEquals(LocalDate.of(2026, 1, 28), p.start)
        assertEquals(LocalDate.of(2026, 2, 27), p.endInclusive)
    }

    @Test
    fun `periods tile the calendar with no gap or overlap`() {
        var period = Periods.monthlyPeriodContaining(LocalDate.of(2026, 1, 1), anchorDay = 5)
        repeat(24) {
            val next = Periods.nextMonthlyPeriod(period, anchorDay = 5)
            assertEquals(period.endInclusive.plusDays(1), next.start)
            period = next
        }
    }

    @Test
    fun `previous period is adjacent`() {
        val p = Periods.monthlyPeriodContaining(LocalDate.of(2026, 8, 13), anchorDay = 5)
        val prev = Periods.previousMonthlyPeriod(p, anchorDay = 5)
        assertEquals(p.start.minusDays(1), prev.endInclusive)
    }

    @Test
    fun `weekly period runs monday to sunday`() {
        // 2026-08-13 is a Thursday
        val p = Periods.weeklyPeriodContaining(LocalDate.of(2026, 8, 13))
        assertEquals(LocalDate.of(2026, 8, 10), p.start)
        assertEquals(LocalDate.of(2026, 8, 16), p.endInclusive)
        assertTrue(LocalDate.of(2026, 8, 13) in p)
    }

    @Test
    fun `financial year is april to march`() {
        val beforeApril = Periods.financialYearContaining(LocalDate.of(2026, 3, 31))
        assertEquals(LocalDate.of(2025, 4, 1), beforeApril.start)
        assertEquals(LocalDate.of(2026, 3, 31), beforeApril.endInclusive)

        val fromApril = Periods.financialYearContaining(LocalDate.of(2026, 4, 1))
        assertEquals(LocalDate.of(2026, 4, 1), fromApril.start)
        assertEquals(LocalDate.of(2027, 3, 31), fromApril.endInclusive)
    }
}

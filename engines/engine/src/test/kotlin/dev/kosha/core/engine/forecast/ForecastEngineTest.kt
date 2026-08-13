package dev.kosha.core.engine.forecast

import dev.kosha.core.common.Money
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase-5 exit gate: the forecast matches a hand-computed projection on
 * fixture data.
 */
class ForecastEngineTest {

    private val today = LocalDate.of(2026, 8, 13)

    @Test
    fun `hand-computed projection - balance, salary, emi and daily rate`() {
        // Opening 50,000. Salary 85,000 on the 1st (day 19).
        // EMI 15,000 on the 5th (day 23). Discretionary 60,000 over 60 days
        // = 1,000/day.
        val input = ForecastEngine.Input(
            today = today,
            openingBalance = Money.ofRupees(50_000),
            scheduledOutflows = listOf(
                ForecastEngine.ScheduledOutflow(LocalDate.of(2026, 9, 5), Money.ofRupees(15_000)),
            ),
            expectedCredits = listOf(
                ForecastEngine.ExpectedCredit(LocalDate.of(2026, 9, 1), Money.ofRupees(85_000)),
            ),
            trailingDiscretionarySpend = Money.ofRupees(60_000),
            historyDays = 60,
        )
        val forecast = ForecastEngine.forecast(input)

        assertEquals(Money.ofRupees(1_000), forecast.dailyDiscretionaryRate)
        assertFalse(forecast.earlyEstimate)

        // Day 0 = today's balance, untouched.
        assertEquals(Money.ofRupees(50_000), forecast.points.first().balance)

        // Day 10: 50,000 − 10 × 1,000 = 40,000
        assertEquals(Money.ofRupees(40_000), forecast.points[10].balance)

        // Day 19 (Sep 1, salary lands): 50,000 + 85,000 − 19,000 = 116,000
        assertEquals(Money.ofRupees(116_000), forecast.points[19].balance)

        // Day 23 (Sep 5, EMI): 50,000 + 85,000 − 15,000 − 23,000 = 97,000
        assertEquals(Money.ofRupees(97_000), forecast.points[23].balance)

        // Day 30: 50,000 + 85,000 − 15,000 − 30,000 = 90,000
        assertEquals(Money.ofRupees(90_000), forecast.points[30].balance)
        assertEquals(31, forecast.points.size)
    }

    @Test
    fun `amber flag when the balance dips below zero before the next credit`() {
        // 5,000 balance, 500/day discretionary → crosses zero on day 11.
        val input = ForecastEngine.Input(
            today = today,
            openingBalance = Money.ofRupees(5_000),
            scheduledOutflows = emptyList(),
            expectedCredits = listOf(
                ForecastEngine.ExpectedCredit(LocalDate.of(2026, 9, 1), Money.ofRupees(85_000)),
            ),
            trailingDiscretionarySpend = Money.ofRupees(30_000),
            historyDays = 60,
        )
        val forecast = ForecastEngine.forecast(input)
        assertEquals(Money.ofRupees(500), forecast.dailyDiscretionaryRate)
        assertEquals(today.plusDays(11), forecast.firstNegativeDate)
        assertTrue(forecast.negativeBeforeNextCredit)
    }

    @Test
    fun `no flag when the projection stays positive`() {
        val input = ForecastEngine.Input(
            today = today,
            openingBalance = Money.ofRupees(100_000),
            scheduledOutflows = emptyList(),
            expectedCredits = emptyList(),
            trailingDiscretionarySpend = Money.ofRupees(30_000),
            historyDays = 60,
        )
        val forecast = ForecastEngine.forecast(input)
        assertNull(forecast.firstNegativeDate)
        assertFalse(forecast.negativeBeforeNextCredit)
    }

    @Test
    fun `thin history gives a recurring-only early estimate`() {
        val input = ForecastEngine.Input(
            today = today,
            openingBalance = Money.ofRupees(20_000),
            scheduledOutflows = listOf(
                ForecastEngine.ScheduledOutflow(today.plusDays(10), Money.ofRupees(5_000)),
            ),
            expectedCredits = emptyList(),
            trailingDiscretionarySpend = Money.ofRupees(9_000),
            historyDays = 14, // < 21
        )
        val forecast = ForecastEngine.forecast(input)
        assertTrue(forecast.earlyEstimate)
        assertEquals(Money.ZERO, forecast.dailyDiscretionaryRate)
        // Only the recurring outflow moves the curve.
        assertEquals(Money.ofRupees(20_000), forecast.points[9].balance)
        assertEquals(Money.ofRupees(15_000), forecast.points[10].balance)
    }

    @Test
    fun `partial history divides by the days actually observed`() {
        // 30 days of history, 30,000 spent → 1,000/day (not 30,000/60).
        val input = ForecastEngine.Input(
            today = today,
            openingBalance = Money.ofRupees(10_000),
            scheduledOutflows = emptyList(),
            expectedCredits = emptyList(),
            trailingDiscretionarySpend = Money.ofRupees(30_000),
            historyDays = 30,
        )
        assertEquals(Money.ofRupees(1_000), ForecastEngine.forecast(input).dailyDiscretionaryRate)
    }
}

class RecurringEngineTest {

    private val today = LocalDate.of(2026, 8, 13)

    @Test
    fun `monthly occurrences inside the horizon`() {
        // from 13 Aug, until 12 Oct (60 days): 20 Aug and 20 Sep fall inside,
        // 20 Oct is past the horizon.
        val occurrences = RecurringEngine.occurrencesBetween(
            nextDue = LocalDate.of(2026, 8, 20),
            frequency = RecurringEngine.Frequency.MONTHLY,
            from = today,
            until = today.plusDays(60),
        )
        assertEquals(listOf(LocalDate.of(2026, 8, 20), LocalDate.of(2026, 9, 20)), occurrences)
    }

    @Test
    fun `a stale due date catches up instead of firing in the past`() {
        // The rule's stored nextDue is three months stale; it must roll
        // forward past today rather than emitting May/June/July.
        val occurrences = RecurringEngine.occurrencesBetween(
            nextDue = LocalDate.of(2026, 5, 20),
            frequency = RecurringEngine.Frequency.MONTHLY,
            from = today,
            until = today.plusDays(30), // 12 Sep
        )
        assertEquals(listOf(LocalDate.of(2026, 8, 20)), occurrences)
        assertTrue(occurrences.all { it > today })
    }

    @Test
    fun `weekly frequency`() {
        val occurrences = RecurringEngine.occurrencesBetween(
            nextDue = today.plusDays(2),
            frequency = RecurringEngine.Frequency.WEEKLY,
            from = today,
            until = today.plusDays(21),
        )
        assertEquals(3, occurrences.size)
        assertEquals(today.plusDays(2), occurrences.first())
        assertEquals(today.plusDays(16), occurrences.last())
    }

    @Test
    fun `match window brackets the due date by three days`() {
        val window = RecurringEngine.matchWindow(LocalDate.of(2026, 8, 20))
        assertTrue(LocalDate.of(2026, 8, 17) in window)
        assertTrue(LocalDate.of(2026, 8, 23) in window)
        assertFalse(LocalDate.of(2026, 8, 16) in window)
        assertFalse(LocalDate.of(2026, 8, 24) in window)
    }
}

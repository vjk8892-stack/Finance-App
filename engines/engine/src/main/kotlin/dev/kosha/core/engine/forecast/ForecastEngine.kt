package dev.kosha.core.engine.forecast

import dev.kosha.core.common.Money
import java.time.LocalDate

/**
 * 30-day cash-flow forecast, spec G6:
 *
 *   Projected(d) = today's balance
 *                + Σ expected income credits in the next d days
 *                − Σ scheduled recurring outflows in the next d days
 *                − dailyDiscretionaryRate · d
 *
 * dailyDiscretionaryRate = trailing-60-day average daily spend EXCLUDING
 * recurring-linked and transfer transactions (they are counted explicitly).
 */
object ForecastEngine {

    const val HORIZON_DAYS = 30
    const val DISCRETIONARY_WINDOW_DAYS = 60
    const val MIN_HISTORY_DAYS = 21

    data class ScheduledOutflow(val date: LocalDate, val amount: Money)
    data class ExpectedCredit(val date: LocalDate, val amount: Money)

    data class Input(
        val today: LocalDate,
        val openingBalance: Money,
        val scheduledOutflows: List<ScheduledOutflow>,
        val expectedCredits: List<ExpectedCredit>,
        /** Discretionary spend (non-recurring, non-transfer) in the trailing window. */
        val trailingDiscretionarySpend: Money,
        val historyDays: Int,
    )

    data class Point(val date: LocalDate, val dayOffset: Int, val balance: Money)

    data class Forecast(
        val points: List<Point>,
        val dailyDiscretionaryRate: Money,
        /**
         * True when history is too thin for a discretionary rate: the curve
         * is recurring-only and must be labeled "early estimate" (spec G6).
         */
        val earlyEstimate: Boolean,
        /** First day the projection goes below zero, if any (amber flag). */
        val firstNegativeDate: LocalDate?,
        /** Whether that dip happens before the next expected income credit. */
        val negativeBeforeNextCredit: Boolean,
    )

    fun forecast(input: Input): Forecast {
        val earlyEstimate = input.historyDays < MIN_HISTORY_DAYS
        val effectiveWindow = minOf(input.historyDays, DISCRETIONARY_WINDOW_DAYS)
        val dailyRate = if (earlyEstimate || effectiveWindow <= 0) {
            Money.ZERO
        } else {
            Money(input.trailingDiscretionarySpend.paise / effectiveWindow)
        }

        val points = mutableListOf<Point>()
        var firstNegative: LocalDate? = null

        for (day in 0..HORIZON_DAYS) {
            val date = input.today.plusDays(day.toLong())
            val credits = input.expectedCredits
                .filter { it.date > input.today && it.date <= date }
                .sumOf { it.amount.paise }
            val outflows = input.scheduledOutflows
                .filter { it.date > input.today && it.date <= date }
                .sumOf { it.amount.paise }
            val discretionary = dailyRate.paise * day
            val balance = input.openingBalance.paise + credits - outflows - discretionary
            if (firstNegative == null && balance < 0) firstNegative = date
            points += Point(date, day, Money(balance))
        }

        val nextCredit = input.expectedCredits
            .filter { it.date > input.today }
            .minByOrNull { it.date }
            ?.date

        return Forecast(
            points = points,
            dailyDiscretionaryRate = dailyRate,
            earlyEstimate = earlyEstimate,
            firstNegativeDate = firstNegative,
            negativeBeforeNextCredit = firstNegative != null &&
                (nextCredit == null || firstNegative <= nextCredit),
        )
    }
}

/**
 * Recurring-rule scheduling (spec Phase 5). Pure date math so due dates and
 * the "expected window" used for recurring↔actual linking are testable.
 */
object RecurringEngine {

    enum class Frequency { DAILY, WEEKLY, MONTHLY, QUARTERLY, YEARLY }

    /** Tolerance around a due date within which a real transaction links. */
    const val MATCH_WINDOW_DAYS = 3L

    fun nextDueDate(from: LocalDate, frequency: Frequency): LocalDate = when (frequency) {
        Frequency.DAILY -> from.plusDays(1)
        Frequency.WEEKLY -> from.plusWeeks(1)
        Frequency.MONTHLY -> from.plusMonths(1)
        Frequency.QUARTERLY -> from.plusMonths(3)
        Frequency.YEARLY -> from.plusYears(1)
    }

    /** Due dates strictly after [from] and on/before [until]. */
    fun occurrencesBetween(
        nextDue: LocalDate,
        frequency: Frequency,
        from: LocalDate,
        until: LocalDate,
    ): List<LocalDate> {
        val result = mutableListOf<LocalDate>()
        var cursor = nextDue
        // Catch up if the rule's next due date is stale.
        var guard = 0
        while (cursor <= from && guard < 500) {
            cursor = nextDueDate(cursor, frequency)
            guard++
        }
        while (cursor <= until && guard < 500) {
            result += cursor
            cursor = nextDueDate(cursor, frequency)
            guard++
        }
        return result
    }

    fun matchWindow(dueDate: LocalDate): ClosedRange<LocalDate> =
        dueDate.minusDays(MATCH_WINDOW_DAYS)..dueDate.plusDays(MATCH_WINDOW_DAYS)
}

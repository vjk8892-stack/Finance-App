package dev.kosha.core.database.repo

import dev.kosha.core.common.Money
import dev.kosha.core.database.dao.AccountDao
import dev.kosha.core.database.dao.PlanningDao
import dev.kosha.core.database.dao.TransactionDao
import dev.kosha.core.database.model.IncomeFrequency
import dev.kosha.core.database.model.RecurringFrequency
import dev.kosha.core.database.model.TxnStatus
import dev.kosha.core.database.model.TxnType
import dev.kosha.core.engine.forecast.ForecastEngine
import dev.kosha.core.engine.forecast.RecurringEngine
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/** Assembles ledger + rules into [ForecastEngine] inputs (spec G6). */
@Singleton
class ForecastRepository @Inject constructor(
    private val accountDao: AccountDao,
    private val planningDao: PlanningDao,
    private val transactionDao: TransactionDao,
) {
    private val zone: ZoneId = ZoneId.systemDefault()

    suspend fun forecast(today: LocalDate = LocalDate.now(zone)): ForecastEngine.Forecast {
        val accounts = accountDao.activeAccounts()
        val openingBalance = Money(accounts.sumOf { it.currentBalancePaise })
        val horizonEnd = today.plusDays(ForecastEngine.HORIZON_DAYS.toLong())

        // Scheduled recurring outflows in the horizon.
        val rules = planningDao.activeRecurringRules()
        val outflows = rules.flatMap { rule ->
            val amount = rule.amountPaise ?: return@flatMap emptyList()
            RecurringEngine.occurrencesBetween(
                nextDue = localDate(rule.nextDueDateMillis),
                frequency = rule.frequency.toEngine(),
                from = today,
                until = horizonEnd,
            ).map { ForecastEngine.ScheduledOutflow(it, Money(amount)) }
        }

        // Expected income credits in the horizon.
        val credits = planningDao.incomeSourcesOnce().flatMap { source ->
            when (source.frequency) {
                IncomeFrequency.MONTHLY -> {
                    val day = source.expectedDay ?: 1
                    generateSequence(today.withDayOfMonth(minOf(day, today.lengthOfMonth()))) { prev ->
                        prev.plusMonths(1).let { it.withDayOfMonth(minOf(day, it.lengthOfMonth())) }
                    }
                        .takeWhile { it <= horizonEnd }
                        .filter { it > today }
                        .map { ForecastEngine.ExpectedCredit(it, Money(source.amountPaise)) }
                        .toList()
                }
                // Variable income: trailing 3-month median, placed at period
                // end. One-time sources never repeat into the forecast.
                IncomeFrequency.VARIABLE -> emptyList()
                IncomeFrequency.ONE_TIME -> emptyList()
            }
        }

        // Discretionary spend: trailing 60 days, excluding recurring-linked
        // and transfer legs (both are counted explicitly above).
        val windowStart = today.minusDays(ForecastEngine.DISCRETIONARY_WINDOW_DAYS.toLong())
        val windowTxns = transactionDao.inWindow(
            windowStart.atStartOfDay(zone).toInstant().toEpochMilli(),
            today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(),
        )
        val transferLegIds = transactionDao.transferLegIds().toSet()
        val discretionary = windowTxns
            .filter {
                it.status == TxnStatus.COMMITTED &&
                    it.type == TxnType.DEBIT &&
                    it.parentTransactionId == null &&
                    it.recurringRuleId == null &&
                    it.id !in transferLegIds
            }
            .sumOf { it.amountPaise }

        val oldest = transactionDao.oldestTimestamp()
        val historyDays = if (oldest == null) {
            0
        } else {
            java.time.temporal.ChronoUnit.DAYS.between(localDate(oldest), today).toInt().coerceAtLeast(0)
        }

        return ForecastEngine.forecast(
            ForecastEngine.Input(
                today = today,
                openingBalance = openingBalance,
                scheduledOutflows = outflows,
                expectedCredits = credits,
                trailingDiscretionarySpend = Money(discretionary),
                historyDays = historyDays,
            ),
        )
    }

    private fun localDate(epochMillis: Long): LocalDate =
        java.time.Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate()
}

internal fun RecurringFrequency.toEngine(): RecurringEngine.Frequency = when (this) {
    RecurringFrequency.DAILY -> RecurringEngine.Frequency.DAILY
    RecurringFrequency.WEEKLY -> RecurringEngine.Frequency.WEEKLY
    RecurringFrequency.MONTHLY -> RecurringEngine.Frequency.MONTHLY
    RecurringFrequency.QUARTERLY -> RecurringEngine.Frequency.QUARTERLY
    RecurringFrequency.YEARLY -> RecurringEngine.Frequency.YEARLY
}

package dev.kosha.core.database.repo

import dev.kosha.core.database.dao.AccountDao
import dev.kosha.core.database.dao.PlanningDao
import dev.kosha.core.database.dao.TransactionDao
import dev.kosha.core.database.model.RecurringRuleEntity
import dev.kosha.core.database.model.TransactionEntity
import dev.kosha.core.database.model.TxnSource
import dev.kosha.core.database.model.TxnStatus
import dev.kosha.core.database.model.TxnType
import dev.kosha.core.engine.forecast.RecurringEngine
import dev.kosha.core.engine.pipeline.DedupEngine
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

/**
 * Recurring rules (spec Phase 5).
 *
 * Auto-log posts the instance only when no real transaction already covers
 * the due window — the detected SMS/photo wins and gets linked to the rule
 * instead, so an EMI is never double-counted (Phase-5 exit gate).
 */
@Singleton
class RecurringRepository @Inject constructor(
    private val balanceMaintainer: BalanceMaintainer,
    private val planningDao: PlanningDao,
    private val transactionDao: TransactionDao,
    private val accountDao: AccountDao,
) {
    private val zone: ZoneId = ZoneId.systemDefault()

    fun observeRules(): Flow<List<RecurringRuleEntity>> = planningDao.observeRecurringRules()

    suspend fun addRule(rule: RecurringRuleEntity): Long = planningDao.insertRecurringRule(rule)

    suspend fun removeRule(id: Long) = planningDao.deactivateRecurringRule(id)

    /** Windows the dedup engine uses to link real transactions to rules. */
    suspend fun expectedRecurringWindows(
        today: LocalDate = LocalDate.now(zone),
    ): List<DedupEngine.ExpectedRecurring> = planningDao.activeRecurringRules().map { rule ->
        val due = localDate(rule.nextDueDateMillis)
        val window = RecurringEngine.matchWindow(due)
        DedupEngine.ExpectedRecurring(
            ruleId = rule.id,
            amountPaise = rule.amountPaise,
            accountId = rule.accountId,
            merchantPattern = rule.merchantPattern,
            dueWindowStartMillis = window.start.atStartOfDay(zone).toInstant().toEpochMilli(),
            dueWindowEndMillis = window.endInclusive.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(),
        )
    }

    data class DueRule(val rule: RecurringRuleEntity, val alreadyCovered: Boolean)

    /** Rules due on or before today, and whether a real txn already covers them. */
    suspend fun dueRules(today: LocalDate = LocalDate.now(zone)): List<DueRule> {
        val endOfToday = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return planningDao.rulesDueBy(endOfToday).map { rule ->
            DueRule(rule, alreadyCovered = isCovered(rule))
        }
    }

    /**
     * Posts due auto-log instances that no real transaction covers, then
     * rolls each processed rule forward to its next due date.
     * Returns the number of instances actually posted.
     */
    suspend fun postDueAutoLogs(today: LocalDate = LocalDate.now(zone)): Int {
        var posted = 0
        for ((rule, covered) in dueRules(today)) {
            if (rule.autoLog && !covered) {
                val amount = rule.amountPaise
                if (amount != null) {
                    val now = System.currentTimeMillis()
                    transactionDao.insert(
                        TransactionEntity(
                            accountId = rule.accountId,
                            categoryId = rule.categoryId,
                            amountPaise = amount,
                            type = TxnType.DEBIT,
                            merchantRaw = rule.label,
                            merchantNormalized = rule.merchantPattern,
                            timestampMillis = rule.nextDueDateMillis,
                            source = TxnSource.RECURRING,
                            confidence = 1.0,
                            recurringRuleId = rule.id,
                            status = TxnStatus.COMMITTED,
                            createdAtMillis = now,
                            updatedAtMillis = now,
                        ),
                    )
                    balanceMaintainer.recompute(rule.accountId)
                    posted++
                }
            }
            // Roll forward whether or not we posted: a covered instance is
            // already in the ledger via the linked real transaction.
            val next = RecurringEngine.nextDueDate(
                localDate(rule.nextDueDateMillis),
                rule.frequency.toEngine(),
            )
            planningDao.setNextDue(rule.id, next.atStartOfDay(zone).toInstant().toEpochMilli())
        }
        return posted
    }

    /** A real transaction already linked to this rule inside its due window. */
    private suspend fun isCovered(rule: RecurringRuleEntity): Boolean {
        val window = RecurringEngine.matchWindow(localDate(rule.nextDueDateMillis))
        val from = window.start.atStartOfDay(zone).toInstant().toEpochMilli()
        val to = window.endInclusive.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return transactionDao.inWindow(from, to).any { txn ->
            txn.recurringRuleId == rule.id ||
                (
                    rule.amountPaise != null &&
                        txn.amountPaise == rule.amountPaise &&
                        txn.accountId == rule.accountId &&
                        txn.source != TxnSource.RECURRING
                    )
        }
    }

    private fun localDate(epochMillis: Long): LocalDate =
        java.time.Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate()
}

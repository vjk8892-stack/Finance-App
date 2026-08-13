package dev.kosha.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import dev.kosha.core.database.model.BudgetEntity
import dev.kosha.core.database.model.IncomeSourceEntity
import dev.kosha.core.database.model.PeriodSummaryEntity
import dev.kosha.core.database.model.RecurringRuleEntity
import kotlinx.coroutines.flow.Flow

/** Budgets, income sources, period summaries, recurring rules. */
@Dao
interface PlanningDao {

    // Budgets
    @Insert
    suspend fun insertBudget(budget: BudgetEntity): Long

    @Update
    suspend fun updateBudget(budget: BudgetEntity)

    @Query("UPDATE budgets SET isActive = 0 WHERE id = :id")
    suspend fun deactivateBudget(id: Long)

    @Query("SELECT * FROM budgets WHERE isActive = 1")
    fun observeBudgets(): Flow<List<BudgetEntity>>

    // Income sources
    @Insert
    suspend fun insertIncomeSource(source: IncomeSourceEntity): Long

    @Update
    suspend fun updateIncomeSource(source: IncomeSourceEntity)

    @Query("UPDATE income_sources SET isActive = 0 WHERE id = :id")
    suspend fun deactivateIncomeSource(id: Long)

    @Query("SELECT * FROM income_sources WHERE isActive = 1 ORDER BY id")
    fun observeIncomeSources(): Flow<List<IncomeSourceEntity>>

    @Query("SELECT * FROM income_sources WHERE isActive = 1 ORDER BY id")
    suspend fun incomeSourcesOnce(): List<IncomeSourceEntity>

    @Query("SELECT * FROM budgets WHERE isActive = 1")
    suspend fun budgetsOnce(): List<BudgetEntity>

    // Period summaries — immutable once written (spec B5)
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertPeriodSummary(summary: PeriodSummaryEntity): Long

    @Query("SELECT * FROM period_summaries ORDER BY periodStartMillis DESC")
    fun observePeriodSummaries(): Flow<List<PeriodSummaryEntity>>

    @Query("SELECT * FROM period_summaries WHERE periodStartMillis = :startMillis LIMIT 1")
    suspend fun summaryForPeriodStart(startMillis: Long): PeriodSummaryEntity?

    // Recurring rules
    @Insert
    suspend fun insertRecurringRule(rule: RecurringRuleEntity): Long

    @Update
    suspend fun updateRecurringRule(rule: RecurringRuleEntity)

    @Query("SELECT * FROM recurring_rules WHERE isActive = 1 ORDER BY nextDueDateMillis")
    fun observeRecurringRules(): Flow<List<RecurringRuleEntity>>

    @Query("SELECT * FROM recurring_rules WHERE isActive = 1 AND nextDueDateMillis <= :byMillis")
    suspend fun rulesDueBy(byMillis: Long): List<RecurringRuleEntity>

    @Query("SELECT * FROM recurring_rules WHERE isActive = 1 ORDER BY nextDueDateMillis")
    suspend fun activeRecurringRules(): List<RecurringRuleEntity>

    @Query("UPDATE recurring_rules SET isActive = 0 WHERE id = :id")
    suspend fun deactivateRecurringRule(id: Long)

    @Query("UPDATE recurring_rules SET nextDueDateMillis = :nextDueMillis WHERE id = :id")
    suspend fun setNextDue(id: Long, nextDueMillis: Long)
}

package dev.kosha.core.engine.insight

import dev.kosha.core.common.Money
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase-7 exit gate: every insight number reproducible by hand, and the
 * score's active-component set matches its phase (no claiming debt or
 * emergency-fund coverage it cannot yet measure).
 */
class HealthScoreTest {

    private fun input(
        savingsGap: Money = Money.ofRupees(20_000),
        income: Money = Money.ofRupees(100_000),
        emergencyFund: Money? = null,
        avgExpense: Money = Money.ofRupees(50_000),
        budgets: Int = 4,
        overBudget: Int = 1,
        emi: Money? = null,
        closedPeriods: Int = 3,
    ) = HealthScore.Input(
        savingsGap = savingsGap,
        actualIncome = income,
        emergencyFundBalance = emergencyFund,
        averageMonthlyExpense = avgExpense,
        categoriesWithBudgets = budgets,
        categoriesOverBudget = overBudget,
        monthlyEmiOutflow = emi,
        closedPeriods = closedPeriods,
    )

    @Test
    fun `fewer than two closed periods shows collecting data, not a number`() {
        val result = HealthScore.compute(input(closedPeriods = 1))
        assertTrue(result is HealthScore.Result.CollectingData)
    }

    @Test
    fun `phase 7 mode - two components, weights redistributed to 100`() {
        // No goals/debt UI yet: only SavingsRate (35) + BudgetDiscipline (20)
        // are measurable → weights scale by 100/55.
        val result = HealthScore.compute(input()) as HealthScore.Result.Score
        val components = result.breakdown.map { it.component }.toSet()
        assertEquals(
            setOf(HealthScore.Component.SAVINGS_RATE, HealthScore.Component.BUDGET_DISCIPLINE),
            components,
        )
        assertEquals(100.0, result.breakdown.sumOf { it.appliedWeight }, 1e-6)

        // Hand-check: savings 20,000/100,000 = 20% → 0.20/0.40 = 0.5 normalized.
        // Discipline: 1 - 1/4 = 0.75.
        // Weights: 35 → 63.636, 20 → 36.364.
        // Score = 0.5(63.636) + 0.75(36.364) = 31.818 + 27.273 = 59.09 → 59
        assertEquals(59, result.value)
    }

    @Test
    fun `phase 9 mode - all four components active`() {
        val result = HealthScore.compute(
            input(
                emergencyFund = Money.ofRupees(150_000), // 3 months of 50,000
                emi = Money.ofRupees(25_000), // 25% of income
            ),
        ) as HealthScore.Result.Score

        assertEquals(4, result.breakdown.size)
        // Full weights, no redistribution.
        assertEquals(35.0, result.breakdown.first { it.component == HealthScore.Component.SAVINGS_RATE }.appliedWeight, 1e-6)

        // Hand-check: savings 0.5 × 35 = 17.5
        // EmergencyCover 3/3 = 1.0 × 25 = 25
        // Discipline 0.75 × 20 = 15
        // DebtLoad 1 - (0.25/0.5) = 0.5 × 20 = 10
        // Total = 67.5 → 68 (round half up)
        assertEquals(68, result.value)
    }

    @Test
    fun `full marks at a 40 percent savings rate, capped above it`() {
        val at40 = HealthScore.compute(
            input(savingsGap = Money.ofRupees(40_000), budgets = 0),
        ) as HealthScore.Result.Score
        val at60 = HealthScore.compute(
            input(savingsGap = Money.ofRupees(60_000), budgets = 0),
        ) as HealthScore.Result.Score
        assertEquals(at40.value, at60.value)
        assertEquals(1.0, at40.breakdown.first().normalized, 1e-9)
    }

    @Test
    fun `no budgets counts as neutral, not perfect`() {
        val result = HealthScore.compute(input(budgets = 0, overBudget = 0)) as HealthScore.Result.Score
        val discipline = result.breakdown.first { it.component == HealthScore.Component.BUDGET_DISCIPLINE }
        assertEquals(0.5, discipline.normalized, 1e-9)
        assertTrue(discipline.explanation.contains("neutral"))
    }

    @Test
    fun `debt load scores zero at a 50 percent emi ratio`() {
        val result = HealthScore.compute(
            input(emi = Money.ofRupees(50_000), emergencyFund = Money.ZERO),
        ) as HealthScore.Result.Score
        val debt = result.breakdown.first { it.component == HealthScore.Component.DEBT_LOAD }
        assertEquals(0.0, debt.normalized, 1e-9)
    }

    @Test
    fun `every active component explains itself`() {
        val result = HealthScore.compute(
            input(emergencyFund = Money.ofRupees(100_000), emi = Money.ofRupees(10_000)),
        ) as HealthScore.Result.Score
        assertTrue(result.breakdown.all { it.explanation.isNotBlank() })
        assertFalse(result.breakdown.any { it.explanation.contains("null") })
    }

    @Test
    fun `zero income does not divide by zero`() {
        val result = HealthScore.compute(
            input(savingsGap = Money.ZERO, income = Money.ZERO, emi = Money.ofRupees(5_000)),
        ) as HealthScore.Result.Score
        assertTrue(result.value in 0..100)
    }
}

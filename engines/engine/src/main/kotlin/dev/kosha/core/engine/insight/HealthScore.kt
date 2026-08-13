package dev.kosha.core.engine.insight

import dev.kosha.core.common.Money
import kotlin.math.roundToInt

/**
 * Financial health score, spec G4:
 *
 *   Score = 35·SavingsRate + 25·EmergencyCover + 20·BudgetDiscipline + 20·DebtLoad
 *
 * Phase-sequencing rule (G4): EmergencyCover and DebtLoad read from
 * FinancialGoal/DebtAccount, whose management UI ships in Phase 9. Until a
 * component can be *measured*, it is EXCLUDED and its weight redistributed
 * proportionally — a zero-debt score must mean verified zero debt, never
 * "no UI to enter debt yet".
 */
object HealthScore {

    enum class Component(val weight: Double) {
        SAVINGS_RATE(35.0),
        EMERGENCY_COVER(25.0),
        BUDGET_DISCIPLINE(20.0),
        DEBT_LOAD(20.0),
    }

    const val MIN_CLOSED_PERIODS = 2

    data class Input(
        val savingsGap: Money,
        val actualIncome: Money,
        /** null when goals aren't manageable yet (pre-Phase 9). */
        val emergencyFundBalance: Money?,
        val averageMonthlyExpense: Money,
        val emergencyFundMonthsTarget: Int = 3,
        val categoriesWithBudgets: Int,
        val categoriesOverBudget: Int,
        /** null when debts aren't manageable yet (pre-Phase 9). */
        val monthlyEmiOutflow: Money?,
        val closedPeriods: Int,
    )

    data class Breakdown(
        val component: Component,
        /** Normalized 0..1 before weighting. */
        val normalized: Double,
        /** Weight actually applied after redistribution. */
        val appliedWeight: Double,
        val explanation: String,
    )

    sealed interface Result {
        /** Fewer than 2 closed periods — no fake precision (spec G4). */
        data class CollectingData(val closedPeriods: Int) : Result
        data class Score(val value: Int, val breakdown: List<Breakdown>) : Result
    }

    fun compute(input: Input): Result {
        if (input.closedPeriods < MIN_CLOSED_PERIODS) {
            return Result.CollectingData(input.closedPeriods)
        }

        val active = mutableListOf<Pair<Component, Pair<Double, String>>>()

        // SavingsRate: full marks at a 40% savings rate.
        val savingsRate = if (input.actualIncome.paise <= 0) {
            0.0
        } else {
            (input.savingsGap.paise.toDouble() / input.actualIncome.paise).coerceIn(0.0, 0.40) / 0.40
        }
        active += Component.SAVINGS_RATE to (
            savingsRate to "saved ${pct(input.savingsGap, input.actualIncome)}% of income (full marks at 40%)"
            )

        // EmergencyCover: full marks at the user's month target (default 3, G12).
        if (input.emergencyFundBalance != null) {
            val target = input.emergencyFundMonthsTarget.coerceIn(3, 12)
            val months = if (input.averageMonthlyExpense.paise <= 0) {
                0.0
            } else {
                input.emergencyFundBalance.paise.toDouble() / input.averageMonthlyExpense.paise
            }
            val normalized = (months / target).coerceIn(0.0, 1.0)
            active += Component.EMERGENCY_COVER to (
                normalized to "emergency fund covers ${months.roundToInt()} of $target target months"
                )
        }

        // BudgetDiscipline: 0.5 neutral when no budgets are set.
        val discipline = if (input.categoriesWithBudgets <= 0) {
            0.5
        } else {
            1.0 - (input.categoriesOverBudget.toDouble() / input.categoriesWithBudgets)
        }
        active += Component.BUDGET_DISCIPLINE to (
            discipline.coerceIn(0.0, 1.0) to if (input.categoriesWithBudgets <= 0) {
                "no budgets set yet — counted as neutral"
            } else {
                "${input.categoriesOverBudget} of ${input.categoriesWithBudgets} budgets exceeded"
            }
            )

        // DebtLoad: zero marks at a 50% EMI-to-income ratio.
        if (input.monthlyEmiOutflow != null) {
            val ratio = if (input.actualIncome.paise <= 0) {
                0.5
            } else {
                (input.monthlyEmiOutflow.paise.toDouble() / input.actualIncome.paise).coerceIn(0.0, 0.5)
            }
            active += Component.DEBT_LOAD to (
                (1.0 - ratio / 0.5) to "EMIs take ${pct(input.monthlyEmiOutflow, input.actualIncome)}% of income"
                )
        }

        // Redistribute the weights of excluded components proportionally.
        val activeWeight = active.sumOf { it.first.weight }
        val scale = if (activeWeight <= 0) 0.0 else 100.0 / activeWeight

        val breakdown = active.map { (component, value) ->
            Breakdown(
                component = component,
                normalized = value.first,
                appliedWeight = component.weight * scale,
                explanation = value.second,
            )
        }
        val score = breakdown.sumOf { it.normalized * it.appliedWeight }
        return Result.Score(score.roundToInt().coerceIn(0, 100), breakdown)
    }

    private fun pct(part: Money, whole: Money): Int =
        if (whole.paise <= 0) 0 else ((part.paise * 100) / whole.paise).toInt()
}

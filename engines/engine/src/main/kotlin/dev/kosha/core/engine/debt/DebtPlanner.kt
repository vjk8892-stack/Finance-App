package dev.kosha.core.engine.debt

import dev.kosha.core.common.Money
import kotlin.math.roundToLong

/**
 * Debt payoff simulation, spec Phase 9. Avalanche (highest rate first) vs
 * snowball (smallest balance first), both amortized month by month so the
 * output matches a spreadsheet — the Phase-9 exit gate.
 *
 * Rates are basis points (950 = 9.50%) so nothing is floating point until
 * the monthly interest calculation, which rounds to whole paise.
 */
object DebtPlanner {

    enum class Strategy { AVALANCHE, SNOWBALL }

    data class Debt(
        val id: Long,
        val name: String,
        val principal: Money,
        val rateBps: Int,
        val minimumPayment: Money,
    )

    data class MonthlyStep(
        val month: Int,
        val debtId: Long,
        val interestPaid: Money,
        val principalPaid: Money,
        val remaining: Money,
    )

    data class DebtOutcome(
        val debtId: Long,
        val name: String,
        val monthsToPayoff: Int,
        val totalInterest: Money,
    )

    data class Plan(
        val strategy: Strategy,
        val monthsToDebtFree: Int,
        val totalInterest: Money,
        val totalPaid: Money,
        val perDebt: List<DebtOutcome>,
        val schedule: List<MonthlyStep>,
    )

    const val MAX_MONTHS = 600 // 50 years — a guard, not a real horizon

    /**
     * @param extraPerMonth surplus applied to the focus debt on top of every
     *   minimum payment; freed-up minimums roll into the focus debt as each
     *   debt clears (the "snowball" effect, applied to both strategies).
     */
    fun simulate(
        debts: List<Debt>,
        strategy: Strategy,
        extraPerMonth: Money = Money.ZERO,
    ): Plan {
        val balances = debts.associate { it.id to it.principal.paise }.toMutableMap()
        val interestPaid = debts.associate { it.id to 0L }.toMutableMap()
        val payoffMonth = mutableMapOf<Long, Int>()
        val schedule = mutableListOf<MonthlyStep>()
        var month = 0

        while (balances.values.any { it > 0 } && month < MAX_MONTHS) {
            month++

            // 1. Accrue interest on every outstanding debt.
            for (debt in debts) {
                val balance = balances.getValue(debt.id)
                if (balance <= 0) continue
                val monthlyInterest = (balance * debt.rateBps / 10_000.0 / 12.0).roundToLong()
                balances[debt.id] = balance + monthlyInterest
                interestPaid[debt.id] = interestPaid.getValue(debt.id) + monthlyInterest
            }

            // 2. Budget = every minimum (including ones freed by cleared
            //    debts) + the user's extra.
            var budget = debts.sumOf { it.minimumPayment.paise } + extraPerMonth.paise

            // 3. Pay minimums on non-focus debts first.
            val outstanding = debts.filter { balances.getValue(it.id) > 0 }
            val focus = focusDebt(outstanding, balances, strategy) ?: break

            val paidThisMonth = mutableMapOf<Long, Long>()
            for (debt in outstanding) {
                if (debt.id == focus.id) continue
                val payment = minOf(debt.minimumPayment.paise, balances.getValue(debt.id), budget)
                if (payment <= 0) continue
                balances[debt.id] = balances.getValue(debt.id) - payment
                budget -= payment
                paidThisMonth[debt.id] = payment
            }

            // 4. Everything left goes at the focus debt.
            val focusPayment = minOf(budget, balances.getValue(focus.id))
            if (focusPayment > 0) {
                balances[focus.id] = balances.getValue(focus.id) - focusPayment
                budget -= focusPayment
                paidThisMonth[focus.id] = (paidThisMonth[focus.id] ?: 0) + focusPayment
            }

            for ((debtId, payment) in paidThisMonth) {
                val remaining = balances.getValue(debtId)
                schedule += MonthlyStep(
                    month = month,
                    debtId = debtId,
                    interestPaid = Money(interestPaid.getValue(debtId)),
                    principalPaid = Money(payment),
                    remaining = Money(remaining),
                )
                if (remaining <= 0 && debtId !in payoffMonth) payoffMonth[debtId] = month
            }

            // No progress possible (minimums don't cover interest) — stop
            // rather than looping to the guard.
            if (paidThisMonth.values.sum() == 0L) break
        }

        return Plan(
            strategy = strategy,
            monthsToDebtFree = month,
            totalInterest = Money(interestPaid.values.sum()),
            totalPaid = Money(debts.sumOf { it.principal.paise } + interestPaid.values.sum()),
            perDebt = debts.map { debt ->
                DebtOutcome(
                    debtId = debt.id,
                    name = debt.name,
                    monthsToPayoff = payoffMonth[debt.id] ?: month,
                    totalInterest = Money(interestPaid.getValue(debt.id)),
                )
            },
            schedule = schedule,
        )
    }

    /** Side-by-side comparison for the planner UI (spec C7). */
    data class Comparison(
        val avalanche: Plan,
        val snowball: Plan,
        val interestSaved: Money,
        val monthsSaved: Int,
    )

    fun compare(debts: List<Debt>, extraPerMonth: Money = Money.ZERO): Comparison {
        val avalanche = simulate(debts, Strategy.AVALANCHE, extraPerMonth)
        val snowball = simulate(debts, Strategy.SNOWBALL, extraPerMonth)
        return Comparison(
            avalanche = avalanche,
            snowball = snowball,
            interestSaved = Money(snowball.totalInterest.paise - avalanche.totalInterest.paise),
            monthsSaved = snowball.monthsToDebtFree - avalanche.monthsToDebtFree,
        )
    }

    private fun focusDebt(
        outstanding: List<Debt>,
        balances: Map<Long, Long>,
        strategy: Strategy,
    ): Debt? = when (strategy) {
        // Highest rate first; ties broken by the smaller balance.
        Strategy.AVALANCHE -> outstanding.minWithOrNull(
            compareByDescending<Debt> { it.rateBps }.thenBy { balances.getValue(it.id) },
        )
        // Smallest balance first; ties broken by the higher rate.
        Strategy.SNOWBALL -> outstanding.minWithOrNull(
            compareBy<Debt> { balances.getValue(it.id) }.thenByDescending { it.rateBps },
        )
    }
}

/** Net worth (spec C7): assets − liabilities, with tracked debts included once. */
object NetWorthCalculator {

    data class Item(val label: String, val value: Money, val isLiability: Boolean)

    data class NetWorth(
        val assets: Money,
        val liabilities: Money,
        val net: Money,
        val items: List<Item>,
    )

    /**
     * @param manualAssets user-entered assets (property, gold, EPF…)
     * @param manualLiabilities informal loans NOT modeled as a DebtAccount
     * @param trackedDebtBalances remaining balances of DebtAccounts — the
     *   authoritative source, so the same loan is never counted twice (B5)
     */
    fun compute(
        manualAssets: List<Item>,
        manualLiabilities: List<Item>,
        trackedDebtBalances: List<Item>,
        accountBalances: Money,
    ): NetWorth {
        val assetTotal = manualAssets.sumOf { it.value.paise } + accountBalances.paise
        val liabilityTotal = manualLiabilities.sumOf { it.value.paise } +
            trackedDebtBalances.sumOf { it.value.paise }
        return NetWorth(
            assets = Money(assetTotal),
            liabilities = Money(liabilityTotal),
            net = Money(assetTotal - liabilityTotal),
            items = manualAssets + manualLiabilities + trackedDebtBalances,
        )
    }
}

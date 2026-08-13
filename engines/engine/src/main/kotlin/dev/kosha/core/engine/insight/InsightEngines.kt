package dev.kosha.core.engine.insight

import dev.kosha.core.common.Money
import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * Spending-leak detector (spec C5.7): small, frequent, easily-invisible
 * spends, annualized so the real cost is legible. "₹120 × 18/month = ₹25,920
 * a year" is the whole point.
 */
object LeakDetector {

    val MAX_LEAK_AMOUNT = Money.ofRupees(500)
    const val MIN_OCCURRENCES = 6
    const val WINDOW_DAYS = 90

    data class Spend(val merchantNormalized: String, val amount: Money, val timestampMillis: Long)

    data class Leak(
        val merchant: String,
        val occurrences: Int,
        val total: Money,
        val averageAmount: Money,
        val monthlyRate: Money,
        val annualized: Money,
    )

    /** [windowDays] of history; only micro-spends repeated often enough count. */
    fun detect(spends: List<Spend>, windowDays: Int = WINDOW_DAYS): List<Leak> {
        if (windowDays <= 0) return emptyList()
        return spends
            .filter { it.amount.paise in 1..MAX_LEAK_AMOUNT.paise }
            .groupBy { it.merchantNormalized }
            .filterValues { it.size >= MIN_OCCURRENCES }
            .map { (merchant, group) ->
                val total = group.sumOf { it.amount.paise }
                val monthly = (total.toDouble() / windowDays * 30.0).roundToLong()
                Leak(
                    merchant = merchant,
                    occurrences = group.size,
                    total = Money(total),
                    averageAmount = Money(total / group.size),
                    monthlyRate = Money(monthly),
                    annualized = Money(monthly * 12),
                )
            }
            .sortedByDescending { it.annualized.paise }
    }
}

/**
 * What-if simulator (spec C5.8): cut a category by X% → annual impact.
 * Deliberately linear and transparent — no hidden modelling.
 */
object WhatIfSimulator {

    data class Result(
        val monthlySaving: Money,
        val annualSaving: Money,
        val newMonthlySpend: Money,
    )

    fun simulate(currentMonthlySpend: Money, cutPercent: Int): Result {
        val pct = cutPercent.coerceIn(0, 100)
        val monthlySaving = Money(currentMonthlySpend.paise * pct / 100)
        return Result(
            monthlySaving = monthlySaving,
            annualSaving = Money(monthlySaving.paise * 12),
            newMonthlySpend = currentMonthlySpend - monthlySaving,
        )
    }
}

/**
 * Retroactive opportunity-cost simulator (spec C5.9). The benchmark rate is
 * the USER'S OWN assumption and the output is explicitly hypothetical —
 * this is arithmetic on their number, never a projection or a recommendation.
 */
object OpportunityCostSimulator {

    data class Result(
        val invested: Money,
        val hypotheticalValue: Money,
        val difference: Money,
        val years: Double,
        val annualRatePercent: Double,
    )

    /**
     * Monthly contributions of [monthlyAmount] for [months], compounded
     * monthly at [annualRatePercent] / 12 — a standard future-value-of-an-
     * annuity, computed on the user's own rate assumption.
     */
    fun simulate(monthlyAmount: Money, months: Int, annualRatePercent: Double): Result {
        val n = months.coerceAtLeast(0)
        val invested = Money(monthlyAmount.paise * n)
        if (n == 0) {
            return Result(invested, invested, Money.ZERO, 0.0, annualRatePercent)
        }
        val monthlyRate = annualRatePercent / 100.0 / 12.0
        val futureValuePaise = if (monthlyRate == 0.0) {
            invested.paise.toDouble()
        } else {
            monthlyAmount.paise * (((1 + monthlyRate).pow(n) - 1) / monthlyRate)
        }
        val future = Money(futureValuePaise.roundToLong())
        return Result(
            invested = invested,
            hypotheticalValue = future,
            difference = future - invested,
            years = n / 12.0,
            annualRatePercent = annualRatePercent,
        )
    }
}

/**
 * Surplus advisory rule engine (spec C5.6). HARD BOUNDARY: allocation
 * amounts only, never instruments or products — and every output carries
 * its reasoning, so the user can check the logic.
 */
object Advisor {

    data class Input(
        /** Trailing 4-period average surplus (spec wording: "4-month average"). */
        val averageSurplus: Money,
        val emergencyFundBalance: Money,
        val averageMonthlyExpense: Money,
        val emergencyFundMonthsTarget: Int = 3,
        val highInterestDebtOutstanding: Money = Money.ZERO,
        /** Goal name → remaining amount, in the user's priority order. */
        val goalShortfalls: List<Pair<String, Money>> = emptyList(),
    )

    data class Allocation(val label: String, val amount: Money, val reason: String)

    data class Advice(
        val allocations: List<Allocation>,
        val reasoning: String,
        /** Months to fully fund the emergency target at the current surplus. */
        val monthsToEmergencyTarget: Int?,
    )

    fun advise(input: Input): Advice {
        val surplus = input.averageSurplus
        if (surplus.paise <= 0) {
            return Advice(
                allocations = emptyList(),
                reasoning = "Your recent periods have no surplus to allocate — " +
                    "the first win is closing the gap, not placing it.",
                monthsToEmergencyTarget = null,
            )
        }

        val target = Money(input.averageMonthlyExpense.paise * input.emergencyFundMonthsTarget.coerceIn(3, 12))
        val emergencyShortfall = Money((target.paise - input.emergencyFundBalance.paise).coerceAtLeast(0))
        val allocations = mutableListOf<Allocation>()
        var remaining = surplus

        // 1. Emergency fund first — always.
        if (emergencyShortfall.paise > 0) {
            val amount = Money(minOf(remaining.paise, maxOf(surplus.paise / 2, 1)))
            allocations += Allocation(
                label = "Emergency fund",
                amount = amount,
                reason = "still ${emergencyShortfall.format(withPaise = false)} short of " +
                    "${input.emergencyFundMonthsTarget} months of expenses",
            )
            remaining -= amount
        }

        // 2. High-interest debt next.
        if (remaining.paise > 0 && input.highInterestDebtOutstanding.paise > 0) {
            val amount = Money(minOf(remaining.paise, remaining.paise / 2 + remaining.paise % 2))
            allocations += Allocation(
                label = "Extra debt repayment",
                amount = amount,
                reason = "${input.highInterestDebtOutstanding.format(withPaise = false)} outstanding — " +
                    "repaying early costs nothing and removes interest",
            )
            remaining -= amount
        }

        // 3. Goals, in the user's priority order.
        for ((name, shortfall) in input.goalShortfalls) {
            if (remaining.paise <= 0) break
            val amount = Money(minOf(remaining.paise, shortfall.paise))
            if (amount.paise <= 0) continue
            allocations += Allocation(
                label = name,
                amount = amount,
                reason = "${shortfall.format(withPaise = false)} left to reach this goal",
            )
            remaining -= amount
        }

        // 4. Anything left stays unallocated — deliberately not "invested".
        if (remaining.paise > 0) {
            allocations += Allocation(
                label = "Unallocated",
                amount = remaining,
                reason = "yours to direct — Kosha suggests amounts, never products",
            )
        }

        val monthsToTarget = if (emergencyShortfall.paise <= 0) {
            0
        } else {
            val perMonth = allocations.firstOrNull { it.label == "Emergency fund" }?.amount?.paise
            if (perMonth == null || perMonth <= 0) {
                null
            } else {
                ((emergencyShortfall.paise + perMonth - 1) / perMonth).toInt()
            }
        }

        return Advice(
            allocations = allocations,
            reasoning = "because your average surplus over recent periods is " +
                surplus.format(withPaise = false),
            monthsToEmergencyTarget = monthsToTarget,
        )
    }
}

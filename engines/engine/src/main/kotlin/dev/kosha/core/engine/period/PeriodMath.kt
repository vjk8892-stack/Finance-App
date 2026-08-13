package dev.kosha.core.engine.period

import dev.kosha.core.common.Money

/**
 * Period aggregation (spec C2/B5 PeriodSummary) — pure math, hand-verifiable.
 *
 * Rules:
 *  - Transfer legs are excluded from income/expense/savings-gap math (B5).
 *  - System categories Transfers + Cash Withdrawal are excluded from spend
 *    analytics (G12).
 *  - Split children carry the categories; the parent carries the money.
 *    Category totals use children when present, else the parent; overall
 *    income/expense totals use parents only — never both (B5).
 *  - Only committed transactions count.
 */
object PeriodMath {

    enum class Direction { DEBIT, CREDIT }

    data class Txn(
        val id: Long,
        val amountPaise: Long,
        val direction: Direction,
        val categoryId: Long?,
        val parentId: Long? = null,
        val isTransferLeg: Boolean = false,
        val isAnalyticsExcludedCategory: Boolean = false,
    )

    data class Totals(
        val actualIncome: Money,
        val totalExpense: Money,
        val savingsGap: Money,
        /** Income expected but not traced this period (≥ 0). */
        val untrackedGap: Money,
    )

    fun totals(txns: List<Txn>, expectedIncome: Money): Totals {
        val parents = txns.filter { it.parentId == null }
        var income = 0L
        var expense = 0L
        for (t in parents) {
            if (t.isTransferLeg || t.isAnalyticsExcludedCategory) continue
            when (t.direction) {
                Direction.CREDIT -> income += t.amountPaise
                Direction.DEBIT -> expense += t.amountPaise
            }
        }
        val gap = income - expense
        return Totals(
            actualIncome = Money(income),
            totalExpense = Money(expense),
            savingsGap = Money(gap),
            untrackedGap = Money((expectedIncome.paise - income).coerceAtLeast(0)),
        )
    }

    /**
     * Spend per category honoring splits: a parent with children contributes
     * its children's amounts (each to the child's category); a childless
     * parent contributes its own amount to its own category.
     */
    fun spendByCategory(txns: List<Txn>): Map<Long?, Money> {
        val childrenByParent = txns.filter { it.parentId != null }.groupBy { it.parentId }
        val result = mutableMapOf<Long?, Long>()
        for (t in txns) {
            if (t.parentId != null) continue // children contribute via their parent below
            if (t.isTransferLeg || t.isAnalyticsExcludedCategory) continue
            if (t.direction != Direction.DEBIT) continue
            val children = childrenByParent[t.id]
            if (children.isNullOrEmpty()) {
                result.merge(t.categoryId, t.amountPaise, Long::plus)
            } else {
                for (c in children) {
                    result.merge(c.categoryId, c.amountPaise, Long::plus)
                }
            }
        }
        return result.mapValues { Money(it.value) }
    }

    /** Financial-weather tone bands (spec C2): never alarmist, amber at worst. */
    enum class WeatherTone { AHEAD, ON_TRACK, HEADS_UP }

    fun weatherTone(totals: Totals, expectedIncome: Money): WeatherTone {
        val gap = totals.savingsGap.paise
        if (gap < 0) return WeatherTone.HEADS_UP
        val reference = maxOf(expectedIncome.paise, totals.actualIncome.paise)
        if (reference > 0 && gap >= reference / 10) return WeatherTone.AHEAD
        return WeatherTone.ON_TRACK
    }
}

/** Budget progress math (spec C7/C2 rings). */
object BudgetMath {

    data class BudgetProgress(
        val budgetId: Long,
        val categoryId: Long?,
        val limit: Money,
        val spent: Money,
        val alertThresholdPct: Int,
    ) {
        val fraction: Float
            get() = if (limit.paise <= 0) 0f else (spent.paise.toFloat() / limit.paise).coerceIn(0f, 1f)
        val pct: Int
            get() = if (limit.paise <= 0) 0 else ((spent.paise * 100) / limit.paise).toInt()
        val isAtThreshold: Boolean get() = pct >= alertThresholdPct
        val isOver: Boolean get() = spent.paise > limit.paise
    }

    data class Budget(
        val id: Long,
        val categoryId: Long?,
        val limitPaise: Long,
        val alertThresholdPct: Int,
    )

    fun progress(
        budgets: List<Budget>,
        spendByCategory: Map<Long?, Money>,
    ): List<BudgetProgress> {
        val totalSpend = Money(spendByCategory.values.sumOf { it.paise })
        return budgets.map { b ->
            val spent = if (b.categoryId == null) {
                totalSpend // overall budget
            } else {
                spendByCategory[b.categoryId] ?: Money.ZERO
            }
            BudgetProgress(
                budgetId = b.id,
                categoryId = b.categoryId,
                limit = Money(b.limitPaise),
                spent = spent,
                alertThresholdPct = b.alertThresholdPct,
            )
        }
    }
}

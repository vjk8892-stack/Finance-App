package dev.kosha.core.engine.period

import dev.kosha.core.common.Money
import dev.kosha.core.engine.period.PeriodMath.Direction
import dev.kosha.core.engine.period.PeriodMath.Txn
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Phase-3 exit gate: month-end close produces correct numbers against
 * hand-verified figures. Every case here is hand-computable from the inputs.
 */
class PeriodMathTest {

    private fun debit(id: Long, rupees: Long, categoryId: Long? = 1, parentId: Long? = null) =
        Txn(id, rupees * 100, Direction.DEBIT, categoryId, parentId)

    private fun credit(id: Long, rupees: Long, categoryId: Long? = 20) =
        Txn(id, rupees * 100, Direction.CREDIT, categoryId)

    @Test
    fun `hand-verified month - salary 85000, spends 42350, gap 42650`() {
        val txns = listOf(
            credit(1, 85_000),
            debit(2, 12_000, categoryId = 7),  // rent
            debit(3, 8_450, categoryId = 1),   // dining
            debit(4, 15_000, categoryId = 8),  // emi
            debit(5, 6_900, categoryId = 2),   // groceries
        )
        val totals = PeriodMath.totals(txns, expectedIncome = Money.ofRupees(85_000))
        assertEquals(Money.ofRupees(85_000), totals.actualIncome)
        assertEquals(Money.ofRupees(42_350), totals.totalExpense)
        assertEquals(Money.ofRupees(42_650), totals.savingsGap)
        assertEquals(Money.ZERO, totals.untrackedGap)
    }

    @Test
    fun `transfer legs never touch income or expense`() {
        val txns = listOf(
            credit(1, 50_000),
            debit(2, 5_000, categoryId = 1),
            // self-transfer bank -> wallet: both legs excluded
            Txn(3, 10_000_00, Direction.DEBIT, 17, isTransferLeg = true),
            Txn(4, 10_000_00, Direction.CREDIT, 17, isTransferLeg = true),
        )
        val totals = PeriodMath.totals(txns, expectedIncome = Money.ofRupees(50_000))
        assertEquals(Money.ofRupees(50_000), totals.actualIncome)
        assertEquals(Money.ofRupees(5_000), totals.totalExpense)
        assertEquals(Money.ofRupees(45_000), totals.savingsGap)
    }

    @Test
    fun `analytics-excluded categories are out of spend math`() {
        val txns = listOf(
            credit(1, 30_000),
            debit(2, 2_000, categoryId = 1),
            // Cash Withdrawal with no cash account: money not spent yet (G2)
            Txn(3, 5_000_00, Direction.DEBIT, 18, isAnalyticsExcludedCategory = true),
        )
        val totals = PeriodMath.totals(txns, expectedIncome = Money.ofRupees(30_000))
        assertEquals(Money.ofRupees(2_000), totals.totalExpense)
        assertEquals(Money.ofRupees(28_000), totals.savingsGap)
    }

    @Test
    fun `untracked gap is expected minus actual income, never negative`() {
        val short = PeriodMath.totals(listOf(credit(1, 60_000)), Money.ofRupees(85_000))
        assertEquals(Money.ofRupees(25_000), short.untrackedGap)

        val over = PeriodMath.totals(listOf(credit(1, 90_000)), Money.ofRupees(85_000))
        assertEquals(Money.ZERO, over.untrackedGap)
    }

    @Test
    fun `splits count once - children carry categories, parent carries money`() {
        // 3000 supermarket bill split: 1800 groceries + 1200 personal care
        val txns = listOf(
            credit(1, 40_000),
            debit(10, 3_000, categoryId = null),
            debit(11, 1_800, categoryId = 2, parentId = 10),
            debit(12, 1_200, categoryId = 15, parentId = 10),
        )
        val totals = PeriodMath.totals(txns, Money.ofRupees(40_000))
        // Expense counts the parent only — 3000, not 6000
        assertEquals(Money.ofRupees(3_000), totals.totalExpense)

        val byCategory = PeriodMath.spendByCategory(txns)
        assertEquals(Money.ofRupees(1_800), byCategory[2L])
        assertEquals(Money.ofRupees(1_200), byCategory[15L])
        assertEquals(null, byCategory[null])
    }

    @Test
    fun `spend by category uses parent when there are no children`() {
        val txns = listOf(debit(1, 500, categoryId = 1), debit(2, 250, categoryId = 1))
        assertEquals(Money.ofRupees(750), PeriodMath.spendByCategory(txns)[1L])
    }

    @Test
    fun `weather tone bands`() {
        val expected = Money.ofRupees(85_000)
        val ahead = PeriodMath.totals(listOf(credit(1, 85_000), debit(2, 40_000)), expected)
        assertEquals(PeriodMath.WeatherTone.AHEAD, PeriodMath.weatherTone(ahead, expected))

        val onTrack = PeriodMath.totals(listOf(credit(1, 85_000), debit(2, 84_000)), expected)
        assertEquals(PeriodMath.WeatherTone.ON_TRACK, PeriodMath.weatherTone(onTrack, expected))

        val headsUp = PeriodMath.totals(listOf(credit(1, 85_000), debit(2, 90_000)), expected)
        assertEquals(PeriodMath.WeatherTone.HEADS_UP, PeriodMath.weatherTone(headsUp, expected))
    }
}

class BudgetMathTest {

    @Test
    fun `per-category progress and threshold`() {
        val budgets = listOf(
            BudgetMath.Budget(id = 1, categoryId = 1, limitPaise = 800_000, alertThresholdPct = 80),
            BudgetMath.Budget(id = 2, categoryId = 2, limitPaise = 1_000_000, alertThresholdPct = 80),
        )
        val spend = mapOf<Long?, Money>(
            1L to Money.ofRupees(6_800), // 85% of 8000 → at threshold
            2L to Money.ofRupees(2_500), // 25% of 10000
        )
        val progress = BudgetMath.progress(budgets, spend).associateBy { it.budgetId }

        assertEquals(85, progress.getValue(1).pct)
        assertEquals(true, progress.getValue(1).isAtThreshold)
        assertEquals(false, progress.getValue(1).isOver)

        assertEquals(25, progress.getValue(2).pct)
        assertEquals(false, progress.getValue(2).isAtThreshold)
    }

    @Test
    fun `overall budget sums all category spend`() {
        val budgets = listOf(BudgetMath.Budget(id = 9, categoryId = null, limitPaise = 5_000_000, alertThresholdPct = 80))
        val spend = mapOf<Long?, Money>(1L to Money.ofRupees(20_000), 2L to Money.ofRupees(25_000))
        val progress = BudgetMath.progress(budgets, spend).single()
        assertEquals(Money.ofRupees(45_000), progress.spent)
        assertEquals(90, progress.pct)
        assertEquals(true, progress.isAtThreshold)
    }

    @Test
    fun `over budget is flagged but fraction clamps for the ring`() {
        val budgets = listOf(BudgetMath.Budget(id = 1, categoryId = 1, limitPaise = 100_000, alertThresholdPct = 80))
        val progress = BudgetMath.progress(budgets, mapOf<Long?, Money>(1L to Money.ofRupees(1_500))).single()
        assertEquals(true, progress.isOver)
        assertEquals(150, progress.pct)
        assertEquals(1f, progress.fraction, 1e-6f)
    }
}

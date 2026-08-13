package dev.kosha.core.engine.debt

import dev.kosha.core.common.Money
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase-9 exit gate: debt simulations match spreadsheet-verified
 * amortization.
 */
class DebtPlannerTest {

    private fun debt(id: Long, name: String, principal: Long, rateBps: Int, minimum: Long) =
        DebtPlanner.Debt(id, name, Money.ofRupees(principal), rateBps, Money.ofRupees(minimum))

    @Test
    fun `single debt amortizes like a spreadsheet`() {
        // 100,000 at 12% (1%/month), paying 10,000/month.
        // Month 1: interest 1,000 → balance 101,000, pay 10,000 → 91,000
        // Month 2: interest 910 → 91,910, pay 10,000 → 81,910
        // Month 3: interest 819.10 → 82,729.10, pay 10,000 → 72,729.10
        val plan = DebtPlanner.simulate(
            listOf(debt(1, "Personal loan", 100_000, 1200, 10_000)),
            DebtPlanner.Strategy.AVALANCHE,
        )
        val month1 = plan.schedule.first { it.month == 1 }
        assertEquals(Money.ofRupees(91_000), month1.remaining)

        val month2 = plan.schedule.first { it.month == 2 }
        assertEquals(Money.ofRupees(81_910), month2.remaining)

        val month3 = plan.schedule.first { it.month == 3 }
        assertEquals(Money(7_272_910), month3.remaining)

        // Paid off in 11 months, since interest keeps adding to the tail.
        assertEquals(11, plan.monthsToDebtFree)
        assertTrue(plan.totalInterest.paise > 0)
    }

    @Test
    fun `zero rate debt pays off in exactly principal over payment`() {
        val plan = DebtPlanner.simulate(
            listOf(debt(1, "Interest-free", 50_000, 0, 10_000)),
            DebtPlanner.Strategy.AVALANCHE,
        )
        assertEquals(5, plan.monthsToDebtFree)
        assertEquals(Money.ZERO, plan.totalInterest)
        assertEquals(Money.ofRupees(50_000), plan.totalPaid)
    }

    @Test
    fun `avalanche targets the highest rate first`() {
        val debts = listOf(
            debt(1, "Card", 50_000, 3600, 2_000), // 36%
            debt(2, "Car loan", 200_000, 900, 5_000), // 9%
        )
        val plan = DebtPlanner.simulate(debts, DebtPlanner.Strategy.AVALANCHE, Money.ofRupees(5_000))
        val cardPayoff = plan.perDebt.first { it.debtId == 1L }.monthsToPayoff
        val carPayoff = plan.perDebt.first { it.debtId == 2L }.monthsToPayoff
        assertTrue("card ($cardPayoff) should clear before car ($carPayoff)", cardPayoff < carPayoff)
    }

    @Test
    fun `snowball targets the smallest balance first`() {
        val debts = listOf(
            debt(1, "Big but cheap", 200_000, 900, 5_000),
            debt(2, "Small but pricey", 20_000, 3600, 1_000),
        )
        val plan = DebtPlanner.simulate(debts, DebtPlanner.Strategy.SNOWBALL, Money.ofRupees(5_000))
        val smallPayoff = plan.perDebt.first { it.debtId == 2L }.monthsToPayoff
        val bigPayoff = plan.perDebt.first { it.debtId == 1L }.monthsToPayoff
        assertTrue(smallPayoff < bigPayoff)
    }

    @Test
    fun `avalanche never costs more interest than snowball`() {
        val debts = listOf(
            debt(1, "Card", 80_000, 3600, 3_000),
            debt(2, "Personal", 40_000, 1400, 2_000),
            debt(3, "Car", 300_000, 900, 8_000),
        )
        val comparison = DebtPlanner.compare(debts, Money.ofRupees(10_000))
        assertTrue(
            "avalanche ${comparison.avalanche.totalInterest} vs snowball ${comparison.snowball.totalInterest}",
            comparison.avalanche.totalInterest.paise <= comparison.snowball.totalInterest.paise,
        )
        assertTrue(comparison.interestSaved.paise >= 0)
    }

    @Test
    fun `extra payments shorten the payoff`() {
        val debts = listOf(debt(1, "Loan", 100_000, 1200, 10_000))
        val base = DebtPlanner.simulate(debts, DebtPlanner.Strategy.AVALANCHE)
        val boosted = DebtPlanner.simulate(debts, DebtPlanner.Strategy.AVALANCHE, Money.ofRupees(10_000))
        assertTrue(boosted.monthsToDebtFree < base.monthsToDebtFree)
        assertTrue(boosted.totalInterest.paise < base.totalInterest.paise)
    }

    @Test
    fun `freed minimums roll into the remaining debt`() {
        // Once the small debt clears, its 5,000 minimum should accelerate the
        // big one rather than vanishing.
        val debts = listOf(
            debt(1, "Small", 10_000, 1200, 5_000),
            debt(2, "Big", 100_000, 1200, 5_000),
        )
        val plan = DebtPlanner.simulate(debts, DebtPlanner.Strategy.SNOWBALL)
        val smallCleared = plan.perDebt.first { it.debtId == 1L }.monthsToPayoff
        val afterClear = plan.schedule.filter { it.debtId == 2L && it.month == smallCleared + 1 }
        assertTrue(afterClear.isNotEmpty())
        // Full 10,000 budget goes at the big debt after the small one clears.
        assertEquals(Money.ofRupees(10_000), afterClear.first().principalPaid)
    }

    @Test
    fun `payments that never cover interest terminate instead of looping`() {
        val plan = DebtPlanner.simulate(
            listOf(debt(1, "Underwater", 100_000, 3600, 100)),
            DebtPlanner.Strategy.AVALANCHE,
        )
        assertTrue(plan.monthsToDebtFree <= DebtPlanner.MAX_MONTHS)
    }
}

class NetWorthCalculatorTest {

    @Test
    fun `net worth nets assets against liabilities`() {
        val result = NetWorthCalculator.compute(
            manualAssets = listOf(
                NetWorthCalculator.Item("Flat", Money.ofRupees(5_000_000), false),
                NetWorthCalculator.Item("Gold", Money.ofRupees(300_000), false),
            ),
            manualLiabilities = listOf(
                NetWorthCalculator.Item("Loan from family", Money.ofRupees(100_000), true),
            ),
            trackedDebtBalances = listOf(
                NetWorthCalculator.Item("Home loan", Money.ofRupees(3_000_000), true),
            ),
            accountBalances = Money.ofRupees(200_000),
        )
        assertEquals(Money.ofRupees(5_500_000), result.assets)
        assertEquals(Money.ofRupees(3_100_000), result.liabilities)
        assertEquals(Money.ofRupees(2_400_000), result.net)
    }

    @Test
    fun `negative net worth is reported honestly`() {
        val result = NetWorthCalculator.compute(
            manualAssets = emptyList(),
            manualLiabilities = emptyList(),
            trackedDebtBalances = listOf(
                NetWorthCalculator.Item("Education loan", Money.ofRupees(800_000), true),
            ),
            accountBalances = Money.ofRupees(50_000),
        )
        assertEquals(Money.ofRupees(-750_000), result.net)
    }
}

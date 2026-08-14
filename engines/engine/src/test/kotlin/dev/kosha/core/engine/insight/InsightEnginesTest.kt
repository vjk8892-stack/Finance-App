package dev.kosha.core.engine.insight

import dev.kosha.core.common.Money
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnomalyEngineTest {

    private fun candidate(amountRupees: Long, id: Long = 1) = AnomalyEngine.Candidate(
        transactionId = id,
        amount = Money.ofRupees(amountRupees),
        merchantNormalized = "SWIGGY",
        categoryId = 1,
        timestampMillis = 0,
    )

    private fun history(vararg rupees: Long, dismissals: Int = 0) =
        AnomalyEngine.History(rupees.map { Money.ofRupees(it) }, dismissals)

    @Test
    fun `cold start - no flags without enough history`() {
        // 4 merchant txns (<5) and 3 category txns (<8) → inactive
        assertNull(
            AnomalyEngine.evaluate(
                candidate(5_000),
                merchantHistory = history(300, 320, 280, 310),
                categoryHistory = history(300, 320, 280),
            ),
        )
    }

    @Test
    fun `outlier against a steady merchant history is flagged`() {
        val flag = AnomalyEngine.evaluate(
            candidate(5_000),
            merchantHistory = history(300, 320, 280, 310, 305, 295),
            categoryHistory = null,
        )
        assertNotNull(flag)
        assertEquals(AnomalyEngine.Scope.MERCHANT, flag!!.scope)
        // Median of [280,295,300,305,310,320] = (300+305)/2 = 302.50
        assertEquals(Money(30_250), flag.median)
        assertTrue(flag.explanation.contains("SWIGGY"))
    }

    @Test
    fun `an ordinary amount is not flagged`() {
        assertNull(
            AnomalyEngine.evaluate(
                candidate(310),
                merchantHistory = history(300, 320, 280, 310, 305, 295),
                categoryHistory = null,
            ),
        )
    }

    @Test
    fun `small absolute deviations are suppressed even when statistically odd`() {
        // Perfectly consistent 100 history; a 250 spend is a huge robust z but
        // only 150 rupees away — below the 200 floor, so no flag (G5).
        assertNull(
            AnomalyEngine.evaluate(
                candidate(250),
                merchantHistory = history(100, 100, 100, 100, 100, 100),
                categoryHistory = null,
            ),
        )
    }

    @Test
    fun `two dismissals raise the threshold for that merchant`() {
        // Spread-out history: median 325, MAD 75 → robust sigma ≈ 111.2.
        // A 700 spend deviates by 375 → z ≈ 3.37: over the default 3.0
        // threshold but under the raised 4.0 one.
        val amounts = history(200, 400, 300, 500, 250, 350)
        val borderline = candidate(700)

        assertNotNull(AnomalyEngine.evaluate(borderline, amounts, null))

        val raised = AnomalyEngine.History(amounts.amounts, dismissals = 2)
        assertNull(AnomalyEngine.evaluate(borderline, raised, null))
    }

    @Test
    fun `falls back to category scope when merchant history is thin`() {
        val flag = AnomalyEngine.evaluate(
            candidate(9_000),
            merchantHistory = history(300, 320),
            categoryHistory = history(500, 520, 480, 510, 505, 495, 515, 490),
        )
        assertNotNull(flag)
        assertEquals(AnomalyEngine.Scope.CATEGORY, flag!!.scope)
    }

    @Test
    fun `median resists outliers where a mean would not`() {
        // Mean of these is ~223,800; the median is the middle value, 31,000.
        assertEquals(31_000L, AnomalyEngine.median(listOf(30_000, 32_000, 28_000, 31_000, 1_000_000)))
    }

    @Test
    fun `at most three flags are shown at once`() {
        val flags = (1..7).map {
            AnomalyEngine.Flag(
                transactionId = it.toLong(),
                amount = Money.ofRupees(1_000L * it),
                median = Money.ofRupees(300),
                robustZ = 5.0,
                scope = AnomalyEngine.Scope.MERCHANT,
                label = "MERCHANT $it",
                explanation = "",
            )
        }
        val capped = AnomalyEngine.cap(flags)
        assertEquals(3, capped.size)
        // Biggest deviations first.
        assertEquals(7L, capped.first().transactionId)
    }
}

class LeakDetectorTest {

    private fun spend(merchant: String, rupees: Long, day: Long) = LeakDetector.Spend(
        merchantNormalized = merchant,
        amount = Money.ofRupees(rupees),
        timestampMillis = day * 86_400_000L,
    )

    @Test
    fun `frequent micro-spends annualize`() {
        // 18 coffees at 120 over 90 days = 2,160 → 720/month → 8,640/year
        val spends = (1..18).map { spend("CAFE", 120, it.toLong()) }
        val leaks = LeakDetector.detect(spends)
        assertEquals(1, leaks.size)
        val leak = leaks.first()
        assertEquals(18, leak.occurrences)
        assertEquals(Money.ofRupees(2_160), leak.total)
        assertEquals(Money.ofRupees(120), leak.averageAmount)
        assertEquals(Money.ofRupees(720), leak.monthlyRate)
        assertEquals(Money.ofRupees(8_640), leak.annualized)
    }

    @Test
    fun `infrequent spends are not leaks`() {
        val spends = (1..5).map { spend("CAFE", 120, it.toLong()) }
        assertTrue(LeakDetector.detect(spends).isEmpty())
    }

    @Test
    fun `large purchases are not leaks`() {
        val spends = (1..10).map { spend("ELECTRONICS", 5_000, it.toLong()) }
        assertTrue(LeakDetector.detect(spends).isEmpty())
    }

    @Test
    fun `leaks are ranked by annual cost`() {
        val spends = (1..10).map { spend("CAFE", 100, it.toLong()) } +
            (1..10).map { spend("SNACKS", 300, it.toLong()) }
        val leaks = LeakDetector.detect(spends)
        assertEquals("SNACKS", leaks.first().merchant)
    }
}

class WhatIfSimulatorTest {

    @Test
    fun `cutting a category by twenty percent`() {
        val result = WhatIfSimulator.simulate(Money.ofRupees(8_000), 20)
        assertEquals(Money.ofRupees(1_600), result.monthlySaving)
        assertEquals(Money.ofRupees(19_200), result.annualSaving)
        assertEquals(Money.ofRupees(6_400), result.newMonthlySpend)
    }

    @Test
    fun `zero and full cuts are the boundaries`() {
        assertEquals(Money.ZERO, WhatIfSimulator.simulate(Money.ofRupees(8_000), 0).monthlySaving)
        assertEquals(Money.ZERO, WhatIfSimulator.simulate(Money.ofRupees(8_000), 100).newMonthlySpend)
    }
}

class OpportunityCostSimulatorTest {

    @Test
    fun `zero rate returns exactly what was contributed`() {
        val result = OpportunityCostSimulator.simulate(Money.ofRupees(5_000), 12, 0.0)
        assertEquals(Money.ofRupees(60_000), result.invested)
        assertEquals(Money.ofRupees(60_000), result.hypotheticalValue)
        assertEquals(Money.ZERO, result.difference)
    }

    @Test
    fun `twelve percent annual on 5000 monthly for a year`() {
        // FV of an annuity, 1% monthly, 12 months: 5000 × 12.6825 = 63,412.
        val result = OpportunityCostSimulator.simulate(Money.ofRupees(5_000), 12, 12.0)
        assertEquals(Money.ofRupees(60_000), result.invested)
        assertEquals(63_412_00L, result.hypotheticalValue.paise / 100 * 100)
        assertTrue(result.difference.paise > 0)
        assertEquals(1.0, result.years, 1e-9)
    }

    @Test
    fun `zero months is a no-op`() {
        val result = OpportunityCostSimulator.simulate(Money.ofRupees(5_000), 0, 12.0)
        assertEquals(Money.ZERO, result.invested)
        assertEquals(Money.ZERO, result.difference)
    }
}

class AdvisorTest {

    @Test
    fun `no surplus means no allocation and an honest sentence`() {
        val advice = Advisor.advise(
            Advisor.Input(
                averageSurplus = Money.ZERO,
                emergencyFundBalance = Money.ZERO,
                averageMonthlyExpense = Money.ofRupees(50_000),
            ),
        )
        assertTrue(advice.allocations.isEmpty())
        assertTrue(advice.reasoning.contains("no surplus"))
    }

    @Test
    fun `emergency fund comes first and reasoning is always shown`() {
        val advice = Advisor.advise(
            Advisor.Input(
                averageSurplus = Money.ofRupees(20_000),
                emergencyFundBalance = Money.ofRupees(30_000),
                averageMonthlyExpense = Money.ofRupees(50_000),
                emergencyFundMonthsTarget = 3,
            ),
        )
        assertEquals("Emergency fund", advice.allocations.first().label)
        assertTrue(advice.reasoning.contains("average surplus"))
        assertTrue(advice.allocations.all { it.reason.isNotBlank() })
    }

    @Test
    fun `months to target is computed from the allocation, not invented`() {
        // Shortfall 120,000; half of a 20,000 surplus = 10,000/month → 12 months.
        val advice = Advisor.advise(
            Advisor.Input(
                averageSurplus = Money.ofRupees(20_000),
                emergencyFundBalance = Money.ofRupees(30_000),
                averageMonthlyExpense = Money.ofRupees(50_000),
                emergencyFundMonthsTarget = 3,
            ),
        )
        assertEquals(12, advice.monthsToEmergencyTarget)
    }

    @Test
    fun `a funded emergency fund moves the surplus to debt and goals`() {
        val advice = Advisor.advise(
            Advisor.Input(
                averageSurplus = Money.ofRupees(20_000),
                emergencyFundBalance = Money.ofRupees(200_000),
                averageMonthlyExpense = Money.ofRupees(50_000),
                emergencyFundMonthsTarget = 3,
                highInterestDebtOutstanding = Money.ofRupees(80_000),
                goalShortfalls = listOf("Trip to Japan" to Money.ofRupees(50_000)),
            ),
        )
        val labels = advice.allocations.map { it.label }
        assertEquals(listOf("Extra debt repayment", "Trip to Japan"), labels)
        assertEquals(0, advice.monthsToEmergencyTarget)
    }

    @Test
    fun `allocations never exceed the surplus`() {
        val surplus = Money.ofRupees(20_000)
        val advice = Advisor.advise(
            Advisor.Input(
                averageSurplus = surplus,
                emergencyFundBalance = Money.ofRupees(200_000),
                averageMonthlyExpense = Money.ofRupees(50_000),
                highInterestDebtOutstanding = Money.ofRupees(500_000),
                goalShortfalls = listOf("Car" to Money.ofRupees(300_000)),
            ),
        )
        assertEquals(surplus.paise, advice.allocations.sumOf { it.amount.paise })
    }

    @Test
    fun `advice never names a product or instrument - advisory boundary`() {
        val advice = Advisor.advise(
            Advisor.Input(
                averageSurplus = Money.ofRupees(50_000),
                emergencyFundBalance = Money.ofRupees(500_000),
                averageMonthlyExpense = Money.ofRupees(50_000),
            ),
        )
        val banned = listOf("mutual fund", "elss", "stock", "sip", "nifty", "gold bond", "fd", "insurance plan")
        val text = (advice.allocations.joinToString { it.label + " " + it.reason } + advice.reasoning).lowercase()
        banned.forEach { term ->
            assertTrue("advice must not name products, found '$term'", !text.contains(term))
        }
    }
}

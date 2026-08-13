package dev.kosha.core.engine.constitution

import dev.kosha.core.common.Money
import dev.kosha.core.engine.query.QueryFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConstitutionEngineTest {

    private fun sumLimit(rupees: Long) = MachineCheck(
        filter = QueryFilter(categoryNames = listOf("Food & Dining")),
        assert = MachineCheck.Assertion(sumLtePaise = rupees * 100),
    )

    @Test
    fun `spending under the limit is not a violation`() {
        val result = ConstitutionEngine.evaluate(
            ruleId = 1,
            check = sumLimit(2_000),
            matchedTotal = Money.ofRupees(1_500),
            matchedCount = 4,
        )
        assertNotNull(result)
        assertFalse(result!!.violated)
        assertTrue(result.explanation.contains("within your rule"))
    }

    @Test
    fun `spending over the limit is a violation with the numbers shown`() {
        val result = ConstitutionEngine.evaluate(
            ruleId = 1,
            check = sumLimit(2_000),
            matchedTotal = Money.ofRupees(2_400),
            matchedCount = 6,
        )!!
        assertTrue(result.violated)
        assertTrue(result.explanation.contains("2,400"))
        assertTrue(result.explanation.contains("2,000"))
    }

    @Test
    fun `count limits work`() {
        val check = MachineCheck(
            filter = QueryFilter(merchantContains = "SWIGGY"),
            assert = MachineCheck.Assertion(countLte = 4),
        )
        assertFalse(ConstitutionEngine.evaluate(1, check, Money.ofRupees(900), 3)!!.violated)
        assertTrue(ConstitutionEngine.evaluate(1, check, Money.ofRupees(1_500), 5)!!.violated)
    }

    @Test
    fun `savings floors work in the other direction`() {
        val check = MachineCheck(
            filter = QueryFilter(direction = QueryFilter.Direction.INCOME),
            assert = MachineCheck.Assertion(sumGtePaise = 20_000_00),
        )
        assertTrue(ConstitutionEngine.evaluate(1, check, Money.ofRupees(15_000), 1)!!.violated)
        assertFalse(ConstitutionEngine.evaluate(1, check, Money.ofRupees(25_000), 1)!!.violated)
    }

    @Test
    fun `free-text rules are never auto-judged`() {
        // No machineCheck → nothing to evaluate; it surfaces at period close
        // for the user's own review instead (spec G12).
        assertNull(ConstitutionEngine.evaluate(1, null, Money.ofRupees(9_999), 12))
        assertNull(ConstitutionEngine.parseCheck(null))
        assertNull(ConstitutionEngine.parseCheck("not json"))
    }

    @Test
    fun `machine check round-trips through json`() {
        val json = """
            {"filter":{"categoryNames":["Food & Dining"],"period":{"type":"dev.kosha.core.engine.query.QueryPeriod.ThisMonth"}},
             "assert":{"sumLtePaise":200000}}
        """.trimIndent()
        val parsed = ConstitutionEngine.parseCheck(json)
        assertNotNull(parsed)
        assertEquals(200_000L, parsed!!.assert.sumLtePaise)
        assertEquals(listOf("Food & Dining"), parsed.filter.categoryNames)
    }

    @Test
    fun `violation trend needs two periods`() {
        assertEquals(
            ConstitutionEngine.Trend.NOT_ENOUGH_DATA,
            ConstitutionEngine.violationTrend(listOf(3)),
        )
        assertEquals(
            ConstitutionEngine.Trend.IMPROVING,
            ConstitutionEngine.violationTrend(listOf(5, 3, 1)),
        )
        assertEquals(
            ConstitutionEngine.Trend.SLIPPING,
            ConstitutionEngine.violationTrend(listOf(1, 2, 4)),
        )
        assertEquals(
            ConstitutionEngine.Trend.STEADY,
            ConstitutionEngine.violationTrend(listOf(2, 2)),
        )
    }
}

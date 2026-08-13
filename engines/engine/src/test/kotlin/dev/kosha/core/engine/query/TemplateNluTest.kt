package dev.kosha.core.engine.query

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase-11 exit gate (spec G8): the 20 canonical phrasings resolve
 * correctly, and unknown phrasings fail gracefully to the builder with
 * whatever slots were recognized.
 */
class TemplateNluTest {

    private val nlu = TemplateNlu(
        categoryNames = listOf(
            "Food & Dining", "Groceries", "Transport", "Fuel", "Shopping",
            "Bills & Utilities", "Rent", "EMI & Loans", "Health", "Insurance",
            "Education", "Entertainment", "Subscriptions", "Travel",
            "Personal Care", "Construction & Home", "Salary",
        ),
        merchantNames = listOf("SWIGGY", "ZOMATO", "AMAZON", "UBER"),
    )

    private fun parsed(input: String): Query {
        val result = nlu.parse(input)
        assertTrue("could not parse: \"$input\" → $result", result is TemplateNlu.Result.Parsed)
        return (result as TemplateNlu.Result.Parsed).query
    }

    // --- The 20 canonical phrasings (G8) ---

    @Test fun `01 how much on dining last month`() {
        val q = parsed("how much on dining last month")
        assertEquals(Aggregation.SUM, q.aggregation)
        assertEquals(listOf("Food & Dining"), q.filter.categoryNames)
        assertEquals(QueryPeriod.LastMonth, q.filter.period)
    }

    @Test fun `02 total spent this week`() {
        val q = parsed("total spent this week")
        assertEquals(Aggregation.SUM, q.aggregation)
        assertEquals(QueryPeriod.ThisWeek, q.filter.period)
    }

    @Test fun `03 biggest expense in march`() {
        val q = parsed("biggest expense in march")
        assertEquals(Aggregation.MAX, q.aggregation)
        assertEquals(QueryPeriod.NamedMonth(3), q.filter.period)
    }

    @Test fun `04 average grocery spend`() {
        val q = parsed("average grocery spend")
        assertEquals(Aggregation.AVG, q.aggregation)
        assertEquals(listOf("Groceries"), q.filter.categoryNames)
    }

    @Test fun `05 how many swiggy orders last month`() {
        val q = parsed("how many swiggy orders last month")
        assertEquals(Aggregation.COUNT, q.aggregation)
        assertEquals("SWIGGY", q.filter.merchantContains)
        assertEquals(QueryPeriod.LastMonth, q.filter.period)
    }

    @Test fun `06 list transactions above 2000 this month`() {
        val q = parsed("list transactions above 2000 this month")
        assertEquals(Aggregation.LIST, q.aggregation)
        assertEquals(200_000L, q.filter.minAmountPaise)
        assertEquals(QueryPeriod.ThisMonth, q.filter.period)
    }

    @Test fun `07 what did I save in june`() {
        val q = parsed("what did i save in june")
        assertEquals(Aggregation.SUM, q.aggregation)
        assertEquals(QueryPeriod.NamedMonth(6), q.filter.period)
    }

    @Test fun `08 salary received this year`() {
        val q = parsed("salary received this year")
        assertEquals(QueryFilter.Direction.INCOME, q.filter.direction)
        assertEquals(QueryPeriod.ThisYear, q.filter.period)
    }

    @Test fun `09 emi total this fy`() {
        val q = parsed("emi total this fy")
        assertEquals(Aggregation.SUM, q.aggregation)
        assertEquals(listOf("EMI & Loans"), q.filter.categoryNames)
        assertEquals(QueryPeriod.FinancialYear, q.filter.period)
    }

    @Test fun `10 cash spent last 30 days`() {
        val q = parsed("cash spent last 30 days")
        assertEquals(Aggregation.SUM, q.aggregation)
        assertEquals(QueryPeriod.LastNDays(30), q.filter.period)
    }

    @Test fun `11 how much on fuel this month`() {
        val q = parsed("how much on fuel this month")
        assertEquals(listOf("Fuel"), q.filter.categoryNames)
        assertEquals(QueryPeriod.ThisMonth, q.filter.period)
    }

    @Test fun `12 total spent today`() {
        assertEquals(QueryPeriod.Today, parsed("total spent today").filter.period)
    }

    @Test fun `13 how much did I spend yesterday`() {
        assertEquals(QueryPeriod.Yesterday, parsed("how much did i spend yesterday").filter.period)
    }

    @Test fun `14 shopping last week`() {
        val q = parsed("shopping last week")
        assertEquals(listOf("Shopping"), q.filter.categoryNames)
        assertEquals(QueryPeriod.LastWeek, q.filter.period)
    }

    @Test fun `15 average uber ride`() {
        val q = parsed("average uber ride")
        assertEquals(Aggregation.AVG, q.aggregation)
        assertEquals("UBER", q.filter.merchantContains)
    }

    @Test fun `16 subscriptions last 3 months`() {
        val q = parsed("subscriptions last 3 months")
        assertEquals(listOf("Subscriptions"), q.filter.categoryNames)
        assertEquals(QueryPeriod.LastNMonths(3), q.filter.period)
    }

    @Test fun `17 largest amazon purchase`() {
        val q = parsed("largest amazon purchase")
        assertEquals(Aggregation.MAX, q.aggregation)
        assertEquals("AMAZON", q.filter.merchantContains)
    }

    @Test fun `18 how many transactions over 5000 last 2 weeks`() {
        val q = parsed("how many transactions over 5000 last 2 weeks")
        assertEquals(Aggregation.COUNT, q.aggregation)
        assertEquals(500_000L, q.filter.minAmountPaise)
        assertEquals(QueryPeriod.LastNWeeks(2), q.filter.period)
    }

    @Test fun `19 rent paid this year`() {
        val q = parsed("rent paid this year")
        assertEquals(listOf("Rent"), q.filter.categoryNames)
        assertEquals(QueryPeriod.ThisYear, q.filter.period)
    }

    @Test fun `20 show travel spending in december`() {
        val q = parsed("show travel spending in december")
        assertEquals(listOf("Travel"), q.filter.categoryNames)
        assertEquals(QueryPeriod.NamedMonth(12), q.filter.period)
    }

    // --- Graceful failure (G8) ---

    @Test
    fun `unknown phrasing falls back to the builder with recognized slots`() {
        val result = nlu.parse("did I do better than my cousin last month")
        assertTrue(result is TemplateNlu.Result.Unparsed)
        val unparsed = result as TemplateNlu.Result.Unparsed
        // The period WAS recognized — pre-fill it rather than discard it.
        assertEquals(QueryPeriod.LastMonth, unparsed.partial.period)
        assertTrue("period" in unparsed.recognizedSlots)
    }

    @Test
    fun `empty input is unparsed, not a silent full-ledger query`() {
        val result = nlu.parse("   ")
        assertTrue(result is TemplateNlu.Result.Unparsed)
    }

    @Test
    fun `nonsense recognizes nothing`() {
        val result = nlu.parse("qwertyuiop")
        assertTrue(result is TemplateNlu.Result.Unparsed)
        assertTrue((result as TemplateNlu.Result.Unparsed).recognizedSlots.isEmpty())
    }
}

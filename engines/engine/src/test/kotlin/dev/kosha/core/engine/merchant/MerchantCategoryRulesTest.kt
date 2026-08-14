package dev.kosha.core.engine.merchant

import dev.kosha.core.engine.merchant.MerchantCategoryRules.categoryNameFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The keyword guess exists so a fresh install does not put every rupee in
 * Uncategorized, which collapses every category chart to one 100% slice.
 */
class MerchantCategoryRulesTest {

    @Test
    fun `common indian merchants land in the right category`() {
        assertEquals("Food & Dining", categoryNameFor("ZOMATO"))
        assertEquals("Food & Dining", categoryNameFor("EDEN PARK CAFETERIA"))
        assertEquals("Groceries", categoryNameFor("BIGBASKET"))
        assertEquals("Transport", categoryNameFor("UBER INDIA"))
        assertEquals("Fuel", categoryNameFor("INDIAN OIL CORP"))
        assertEquals("Shopping", categoryNameFor("AMAZON PAY INDIA"))
        assertEquals("Subscriptions", categoryNameFor("NETFLIX ENT"))
        assertEquals("Health", categoryNameFor("APOLLO PHARMACY"))
        assertEquals("Bills & Utilities", categoryNameFor("AIRTEL POSTPAID"))
    }

    @Test
    fun `a longer keyword wins over a shorter one it contains`() {
        // Both "swiggy" and "swiggy instamart" match; the quick-commerce arm
        // is groceries, not a restaurant.
        assertEquals("Groceries", categoryNameFor("SWIGGY INSTAMART PRI"))
        assertEquals("Food & Dining", categoryNameFor("SWIGGY"))
    }

    @Test
    fun `keywords match on token boundaries, not as substrings`() {
        // "lic" is an insurer; LICIOUS is a meat delivery service. Substring
        // matching would have filed the latter under Insurance.
        assertEquals("Groceries", categoryNameFor("LICIOUS"))
        assertEquals("Insurance", categoryNameFor("LIC OF INDIA"))
    }

    @Test
    fun `credits use the income table`() {
        assertEquals("Salary", categoryNameFor("ACME PAYROLL", isCredit = true))
        assertEquals("Refunds & Cashback", categoryNameFor("AMAZON REFUND", isCredit = true))
        // The same string on a debit is shopping, not income.
        assertEquals("Shopping", categoryNameFor("AMAZON REFUND", isCredit = false))
    }

    @Test
    fun `an unknown merchant stays unguessed`() {
        assertNull(categoryNameFor("K G JYOTHI"))
        assertNull(categoryNameFor("S M GHOUSE"))
        assertNull(categoryNameFor(null))
        assertNull(categoryNameFor("   "))
    }

    @Test
    fun `a vpa handle is matched on its name part`() {
        // MerchantMatcher strips the @bank suffix, so the rule sees "ZOMATO".
        assertEquals("Food & Dining", categoryNameFor("zomato@paytm"))
        assertEquals("Rent", categoryNameFor("landlord.rent@okicici"))
    }

    @Test
    fun `every rule points at a real seeded category`() {
        // A typo in a category name would silently mean "no guess", so the
        // rule table is checked against the seeded set (spec G2).
        val seeded = setOf(
            "Food & Dining", "Groceries", "Transport", "Fuel", "Shopping",
            "Bills & Utilities", "Rent", "EMI & Loans", "Health", "Insurance",
            "Education", "Entertainment", "Subscriptions", "Travel",
            "Personal Care", "Construction & Home",
            "Salary", "Business", "Interest & Dividends", "Refunds & Cashback",
            "Other Income",
        )
        val probes = listOf(
            "ZOMATO", "BIGBASKET", "UBER", "INDIAN OIL", "AMAZON", "AIRTEL",
            "RENT", "EMI", "APOLLO", "INSURANCE", "SCHOOL", "PVR", "NETFLIX",
            "OYO", "SALON", "CEMENT",
        )
        probes.forEach { probe ->
            val name = categoryNameFor(probe)
            assertEquals("$probe mapped outside the seeded set: $name", true, name in seeded)
        }
        listOf("PAYROLL", "INTEREST", "CASHBACK").forEach { probe ->
            val name = categoryNameFor(probe, isCredit = true)
            assertEquals("$probe mapped outside the seeded set: $name", true, name in seeded)
        }
    }
}

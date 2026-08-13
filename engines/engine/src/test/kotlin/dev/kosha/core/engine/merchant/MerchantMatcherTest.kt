package dev.kosha.core.engine.merchant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MerchantMatcherTest {

    @Test
    fun `normalize strips vpa handles and noise`() {
        assertEquals("SWIGGY", MerchantMatcher.normalize("swiggy@icici"))
        assertEquals("SWIGGY", MerchantMatcher.normalize("SWIGGY UPI 123456789"))
        assertEquals("AMAZON", MerchantMatcher.normalize("AMAZON PAY INDIA PVT LTD"))
        assertEquals("BIG BAZAAR", MerchantMatcher.normalize("BIG BAZAAR POS 998877"))
        assertEquals("ZOMATO ORDER", MerchantMatcher.normalize("zomato-order@paytm"))
    }

    @Test
    fun `normalize drops dates and ref numbers`() {
        assertEquals("RELIANCE FRESH", MerchantMatcher.normalize("Reliance Fresh 12-08-26 000123456"))
    }

    @Test
    fun `exact normalized match auto-links`() {
        val result = MerchantMatcher.match("swiggy@icici", setOf("SWIGGY", "ZOMATO"))
        assertTrue(result is MerchantMatcher.MatchResult.AutoLink)
        assertEquals("SWIGGY", (result as MerchantMatcher.MatchResult.AutoLink).canonical)
    }

    @Test
    fun `close spelling auto-links at or above 90`() {
        val result = MerchantMatcher.match("SWIGY", setOf("SWIGGY"))
        assertTrue("got $result", result is MerchantMatcher.MatchResult.AutoLink)
    }

    @Test
    fun `distant names are new merchants`() {
        val result = MerchantMatcher.match("APOLLO PHARMACY", setOf("SWIGGY", "ZOMATO"))
        assertTrue(result is MerchantMatcher.MatchResult.NewMerchant)
    }

    @Test
    fun `jaro winkler sanity`() {
        assertEquals(1.0, MerchantMatcher.jaroWinkler("SWIGGY", "SWIGGY"), 1e-9)
        assertEquals(0.0, MerchantMatcher.jaroWinkler("AB", "XY"), 1e-9)
        assertTrue(MerchantMatcher.jaroWinkler("MARTHA", "MARHTA") > 0.94)
        assertTrue(MerchantMatcher.jaroWinkler("DWAYNE", "DUANE") in 0.8..0.9)
    }

    @Test
    fun `empty strings never match`() {
        assertTrue(!MerchantMatcher.sameMerchant("", ""))
        assertTrue(MerchantMatcher.match("", setOf("SWIGGY")) is MerchantMatcher.MatchResult.NewMerchant)
    }
}

package dev.kosha.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MoneyTest {

    @Test
    fun `indian grouping matches spec example`() {
        assertEquals("₹1,23,456.78", Money(12_345_678).format())
    }

    @Test
    fun `small amounts have no grouping`() {
        assertEquals("₹0.00", Money.ZERO.format())
        assertEquals("₹5.00", Money.ofRupees(5).format())
        assertEquals("₹999.99", Money(99_999).format())
        assertEquals("₹1,000.00", Money(100_000).format())
    }

    @Test
    fun `large amounts group by two after the first three`() {
        assertEquals("₹10,00,000.00", Money.ofRupees(1_000_000).format())
        assertEquals("₹1,00,00,000.00", Money.ofRupees(10_000_000).format()) // 1 crore
        assertEquals("₹12,34,56,789.01", Money(1_234_567_8901).format())
    }

    @Test
    fun `negative uses typographic minus`() {
        assertEquals("−₹4,200.00", Money.ofRupees(-4200).format())
    }

    @Test
    fun `signed positive gets plus prefix`() {
        assertEquals("+₹4,200.00", Money.ofRupees(4200).format(signed = true))
        assertEquals("₹0.00", Money.ZERO.format(signed = true))
    }

    @Test
    fun `without paise drops decimals`() {
        assertEquals("₹1,23,456", Money(12_345_678).format(withPaise = false))
    }

    @Test
    fun `parse handles common sms and user formats`() {
        assertEquals(Money(12_345_678), Money.parseOrNull("1,23,456.78"))
        assertEquals(Money(123_400), Money.parseOrNull("₹1234"))
        assertEquals(Money(123_450), Money.parseOrNull("1234.5"))
        assertEquals(Money(9_900), Money.parseOrNull("Rs. 99"))
        assertEquals(Money(9_900), Money.parseOrNull("INR 99"))
    }

    @Test
    fun `parse rejects garbage`() {
        assertNull(Money.parseOrNull(""))
        assertNull(Money.parseOrNull("abc"))
        assertNull(Money.parseOrNull("12.345"))
        assertNull(Money.parseOrNull("-50"))
    }

    @Test
    fun `arithmetic stays in paise`() {
        assertEquals(Money(150), Money(100) + Money(50))
        assertEquals(Money(-50), Money(100) - Money(150))
        assertEquals(Money(300), Money(100) * 3)
    }
}

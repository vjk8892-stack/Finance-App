package dev.kosha.core.engine.export

import dev.kosha.core.common.Money
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CsvWriterTest {

    private fun row(
        merchant: String = "Swiggy",
        note: String = "",
        amount: Money = Money.ofRupees(545),
    ) = CsvWriter.Row(
        date = "2026-08-13",
        merchant = merchant,
        category = "Food & Dining",
        account = "HDFC Savings",
        type = "debit",
        amount = amount,
        note = note,
        source = "sms",
        tags = "",
    )

    @Test
    fun `header comes first and rows follow`() {
        val csv = CsvWriter.write(listOf(row()))
        val lines = csv.trim().lines()
        assertEquals("Date,Merchant,Category,Account,Type,Amount,Note,Source,Tags", lines[0])
        assertTrue(lines[1].startsWith("2026-08-13,Swiggy,Food & Dining,HDFC Savings,debit,545.00"))
    }

    @Test
    fun `amounts are plain decimals for spreadsheets`() {
        val csv = CsvWriter.write(listOf(row(amount = Money(12_345_678))))
        assertTrue(csv.contains("123456.78"))
        // No symbol, no Indian grouping — that formatting is for the UI only.
        assertFalse(csv.contains("₹"))
        assertFalse(csv.contains("1,23,456"))
    }

    @Test
    fun `commas quotes and newlines are escaped`() {
        val csv = CsvWriter.write(listOf(row(merchant = "Big Bazaar, Andheri", note = "said \"keep it\"")))
        assertTrue(csv.contains("\"Big Bazaar, Andheri\""))
        assertTrue(csv.contains("\"said \"\"keep it\"\"\""))
    }

    @Test
    fun `formula injection is neutralized`() {
        // A merchant named like a formula must not execute on open.
        listOf("=cmd|'/c calc'!A1", "+1+1", "-2+3", "@SUM(A1)").forEach { hostile ->
            val cell = CsvWriter.escape(hostile)
            assertTrue("expected leading quote for $hostile, got $cell", cell.startsWith("'") || cell.startsWith("\"'"))
        }
    }

    @Test
    fun `ordinary negative amounts still render normally`() {
        val csv = CsvWriter.write(listOf(row(amount = Money.ofRupees(-500))))
        assertTrue(csv.contains("-500.00"))
    }

    @Test
    fun `empty export still writes a usable header`() {
        assertEquals(
            "Date,Merchant,Category,Account,Type,Amount,Note,Source,Tags",
            CsvWriter.write(emptyList()).trim(),
        )
    }
}

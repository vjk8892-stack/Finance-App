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

    private fun credit(amount: Money) = row(amount = amount).copy(type = "credit")

    @Test
    fun `header comes first and rows follow`() {
        val csv = CsvWriter.write(listOf(row()))
        val lines = csv.trim().lines()
        assertEquals("Date,Merchant,Category,Account,Type,Amount,Source,Note,Tags", lines[0])
        assertTrue(lines[1].startsWith("2026-08-13,Swiggy,Food & Dining,HDFC Savings,debit,-545.00"))
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
    fun `a debit exports as a negative number, not as text`() {
        // The formula guard prefixes anything starting with '-' with an
        // apostrophe. Applied to the Amount column that turns every debit into
        // TEXT, so the column stops summing — which is the single thing people
        // export a CSV to do.
        val csv = CsvWriter.write(listOf(row(amount = Money.ofRupees(500))))
        val amountCell = csv.trim().lines()[1].split(",")[5]
        assertEquals("-500.00", amountCell)
    }

    @Test
    fun `split columns keep money in and money out apart`() {
        val csv = CsvWriter.write(
            listOf(row(amount = Money.ofRupees(500)), credit(Money.ofRupees(2_000))),
            CsvWriter.Options(splitAmountColumns = true),
        )
        val lines = csv.trim().lines()
        assertEquals("Date,Merchant,Category,Account,Type,Money in,Money out,Source,Note,Tags", lines[0])
        // Debit: nothing in the "in" column, magnitude in "out".
        assertEquals(listOf("", "500.00"), lines[1].split(",").subList(5, 7))
        assertEquals(listOf("2000.00", ""), lines[2].split(",").subList(5, 7))
    }

    @Test
    fun `running balance accumulates in file order`() {
        val csv = CsvWriter.write(
            listOf(
                credit(Money.ofRupees(10_000)),
                row(amount = Money.ofRupees(2_500)),
                row(amount = Money.ofRupees(500)),
            ),
            CsvWriter.Options(includeRunningBalance = true),
        )
        val balances = csv.trim().lines().drop(1).map { it.split(",")[6] }
        assertEquals(listOf("10000.00", "7500.00", "7000.00"), balances)
    }

    @Test
    fun `notes and tags can be left out entirely`() {
        val csv = CsvWriter.write(
            listOf(row(note = "private")),
            CsvWriter.Options(includeNotesAndTags = false),
        )
        assertFalse(csv.contains("private"))
        assertFalse(csv.contains("Note"))
    }

    @Test
    fun `empty export still writes a usable header`() {
        assertEquals(
            "Date,Merchant,Category,Account,Type,Amount,Source,Note,Tags",
            CsvWriter.write(emptyList()).trim(),
        )
    }
}

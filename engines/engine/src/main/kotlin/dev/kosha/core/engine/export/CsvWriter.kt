package dev.kosha.core.engine.export

import dev.kosha.core.common.Money

/**
 * Hand-rolled CSV writer (spec B1: avoids Apache POI on Android entirely).
 * RFC 4180 quoting, and a deliberate guard against spreadsheet formula
 * injection — a merchant literally named "=cmd" must not execute in Excel.
 *
 * The vault is structurally absent here: this writer only ever sees the
 * transaction columns it is handed (spec B4/G10).
 */
object CsvWriter {

    val TRANSACTION_HEADER = listOf(
        "Date", "Merchant", "Category", "Account", "Type", "Amount", "Note", "Source", "Tags",
    )

    data class Row(
        val date: String,
        val merchant: String,
        val category: String,
        val account: String,
        val type: String,
        val amount: Money,
        val note: String,
        val source: String,
        val tags: String,
    ) {
        fun toCells(): List<String> = listOf(
            date, merchant, category, account, type,
            // Plain decimal for spreadsheets — no symbol, no grouping.
            "%d.%02d".format(amount.paise / 100, (amount.paise % 100).let { if (it < 0) -it else it }),
            note, source, tags,
        )
    }

    fun write(rows: List<Row>): String = buildString {
        appendLine(TRANSACTION_HEADER.joinToString(",") { escape(it) })
        rows.forEach { row ->
            appendLine(row.toCells().joinToString(",") { escape(it) })
        }
    }

    internal fun escape(value: String): String {
        // Neutralize leading formula characters before quoting (CSV injection).
        val safe = if (value.isNotEmpty() && value.first() in FORMULA_STARTERS) {
            "'$value"
        } else {
            value
        }
        val needsQuoting = safe.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
        return if (needsQuoting) {
            "\"" + safe.replace("\"", "\"\"") + "\""
        } else {
            safe
        }
    }

    private val FORMULA_STARTERS = setOf('=', '+', '-', '@', '\t')
}

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

    /**
     * A cell, and whether it is a NUMBER.
     *
     * The formula guard prefixes anything starting with `=`, `+`, `-`, `@` with
     * an apostrophe. Applied blindly that turns `-1500.00` into text, so a
     * column of amounts with any debit in it stops summing in the spreadsheet —
     * the one thing people export a CSV to do. Numeric cells skip the guard,
     * which is safe because they are generated from Long paise and never
     * contain user text.
     */
    data class Cell(val text: String, val numeric: Boolean = false)

    /** What the user chose to put in the file. */
    data class Options(
        /**
         * Separate "Money in" and "Money out" columns instead of one signed
         * Amount. Pivot tables and SUMIF want this; a signed column wants the
         * other. There is no default that suits both, so it is a choice.
         */
        val splitAmountColumns: Boolean = false,
        val includeNotesAndTags: Boolean = true,
        /** Running total down the file — only meaningful in date order. */
        val includeRunningBalance: Boolean = false,
    )

    data class Row(
        val date: String,
        val merchant: String,
        val category: String,
        val account: String,
        val type: String,
        /** Magnitude. Direction lives in [type]. */
        val amount: Money,
        val note: String,
        val source: String,
        val tags: String,
    ) {
        val isCredit: Boolean get() = type.equals("credit", ignoreCase = true)

        /** Signed paise: credits add, debits subtract. */
        val signedPaise: Long get() = if (isCredit) amount.paise else -amount.paise
    }

    fun header(options: Options = Options()): List<String> = buildList {
        add("Date")
        add("Merchant")
        add("Category")
        add("Account")
        add("Type")
        if (options.splitAmountColumns) {
            add("Money in")
            add("Money out")
        } else {
            add("Amount")
        }
        if (options.includeRunningBalance) add("Running balance")
        add("Source")
        if (options.includeNotesAndTags) {
            add("Note")
            add("Tags")
        }
    }

    fun write(rows: List<Row>, options: Options = Options()): String = buildString {
        appendLine(header(options).joinToString(",") { escape(it) })
        var running = 0L
        rows.forEach { row ->
            running += row.signedPaise
            appendLine(cells(row, options, running).joinToString(",") { cell(it) })
        }
    }

    private fun cells(row: Row, options: Options, runningPaise: Long): List<Cell> = buildList {
        add(Cell(row.date))
        add(Cell(row.merchant))
        add(Cell(row.category))
        add(Cell(row.account))
        add(Cell(row.type))
        if (options.splitAmountColumns) {
            add(Cell(if (row.isCredit) decimal(row.amount.paise) else "", numeric = true))
            add(Cell(if (row.isCredit) "" else decimal(row.amount.paise), numeric = true))
        } else {
            add(Cell(decimal(row.signedPaise), numeric = true))
        }
        if (options.includeRunningBalance) add(Cell(decimal(runningPaise), numeric = true))
        add(Cell(row.source))
        if (options.includeNotesAndTags) {
            add(Cell(row.note))
            add(Cell(row.tags))
        }
    }

    /** Plain decimal for spreadsheets — no symbol, no grouping, sign kept. */
    private fun decimal(paise: Long): String {
        val sign = if (paise < 0) "-" else ""
        val magnitude = if (paise < 0) -paise else paise
        return "$sign${magnitude / 100}.${"%02d".format(magnitude % 100)}"
    }

    private fun cell(cell: Cell): String = if (cell.numeric) quoteOnly(cell.text) else escape(cell.text)

    internal fun escape(value: String): String {
        // Neutralize leading formula characters before quoting (CSV injection).
        val safe = if (value.isNotEmpty() && value.first() in FORMULA_STARTERS) {
            "'$value"
        } else {
            value
        }
        return quoteOnly(safe)
    }

    private fun quoteOnly(value: String): String {
        val needsQuoting = value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
        return if (needsQuoting) {
            "\"" + value.replace("\"", "\"\"") + "\""
        } else {
            value
        }
    }

    private val FORMULA_STARTERS = setOf('=', '+', '-', '@', '\t')
}

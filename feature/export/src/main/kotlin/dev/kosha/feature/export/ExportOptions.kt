package dev.kosha.feature.export

import dev.kosha.core.common.Period
import java.time.LocalDate

/**
 * How much history an export covers.
 *
 * The period is the app's own month — anchored on the user's salary day —
 * which is right for a statement and wrong for "give me everything for my
 * accountant". Both were previously unavailable: every export silently used
 * the current period, so asking for a year got you a month with no indication
 * that anything had been left out.
 */
enum class ExportRange {
    THIS_PERIOD,
    LAST_3_MONTHS,
    LAST_12_MONTHS,
    THIS_FINANCIAL_YEAR,
    EVERYTHING,
    ;

    /** Inclusive start; null means "from the first transaction". */
    fun startDate(current: Period, today: LocalDate): LocalDate? = when (this) {
        THIS_PERIOD -> current.start
        LAST_3_MONTHS -> current.start.minusMonths(2)
        LAST_12_MONTHS -> current.start.minusMonths(11)
        // India's financial year runs April to March, which is what an Indian
        // user means by "this year" when the export is going to a CA.
        THIS_FINANCIAL_YEAR ->
            if (today.monthValue >= 4) {
                LocalDate.of(today.year, 4, 1)
            } else {
                LocalDate.of(today.year - 1, 4, 1)
            }
        EVERYTHING -> null
    }

    /** Exclusive end; null means "up to now". */
    fun endDateExclusive(current: Period): LocalDate? = when (this) {
        THIS_PERIOD -> current.endInclusive.plusDays(1)
        else -> null
    }
}

/**
 * What goes in the PDF. Everything is on by default except the full ledger,
 * which can run to many pages — that one is a deliberate ask.
 */
data class PdfOptions(
    val summary: Boolean = true,
    val categoryTable: Boolean = true,
    /**
     * Add a Month column to the spend table. Only meaningful once the range
     * spans more than one month, and switched on automatically when it does —
     * a table of "Food & Dining ₹40,000" across a year with no month column is
     * a number nobody can act on.
     */
    val monthColumn: Boolean = true,
    val pieChart: Boolean = true,
    val trendChart: Boolean = true,
    val topMerchants: Boolean = true,
    val recurring: Boolean = true,
    val fullLedger: Boolean = false,
    val range: ExportRange = ExportRange.THIS_PERIOD,
) {
    /** Nothing selected would produce a blank file; the screen disables export. */
    val hasAnySection: Boolean
        get() = summary || categoryTable || pieChart || trendChart ||
            topMerchants || recurring || fullLedger
}

/** What goes in the CSV. */
data class CsvOptions(
    val range: ExportRange = ExportRange.THIS_PERIOD,
    /**
     * Off by default, matching every total in the app: transfers between your
     * own accounts and cash withdrawals are movement, not spending, and
     * leaving them in makes a spreadsheet's totals disagree with Kosha's.
     */
    val includeTransfers: Boolean = false,
    /** Rows still waiting in the review queue are unconfirmed readings. */
    val includePending: Boolean = false,
    val splitAmountColumns: Boolean = false,
    val includeNotesAndTags: Boolean = true,
    val includeRunningBalance: Boolean = false,
)

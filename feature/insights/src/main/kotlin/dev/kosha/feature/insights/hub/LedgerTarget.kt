package dev.kosha.feature.insights.hub

import dev.kosha.core.database.repo.InsightsRepository
import java.time.LocalDate
import java.time.YearMonth

/**
 * What a tapped chart means in ledger terms.
 *
 * This used to be three loose arguments — category, month, search — and every
 * chart had to squeeze itself into them. A heatmap day became its whole month,
 * so tapping the one dark square you were curious about showed you thirty
 * other days as well; and a treemap slice always went out as a category, even
 * when its label was a merchant name lifted from the uncategorized pile, which
 * matched no category and so found nothing at all. Naming the fields, and
 * building each target from a factory that knows what the slice IS, makes both
 * of those impossible to write by accident.
 */
data class LedgerTarget(
    val category: String? = null,
    val monthKey: String? = null,
    val search: String? = null,
    val from: String? = null,
    val to: String? = null,
) {
    companion object {
        /** One exact day — both ends of the range are that day. */
        fun day(date: LocalDate) = LedgerTarget(from = date.toString(), to = date.toString())

        fun month(monthKey: String) = LedgerTarget(monthKey = monthKey)

        fun month(month: YearMonth) = LedgerTarget(monthKey = month.toString())

        /** A merchant is looked up by text, never as a category that does not exist. */
        fun merchant(name: String) = LedgerTarget(search = name)

        fun category(name: String) = LedgerTarget(category = name)

        /** Routes a spend slice by what its label actually is. */
        fun slice(slice: InsightsRepository.SpendSlice) = when (slice.kind) {
            InsightsRepository.SliceKind.CATEGORY -> category(slice.label)
            InsightsRepository.SliceKind.MERCHANT -> merchant(slice.label)
        }
    }
}

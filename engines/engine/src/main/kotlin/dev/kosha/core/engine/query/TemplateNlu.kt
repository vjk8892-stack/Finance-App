package dev.kosha.core.engine.query

import dev.kosha.core.common.Money

/**
 * Template NLU (spec G8). Deliberately deterministic: slot-filling over
 * regular expressions, NOT an LLM or embeddings — explicitly out of scope
 * for v1 (spec Phase 11).
 *
 * Grammar: {category|merchant} {sum|count|avg|max|list} {period}
 *
 * Unknown phrasings fail gracefully to the builder with whatever slots WERE
 * recognized, rather than guessing.
 */
class TemplateNlu(
    /** Known category names, so "dining" can resolve to "Food & Dining". */
    private val categoryNames: List<String> = emptyList(),
    /** Known normalized merchant names, so "Swiggy" resolves as a merchant. */
    private val merchantNames: List<String> = emptyList(),
) {

    sealed interface Result {
        data class Parsed(val query: Query, val confidence: Double) : Result
        /** Partial recognition — hand these slots to the builder UI. */
        data class Unparsed(val partial: QueryFilter, val recognizedSlots: Set<String>) : Result
    }

    fun parse(input: String): Result {
        val text = input.lowercase().trim()
        if (text.isBlank()) return Result.Unparsed(QueryFilter(), emptySet())

        val recognized = mutableSetOf<String>()

        val aggregation = detectAggregation(text)?.also { recognized += "aggregation" }
        val period = detectPeriod(text)?.also { recognized += "period" }
        val direction = detectDirection(text)?.also { recognized += "direction" }
        val amountFloor = detectAmountFloor(text)?.also { recognized += "amount" }

        val category = categoryNames.firstOrNull { matchesCategory(text, it) }
            ?.also { recognized += "category" }
        val merchant = merchantNames.firstOrNull { text.contains(it.lowercase()) }
            ?.also { recognized += "merchant" }

        val filter = QueryFilter(
            categoryNames = listOfNotNull(category),
            merchantContains = merchant,
            minAmountPaise = amountFloor?.paise,
            period = period ?: QueryPeriod.ThisMonth,
            direction = direction ?: QueryFilter.Direction.EXPENSE,
        )

        // A usable question needs a subject (category/merchant/amount filter)
        // or an explicit aggregation; a bare period is not a question.
        val hasSubject = category != null || merchant != null || amountFloor != null ||
            direction == QueryFilter.Direction.INCOME
        if (aggregation == null && !hasSubject) {
            return Result.Unparsed(filter, recognized)
        }

        val resolvedAggregation = aggregation
            ?: if (amountFloor != null) Aggregation.LIST else Aggregation.SUM

        // Confidence reflects how much of the grammar we actually matched.
        val confidence = (recognized.size / 3.0).coerceIn(0.34, 1.0)
        return Result.Parsed(Query(filter, resolvedAggregation), confidence)
    }

    /**
     * "dining" → Food & Dining; "grocery" → Groceries; "emi" → EMI & Loans.
     * Matching is per word and stem-aware, so plural/singular forms of a
     * category word resolve to the same category.
     */
    private fun matchesCategory(text: String, categoryName: String): Boolean {
        val lower = categoryName.lowercase()
        if (text.contains(lower)) return true
        val inputStems = text.split(NON_WORD).filter { it.isNotBlank() }.map(::stem).toSet()
        return lower.split(NON_WORD).any { word ->
            word.length >= MIN_CATEGORY_WORD && stem(word) in inputStems
        }
    }

    /** Crude but predictable stemmer — enough for category words. */
    private fun stem(word: String): String = when {
        word.endsWith("ies") && word.length > 4 -> word.dropLast(3) + "y"
        word.endsWith("es") && word.length > 4 -> word.dropLast(2)
        word.endsWith("s") && word.length > 3 -> word.dropLast(1)
        else -> word
    }

    private fun detectAggregation(text: String): Aggregation? = when {
        COUNT_PATTERN.containsMatchIn(text) -> Aggregation.COUNT
        AVG_PATTERN.containsMatchIn(text) -> Aggregation.AVG
        MAX_PATTERN.containsMatchIn(text) -> Aggregation.MAX
        LIST_PATTERN.containsMatchIn(text) -> Aggregation.LIST
        SUM_PATTERN.containsMatchIn(text) -> Aggregation.SUM
        else -> null
    }

    private fun detectDirection(text: String): QueryFilter.Direction? = when {
        INCOME_PATTERN.containsMatchIn(text) -> QueryFilter.Direction.INCOME
        SPEND_PATTERN.containsMatchIn(text) -> QueryFilter.Direction.EXPENSE
        else -> null
    }

    private fun detectAmountFloor(text: String): Money? {
        val match = ABOVE_PATTERN.find(text) ?: return null
        return Money.parseOrNull(match.groups["amount"]!!.value)
    }

    private fun detectPeriod(text: String): QueryPeriod? {
        LAST_N_PATTERN.find(text)?.let { match ->
            val n = match.groups["n"]!!.value.toIntOrNull() ?: return@let
            return when (match.groups["unit"]!!.value) {
                "day", "days" -> QueryPeriod.LastNDays(n)
                "week", "weeks" -> QueryPeriod.LastNWeeks(n)
                else -> QueryPeriod.LastNMonths(n)
            }
        }
        MONTH_NAMES.entries.firstOrNull { (name, _) -> text.contains(name) }?.let {
            return QueryPeriod.NamedMonth(it.value)
        }
        return when {
            text.contains("today") -> QueryPeriod.Today
            text.contains("yesterday") -> QueryPeriod.Yesterday
            text.contains("last week") -> QueryPeriod.LastWeek
            text.contains("this week") -> QueryPeriod.ThisWeek
            text.contains("last month") -> QueryPeriod.LastMonth
            text.contains("this month") -> QueryPeriod.ThisMonth
            // FY before "this year": "EMI total this FY" mentions both.
            FY_PATTERN.containsMatchIn(text) -> QueryPeriod.FinancialYear
            text.contains("this year") -> QueryPeriod.ThisYear
            else -> null
        }
    }

    private companion object {
        /** "emi" must match, so the floor is 3 rather than 4. */
        const val MIN_CATEGORY_WORD = 3
        val NON_WORD = Regex("[^a-z0-9]+")
        val SUM_PATTERN = Regex("\\b(how much|total|spent|spend|sum|paid|save[d]?|received)\\b")
        val COUNT_PATTERN = Regex("\\b(how many|count|number of|orders|times)\\b")
        val AVG_PATTERN = Regex("\\b(average|avg|typical|mean)\\b")
        val MAX_PATTERN = Regex("\\b(biggest|largest|most expensive|max|highest)\\b")
        val LIST_PATTERN = Regex("\\b(list|show me|show|which)\\b")
        val INCOME_PATTERN = Regex("\\b(salary|income|received|credited|earned)\\b")
        val SPEND_PATTERN = Regex("\\b(spent|spend|paid|expense|expenses)\\b")
        val ABOVE_PATTERN = Regex("\\b(?:above|over|more than|greater than)\\s*(?:₹|rs\\.?)?\\s*(?<amount>[\\d,]+(?:\\.\\d{1,2})?)")
        val LAST_N_PATTERN = Regex("\\blast\\s+(?<n>\\d+)\\s+(?<unit>days?|weeks?|months?)\\b")
        val FY_PATTERN = Regex("\\b(fy|financial year|this fy)\\b")
        val MONTH_NAMES = mapOf(
            "january" to 1, "february" to 2, "march" to 3, "april" to 4,
            "may" to 5, "june" to 6, "july" to 7, "august" to 8,
            "september" to 9, "october" to 10, "november" to 11, "december" to 12,
        )
    }
}

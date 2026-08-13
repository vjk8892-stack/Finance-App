package dev.kosha.core.engine.query

import dev.kosha.core.common.Money
import kotlinx.serialization.Serializable

/**
 * Query grammar shared by the builder UI, SavedQuery persistence and the
 * template NLU assistant (spec C3/G8/G12) — one filter engine, three front
 * doors.
 */
@Serializable
data class QueryFilter(
    val categoryNames: List<String> = emptyList(),
    val merchantContains: String? = null,
    val accountNames: List<String> = emptyList(),
    val minAmountPaise: Long? = null,
    val maxAmountPaise: Long? = null,
    val period: QueryPeriod = QueryPeriod.ThisMonth,
    val direction: Direction = Direction.EXPENSE,
    val moodTags: List<String> = emptyList(),
    val taxTags: List<String> = emptyList(),
) {
    enum class Direction { EXPENSE, INCOME, BOTH }
}

@Serializable
sealed interface QueryPeriod {
    @Serializable data object Today : QueryPeriod
    @Serializable data object Yesterday : QueryPeriod
    @Serializable data object ThisWeek : QueryPeriod
    @Serializable data object LastWeek : QueryPeriod
    @Serializable data object ThisMonth : QueryPeriod
    @Serializable data object LastMonth : QueryPeriod
    @Serializable data object ThisYear : QueryPeriod
    @Serializable data object FinancialYear : QueryPeriod
    @Serializable data class LastNDays(val n: Int) : QueryPeriod
    @Serializable data class LastNWeeks(val n: Int) : QueryPeriod
    @Serializable data class LastNMonths(val n: Int) : QueryPeriod
    /** 1–12; resolves to that month in the most recent year it has passed. */
    @Serializable data class NamedMonth(val month: Int) : QueryPeriod
}

/** What the question asks for (spec G8 slots). */
enum class Aggregation { SUM, COUNT, AVG, MAX, LIST }

@Serializable
data class Query(
    val filter: QueryFilter,
    val aggregation: Aggregation = Aggregation.SUM,
)

/** Result shape the answer card renders. */
sealed interface QueryAnswer {
    data class Sum(val total: Money, val count: Int) : QueryAnswer
    data class Count(val count: Int) : QueryAnswer
    data class Average(val average: Money, val count: Int) : QueryAnswer
    data class Max(val amount: Money, val merchant: String?) : QueryAnswer
    data class Listing(val count: Int) : QueryAnswer
    data object Empty : QueryAnswer
}

package dev.kosha.core.database.repo

import dev.kosha.core.common.Money
import dev.kosha.core.common.Period
import dev.kosha.core.common.Periods
import dev.kosha.core.database.dao.CategoryDao
import dev.kosha.core.database.dao.LedgerRow
import dev.kosha.core.database.dao.TransactionDao
import dev.kosha.core.database.model.TxnStatus
import dev.kosha.core.database.model.TxnType
import dev.kosha.core.engine.query.Aggregation
import dev.kosha.core.engine.query.Query
import dev.kosha.core.engine.query.QueryAnswer
import dev.kosha.core.engine.query.QueryFilter
import dev.kosha.core.engine.query.QueryPeriod
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

/**
 * Runs a [Query] against the ledger. The builder UI and the NLU assistant
 * both land here — one filter engine, so a typed question and a built filter
 * can never disagree (spec C3/G8).
 */
@Singleton
class QueryRepository @Inject constructor(
    private val transactionDao: TransactionDao,
    private val categoryDao: CategoryDao,
) {
    private val zone: ZoneId = ZoneId.systemDefault()

    data class QueryResult(val answer: QueryAnswer, val rows: List<LedgerRow>)

    suspend fun run(query: Query, anchorDay: Int): QueryResult {
        val period = resolvePeriod(query.filter.period, anchorDay)
        val categories = categoryDao.observeAll().first()
        val categoryIds = query.filter.categoryNames
            .mapNotNull { name -> categories.firstOrNull { it.name.equals(name, true) }?.id }
            .toSet()

        val rows = transactionDao.observeLedger().first()
            .filter { row ->
                val txn = row.txn
                if (txn.status != TxnStatus.COMMITTED) return@filter false

                val date = Periods.localDateOf(txn.timestampMillis, zone)
                if (date !in period) return@filter false

                val directionOk = when (query.filter.direction) {
                    QueryFilter.Direction.EXPENSE -> txn.type == TxnType.DEBIT
                    QueryFilter.Direction.INCOME -> txn.type == TxnType.CREDIT
                    QueryFilter.Direction.BOTH -> true
                }
                if (!directionOk) return@filter false

                if (categoryIds.isNotEmpty() && txn.categoryId !in categoryIds) return@filter false

                query.filter.merchantContains?.let { needle ->
                    val haystack = (txn.merchantNormalized ?: txn.merchantRaw).orEmpty()
                    if (!haystack.contains(needle, ignoreCase = true)) return@filter false
                }

                query.filter.minAmountPaise?.let { if (txn.amountPaise < it) return@filter false }
                query.filter.maxAmountPaise?.let { if (txn.amountPaise > it) return@filter false }

                if (query.filter.accountNames.isNotEmpty() &&
                    query.filter.accountNames.none { it.equals(row.accountName, true) }
                ) {
                    return@filter false
                }

                if (query.filter.moodTags.isNotEmpty() &&
                    txn.moodTag?.name?.lowercase() !in query.filter.moodTags.map { it.lowercase() }
                ) {
                    return@filter false
                }
                true
            }

        val total = rows.sumOf { it.txn.amountPaise }
        val answer = when {
            rows.isEmpty() -> QueryAnswer.Empty
            query.aggregation == Aggregation.SUM -> QueryAnswer.Sum(Money(total), rows.size)
            query.aggregation == Aggregation.COUNT -> QueryAnswer.Count(rows.size)
            query.aggregation == Aggregation.AVG -> QueryAnswer.Average(Money(total / rows.size), rows.size)
            query.aggregation == Aggregation.MAX -> rows.maxBy { it.txn.amountPaise }.let {
                QueryAnswer.Max(Money(it.txn.amountPaise), it.txn.merchantRaw)
            }
            else -> QueryAnswer.Listing(rows.size)
        }
        return QueryResult(answer, rows)
    }

    /** Resolves the grammar's period slot against the user's month anchor. */
    fun resolvePeriod(period: QueryPeriod, anchorDay: Int, today: LocalDate = LocalDate.now(zone)): Period =
        when (period) {
            QueryPeriod.Today -> Period(today, today)
            QueryPeriod.Yesterday -> Period(today.minusDays(1), today.minusDays(1))
            QueryPeriod.ThisWeek -> Periods.weeklyPeriodContaining(today)
            QueryPeriod.LastWeek -> Periods.weeklyPeriodContaining(today.minusWeeks(1))
            QueryPeriod.ThisMonth -> Periods.monthlyPeriodContaining(today, anchorDay)
            QueryPeriod.LastMonth -> Periods.previousMonthlyPeriod(
                Periods.monthlyPeriodContaining(today, anchorDay),
                anchorDay,
            )
            QueryPeriod.ThisYear -> Period(today.withDayOfYear(1), today)
            QueryPeriod.FinancialYear -> Periods.financialYearContaining(today)
            is QueryPeriod.LastNDays -> Period(today.minusDays(period.n.toLong()), today)
            is QueryPeriod.LastNWeeks -> Period(today.minusWeeks(period.n.toLong()), today)
            is QueryPeriod.LastNMonths -> Period(today.minusMonths(period.n.toLong()), today)
            is QueryPeriod.NamedMonth -> {
                // The named month in the most recent year it has completed.
                val year = if (period.month <= today.monthValue) today.year else today.year - 1
                val start = LocalDate.of(year, period.month, 1)
                Period(start, start.plusMonths(1).minusDays(1))
            }
        }
}

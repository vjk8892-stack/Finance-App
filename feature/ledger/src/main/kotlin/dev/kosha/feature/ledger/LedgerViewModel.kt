package dev.kosha.feature.ledger

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.kosha.core.common.Money
import dev.kosha.core.database.dao.LedgerRow
import dev.kosha.core.database.dao.MetaDao
import dev.kosha.core.database.dao.TransactionDao
import dev.kosha.core.database.model.CategoryEntity
import dev.kosha.core.database.model.SavedQueryEntity
import dev.kosha.core.database.model.TxnType
import dev.kosha.core.database.repo.CategoryRepository
import dev.kosha.core.database.repo.QueryRepository
import dev.kosha.core.database.repo.TransactionRepository
import dev.kosha.core.database.settings.SettingsRepository
import dev.kosha.core.engine.query.Aggregation
import dev.kosha.core.engine.query.Query
import dev.kosha.core.engine.query.QueryAnswer
import dev.kosha.core.engine.query.QueryFilter
import dev.kosha.core.engine.query.TemplateNlu
import dev.kosha.feature.ledger.query.QueryUiState
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

data class LedgerDayGroup(
    val date: LocalDate,
    val label: String,
    val rows: List<LedgerRow>,
)

data class LedgerMonthGroup(
    val monthLabel: String,
    /** Net spend for the month: debits − credits, parents only. */
    val totalSpend: Money,
    val days: List<LedgerDayGroup>,
)

data class LedgerUiState(
    val months: List<LedgerMonthGroup> = emptyList(),
    val categories: List<CategoryEntity> = emptyList(),
    val isEmpty: Boolean = false,
    val reviewCount: Int = 0,
)

@HiltViewModel
class LedgerViewModel @Inject constructor(
    private val transactionRepository: TransactionRepository,
    private val transactionDao: TransactionDao,
    private val queryRepository: QueryRepository,
    private val settingsRepository: SettingsRepository,
    private val metaDao: MetaDao,
    private val categoryRepository: CategoryRepository,
) : ViewModel() {

    private val zone: ZoneId = ZoneId.systemDefault()
    private val dayFormat = DateTimeFormatter.ofPattern("EEE d MMM")
    private val monthFormat = DateTimeFormatter.ofPattern("MMMM yyyy")

    val uiState: StateFlow<LedgerUiState> = combine(
        transactionRepository.observeLedger(),
        categoryRepository.observeAll(),
        transactionDao.observeReviewCount(),
    ) { rows, categories, reviewCount ->
        LedgerUiState(
            months = group(rows),
            categories = categories,
            isEmpty = rows.isEmpty(),
            reviewCount = reviewCount,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LedgerUiState())

    private fun group(rows: List<LedgerRow>): List<LedgerMonthGroup> {
        val today = LocalDate.now(zone)
        return rows
            .groupBy { localDate(it.txn.timestampMillis) }
            .entries
            .sortedByDescending { it.key }
            .groupBy { it.key.withDayOfMonth(1) }
            .entries
            .sortedByDescending { it.key }
            .map { (month, dayEntries) ->
                val allRows = dayEntries.flatMap { it.value }
                val spend = allRows.sumOf { row ->
                    if (row.txn.type == TxnType.DEBIT) row.txn.amountPaise else -row.txn.amountPaise
                }
                LedgerMonthGroup(
                    monthLabel = month.format(monthFormat),
                    totalSpend = Money(spend),
                    days = dayEntries.map { (date, dayRows) ->
                        LedgerDayGroup(
                            date = date,
                            label = when (date) {
                                today -> "Today"
                                today.minusDays(1) -> "Yesterday"
                                else -> date.format(dayFormat)
                            },
                            rows = dayRows,
                        )
                    },
                )
            }
    }

    private fun localDate(epochMillis: Long): LocalDate =
        java.time.Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate()

    fun recategorize(txnId: Long, categoryId: Long) {
        viewModelScope.launch { transactionRepository.recategorize(txnId, categoryId) }
    }

    fun delete(txnId: Long) {
        viewModelScope.launch { transactionRepository.delete(txnId) }
    }

    fun updateNote(txnId: Long, note: String?) {
        viewModelScope.launch {
            transactionRepository.byId(txnId)?.let {
                transactionRepository.update(it.copy(note = note?.takeIf { n -> n.isNotBlank() }))
            }
        }
    }

    // --- Query assistant (spec C3/G8) ---

    private val _query = MutableStateFlow(QueryUiState())
    val query: StateFlow<QueryUiState> = _query.asStateFlow()

    fun onQueryTextChange(text: String) {
        _query.value = _query.value.copy(text = text)
    }

    fun submitQuery() {
        val text = _query.value.text
        if (text.isBlank()) {
            clearQuery()
            return
        }
        viewModelScope.launch {
            val settings = settingsRepository.settings.first()
            val categories = categoryRepository.observeAll().first()
            val merchants = transactionDao.knownMerchants()
            val nlu = TemplateNlu(
                categoryNames = categories.map { it.name },
                merchantNames = merchants,
            )
            when (val parsed = nlu.parse(text)) {
                is TemplateNlu.Result.Parsed -> {
                    val result = queryRepository.run(parsed.query, settings.periodAnchorDay)
                    _query.value = _query.value.copy(
                        answer = result.answer,
                        rows = result.rows,
                        fellBackToBuilder = false,
                        partialFilter = parsed.query.filter,
                    )
                }
                is TemplateNlu.Result.Unparsed -> {
                    // Never guess: show what was recognized and open the builder.
                    _query.value = _query.value.copy(
                        answer = QueryAnswer.Empty,
                        rows = emptyList(),
                        fellBackToBuilder = true,
                        partialFilter = parsed.partial,
                        builderOpen = true,
                    )
                }
            }
        }
    }

    fun clearQuery() {
        _query.value = QueryUiState()
    }

    fun openBuilder() {
        _query.value = _query.value.copy(builderOpen = true)
    }

    fun closeBuilder() {
        _query.value = _query.value.copy(builderOpen = false)
    }

    fun runBuilderQuery(filter: QueryFilter, aggregation: Aggregation) {
        viewModelScope.launch {
            val anchor = settingsRepository.settings.first().periodAnchorDay
            val result = queryRepository.run(Query(filter, aggregation), anchor)
            _query.value = _query.value.copy(
                answer = result.answer,
                rows = result.rows,
                fellBackToBuilder = false,
                partialFilter = filter,
                builderOpen = false,
            )
        }
    }

    fun saveCurrentView(name: String) {
        val filter = _query.value.partialFilter ?: return
        viewModelScope.launch {
            metaDao.insertSavedQuery(
                SavedQueryEntity(name = name, filterJson = Json.encodeToString(filter)),
            )
        }
    }

    val savedViews: StateFlow<List<SavedQueryEntity>> = metaDao.observeSavedQueries()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun runSavedView(saved: SavedQueryEntity) {
        val filter = runCatching { Json.decodeFromString<QueryFilter>(saved.filterJson) }.getOrNull()
            ?: return
        runBuilderQuery(filter, Aggregation.SUM)
    }
}

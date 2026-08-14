package dev.kosha.feature.ledger

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.kosha.core.common.Money
import dev.kosha.core.database.dao.LedgerRow
import dev.kosha.core.database.dao.MetaDao
import dev.kosha.core.database.dao.TransactionDao
import dev.kosha.core.database.model.AccountEntity
import dev.kosha.core.database.model.CategoryEntity
import dev.kosha.core.database.model.EvidenceKind
import dev.kosha.core.database.model.SavedQueryEntity
import dev.kosha.core.database.model.SystemCategoryKey
import dev.kosha.core.database.model.TxnSource
import dev.kosha.core.database.model.TxnType
import dev.kosha.core.database.repo.AccountRepository
import dev.kosha.core.database.repo.CategoryRepository
import dev.kosha.core.database.repo.OriginalMessageSource
import dev.kosha.core.database.repo.QueryRepository
import dev.kosha.core.database.repo.RetroCategorizer
import dev.kosha.core.database.repo.TransactionRepository
import dev.kosha.core.database.settings.SettingsRepository
import dev.kosha.core.engine.merchant.MerchantMatcher
import dev.kosha.core.engine.query.Aggregation
import dev.kosha.core.engine.query.Query
import dev.kosha.core.engine.query.QueryAnswer
import dev.kosha.core.engine.query.QueryFilter
import dev.kosha.core.engine.query.TemplateNlu
import dev.kosha.feature.ledger.query.QueryUiState
import java.time.LocalDate
import java.time.YearMonth
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
    /** Net change for the day: credits positive, debits negative. */
    val total: Money,
    val rows: List<LedgerRow>,
)

data class LedgerMonthGroup(
    val monthLabel: String,
    /**
     * Net CHANGE for the month: credits positive, debits negative.
     *
     * It used to be spend-positive (debits − credits), which rendered a screen
     * of nothing but credits as "−₹21,386" — the exact opposite of what the
     * rows said. Matching the sign convention the rows already use means the
     * header agrees with them under every filter.
     */
    val total: Money,
    val days: List<LedgerDayGroup>,
)

/** Ledger direction filter — "what came in" vs "what went out". */
enum class LedgerFilter { ALL, OUT, IN }

/**
 * Everything narrowing the ledger at once. Kept as one object so the screen
 * can say how many narrowings are active without inspecting each field.
 */
data class LedgerFilters(
    val direction: LedgerFilter = LedgerFilter.ALL,
    val accountId: Long? = null,
    val month: YearMonth? = null,
    val categoryId: Long? = null,
) {
    val activeCount: Int =
        listOfNotNull(
            accountId, month, categoryId,
            direction.takeIf { it != LedgerFilter.ALL },
        ).size
}

data class LedgerUiState(
    val months: List<LedgerMonthGroup> = emptyList(),
    val categories: List<CategoryEntity> = emptyList(),
    val isEmpty: Boolean = false,
    val reviewCount: Int = 0,
    val filters: LedgerFilters = LedgerFilters(),
    /** Accounts and months present in the data, for the filter sheet. */
    val accounts: List<AccountEntity> = emptyList(),
    val availableMonths: List<YearMonth> = emptyList(),
    /** True when rows exist but the current filters hide them all. */
    val hiddenByFilter: Boolean = false,
) {
    /** System Transfers row — the "this is my own account" shortcut. */
    val transfersCategoryId: Long?
        get() = categories.firstOrNull { it.systemKey == SystemCategoryKey.TRANSFERS }?.id
}

/**
 * What is behind one ledger row. The original bank message is the point: a
 * merchant name Kosha read wrong is impossible to correct — or even to judge —
 * without seeing the text it came from.
 */
data class TransactionDetail(
    val row: LedgerRow,
    /**
     * The bank message. Read back from the inbox on demand, falling back to a
     * stored copy when raw retention happened to be on (spec B4).
     */
    val originalMessage: String? = null,
    /** Photo evidence URI for OCR captures. */
    val photoUri: String? = null,
    /** Still loading the message — distinct from "there isn't one". */
    val loadingMessage: Boolean = false,
    /**
     * SMS-sourced, but the message could not be read back — no permission, a
     * lite build, or the user deleted it from their inbox.
     */
    val messageUnavailable: Boolean = false,
)

@HiltViewModel
class LedgerViewModel @Inject constructor(
    private val transactionRepository: TransactionRepository,
    private val transactionDao: TransactionDao,
    private val queryRepository: QueryRepository,
    private val settingsRepository: SettingsRepository,
    private val metaDao: MetaDao,
    private val categoryRepository: CategoryRepository,
    private val originalMessageSource: OriginalMessageSource,
    private val retroCategorizer: RetroCategorizer,
    accountRepository: AccountRepository,
) : ViewModel() {

    private val zone: ZoneId = ZoneId.systemDefault()
    private val dayFormat = DateTimeFormatter.ofPattern("EEE d MMM")
    private val monthFormat = DateTimeFormatter.ofPattern("MMMM yyyy")

    private val _filters = MutableStateFlow(LedgerFilters())

    fun setDirection(direction: LedgerFilter) {
        _filters.value = _filters.value.copy(direction = direction)
    }

    fun setAccount(accountId: Long?) {
        _filters.value = _filters.value.copy(accountId = accountId)
    }

    fun setMonth(month: YearMonth?) {
        _filters.value = _filters.value.copy(month = month)
    }

    fun setCategory(categoryId: Long?) {
        _filters.value = _filters.value.copy(categoryId = categoryId)
    }

    fun clearFilters() {
        _filters.value = LedgerFilters()
    }

    val uiState: StateFlow<LedgerUiState> = combine(
        transactionRepository.observeLedger(),
        categoryRepository.observeAll(),
        transactionDao.observeReviewCount(),
        accountRepository.observeActive(),
        _filters,
    ) { rows, categories, reviewCount, accounts, filters ->
        val visible = rows.filter { row -> filters.matches(row) }
        LedgerUiState(
            months = group(visible),
            categories = categories,
            isEmpty = visible.isEmpty(),
            reviewCount = reviewCount,
            filters = filters,
            accounts = accounts,
            // Only offer months that actually contain something.
            availableMonths = rows
                .map { YearMonth.from(localDate(it.txn.timestampMillis)) }
                .distinct()
                .sortedDescending(),
            // "Nothing here" reads as a bug when the cause is a filter you
            // forgot you set, so the two empties say different things.
            hiddenByFilter = visible.isEmpty() && rows.isNotEmpty(),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LedgerUiState())

    private fun LedgerFilters.matches(row: LedgerRow): Boolean {
        val directionOk = when (direction) {
            LedgerFilter.ALL -> true
            LedgerFilter.OUT -> row.txn.type == TxnType.DEBIT
            LedgerFilter.IN -> row.txn.type == TxnType.CREDIT
        }
        if (!directionOk) return false
        if (accountId != null && row.txn.accountId != accountId) return false
        if (categoryId != null && row.txn.categoryId != categoryId) return false
        if (month != null && YearMonth.from(localDate(row.txn.timestampMillis)) != month) return false
        return true
    }

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
                val net = allRows.sumOf { row ->
                    if (row.txn.type == TxnType.DEBIT) -row.txn.amountPaise else row.txn.amountPaise
                }
                LedgerMonthGroup(
                    monthLabel = month.format(monthFormat),
                    total = Money(net),
                    days = dayEntries.map { (date, dayRows) ->
                        LedgerDayGroup(
                            date = date,
                            label = when (date) {
                                today -> "Today"
                                today.minusDays(1) -> "Yesterday"
                                else -> date.format(dayFormat)
                            },
                            total = Money(
                                dayRows.sumOf {
                                    if (it.txn.type == TxnType.DEBIT) -it.txn.amountPaise else it.txn.amountPaise
                                },
                            ),
                            rows = dayRows,
                        )
                    },
                )
            }
    }

    private fun localDate(epochMillis: Long): LocalDate =
        java.time.Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate()

    // --- Row detail, including the message that produced the row ---

    private val _detail = MutableStateFlow<TransactionDetail?>(null)
    val detail: StateFlow<TransactionDetail?> = _detail.asStateFlow()

    fun openDetail(row: LedgerRow) {
        val isSms = row.txn.source == TxnSource.SMS
        // Show the row immediately; the lookups are IO.
        _detail.value = TransactionDetail(row = row, loadingMessage = isSms)
        viewModelScope.launch {
            val evidence = transactionDao.evidenceFor(row.txn.id)
            val stored = evidence.firstOrNull { it.kind == EvidenceKind.SMS_TEXT }
                ?.payload
                ?.takeIf { it.isNotBlank() }
            // Prefer the inbox: it works for every SMS row, whereas the stored
            // copy only exists when the retention setting happened to be on
            // BEFORE the message arrived — which is never when you need it.
            val message = stored ?: if (isSms) originalMessageSource.messageAt(row.txn.timestampMillis) else null
            _detail.value = TransactionDetail(
                row = row,
                originalMessage = message,
                photoUri = evidence.firstOrNull { it.kind == EvidenceKind.PHOTO_URI }?.payload,
                loadingMessage = false,
                messageUnavailable = message == null && isSms,
            )
        }
    }

    fun closeDetail() {
        _detail.value = null
    }

    /**
     * Apply the user's corrections. `update` recomputes the account balance,
     * so an amount or direction change is reflected immediately.
     */
    fun saveEdit(edited: EditedTransaction) {
        viewModelScope.launch {
            val existing = transactionRepository.byId(edited.id) ?: return@launch
            transactionRepository.update(
                existing.copy(
                    amountPaise = edited.amountPaise,
                    type = edited.type,
                    merchantRaw = edited.merchantRaw,
                    // Renaming a merchant has to renormalize too, or
                    // categorization and dedup keep matching the old name.
                    merchantNormalized = edited.merchantRaw
                        ?.let { MerchantMatcher.normalize(it) }
                        ?.takeIf { it.isNotEmpty() },
                    note = edited.note,
                    timestampMillis = edited.timestampMillis,
                    categoryId = edited.categoryId,
                ),
            )
            closeDetail()
        }
    }

    /**
     * Categorize this merchant's whole history at once. Correcting one row of
     * a merchant you have twenty of is not really a per-row decision.
     */
    fun recategorizeMerchant(row: LedgerRow, categoryId: Long) {
        val merchant = row.txn.merchantNormalized
        viewModelScope.launch {
            if (merchant.isNullOrBlank()) {
                transactionRepository.recategorize(row.txn.id, categoryId)
            } else {
                transactionRepository.recategorizeMerchant(merchant, categoryId)
            }
        }
    }

    /** Retro-categorize existing rows using the current rules. */
    fun categorizeExisting() {
        viewModelScope.launch {
            _retroResult.value = retroCategorizer.run()
        }
    }

    private val _retroResult = MutableStateFlow<RetroCategorizer.Result?>(null)
    val retroResult: StateFlow<RetroCategorizer.Result?> = _retroResult.asStateFlow()

    fun clearRetroResult() {
        _retroResult.value = null
    }

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

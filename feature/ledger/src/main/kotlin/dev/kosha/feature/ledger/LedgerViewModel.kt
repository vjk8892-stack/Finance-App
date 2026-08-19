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
    /** Net for the day on the same basis as every other total in the app. */
    val total: Money,
    val rows: List<LedgerRow>,
)

data class LedgerMonthGroup(
    val monthLabel: String,
    /**
     * Net for the month: credits positive, debits negative, EXCLUDING
     * transfers and cash withdrawals.
     *
     * Those exclusions are the point. Every other figure in the app — the
     * savings gap, the budgets, the charts — leaves transfers out, because
     * moving your own money between your own accounts is neither income nor
     * spending. This header did not, so Home said ₹84,199 spent while the
     * ledger said ₹77,812 for the same August: the difference was exactly one
     * credit-card bill payment, counted here and nowhere else. Two numbers
     * for the same month that disagree makes both untrustworthy.
     */
    val total: Money,
    /** Transfer volume left out above, so the difference is never a mystery. */
    val excludedTransfers: Money,
    val days: List<LedgerDayGroup>,
)

/**
 * A completed action that can still be taken back, with the label to offer it
 * under. Held in memory only — undo is a few seconds of grace, not history.
 */
data class UndoableAction(val kind: UndoKind, val undo: suspend () -> Unit)

/** What was undone — the screen turns this into wording. */
enum class UndoKind { DELETED, RECATEGORIZED, APPROVED, DISCARDED }

/**
 * How the ledger is ordered.
 *
 * [groupsByDate] is the part that was missing. The list is grouped into month
 * and day sections, so sorting by amount sorted only WITHIN each day and the
 * sections stayed in date order — picking "Largest first" left the biggest
 * transaction wherever its date happened to fall, which is not a sort at all.
 * Amount and name orderings now drop the date sections and run flat across
 * everything, which is what "largest, regardless of when" has to mean.
 */
enum class LedgerSort(val groupsByDate: Boolean) {
    NEWEST(true),
    OLDEST(true),
    LARGEST(false),
    SMALLEST(false),
    NAME(false),
}

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
    /**
     * Plain substring match on name, category, account or note.
     *
     * The search bar only ever ran the template NLU, which needs a full known
     * merchant name in the phrase — so typing "swig" matched nothing and the
     * bar read as broken. This filters as you type; the NLU still runs on
     * submit for "dining last month" style questions.
     */
    val text: String = "",
    /**
     * An explicit date window, used when Home hands over its PERIOD.
     *
     * A period is anchored on the user's salary day, so "August" on Home can
     * mean 5 Aug – 4 Sep while "August 2026" in the ledger means the calendar
     * month. Same word, different windows, and no way to tell — which is how
     * two screens end up quoting different totals for "August". Passing the
     * actual range makes Home's number checkable against its own rows.
     */
    val from: LocalDate? = null,
    val to: LocalDate? = null,
) {
    val activeCount: Int =
        listOfNotNull(
            accountId, month, categoryId, from,
            text.takeIf { it.isNotBlank() },
            direction.takeIf { it != LedgerFilter.ALL },
        ).size
}

data class LedgerUiState(
    val months: List<LedgerMonthGroup> = emptyList(),
    /** Populated instead of [months] when the sort ignores dates. */
    val flatRows: List<LedgerRow> = emptyList(),
    /** Net across [flatRows], same exclusions as a month header. */
    val flatTotal: Money = Money.ZERO,
    val categories: List<CategoryEntity> = emptyList(),
    val isEmpty: Boolean = false,
    val reviewCount: Int = 0,
    val filters: LedgerFilters = LedgerFilters(),
    /** Accounts and months present in the data, for the filter sheet. */
    val accounts: List<AccountEntity> = emptyList(),
    val availableMonths: List<YearMonth> = emptyList(),
    val sort: LedgerSort = LedgerSort.NEWEST,
    /** True when rows exist but the current filters hide them all. */
    val hiddenByFilter: Boolean = false,
    /**
     * Balance after each transaction, by transaction id.
     *
     * Only populated when the list is filtered to ONE account. A running
     * balance across several accounts at once is not a number that means
     * anything — it would add a credit card to a savings account and present
     * the result as a fact — so mixed views get none rather than a misleading
     * one.
     */
    val runningBalances: Map<Long, Money> = emptyMap(),
    /**
     * Rows picked for a bulk action. Empty means selection mode is off — the
     * list behaves normally and nothing about it changes.
     */
    val selectedIds: Set<Long> = emptySet(),
) {
    /** System Transfers row — the "this is my own account" shortcut. */
    val transfersCategoryId: Long?
        get() = categories.firstOrNull { it.systemKey == SystemCategoryKey.TRANSFERS }?.id

    /**
     * Categories the month and day totals leave out (spec G12). The rows are
     * still listed — they happened — so the list has to SAY which ones are not
     * being counted, or the totals look wrong to anyone adding up the rows.
     */
    val excludedCategoryIds: Set<Long> get() = excludedCategoryIdsOf(categories)
}

/** Spec G12: money that moved without being income or spending. */
internal fun excludedCategoryIdsOf(categories: List<CategoryEntity>): Set<Long> = categories
    .filter {
        it.systemKey == SystemCategoryKey.TRANSFERS ||
            it.systemKey == SystemCategoryKey.CASH_WITHDRAWAL
    }
    .map { it.id }
    .toSet()

/**
 * What is behind one ledger row. The original bank message is the point: a
 * merchant name Kosha read wrong is impossible to correct — or even to judge —
 * without seeing the text it came from.
 */
private val CSV_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
private val CSV_STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")

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
    @dagger.hilt.android.qualifiers.ApplicationContext
    private val appContext: android.content.Context,
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
    private val _sort = MutableStateFlow(LedgerSort.NEWEST)
    private val _undo = MutableStateFlow<UndoableAction?>(null)
    val undo: StateFlow<UndoableAction?> = _undo.asStateFlow()

    fun setSort(sort: LedgerSort) {
        _sort.value = sort
    }

    fun setSearchText(text: String) {
        _filters.value = _filters.value.copy(text = text)
    }

    /** Take back the last destructive action, then clear the offer. */
    fun performUndo() {
        val action = _undo.value ?: return
        _undo.value = null
        viewModelScope.launch { action.undo() }
    }

    fun dismissUndo() {
        _undo.value = null
    }

    /**
     * Arrive pre-filtered when a chart sent the user here. A chart slice is a
     * claim about a slice of the ledger — "₹16,173 on EMI & Loans" — and the
     * only way to check a claim is to see the rows behind it, so tapping one
     * has to land on exactly those rows rather than on everything.
     */
    fun applyIncomingFilter(
        categoryName: String?,
        monthKey: String?,
        from: String?,
        to: String?,
        search: String? = null,
    ) {
        if (categoryName == null && monthKey == null && from == null && search == null) return
        val month = monthKey?.let { runCatching { YearMonth.parse(it) }.getOrNull() }
        val fromDate = from?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        val toDate = to?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        viewModelScope.launch {
            val categoryId = categoryName
                ?.let { name -> categoryRepository.observeAll().first().firstOrNull { it.name == name }?.id }
            _filters.value = LedgerFilters(
                categoryId = categoryId,
                month = month,
                from = fromDate,
                to = toDate,
                text = search.orEmpty(),
            )
        }
    }

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

    private val _selection = MutableStateFlow<Set<Long>>(emptySet())

    /**
     * Correcting a parser's work is rarely a one-row job — a merchant Kosha
     * read wrong is read wrong every time it appears. Without this the only
     * bulk tools were in the review queue, so anything already committed had
     * to be fixed one row at a time.
     */
    fun toggleSelected(id: Long) {
        _selection.value = _selection.value.let { if (id in it) it - id else it + id }
    }

    fun clearSelection() {
        _selection.value = emptySet()
    }

    fun selectAllVisible() {
        _selection.value = uiState.value.let { state ->
            (state.flatRows + state.months.flatMap { m -> m.days.flatMap { it.rows } })
                .map { it.txn.id }
                .toSet()
        }
    }

    fun deleteSelected() {
        val ids = _selection.value.toList()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            val captured = transactionRepository.deleteAllCapturing(ids)
            _selection.value = emptySet()
            if (captured != null) {
                _undo.value = UndoableAction(UndoKind.DELETED) {
                    transactionRepository.restore(captured)
                }
            }
        }
    }

    fun recategorizeSelected(categoryId: Long) {
        val ids = _selection.value.toList()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            val before = transactionRepository.recategorizeAllCapturing(ids, categoryId)
            _selection.value = emptySet()
            _undo.value = UndoableAction(UndoKind.RECATEGORIZED) {
                transactionRepository.restoreCategories(before)
            }
        }
    }

    /**
     * Writes exactly what the ledger is showing to a CSV and hands back a
     * share URI.
     *
     * The Export screen can already produce a CSV, but only over fixed ranges —
     * this period, last three months, everything. None of those is the thing
     * you are usually looking at: you have filtered to one account, or to a
     * category, or searched a merchant, and THAT is the list you want out.
     * Re-creating a ledger filter inside the export screen is work the user has
     * already done once.
     *
     * When rows are selected, only those go — the selection is a narrower
     * statement of intent than the filters are.
     */
    fun exportVisible(onReady: (android.net.Uri) -> Unit) {
        viewModelScope.launch {
            val state = uiState.value
            val visible = state.flatRows +
                state.months.flatMap { month -> month.days.flatMap { it.rows } }
            val subject = state.selectedIds
                .takeIf { it.isNotEmpty() }
                ?.let { ids -> visible.filter { it.txn.id in ids } }
                ?: visible
            if (subject.isEmpty()) return@launch
            val uri = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                writeVisibleCsv(subject)
            }
            onReady(uri)
        }
    }

    private fun writeVisibleCsv(rows: List<LedgerRow>): android.net.Uri {
        val csv = dev.kosha.core.engine.export.CsvWriter.write(
            rows.map { row ->
                dev.kosha.core.engine.export.CsvWriter.Row(
                    date = CSV_DATE.format(localDate(row.txn.timestampMillis)),
                    merchant = row.txn.merchantRaw.orEmpty(),
                    category = row.categoryName.orEmpty(),
                    account = row.accountName,
                    type = row.txn.type.name.lowercase(),
                    amount = Money(row.txn.amountPaise),
                    note = row.txn.note.orEmpty(),
                    source = row.txn.source.name.lowercase(),
                    tags = listOfNotNull(
                        row.txn.moodTag?.name?.lowercase(),
                        row.txn.taxTag?.name?.lowercase()?.removePrefix("tax_"),
                    ).joinToString(" "),
                )
            },
            // No running balance: this list can be in any order the user chose,
            // and a running total down a list sorted by amount is nonsense.
            dev.kosha.core.engine.export.CsvWriter.Options(includeNotesAndTags = true),
        )
        val dir = java.io.File(appContext.cacheDir, "exports").apply { mkdirs() }
        val file = java.io.File(dir, "kosha-view-${CSV_STAMP.format(LocalDate.now(zone))}.csv")
        file.writeText(csv)
        return androidx.core.content.FileProvider.getUriForFile(
            appContext,
            "${appContext.packageName}.fileprovider",
            file,
        )
    }

    fun clearFilters() {
        _filters.value = LedgerFilters()
    }

    /** Drops a date window handed over by Home or a chart, keeping the rest. */
    fun clearDateRange() {
        _filters.value = _filters.value.copy(from = null, to = null)
    }

    val uiState: StateFlow<LedgerUiState> = combine(
        transactionRepository.observeLedger(),
        categoryRepository.observeAll(),
        transactionDao.observeReviewCount(),
        accountRepository.observeActive(),
        combine(_filters, _sort, _selection) { f, s, sel -> Triple(f, s, sel) },
    ) { rows, categories, reviewCount, accounts, (filters, sort, selection) ->
        val visible = rows.filter { row -> filters.matches(row) }.sortedWith(sort.comparator())
        // The same exclusions the savings gap and the charts use (spec G12),
        // and the same set the rows are dimmed by — one definition, so the
        // totals and the list can never disagree about what was left out.
        val excludedCategoryIds = excludedCategoryIdsOf(categories)
        LedgerUiState(
            months = if (sort.groupsByDate) group(visible, excludedCategoryIds) else emptyList(),
            flatRows = if (sort.groupsByDate) emptyList() else visible,
            flatTotal = if (sort.groupsByDate) Money.ZERO else net(visible, excludedCategoryIds),
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
            sort = sort,
            // "Nothing here" reads as a bug when the cause is a filter you
            // forgot you set, so the two empties say different things.
            hiddenByFilter = visible.isEmpty() && rows.isNotEmpty(),
            runningBalances = runningBalances(filters.accountId, accounts, rows),
            selectedIds = selection,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LedgerUiState())

    /**
     * Balance after each transaction on one account.
     *
     * Computed from ALL of that account's rows in date order, not from the
     * filtered subset: a balance is a running total of everything that
     * happened, so hiding half the rows must not change what the visible ones
     * say the balance was. Same basis as the stored balance — opening plus
     * committed parents — so the last row agrees with the account card.
     */
    private fun runningBalances(
        accountId: Long?,
        accounts: List<AccountEntity>,
        allRows: List<LedgerRow>,
    ): Map<Long, Money> {
        if (accountId == null) return emptyMap()
        val opening = accounts.firstOrNull { it.id == accountId }?.openingBalancePaise ?: return emptyMap()
        var balance = opening
        return allRows
            .filter { it.txn.accountId == accountId }
            .sortedBy { it.txn.timestampMillis }
            .associate { row ->
                balance += if (row.txn.type == TxnType.CREDIT) {
                    row.txn.amountPaise
                } else {
                    -row.txn.amountPaise
                }
                row.txn.id to Money(balance)
            }
    }

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
        if (text.isNotBlank()) {
            val needle = text.trim().lowercase()
            val haystack = listOfNotNull(
                row.txn.merchantRaw,
                row.categoryName,
                row.accountName,
                row.txn.note,
                row.txn.reference,
            ).joinToString(" ").lowercase()
            if (!haystack.contains(needle)) return false
        }
        if (from != null || to != null) {
            val date = localDate(row.txn.timestampMillis)
            if (from != null && date < from) return false
            if (to != null && date > to) return false
        }
        return true
    }

    /** Credits positive, debits negative, transfers not counted at all. */
    private fun net(rows: List<LedgerRow>, excluded: Set<Long>): Money = Money(
        rows.filter { it.txn.categoryId !in excluded }.sumOf { row ->
            if (row.txn.type == TxnType.DEBIT) -row.txn.amountPaise else row.txn.amountPaise
        },
    )

    private fun group(rows: List<LedgerRow>, excludedCategoryIds: Set<Long>): List<LedgerMonthGroup> {
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
                LedgerMonthGroup(
                    monthLabel = month.format(monthFormat),
                    total = net(allRows, excludedCategoryIds),
                    excludedTransfers = Money(
                        allRows.filter { it.txn.categoryId in excludedCategoryIds }
                            .sumOf { it.txn.amountPaise },
                    ),
                    days = dayEntries.map { (date, dayRows) ->
                        LedgerDayGroup(
                            date = date,
                            label = when (date) {
                                today -> "Today"
                                today.minusDays(1) -> "Yesterday"
                                else -> date.format(dayFormat)
                            },
                            total = net(dayRows, excludedCategoryIds),
                            rows = dayRows,
                        )
                    },
                )
            }
    }

    private fun LedgerSort.comparator(): Comparator<LedgerRow> = when (this) {
        LedgerSort.NEWEST -> compareByDescending { it.txn.timestampMillis }
        LedgerSort.OLDEST -> compareBy { it.txn.timestampMillis }
        LedgerSort.LARGEST -> compareByDescending { it.txn.amountPaise }
        LedgerSort.SMALLEST -> compareBy { it.txn.amountPaise }
        LedgerSort.NAME -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.txn.merchantRaw ?: "" }
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
                val before = transactionRepository.recategorizeMerchantCapturing(merchant, categoryId)
                _undo.value = UndoableAction(UndoKind.RECATEGORIZED) {
                    transactionRepository.restoreCategories(before)
                }
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
        viewModelScope.launch {
            val deleted = transactionRepository.deleteCapturing(txnId) ?: return@launch
            _undo.value = UndoableAction(UndoKind.DELETED) {
                transactionRepository.restore(deleted)
            }
        }
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

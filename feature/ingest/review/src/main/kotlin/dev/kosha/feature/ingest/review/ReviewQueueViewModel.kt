package dev.kosha.feature.ingest.review

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.kosha.core.common.Money
import dev.kosha.core.database.dao.LedgerRow
import dev.kosha.core.database.dao.TransactionDao
import dev.kosha.core.database.model.CategoryEntity
import dev.kosha.core.database.model.EvidenceKind
import dev.kosha.core.database.model.TxnType
import dev.kosha.core.database.repo.CategoryRepository
import dev.kosha.core.database.repo.TransactionRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * One reason several rows are waiting, with everything needed to clear them in
 * a single decision.
 */
data class ReviewGroup(
    val key: String,
    val title: String,
    val rows: List<LedgerRow>,
    /** Net of the group, so "approve all" is not a blind action. */
    val total: Money,
    val isDuplicateGroup: Boolean,
)

/** Queue ordering. Oldest first by default — the ones going stale. */
enum class ReviewSort { OLDEST, NEWEST, LARGEST }

data class ReviewUiState(
    val items: List<LedgerRow> = emptyList(),
    val sort: ReviewSort = ReviewSort.OLDEST,
    val groups: List<ReviewGroup> = emptyList(),
    val categories: List<CategoryEntity> = emptyList(),
    /** Transaction id → original message, when raw retention is on (B4). */
    val evidenceByTxnId: Map<Long, String> = emptyMap(),
) {
    val total: Int get() = items.size
}

@HiltViewModel
class ReviewQueueViewModel @Inject constructor(
    private val transactionDao: TransactionDao,
    private val transactionRepository: TransactionRepository,
    categoryRepository: CategoryRepository,
) : ViewModel() {

    private val _sort = MutableStateFlow(ReviewSort.OLDEST)

    fun setSort(sort: ReviewSort) {
        _sort.value = sort
    }

    /**
     * Apply the user's corrections to a queued row WITHOUT approving it.
     *
     * A row with a misread amount previously had to be approved first and
     * fixed afterwards — which puts a number you know is wrong into the ledger
     * and relies on you remembering to come back. Fixing before approving is
     * the order anyone would actually want.
     */
    fun saveEdit(txnId: Long, amountPaise: Long, merchantRaw: String?, categoryId: Long?) {
        viewModelScope.launch {
            val existing = transactionRepository.byId(txnId) ?: return@launch
            transactionRepository.update(
                existing.copy(
                    amountPaise = amountPaise,
                    merchantRaw = merchantRaw,
                    categoryId = categoryId ?: existing.categoryId,
                ),
            )
        }
    }

    val uiState: StateFlow<ReviewUiState> = combine(
        transactionDao.observeReviewQueue(),
        categoryRepository.observeAll(),
        _sort,
    ) { items, categories, sort ->
        // Show the original message when the user opted to keep it — a
        // low-confidence parse is only actionable if you can see the source.
        val evidence = items.associate { row ->
            row.txn.id to transactionDao.evidenceFor(row.txn.id)
                .firstOrNull { it.kind == EvidenceKind.SMS_TEXT }
                ?.payload
                .orEmpty()
        }.filterValues { it.isNotBlank() }

        val ordered = items.sortedWith(sort.comparator())
        ReviewUiState(
            items = ordered,
            sort = sort,
            groups = group(ordered),
            categories = categories.filter { !it.isSystem },
            evidenceByTxnId = evidence,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReviewUiState())

    /**
     * Rows waiting for the SAME reason are one decision, not many.
     *
     * A hundred-item queue reviewed one row at a time is a queue nobody
     * finishes, and everything in it is excluded from every total until it is
     * cleared — so an unread queue quietly makes the whole app wrong. Grouping
     * by why they are waiting turns "117 decisions" into a handful, without
     * hiding what is being approved: each group carries its count and total.
     *
     * Possible duplicates are deliberately never grouped for bulk approval —
     * merging or keeping is a per-row judgement about two specific rows.
     */
    private fun group(items: List<LedgerRow>): List<ReviewGroup> {
        val duplicates = items.filter { it.txn.possibleDuplicateOfId != null }
        val rest = items.filter { it.txn.possibleDuplicateOfId == null }

        val grouped = rest
            .groupBy { reasonKey(it.txn.reviewReason) }
            .map { (key, rows) ->
                ReviewGroup(
                    key = key,
                    title = key,
                    rows = rows,
                    total = Money(
                        rows.sumOf {
                            if (it.txn.type == TxnType.DEBIT) -it.txn.amountPaise else it.txn.amountPaise
                        },
                    ),
                    isDuplicateGroup = false,
                )
            }
            .sortedByDescending { it.rows.size }

        return if (duplicates.isEmpty()) {
            grouped
        } else {
            grouped + ReviewGroup(
                key = DUPLICATES_KEY,
                title = DUPLICATES_KEY,
                rows = duplicates,
                total = Money(duplicates.sumOf { -it.txn.amountPaise }),
                isDuplicateGroup = true,
            )
        }
    }

    private fun ReviewSort.comparator(): Comparator<LedgerRow> = when (this) {
        ReviewSort.OLDEST -> compareBy { it.txn.timestampMillis }
        ReviewSort.NEWEST -> compareByDescending { it.txn.timestampMillis }
        ReviewSort.LARGEST -> compareByDescending { it.txn.amountPaise }
    }

    /** Collapses per-row detail ("new-account-7788") into a shared reason. */
    private fun reasonKey(reason: String?): String = when {
        reason == null -> KEY_LOW_CONFIDENCE
        reason.startsWith("new-account-") -> KEY_NEW_ACCOUNT
        reason.startsWith("account-tail-") -> KEY_ACCOUNT_TAIL
        reason == "account-unknown" -> KEY_ACCOUNT_UNKNOWN
        else -> KEY_LOW_CONFIDENCE
    }

    /** Approve: commit as-is (optionally with a category picked in the sheet). */
    fun approve(txnId: Long, categoryId: Long? = null) {
        viewModelScope.launch {
            if (categoryId != null) transactionRepository.recategorize(txnId, categoryId)
            transactionDao.approveReview(txnId, System.currentTimeMillis())
            transactionRepository.byId(txnId)?.let { txn ->
                // Balance changes now that the row counts.
                transactionRepository.update(txn)
            }
        }
    }

    private val _undo = MutableStateFlow<ReviewUndo?>(null)
    val undo: StateFlow<ReviewUndo?> = _undo.asStateFlow()

    /** A bulk action worth a few seconds of grace — see KoshaUndoBar. */
    data class ReviewUndo(val approved: Boolean, val run: suspend () -> Unit)

    fun performUndo() {
        val action = _undo.value ?: return
        _undo.value = null
        viewModelScope.launch { action.run() }
    }

    fun dismissUndo() {
        _undo.value = null
    }

    /** Approve a whole group in one write, then fix the affected balances. */
    fun approveAll(group: ReviewGroup) {
        if (group.isDuplicateGroup) return
        viewModelScope.launch {
            val before = transactionRepository.approveAllCapturing(group.rows.map { it.txn.id })
            _undo.value = ReviewUndo(approved = true) {
                transactionRepository.restoreReviewStates(before)
            }
        }
    }

    /** "Same — merge": drop the pending row, keep the existing committed one. */
    fun mergeDuplicate(txnId: Long) {
        viewModelScope.launch { transactionRepository.delete(txnId) }
    }

    /** Discard: not a transaction at all. */
    fun discard(txnId: Long) {
        viewModelScope.launch { transactionRepository.delete(txnId) }
    }

    fun discardAll(group: ReviewGroup) {
        viewModelScope.launch {
            val deleted = transactionRepository.deleteAllCapturing(group.rows.map { it.txn.id })
                ?: return@launch
            _undo.value = ReviewUndo(approved = false) {
                transactionRepository.restore(deleted)
            }
        }
    }

    companion object {
        const val KEY_LOW_CONFIDENCE = "low-confidence"
        const val KEY_NEW_ACCOUNT = "new-account"
        const val KEY_ACCOUNT_TAIL = "account-tail"
        const val KEY_ACCOUNT_UNKNOWN = "account-unknown"
        const val DUPLICATES_KEY = "duplicates"
    }
}

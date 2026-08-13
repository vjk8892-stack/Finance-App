package dev.kosha.feature.ingest.review

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.kosha.core.database.dao.LedgerRow
import dev.kosha.core.database.dao.TransactionDao
import dev.kosha.core.database.model.CategoryEntity
import dev.kosha.core.database.repo.CategoryRepository
import dev.kosha.core.database.repo.TransactionRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ReviewUiState(
    val items: List<LedgerRow> = emptyList(),
    val categories: List<CategoryEntity> = emptyList(),
)

@HiltViewModel
class ReviewQueueViewModel @Inject constructor(
    private val transactionDao: TransactionDao,
    private val transactionRepository: TransactionRepository,
    categoryRepository: CategoryRepository,
) : ViewModel() {

    val uiState: StateFlow<ReviewUiState> = combine(
        transactionDao.observeReviewQueue(),
        categoryRepository.observeAll(),
    ) { items, categories ->
        ReviewUiState(items = items, categories = categories.filter { !it.isSystem })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReviewUiState())

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

    /** "Same — merge": drop the pending row, keep the existing committed one. */
    fun mergeDuplicate(txnId: Long) {
        viewModelScope.launch { transactionRepository.delete(txnId) }
    }

    /** Discard: not a transaction at all. */
    fun discard(txnId: Long) {
        viewModelScope.launch { transactionRepository.delete(txnId) }
    }
}

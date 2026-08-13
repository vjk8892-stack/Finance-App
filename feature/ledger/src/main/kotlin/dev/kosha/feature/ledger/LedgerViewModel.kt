package dev.kosha.feature.ledger

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.kosha.core.common.Money
import dev.kosha.core.database.dao.LedgerRow
import dev.kosha.core.database.model.CategoryEntity
import dev.kosha.core.database.model.TxnType
import dev.kosha.core.database.repo.CategoryRepository
import dev.kosha.core.database.repo.TransactionRepository
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

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
)

@HiltViewModel
class LedgerViewModel @Inject constructor(
    private val transactionRepository: TransactionRepository,
    categoryRepository: CategoryRepository,
) : ViewModel() {

    private val zone: ZoneId = ZoneId.systemDefault()
    private val dayFormat = DateTimeFormatter.ofPattern("EEE d MMM")
    private val monthFormat = DateTimeFormatter.ofPattern("MMMM yyyy")

    val uiState: StateFlow<LedgerUiState> = combine(
        transactionRepository.observeLedger(),
        categoryRepository.observeAll(),
    ) { rows, categories ->
        LedgerUiState(
            months = group(rows),
            categories = categories,
            isEmpty = rows.isEmpty(),
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
}

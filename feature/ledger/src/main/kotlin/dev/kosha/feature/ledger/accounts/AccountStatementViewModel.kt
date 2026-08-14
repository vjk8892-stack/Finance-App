package dev.kosha.feature.ledger.accounts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.kosha.core.common.Money
import dev.kosha.core.database.dao.AccountDao
import dev.kosha.core.database.dao.LedgerRow
import dev.kosha.core.database.model.AccountEntity
import dev.kosha.core.database.model.TxnStatus
import dev.kosha.core.database.model.TxnType
import dev.kosha.core.database.repo.TransactionRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Statement ordering — fewer options than the ledger, since scope is narrow. */
enum class StatementSort { NEWEST, OLDEST, LARGEST }

data class AccountStatementState(
    val account: AccountEntity? = null,
    val rows: List<LedgerRow> = emptyList(),
    val opening: Money = Money.ZERO,
    val credits: Money = Money.ZERO,
    /** Positive magnitude; the UI negates it for display. */
    val debits: Money = Money.ZERO,
    val sort: StatementSort = StatementSort.NEWEST,
)

@HiltViewModel
class AccountStatementViewModel @Inject constructor(
    private val accountDao: AccountDao,
    private val transactionRepository: TransactionRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(AccountStatementState())
    val state: StateFlow<AccountStatementState> = _state.asStateFlow()

    private var accountId: Long? = null

    fun load(id: Long) {
        if (accountId == id && _state.value.account != null) return
        accountId = id
        viewModelScope.launch {
            val account = accountDao.byId(id)
            // The ledger flow is already scoped to committed parents, which is
            // exactly the set the stored balance is computed from — so the
            // arithmetic shown here reconciles with the number on the card by
            // construction rather than by coincidence.
            transactionRepository.observeLedger().collect { all ->
                val mine = all.filter { it.txn.accountId == id && it.txn.status == TxnStatus.COMMITTED }
                _state.value = AccountStatementState(
                    account = account,
                    rows = mine.sortedWith(_state.value.sort.comparator()),
                    opening = Money(account?.openingBalancePaise ?: 0),
                    credits = Money(
                        mine.filter { it.txn.type == TxnType.CREDIT }.sumOf { it.txn.amountPaise },
                    ),
                    debits = Money(
                        mine.filter { it.txn.type == TxnType.DEBIT }.sumOf { it.txn.amountPaise },
                    ),
                    sort = _state.value.sort,
                )
            }
        }
    }

    fun setSort(sort: StatementSort) {
        _state.value = _state.value.copy(
            sort = sort,
            rows = _state.value.rows.sortedWith(sort.comparator()),
        )
    }

    private fun StatementSort.comparator(): Comparator<LedgerRow> = when (this) {
        StatementSort.NEWEST -> compareByDescending { it.txn.timestampMillis }
        StatementSort.OLDEST -> compareBy { it.txn.timestampMillis }
        StatementSort.LARGEST -> compareByDescending { it.txn.amountPaise }
    }
}

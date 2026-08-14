package dev.kosha.feature.ledger.accounts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.kosha.core.common.Money
import dev.kosha.core.database.model.AccountEntity
import dev.kosha.core.database.model.AccountType
import dev.kosha.core.database.dao.TransactionDao
import dev.kosha.core.database.repo.AccountRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class AccountsViewModel @Inject constructor(
    private val accountRepository: AccountRepository,
    private val transactionDao: TransactionDao,
) : ViewModel() {

    val accounts: StateFlow<List<AccountEntity>> = accountRepository.observeActive()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun create(name: String, type: AccountType, last4: String, openingRupees: String) {
        viewModelScope.launch {
            accountRepository.create(
                name = name,
                type = type,
                last4 = last4,
                openingBalancePaise = Money.parseOrNull(openingRupees)?.paise ?: 0L,
            )
        }
    }

    /**
     * Remove an account. Transactions hold a RESTRICT foreign key to it, so an
     * account with history cannot be deleted outright — it is deactivated,
     * which hides it everywhere while leaving its rows attributable. Only a
     * genuinely empty account is deleted, which is the common case for one
     * Kosha discovered from a mis-parsed tail.
     */
    fun remove(account: AccountEntity, onResult: (deleted: Boolean) -> Unit = {}) {
        viewModelScope.launch {
            val used = transactionDao.countForAccount(account.id) > 0
            if (used) accountRepository.deactivate(account.id) else accountRepository.delete(account)
            onResult(!used)
        }
    }

    /**
     * Edit the identifying details. The tail matters beyond cosmetics: SMS
     * attribution matches on it, so a wrong or missing one has to be
     * correctable — including on an account Kosha created itself from a
     * message tail it had never seen.
     */
    fun rename(account: AccountEntity, name: String, type: AccountType, last4: String) {
        viewModelScope.launch {
            accountRepository.update(
                account.copy(
                    name = name.trim().ifBlank { account.name },
                    type = type,
                    last4 = last4.takeIf { it.isNotBlank() },
                ),
            )
        }
    }
}

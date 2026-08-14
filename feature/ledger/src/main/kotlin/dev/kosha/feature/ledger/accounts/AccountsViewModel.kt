package dev.kosha.feature.ledger.accounts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.kosha.core.common.Money
import dev.kosha.core.database.model.AccountEntity
import dev.kosha.core.database.model.AccountType
import dev.kosha.core.database.repo.AccountRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class AccountsViewModel @Inject constructor(
    private val accountRepository: AccountRepository,
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

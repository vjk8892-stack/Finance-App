package dev.kosha.feature.budget.recurring

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.kosha.core.common.Money
import dev.kosha.core.database.model.AccountEntity
import dev.kosha.core.database.model.RecurringFrequency
import dev.kosha.core.database.model.RecurringRuleEntity
import dev.kosha.core.database.repo.AccountRepository
import dev.kosha.core.database.repo.RecurringRepository
import dev.kosha.core.engine.forecast.RecurringEngine
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class RecurringUiState(
    val rules: List<RecurringRuleEntity> = emptyList(),
    val accounts: List<AccountEntity> = emptyList(),
)

@HiltViewModel
class RecurringViewModel @Inject constructor(
    private val recurringRepository: RecurringRepository,
    accountRepository: AccountRepository,
) : ViewModel() {

    private val zone: ZoneId = ZoneId.systemDefault()

    val uiState: StateFlow<RecurringUiState> = combine(
        recurringRepository.observeRules(),
        accountRepository.observeActive(),
    ) { rules, accounts ->
        RecurringUiState(rules = rules, accounts = accounts)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RecurringUiState())

    fun add(
        label: String,
        amountRupees: String,
        accountId: Long,
        frequency: RecurringFrequency,
        autoLog: Boolean,
        isCardDue: Boolean,
    ) {
        viewModelScope.launch {
            // First occurrence: one interval out from today.
            val nextDue = RecurringEngine.nextDueDate(
                LocalDate.now(zone),
                when (frequency) {
                    RecurringFrequency.DAILY -> RecurringEngine.Frequency.DAILY
                    RecurringFrequency.WEEKLY -> RecurringEngine.Frequency.WEEKLY
                    RecurringFrequency.MONTHLY -> RecurringEngine.Frequency.MONTHLY
                    RecurringFrequency.QUARTERLY -> RecurringEngine.Frequency.QUARTERLY
                    RecurringFrequency.YEARLY -> RecurringEngine.Frequency.YEARLY
                },
            )
            recurringRepository.addRule(
                RecurringRuleEntity(
                    accountId = accountId,
                    amountPaise = Money.parseOrNull(amountRupees)?.paise,
                    merchantPattern = label.takeIf { it.isNotBlank() },
                    frequency = frequency,
                    nextDueDateMillis = nextDue.atStartOfDay(zone).toInstant().toEpochMilli(),
                    autoLog = autoLog,
                    isCreditCardDue = isCardDue,
                    label = label,
                ),
            )
        }
    }

    fun remove(id: Long) {
        viewModelScope.launch { recurringRepository.removeRule(id) }
    }
}

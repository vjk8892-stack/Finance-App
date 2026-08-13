package dev.kosha.feature.ledger.add

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.kosha.core.common.Money
import dev.kosha.core.database.model.AccountEntity
import dev.kosha.core.database.model.CategoryEntity
import dev.kosha.core.database.model.CategoryType
import dev.kosha.core.database.model.MoodTag
import dev.kosha.core.database.model.TransactionEntity
import dev.kosha.core.database.model.TxnSource
import dev.kosha.core.database.model.TxnType
import dev.kosha.core.database.repo.AccountRepository
import dev.kosha.core.database.repo.CategoryRepository
import dev.kosha.core.database.repo.TransactionRepository
import java.time.ZoneId
import java.time.ZonedDateTime
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class AddFormState(
    /** Rupee-string being typed on the keypad, e.g. "450.5". */
    val amountText: String = "",
    val entryType: TxnType = TxnType.DEBIT,
    val selectedAccountId: Long? = null,
    val note: String = "",
    val moodTag: MoodTag? = null,
    /** Pre-highlighted category when arriving from a Home quick-add chip. */
    val presetCategoryId: Long? = null,
) {
    val amount: Money? get() = Money.parseOrNull(amountText)
    val canSave: Boolean get() = amount != null && amount!!.paise > 0 && selectedAccountId != null
}

data class AddUiState(
    val form: AddFormState = AddFormState(),
    val accounts: List<AccountEntity> = emptyList(),
    val categories: List<CategoryEntity> = emptyList(),
)

@HiltViewModel
class AddViewModel @Inject constructor(
    private val transactionRepository: TransactionRepository,
    accountRepository: AccountRepository,
    categoryRepository: CategoryRepository,
) : ViewModel() {

    private val form = MutableStateFlow(AddFormState())

    /** Emits after a successful save — UI shows the calm "Logged" confirmation. */
    private val savedEvents = Channel<Money>(Channel.BUFFERED)
    val saved = savedEvents.receiveAsFlow()

    val uiState: StateFlow<AddUiState> = combine(
        form.asStateFlow(),
        accountRepository.observeActive(),
        categoryRepository.observeAll(),
    ) { formState, accounts, categories ->
        val withDefaultAccount = if (formState.selectedAccountId == null && accounts.isNotEmpty()) {
            formState.copy(selectedAccountId = accounts.first().id)
        } else {
            formState
        }
        AddUiState(
            form = withDefaultAccount,
            accounts = accounts,
            categories = categories.filter { cat ->
                !cat.isSystem && cat.type == if (withDefaultAccount.entryType == TxnType.DEBIT) {
                    CategoryType.EXPENSE
                } else {
                    CategoryType.INCOME
                }
            },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AddUiState())

    fun onDigit(d: Int) = editAmount { current ->
        val decimals = current.substringAfter('.', "")
        when {
            current.contains('.') && decimals.length >= 2 -> current
            current.substringBefore('.').length >= 7 && !current.contains('.') -> current
            current == "0" -> d.toString()
            else -> current + d
        }
    }

    fun onDecimal() = editAmount { if (it.contains('.')) it else if (it.isEmpty()) "0." else "$it." }

    fun onBackspace() = editAmount { it.dropLast(1) }

    private fun editAmount(edit: (String) -> String) {
        form.value = form.value.copy(amountText = edit(form.value.amountText))
    }

    fun setEntryType(type: TxnType) {
        form.value = form.value.copy(entryType = type)
    }

    fun selectAccount(id: Long) {
        form.value = form.value.copy(selectedAccountId = id)
    }

    fun setNote(note: String) {
        form.value = form.value.copy(note = note)
    }

    fun setMood(mood: MoodTag?) {
        form.value = form.value.copy(moodTag = mood)
    }

    fun presetCategory(categoryId: Long?) {
        form.value = form.value.copy(presetCategoryId = categoryId)
    }

    /** The ≤3-tap path: amount → category tap saves immediately (spec C4). */
    fun saveWithCategory(category: CategoryEntity) {
        val state = uiState.value
        val f = state.form
        val amount = f.amount ?: return
        val accountId = f.selectedAccountId ?: return
        viewModelScope.launch {
            val now = ZonedDateTime.now(ZoneId.systemDefault())
            transactionRepository.add(
                TransactionEntity(
                    accountId = accountId,
                    categoryId = category.id,
                    amountPaise = amount.paise,
                    type = f.entryType,
                    note = f.note.takeIf { it.isNotBlank() },
                    timestampMillis = now.toInstant().toEpochMilli(),
                    tzOffsetMinutes = now.offset.totalSeconds / 60,
                    source = TxnSource.MANUAL,
                    confidence = 1.0,
                    moodTag = f.moodTag,
                    createdAtMillis = System.currentTimeMillis(),
                    updatedAtMillis = System.currentTimeMillis(),
                ),
            )
            savedEvents.trySend(amount)
            form.value = AddFormState(
                entryType = f.entryType,
                selectedAccountId = accountId,
                presetCategoryId = f.presetCategoryId,
            )
        }
    }
}

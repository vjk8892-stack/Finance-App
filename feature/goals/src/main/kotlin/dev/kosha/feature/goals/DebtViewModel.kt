package dev.kosha.feature.goals

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.kosha.core.common.Money
import dev.kosha.core.database.dao.GoalsDao
import dev.kosha.core.database.model.DebtAccountEntity
import dev.kosha.core.engine.debt.DebtPlanner
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class DebtUiState(
    val debts: List<DebtAccountEntity> = emptyList(),
    val comparison: DebtPlanner.Comparison? = null,
)

/**
 * Split out of `GoalsViewModel` (design review: debt planning is substantial
 * enough — an avalanche/snowball simulator — to be its own destination
 * rather than a section inside a four-tool scroll).
 */
@HiltViewModel
class DebtViewModel @Inject constructor(
    private val goalsDao: GoalsDao,
) : ViewModel() {

    val uiState: StateFlow<DebtUiState> = goalsDao.observeDebts().map { debts ->
        val comparison = debts.takeIf { it.isNotEmpty() }?.let { list ->
            DebtPlanner.compare(
                list.map {
                    DebtPlanner.Debt(
                        id = it.id,
                        name = it.name,
                        principal = Money(it.principalPaise),
                        rateBps = it.rateBps,
                        minimumPayment = Money(it.emiAmountPaise),
                    )
                },
            )
        }
        DebtUiState(debts = debts, comparison = comparison)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DebtUiState())

    fun addDebt(
        name: String,
        principalRupees: String,
        ratePercent: String,
        emiRupees: String,
        tenureMonths: String,
    ) {
        val principal = Money.parseOrNull(principalRupees) ?: return
        val emi = Money.parseOrNull(emiRupees) ?: return
        val rateBps = ((ratePercent.toDoubleOrNull() ?: 0.0) * 100).toInt()
        viewModelScope.launch {
            goalsDao.insertDebt(
                DebtAccountEntity(
                    name = name,
                    principalPaise = principal.paise,
                    rateBps = rateBps,
                    emiAmountPaise = emi.paise,
                    tenureMonths = tenureMonths.toIntOrNull() ?: 0,
                    startDateMillis = System.currentTimeMillis(),
                ),
            )
        }
    }

    fun deleteDebt(debt: DebtAccountEntity) {
        viewModelScope.launch { goalsDao.deleteDebt(debt) }
    }
}

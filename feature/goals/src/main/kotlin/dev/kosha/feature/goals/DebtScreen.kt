package dev.kosha.feature.goals

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import dev.kosha.core.common.Money
import dev.kosha.core.database.model.DebtAccountEntity
import dev.kosha.core.designsystem.component.AmountText
import dev.kosha.core.designsystem.component.KoshaCard
import dev.kosha.core.designsystem.token.KoshaColors
import dev.kosha.core.designsystem.token.KoshaSpacing
import dev.kosha.core.designsystem.token.KoshaType

/**
 * Its own destination (design review: an avalanche/snowball simulator is
 * substantial enough to earn a screen, not a section buried in Goals).
 */
@Composable
fun DebtScreen(
    onBack: () -> Unit,
    viewModel: DebtViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    var showEditor by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = KoshaSpacing.xs, vertical = KoshaSpacing.s),
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = null, tint = KoshaColors.OffWhiteMuted)
            }
            Text(
                text = stringResource(R.string.debt_section),
                style = KoshaType.Title,
                color = KoshaColors.OffWhite,
                modifier = Modifier.weight(1f),
            )
        }

        LazyColumn(
            contentPadding = PaddingValues(KoshaSpacing.screenPadding),
            verticalArrangement = Arrangement.spacedBy(KoshaSpacing.s),
        ) {
            if (state.debts.isEmpty()) {
                item { EmptyNote(stringResource(R.string.debt_empty)) }
            } else {
                items(state.debts.size) { i ->
                    DebtCard(state.debts[i]) { viewModel.deleteDebt(state.debts[i]) }
                }
                item { state.comparison?.let { DebtComparisonCard(it) } }
            }
            item {
                Spacer(Modifier.height(KoshaSpacing.s))
                TextButton(onClick = { showEditor = true }) {
                    Text(stringResource(R.string.debt_add), color = KoshaColors.AccentTealBright)
                }
                Spacer(Modifier.height(KoshaSpacing.xxl))
            }
        }
    }

    if (showEditor) {
        DebtEditorSheet(
            onSave = { name, principal, rate, emi, tenure ->
                viewModel.addDebt(name, principal, rate, emi, tenure)
                showEditor = false
            },
            onDismiss = { showEditor = false },
        )
    }
}

@Composable
internal fun DebtCard(debt: DebtAccountEntity, onDelete: () -> Unit) {
    KoshaCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(debt.name, style = KoshaType.Body, color = KoshaColors.OffWhite)
                Text(
                    text = "${debt.rateBps / 100.0}% · EMI ${Money(debt.emiAmountPaise).format(withPaise = false)}",
                    style = KoshaType.Caption,
                    color = KoshaColors.OffWhiteFaint,
                )
            }
            AmountText(amount = Money(debt.principalPaise), style = KoshaType.AmountBody, withPaise = false)
            TextButton(onClick = onDelete) {
                Text("×", style = KoshaType.Title, color = KoshaColors.OffWhiteFaint)
            }
        }
    }
}

@Composable
internal fun DebtComparisonCard(comparison: dev.kosha.core.engine.debt.DebtPlanner.Comparison) {
    KoshaCard(modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth()) {
            StrategyColumn(
                title = stringResource(R.string.debt_avalanche),
                months = comparison.avalanche.monthsToDebtFree,
                interest = comparison.avalanche.totalInterest,
                modifier = Modifier.weight(1f),
            )
            StrategyColumn(
                title = stringResource(R.string.debt_snowball),
                months = comparison.snowball.monthsToDebtFree,
                interest = comparison.snowball.totalInterest,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(KoshaSpacing.xs))
        Text(
            text = if (comparison.interestSaved.paise > 0 || comparison.monthsSaved > 0) {
                stringResource(
                    R.string.debt_avalanche_saves,
                    comparison.interestSaved.format(withPaise = false),
                    comparison.monthsSaved,
                )
            } else {
                stringResource(R.string.debt_same_either_way)
            },
            style = KoshaType.InsightSerif,
            color = KoshaColors.OffWhite,
        )
    }
}

@Composable
private fun StrategyColumn(title: String, months: Int, interest: Money, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(title, style = KoshaType.Label, color = KoshaColors.OffWhiteFaint)
        Text(
            text = stringResource(R.string.debt_payoff_months, months),
            style = KoshaType.Body,
            color = KoshaColors.OffWhite,
        )
        Text(
            text = stringResource(R.string.debt_total_interest, interest.format(withPaise = false)),
            style = KoshaType.Caption,
            color = KoshaColors.OffWhiteMuted,
        )
    }
}

@Composable
private fun DebtEditorSheet(
    onSave: (name: String, principal: String, rate: String, emi: String, tenure: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var principal by remember { mutableStateOf("") }
    var rate by remember { mutableStateOf("") }
    var emi by remember { mutableStateOf("") }
    var tenure by remember { mutableStateOf("") }

    EditorSheet(onDismiss) {
        Text(stringResource(R.string.debt_add), style = KoshaType.Title, color = KoshaColors.OffWhite)
        GoalField(name, { name = it }, stringResource(R.string.debt_name))
        GoalField(principal, { if (it.all { c -> c.isDigit() || c == '.' }) principal = it }, stringResource(R.string.debt_principal))
        GoalField(rate, { if (it.all { c -> c.isDigit() || c == '.' }) rate = it }, stringResource(R.string.debt_rate))
        GoalField(emi, { if (it.all { c -> c.isDigit() || c == '.' }) emi = it }, stringResource(R.string.debt_emi))
        GoalField(tenure, { if (it.all(Char::isDigit)) tenure = it }, stringResource(R.string.debt_tenure))
        TextButton(
            onClick = { onSave(name.trim(), principal, rate, emi, tenure) },
            enabled = name.isNotBlank() && principal.isNotBlank() && emi.isNotBlank(),
        ) {
            Text(stringResource(R.string.goals_save), color = KoshaColors.AccentTeal)
        }
    }
}

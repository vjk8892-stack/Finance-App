package dev.kosha.feature.goals

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.kosha.core.common.Money
import dev.kosha.core.database.model.AssetLiabilityKind
import dev.kosha.core.database.model.DebtAccountEntity
import dev.kosha.core.database.model.FinancialGoalEntity
import dev.kosha.core.database.model.GoalKind
import dev.kosha.core.designsystem.component.AmountText
import dev.kosha.core.designsystem.component.KoshaCard
import dev.kosha.core.designsystem.component.KoshaChip
import dev.kosha.core.designsystem.token.KoshaColors
import dev.kosha.core.designsystem.token.KoshaSpacing
import dev.kosha.core.designsystem.token.KoshaType

@Composable
fun GoalsScreen(
    onBack: () -> Unit,
    viewModel: GoalsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    var editor by remember { mutableStateOf<Editor?>(null) }

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
                text = stringResource(R.string.goals_title),
                style = KoshaType.Title,
                color = KoshaColors.OffWhite,
            )
        }

        LazyColumn(
            contentPadding = PaddingValues(KoshaSpacing.screenPadding),
            verticalArrangement = Arrangement.spacedBy(KoshaSpacing.s),
        ) {
            item {
                SectionHeader(stringResource(R.string.goals_section)) {
                    editor = Editor.Goal
                }
            }
            if (state.goals.isEmpty()) {
                item { EmptyNote(stringResource(R.string.goals_empty)) }
            } else {
                // Emergency fund pinned first (spec C7).
                val ordered = state.goals.sortedBy { if (it.kind == GoalKind.EMERGENCY_FUND) 0 else 1 }
                items(ordered.size) { i ->
                    GoalJar(
                        goal = ordered[i],
                        averageMonthlyExpense = state.averageMonthlyExpense,
                        onDelete = { viewModel.deleteGoal(ordered[i]) },
                    )
                }
            }

            item {
                Spacer(Modifier.height(KoshaSpacing.m))
                SectionHeader(stringResource(R.string.debt_section)) { editor = Editor.Debt }
            }
            if (state.debts.isEmpty()) {
                item { EmptyNote(stringResource(R.string.debt_empty)) }
            } else {
                items(state.debts.size) { i ->
                    DebtCard(state.debts[i]) { viewModel.deleteDebt(state.debts[i]) }
                }
                item { state.debtComparison?.let { DebtComparisonCard(it) } }
            }

            item {
                Spacer(Modifier.height(KoshaSpacing.m))
                NetWorthCard(state, onAddAsset = { editor = Editor.Asset }, onAddLiability = { editor = Editor.Liability })
            }

            item {
                Spacer(Modifier.height(KoshaSpacing.m))
                TaxCard(state)
                Spacer(Modifier.height(KoshaSpacing.xxl))
            }
        }
    }

    when (editor) {
        Editor.Goal -> GoalEditorSheet(
            onSave = { name, target, allocated, isEmergency ->
                viewModel.addGoal(name, target, allocated, isEmergency)
                editor = null
            },
            onDismiss = { editor = null },
        )
        Editor.Debt -> DebtEditorSheet(
            onSave = { name, principal, rate, emi, tenure ->
                viewModel.addDebt(name, principal, rate, emi, tenure)
                editor = null
            },
            onDismiss = { editor = null },
        )
        Editor.Asset, Editor.Liability -> AssetEditorSheet(
            isLiability = editor == Editor.Liability,
            onSave = { name, value ->
                viewModel.addAssetLiability(name, value, editor == Editor.Liability)
                editor = null
            },
            onDismiss = { editor = null },
        )
        null -> Unit
    }
}

private enum class Editor { Goal, Debt, Asset, Liability }

@Composable
private fun SectionHeader(title: String, onAdd: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = KoshaType.Label,
            color = KoshaColors.OffWhiteFaint,
            modifier = Modifier.weight(1f),
        )
        KoshaChip(label = "+", onClick = onAdd)
    }
}

@Composable
private fun EmptyNote(text: String) {
    Text(text, style = KoshaType.InsightSerif, color = KoshaColors.OffWhiteMuted)
}

/** Sinking-fund jar that visibly fills (spec C7). */
@Composable
private fun GoalJar(
    goal: FinancialGoalEntity,
    averageMonthlyExpense: Money,
    onDelete: () -> Unit,
) {
    val fraction = if (goal.targetAmountPaise <= 0) {
        0f
    } else {
        (goal.allocatedPaise.toFloat() / goal.targetAmountPaise).coerceIn(0f, 1f)
    }
    KoshaCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Jar(fraction)
            Spacer(Modifier.width(KoshaSpacing.s))
            Column(Modifier.weight(1f)) {
                Text(goal.name, style = KoshaType.Body, color = KoshaColors.OffWhite)
                Text(
                    text = stringResource(
                        R.string.goals_progress,
                        Money(goal.allocatedPaise).format(withPaise = false),
                        Money(goal.targetAmountPaise).format(withPaise = false),
                    ),
                    style = KoshaType.Caption,
                    color = KoshaColors.OffWhiteFaint,
                )
                if (goal.kind == GoalKind.EMERGENCY_FUND && averageMonthlyExpense.paise > 0) {
                    val months = (goal.allocatedPaise / averageMonthlyExpense.paise).toInt()
                    Text(
                        text = stringResource(R.string.goals_months_covered, months),
                        style = KoshaType.Caption,
                        color = KoshaColors.AccentTeal,
                    )
                }
            }
            TextButton(onClick = onDelete) {
                Text("×", style = KoshaType.Title, color = KoshaColors.OffWhiteFaint)
            }
        }
    }
}

@Composable
private fun Jar(fraction: Float) {
    Canvas(Modifier.size(width = 34.dp, height = 46.dp)) {
        val corner = 6.dp.toPx()
        drawRoundRect(
            color = KoshaColors.Outline,
            size = size,
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(corner, corner),
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2f),
        )
        val fillHeight = size.height * fraction
        if (fillHeight > 0f) {
            drawRoundRect(
                brush = Brush.verticalGradient(
                    listOf(KoshaColors.AccentViolet, KoshaColors.AccentTeal),
                ),
                topLeft = Offset(3f, size.height - fillHeight + 3f),
                size = Size(size.width - 6f, (fillHeight - 6f).coerceAtLeast(0f)),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(corner, corner),
            )
        }
    }
}

@Composable
private fun DebtCard(debt: DebtAccountEntity, onDelete: () -> Unit) {
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
private fun DebtComparisonCard(comparison: dev.kosha.core.engine.debt.DebtPlanner.Comparison) {
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
private fun NetWorthCard(
    state: GoalsUiState,
    onAddAsset: () -> Unit,
    onAddLiability: () -> Unit,
) {
    val netWorth = state.netWorth ?: return
    KoshaCard(modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.networth_section), style = KoshaType.Title, color = KoshaColors.OffWhite)
        Spacer(Modifier.height(KoshaSpacing.xs))
        Row(Modifier.fillMaxWidth()) {
            LabeledAmount(stringResource(R.string.networth_assets), netWorth.assets, KoshaColors.AccentTeal)
            Spacer(Modifier.width(KoshaSpacing.m))
            LabeledAmount(stringResource(R.string.networth_liabilities), netWorth.liabilities, KoshaColors.OffWhiteMuted)
        }
        Spacer(Modifier.height(KoshaSpacing.xs))
        Text(stringResource(R.string.networth_net), style = KoshaType.Label, color = KoshaColors.OffWhiteFaint)
        AmountText(
            amount = netWorth.net,
            style = KoshaType.AmountLarge,
            color = if (netWorth.net.isNegative) KoshaColors.Amber else KoshaColors.OffWhite,
            withPaise = false,
            countUp = true,
        )
        Spacer(Modifier.height(KoshaSpacing.xs))
        Text(
            text = stringResource(R.string.networth_loan_hint),
            style = KoshaType.Caption,
            color = KoshaColors.OffWhiteFaint,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(KoshaSpacing.xs)) {
            KoshaChip(label = stringResource(R.string.networth_add_asset), onClick = onAddAsset)
            KoshaChip(label = stringResource(R.string.networth_add_liability), onClick = onAddLiability)
        }
    }
}

@Composable
private fun TaxCard(state: GoalsUiState) {
    KoshaCard(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.tax_section, state.financialYearLabel),
            style = KoshaType.Title,
            color = KoshaColors.OffWhite,
        )
        Spacer(Modifier.height(KoshaSpacing.xs))
        if (state.taxTotals.isEmpty()) {
            Text(
                text = stringResource(R.string.tax_empty),
                style = KoshaType.Body,
                color = KoshaColors.OffWhiteMuted,
            )
        } else {
            state.taxTotals.forEach { (tag, amount) ->
                Row(Modifier.fillMaxWidth()) {
                    Text(
                        text = tag.name.removePrefix("TAX_"),
                        style = KoshaType.Body,
                        color = KoshaColors.OffWhiteMuted,
                        modifier = Modifier.weight(1f),
                    )
                    AmountText(amount = amount, style = KoshaType.AmountBody, withPaise = false)
                }
            }
        }
    }
}

@Composable
private fun LabeledAmount(label: String, amount: Money, color: androidx.compose.ui.graphics.Color) {
    Column {
        Text(label, style = KoshaType.Caption, color = KoshaColors.OffWhiteFaint)
        AmountText(amount = amount, style = KoshaType.AmountBody, color = color, withPaise = false)
    }
}

@Composable
private fun GoalEditorSheet(
    onSave: (name: String, target: String, allocated: String, isEmergency: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var target by remember { mutableStateOf("") }
    var allocated by remember { mutableStateOf("") }
    var isEmergency by remember { mutableStateOf(false) }

    EditorSheet(onDismiss) {
        Text(stringResource(R.string.goals_add), style = KoshaType.Title, color = KoshaColors.OffWhite)
        GoalField(name, { name = it }, stringResource(R.string.goals_name))
        GoalField(target, { if (it.all { c -> c.isDigit() || c == '.' }) target = it }, stringResource(R.string.goals_target))
        GoalField(allocated, { if (it.all { c -> c.isDigit() || c == '.' }) allocated = it }, stringResource(R.string.goals_allocated))
        Row(horizontalArrangement = Arrangement.spacedBy(KoshaSpacing.xs)) {
            KoshaChip(
                label = stringResource(R.string.goals_emergency),
                selected = isEmergency,
                onClick = { isEmergency = true },
            )
            KoshaChip(
                label = stringResource(R.string.goals_sinking),
                selected = !isEmergency,
                onClick = { isEmergency = false },
            )
        }
        TextButton(
            onClick = { onSave(name.trim(), target, allocated, isEmergency) },
            enabled = name.isNotBlank() && target.isNotBlank(),
        ) {
            Text(stringResource(R.string.goals_save), color = KoshaColors.AccentTeal)
        }
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

@Composable
private fun AssetEditorSheet(
    isLiability: Boolean,
    onSave: (name: String, value: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var value by remember { mutableStateOf("") }

    EditorSheet(onDismiss) {
        Text(
            text = stringResource(
                if (isLiability) R.string.networth_add_liability else R.string.networth_add_asset,
            ),
            style = KoshaType.Title,
            color = KoshaColors.OffWhite,
        )
        if (isLiability) {
            Text(
                text = stringResource(R.string.networth_loan_hint),
                style = KoshaType.Caption,
                color = KoshaColors.Amber,
            )
        }
        GoalField(name, { name = it }, stringResource(R.string.networth_item_name))
        GoalField(value, { if (it.all { c -> c.isDigit() || c == '.' }) value = it }, stringResource(R.string.networth_value))
        TextButton(
            onClick = { onSave(name.trim(), value) },
            enabled = name.isNotBlank() && value.isNotBlank(),
        ) {
            Text(stringResource(R.string.goals_save), color = KoshaColors.AccentTeal)
        }
    }
}

@Composable
private fun EditorSheet(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = KoshaColors.CharcoalOverlay) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(KoshaSpacing.m),
            verticalArrangement = Arrangement.spacedBy(KoshaSpacing.s),
        ) {
            content()
            Spacer(Modifier.height(KoshaSpacing.l))
        }
    }
}

@Composable
private fun GoalField(value: String, onValueChange: (String) -> Unit, placeholder: String) {
    TextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = { Text(placeholder, color = KoshaColors.OffWhiteFaint) },
        colors = TextFieldDefaults.colors(
            focusedContainerColor = KoshaColors.CharcoalRaised,
            unfocusedContainerColor = KoshaColors.CharcoalRaised,
            focusedTextColor = KoshaColors.OffWhite,
            unfocusedTextColor = KoshaColors.OffWhite,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

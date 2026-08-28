package dev.kosha.feature.goals

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.kosha.core.common.Money
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
    onOpenDebt: () -> Unit,
    onOpenNetWorth: () -> Unit,
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
                NavigationCard(
                    title = stringResource(R.string.debt_section),
                    detail = if (state.debtSummary.count == 0) {
                        stringResource(R.string.debt_empty)
                    } else {
                        stringResource(
                            R.string.goals_debt_summary,
                            state.debtSummary.count,
                            state.debtSummary.totalOwed.format(withPaise = false),
                        )
                    },
                    onClick = onOpenDebt,
                )
            }

            item {
                NavigationCard(
                    title = stringResource(R.string.networth_section),
                    detail = state.netWorth?.let {
                        stringResource(R.string.goals_networth_summary, it.net.format(withPaise = false))
                    } ?: "",
                    onClick = onOpenNetWorth,
                )
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
        null -> Unit
    }
}

private enum class Editor { Goal }

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

/** A tappable summary of a screen that used to be a section here (design review). */
@Composable
private fun NavigationCard(title: String, detail: String, onClick: () -> Unit) {
    KoshaCard(modifier = Modifier.fillMaxWidth(), onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text(title, style = KoshaType.Body, color = KoshaColors.OffWhite)
                Text(detail, style = KoshaType.Caption, color = KoshaColors.OffWhiteFaint)
            }
            Icon(
                Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = null,
                tint = KoshaColors.OffWhiteFaint,
            )
        }
    }
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

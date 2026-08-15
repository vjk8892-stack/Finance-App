package dev.kosha.feature.budget

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.kosha.core.database.model.CategoryEntity
import dev.kosha.core.designsystem.component.AmountText
import dev.kosha.core.designsystem.component.KoshaCard
import dev.kosha.core.designsystem.component.KoshaChip
import dev.kosha.core.designsystem.component.KoshaIcons
import dev.kosha.core.designsystem.component.KoshaRing
import dev.kosha.core.designsystem.token.KoshaColors
import dev.kosha.core.designsystem.token.KoshaSpacing
import dev.kosha.core.designsystem.token.KoshaType
import kotlin.math.roundToInt

@Composable
fun BudgetScreen(
    onBack: () -> Unit,
    viewModel: BudgetViewModel = hiltViewModel(),
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
                text = stringResource(R.string.budget_title),
                style = KoshaType.Title,
                color = KoshaColors.OffWhite,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { showEditor = true }) {
                Icon(
                    Icons.Outlined.Add,
                    contentDescription = stringResource(R.string.budget_add),
                    tint = KoshaColors.OffWhite,
                )
            }
        }

        if (state.rows.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(R.string.budget_empty),
                    style = KoshaType.InsightSerif,
                    color = KoshaColors.OffWhiteMuted,
                    modifier = Modifier.padding(KoshaSpacing.xl),
                )
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(KoshaSpacing.screenPadding),
                verticalArrangement = Arrangement.spacedBy(KoshaSpacing.s),
            ) {
                items(state.rows.size) { i ->
                    val row = state.rows[i]
                    BudgetCard(row) { viewModel.removeBudget(row.progress.budgetId) }
                }
            }
        }
    }

    if (showEditor) {
        BudgetEditorSheet(
            categories = state.categories,
            onSave = { categoryId, limit, threshold ->
                viewModel.addBudget(categoryId, limit, threshold)
                showEditor = false
            },
            onDismiss = { showEditor = false },
        )
    }
}

@Composable
private fun BudgetCard(row: BudgetRow, onRemove: () -> Unit) {
    // Amber at threshold — the only non-monochrome state (spec A2: no red).
    val ringColor = if (row.progress.isAtThreshold) KoshaColors.Amber else KoshaColors.OffWhiteMuted
    KoshaCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            KoshaRing(
                progress = row.progress.fraction,
                size = 44.dp,
                color = ringColor,
            )
            Spacer(Modifier.width(KoshaSpacing.s))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (row.categoryIcon != null) {
                        Icon(
                            KoshaIcons.forToken(row.categoryIcon),
                            contentDescription = null,
                            tint = KoshaColors.OffWhiteMuted,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(KoshaSpacing.xxs))
                    }
                    Text(
                        text = row.categoryName.ifEmpty { stringResource(R.string.budget_overall) },
                        style = KoshaType.Body,
                        color = KoshaColors.OffWhite,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AmountText(
                        amount = row.progress.spent,
                        style = KoshaType.AmountSmall,
                        color = ringColor,
                        withPaise = false,
                    )
                    Text(" / ", style = KoshaType.Caption, color = KoshaColors.OffWhiteFaint)
                    AmountText(
                        amount = row.progress.limit,
                        style = KoshaType.AmountSmall,
                        color = KoshaColors.OffWhiteMuted,
                        withPaise = false,
                    )
                }
            }
            TextButton(onClick = onRemove) {
                Text(stringResource(R.string.budget_remove), color = KoshaColors.OffWhiteFaint, style = KoshaType.Caption)
            }
        }
    }
}

@Composable
private fun BudgetEditorSheet(
    categories: List<CategoryEntity>,
    onSave: (categoryId: Long?, limitRupees: String, thresholdPct: Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var categoryId by remember { mutableStateOf<Long?>(null) }
    var limit by remember { mutableStateOf("") }
    var threshold by remember { mutableFloatStateOf(80f) }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = KoshaColors.CharcoalOverlay) {
        Column(
            Modifier
                .fillMaxWidth()
                // The keyboard opens the moment the amount field takes focus
                // and covered the field and the Save button both, with no way
                // to scroll to either. imePadding lifts the sheet clear; the
                // scroll handles a short screen with the keyboard up.
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(KoshaSpacing.m),
            verticalArrangement = Arrangement.spacedBy(KoshaSpacing.s),
        ) {
            // Amount and Save at the TOP, above the category grid. The two
            // things you must reach are now the two nearest the top of the
            // sheet, so the keyboard can cover the categories — which are
            // scrollable and optional — instead of the controls.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = stringResource(R.string.budget_add),
                    style = KoshaType.SectionHeader,
                    color = KoshaColors.OffWhite,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = { onSave(categoryId, limit, threshold.roundToInt()) },
                    enabled = limit.isNotBlank(),
                ) {
                    Text(
                        text = stringResource(R.string.budget_save),
                        style = KoshaType.LabelStrong,
                        color = if (limit.isNotBlank()) {
                            KoshaColors.AccentTealBright
                        } else {
                            KoshaColors.OffWhiteFaint
                        },
                    )
                }
            }

            TextField(
                value = limit,
                onValueChange = { text -> if (text.all { it.isDigit() || it == '.' }) limit = text },
                placeholder = { Text(stringResource(R.string.budget_limit), color = KoshaColors.OffWhiteFaint) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = KoshaColors.CharcoalRaised,
                    unfocusedContainerColor = KoshaColors.CharcoalRaised,
                    focusedTextColor = KoshaColors.OffWhite,
                    unfocusedTextColor = KoshaColors.OffWhite,
                ),
                modifier = Modifier.fillMaxWidth(),
            )

            Text(
                text = stringResource(R.string.budget_threshold, threshold.roundToInt()),
                style = KoshaType.Label,
                color = KoshaColors.OffWhiteMuted,
            )
            Slider(
                value = threshold,
                onValueChange = { threshold = it },
                valueRange = 50f..100f,
                steps = 9,
                colors = SliderDefaults.colors(
                    thumbColor = KoshaColors.AccentTeal,
                    activeTrackColor = KoshaColors.AccentTeal,
                    inactiveTrackColor = KoshaColors.Outline,
                ),
            )

            Text(
                text = stringResource(R.string.budget_applies_to),
                style = KoshaType.Label,
                color = KoshaColors.OffWhiteFaint,
            )
            KoshaChip(
                label = stringResource(R.string.budget_overall),
                selected = categoryId == null,
                onClick = { categoryId = null },
            )
            categories.chunked(2).forEach { rowCats ->
                Row(horizontalArrangement = Arrangement.spacedBy(KoshaSpacing.xs)) {
                    rowCats.forEach { cat ->
                        KoshaChip(
                            label = cat.name,
                            selected = categoryId == cat.id,
                            onClick = { categoryId = cat.id },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (rowCats.size == 1) Spacer(Modifier.weight(1f))
                }
            }

            Spacer(Modifier.height(KoshaSpacing.l))
        }
    }
}

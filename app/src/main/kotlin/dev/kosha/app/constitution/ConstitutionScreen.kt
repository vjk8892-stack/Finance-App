package dev.kosha.app.constitution

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
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import dev.kosha.app.R
import dev.kosha.core.database.repo.ConstitutionRepository
import dev.kosha.core.designsystem.component.KoshaCard
import dev.kosha.core.designsystem.component.KoshaChip
import dev.kosha.core.designsystem.token.KoshaColors
import dev.kosha.core.designsystem.token.KoshaSpacing
import dev.kosha.core.designsystem.token.KoshaType
import dev.kosha.core.engine.constitution.ConstitutionEngine

/**
 * The rule engine and its tables existed with a full test suite and zero
 * screen — a design review finding. This is that screen: write a rule for
 * yourself, or set a category limit Kosha checks automatically each time
 * you open this screen.
 */
@Composable
fun ConstitutionScreen(
    onBack: () -> Unit,
    viewModel: ConstitutionViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    var showAdd by remember { mutableStateOf(false) }

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
            Text(stringResource(R.string.constitution_title), style = KoshaType.Title, color = KoshaColors.OffWhite)
        }

        LazyColumn(
            contentPadding = PaddingValues(KoshaSpacing.screenPadding),
            verticalArrangement = Arrangement.spacedBy(KoshaSpacing.s),
        ) {
            item {
                Text(
                    text = stringResource(
                        when (state.trend) {
                            ConstitutionEngine.Trend.IMPROVING -> R.string.constitution_trend_improving
                            ConstitutionEngine.Trend.SLIPPING -> R.string.constitution_trend_slipping
                            ConstitutionEngine.Trend.STEADY -> R.string.constitution_trend_steady
                            ConstitutionEngine.Trend.NOT_ENOUGH_DATA -> R.string.constitution_trend_none
                        },
                    ),
                    style = KoshaType.InsightSerif,
                    color = if (state.trend == ConstitutionEngine.Trend.SLIPPING) {
                        KoshaColors.Amber
                    } else {
                        KoshaColors.OffWhiteMuted
                    },
                )
                Spacer(Modifier.height(KoshaSpacing.s))
            }

            if (state.statuses.isEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.constitution_empty),
                        style = KoshaType.Body,
                        color = KoshaColors.OffWhiteMuted,
                    )
                }
            } else {
                items(state.statuses.size) { i ->
                    RuleCard(
                        status = state.statuses[i],
                        onToggle = { active -> viewModel.setActive(state.statuses[i].rule, active) },
                        onDelete = { viewModel.delete(state.statuses[i].rule) },
                    )
                }
            }

            item {
                Spacer(Modifier.height(KoshaSpacing.s))
                TextButton(onClick = { showAdd = true }) {
                    Text(stringResource(R.string.constitution_add), color = KoshaColors.AccentTealBright)
                }
                Spacer(Modifier.height(KoshaSpacing.xxl))
            }
        }
    }

    if (showAdd) {
        AddRuleSheet(
            categories = state.expenseCategories.map { it.name },
            onSaveFreeText = { text ->
                viewModel.addFreeTextRule(text)
                showAdd = false
            },
            onSaveCategoryLimit = { category, limit ->
                viewModel.addCategoryLimitRule(category, limit)
                showAdd = false
            },
            onDismiss = { showAdd = false },
        )
    }
}

@Composable
private fun RuleCard(
    status: ConstitutionRepository.RuleStatus,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    val evaluation = status.evaluation
    val violated = evaluation?.violated == true
    KoshaCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    status.rule.ruleText,
                    style = KoshaType.Body,
                    color = if (violated) KoshaColors.Amber else KoshaColors.OffWhite,
                )
                evaluation?.let {
                    Text(it.explanation, style = KoshaType.Caption, color = KoshaColors.OffWhiteFaint)
                }
            }
            KoshaChip(
                label = if (status.rule.isActive) "On" else "Off",
                selected = status.rule.isActive,
                onClick = { onToggle(!status.rule.isActive) },
                accent = KoshaColors.AccentTealBright,
            )
            TextButton(onClick = onDelete) {
                Text("×", style = KoshaType.Title, color = KoshaColors.OffWhiteFaint)
            }
        }
    }
}

private enum class RuleType { FreeText, CategoryLimit }

@Composable
private fun AddRuleSheet(
    categories: List<String>,
    onSaveFreeText: (String) -> Unit,
    onSaveCategoryLimit: (category: String, limitRupees: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var type by remember { mutableStateOf(RuleType.FreeText) }
    var text by remember { mutableStateOf("") }
    var category by remember { mutableStateOf(categories.firstOrNull().orEmpty()) }
    var limit by remember { mutableStateOf("") }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = KoshaColors.CharcoalOverlay) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(KoshaSpacing.m),
            verticalArrangement = Arrangement.spacedBy(KoshaSpacing.s),
        ) {
            Text(stringResource(R.string.constitution_add), style = KoshaType.Title, color = KoshaColors.OffWhite)
            Row(horizontalArrangement = Arrangement.spacedBy(KoshaSpacing.xs)) {
                KoshaChip(
                    label = stringResource(R.string.constitution_free_text),
                    selected = type == RuleType.FreeText,
                    onClick = { type = RuleType.FreeText },
                )
                if (categories.isNotEmpty()) {
                    KoshaChip(
                        label = stringResource(R.string.constitution_category_limit),
                        selected = type == RuleType.CategoryLimit,
                        onClick = { type = RuleType.CategoryLimit },
                    )
                }
            }

            when (type) {
                RuleType.FreeText -> {
                    RuleField(text, { text = it }, stringResource(R.string.constitution_free_text_hint))
                    Text(
                        stringResource(R.string.constitution_free_text_note),
                        style = KoshaType.Caption,
                        color = KoshaColors.OffWhiteFaint,
                    )
                    TextButton(onClick = { onSaveFreeText(text) }, enabled = text.isNotBlank()) {
                        Text(stringResource(R.string.constitution_save), color = KoshaColors.AccentTeal)
                    }
                }

                RuleType.CategoryLimit -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(KoshaSpacing.xs),
                    ) {
                        categories.forEach { name ->
                            KoshaChip(label = name, selected = category == name, onClick = { category = name })
                        }
                    }
                    RuleField(limit, { if (it.all { c -> c.isDigit() || c == '.' }) limit = it }, stringResource(R.string.constitution_limit_amount_hint))
                    Text(
                        stringResource(R.string.constitution_limit_note),
                        style = KoshaType.Caption,
                        color = KoshaColors.OffWhiteFaint,
                    )
                    TextButton(
                        onClick = { onSaveCategoryLimit(category, limit) },
                        enabled = category.isNotBlank() && limit.isNotBlank(),
                    ) {
                        Text(stringResource(R.string.constitution_save), color = KoshaColors.AccentTeal)
                    }
                }
            }
            Spacer(Modifier.height(KoshaSpacing.l))
        }
    }
}

@Composable
private fun RuleField(value: String, onValueChange: (String) -> Unit, placeholder: String) {
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

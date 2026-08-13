package dev.kosha.feature.income

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
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
import dev.kosha.core.common.Money
import dev.kosha.core.database.model.IncomeFrequency
import dev.kosha.core.database.model.IncomeSourceEntity
import dev.kosha.core.database.model.PeriodSummaryEntity
import dev.kosha.core.designsystem.component.AmountText
import dev.kosha.core.designsystem.component.KoshaCard
import dev.kosha.core.designsystem.component.KoshaChip
import dev.kosha.core.designsystem.token.KoshaColors
import dev.kosha.core.designsystem.token.KoshaSpacing
import dev.kosha.core.designsystem.token.KoshaType
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun IncomeScreen(
    onBack: () -> Unit,
    viewModel: IncomeViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    var showEditor by remember { mutableStateOf(false) }
    var confirmingClose by remember { mutableStateOf(false) }

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
                text = stringResource(R.string.income_title),
                style = KoshaType.Title,
                color = KoshaColors.OffWhite,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { showEditor = true }) {
                Icon(
                    Icons.Outlined.Add,
                    contentDescription = stringResource(R.string.income_add),
                    tint = KoshaColors.OffWhite,
                )
            }
        }

        LazyColumn(
            contentPadding = PaddingValues(KoshaSpacing.screenPadding),
            verticalArrangement = Arrangement.spacedBy(KoshaSpacing.s),
        ) {
            item {
                Text(stringResource(R.string.income_sources), style = KoshaType.Label, color = KoshaColors.OffWhiteFaint)
            }
            if (state.sources.isEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.income_empty),
                        style = KoshaType.InsightSerif,
                        color = KoshaColors.OffWhiteMuted,
                    )
                }
            } else {
                items(state.sources.size) { i ->
                    SourceCard(state.sources[i]) { viewModel.removeSource(state.sources[i].id) }
                }
            }

            item {
                Spacer(Modifier.height(KoshaSpacing.m))
                AnchorSection(state.anchorDay, viewModel::setAnchorDay)
            }

            item {
                Spacer(Modifier.height(KoshaSpacing.m))
                KoshaCard(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = stringResource(R.string.period_close_body),
                        style = KoshaType.Body,
                        color = KoshaColors.OffWhiteMuted,
                    )
                    if (!confirmingClose) {
                        TextButton(onClick = { confirmingClose = true }) {
                            Text(stringResource(R.string.period_close), color = KoshaColors.AccentTeal)
                        }
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(KoshaSpacing.s)) {
                            TextButton(onClick = {
                                viewModel.closeCurrentPeriod()
                                confirmingClose = false
                            }) {
                                Text(stringResource(R.string.period_close), color = KoshaColors.Amber)
                            }
                            TextButton(onClick = { confirmingClose = false }) {
                                Text("Cancel", color = KoshaColors.OffWhiteMuted)
                            }
                        }
                    }
                }
            }

            if (state.closedPeriods.isNotEmpty()) {
                item {
                    Spacer(Modifier.height(KoshaSpacing.m))
                    Text(stringResource(R.string.period_closed), style = KoshaType.Label, color = KoshaColors.OffWhiteFaint)
                }
                items(state.closedPeriods.size) { i ->
                    ClosedPeriodCard(state.closedPeriods[i])
                }
            }
            item { Spacer(Modifier.height(KoshaSpacing.xxl)) }
        }
    }

    if (showEditor) {
        IncomeEditorSheet(
            onSave = { name, amount, freq, day ->
                viewModel.addSource(name, amount, freq, day)
                showEditor = false
            },
            onDismiss = { showEditor = false },
        )
    }
}

@Composable
private fun SourceCard(source: IncomeSourceEntity, onRemove: () -> Unit) {
    KoshaCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(source.name, style = KoshaType.Body, color = KoshaColors.OffWhite)
                Text(
                    text = source.frequency.name.lowercase().replaceFirstChar { it.uppercase() } +
                        (source.expectedDay?.let { " · day $it" } ?: ""),
                    style = KoshaType.Caption,
                    color = KoshaColors.OffWhiteFaint,
                )
            }
            AmountText(
                amount = Money(source.amountPaise),
                style = KoshaType.AmountBody,
                color = KoshaColors.AccentTeal,
                withPaise = false,
            )
            TextButton(onClick = onRemove) {
                Text(stringResource(R.string.income_remove), color = KoshaColors.OffWhiteFaint, style = KoshaType.Caption)
            }
        }
    }
}

@Composable
private fun AnchorSection(anchorDay: Int, onSet: (Int) -> Unit) {
    KoshaCard(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.period_anchor, anchorDay),
            style = KoshaType.Body,
            color = KoshaColors.OffWhite,
        )
        Text(
            text = stringResource(R.string.period_anchor_hint),
            style = KoshaType.Caption,
            color = KoshaColors.OffWhiteFaint,
        )
        Spacer(Modifier.height(KoshaSpacing.xs))
        Row(horizontalArrangement = Arrangement.spacedBy(KoshaSpacing.xxs)) {
            listOf(1, 5, 10, 25).forEach { day ->
                KoshaChip(
                    label = day.toString(),
                    selected = anchorDay == day,
                    onClick = { onSet(day) },
                )
            }
        }
    }
}

@Composable
private fun ClosedPeriodCard(summary: PeriodSummaryEntity) {
    val format = DateTimeFormatter.ofPattern("MMM yyyy")
    val label = format.format(
        Instant.ofEpochMilli(summary.periodStartMillis).atZone(ZoneId.systemDefault()),
    )
    KoshaCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = KoshaType.Body, color = KoshaColors.OffWhite, modifier = Modifier.weight(1f))
            AmountText(
                amount = Money(summary.savingsGapPaise),
                style = KoshaType.AmountBody,
                color = if (summary.savingsGapPaise < 0) KoshaColors.Amber else KoshaColors.AccentTeal,
                withPaise = false,
                signed = true,
            )
        }
        Row {
            LabeledAmount(stringResource(R.string.period_actual), Money(summary.actualIncomePaise))
            Spacer(Modifier.width(KoshaSpacing.m))
            LabeledAmount(stringResource(R.string.period_expected), Money(summary.expectedIncomePaise))
            if (summary.untrackedGapPaise > 0) {
                Spacer(Modifier.width(KoshaSpacing.m))
                LabeledAmount(stringResource(R.string.period_untracked), Money(summary.untrackedGapPaise))
            }
        }
    }
}

@Composable
private fun LabeledAmount(label: String, amount: Money) {
    Column {
        Text(label, style = KoshaType.Caption, color = KoshaColors.OffWhiteFaint)
        AmountText(amount = amount, style = KoshaType.AmountSmall, color = KoshaColors.OffWhiteMuted, withPaise = false)
    }
}

@Composable
private fun IncomeEditorSheet(
    onSave: (name: String, amount: String, frequency: IncomeFrequency, expectedDay: Int?) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var frequency by remember { mutableStateOf(IncomeFrequency.MONTHLY) }
    var day by remember { mutableStateOf("") }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = KoshaColors.CharcoalOverlay) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(KoshaSpacing.m),
            verticalArrangement = Arrangement.spacedBy(KoshaSpacing.s),
        ) {
            Text(stringResource(R.string.income_add), style = KoshaType.Title, color = KoshaColors.OffWhite)
            TextField(
                value = name,
                onValueChange = { name = it },
                placeholder = { Text(stringResource(R.string.income_name), color = KoshaColors.OffWhiteFaint) },
                colors = incomeFieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )
            TextField(
                value = amount,
                onValueChange = { text -> if (text.all { it.isDigit() || it == '.' }) amount = text },
                placeholder = { Text(stringResource(R.string.income_amount), color = KoshaColors.OffWhiteFaint) },
                colors = incomeFieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(KoshaSpacing.xs)) {
                listOf(
                    IncomeFrequency.MONTHLY to stringResource(R.string.income_freq_monthly),
                    IncomeFrequency.ONE_TIME to stringResource(R.string.income_freq_once),
                    IncomeFrequency.VARIABLE to stringResource(R.string.income_freq_variable),
                ).forEach { (f, label) ->
                    KoshaChip(label = label, selected = frequency == f, onClick = { frequency = f })
                }
            }
            if (frequency == IncomeFrequency.MONTHLY) {
                TextField(
                    value = day,
                    onValueChange = { text -> if (text.length <= 2 && text.all(Char::isDigit)) day = text },
                    placeholder = { Text(stringResource(R.string.income_expected_day), color = KoshaColors.OffWhiteFaint) },
                    colors = incomeFieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            TextButton(
                onClick = { onSave(name.trim(), amount, frequency, day.toIntOrNull()) },
                enabled = name.isNotBlank() && amount.isNotBlank(),
            ) {
                Text(stringResource(R.string.income_save), color = KoshaColors.AccentTeal)
            }
            Spacer(Modifier.height(KoshaSpacing.l))
        }
    }
}

@Composable
private fun incomeFieldColors() = TextFieldDefaults.colors(
    focusedContainerColor = KoshaColors.CharcoalRaised,
    unfocusedContainerColor = KoshaColors.CharcoalRaised,
    focusedTextColor = KoshaColors.OffWhite,
    unfocusedTextColor = KoshaColors.OffWhite,
)

package dev.kosha.feature.budget.recurring

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
import dev.kosha.core.database.model.AccountEntity
import dev.kosha.core.database.model.RecurringFrequency
import dev.kosha.core.database.model.RecurringRuleEntity
import dev.kosha.core.designsystem.component.AmountText
import dev.kosha.core.designsystem.component.KoshaCard
import dev.kosha.core.designsystem.component.KoshaChip
import dev.kosha.core.designsystem.token.KoshaColors
import dev.kosha.core.designsystem.token.KoshaSpacing
import dev.kosha.core.designsystem.token.KoshaType
import dev.kosha.feature.budget.R
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

@Composable
fun RecurringScreen(
    onBack: () -> Unit,
    viewModel: RecurringViewModel = hiltViewModel(),
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
                text = stringResource(R.string.recurring_title),
                style = KoshaType.Title,
                color = KoshaColors.OffWhite,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { showEditor = true }) {
                Icon(
                    Icons.Outlined.Add,
                    contentDescription = stringResource(R.string.recurring_add),
                    tint = KoshaColors.OffWhite,
                )
            }
        }

        if (state.rules.isEmpty() && state.suggestions.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(R.string.recurring_empty),
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
                // Above the rules, not below: a rule the app found for you is
                // only useful before you have gone looking for it yourself.
                if (state.suggestions.isNotEmpty()) {
                    item {
                        Text(
                            text = stringResource(R.string.recurring_found_title),
                            style = KoshaType.SectionHeader,
                            color = KoshaColors.OffWhite,
                        )
                    }
                    items(state.suggestions.size) { i ->
                        val candidate = state.suggestions[i]
                        SuggestionCard(
                            candidate = candidate,
                            onAccept = { viewModel.accept(candidate) },
                            onDismiss = { viewModel.dismiss(candidate) },
                        )
                    }
                    if (state.rules.isNotEmpty()) {
                        item {
                            Text(
                                text = stringResource(R.string.recurring_your_rules),
                                style = KoshaType.SectionHeader,
                                color = KoshaColors.OffWhite,
                                modifier = Modifier.padding(top = KoshaSpacing.s),
                            )
                        }
                    }
                }
                items(state.rules.size) { i ->
                    RuleCard(state.rules[i]) { viewModel.remove(state.rules[i].id) }
                }
            }
        }
    }

    if (showEditor) {
        RecurringEditorSheet(
            accounts = state.accounts,
            onSave = { label, amount, accountId, frequency, autoLog, isCardDue ->
                viewModel.add(label, amount, accountId, frequency, autoLog, isCardDue)
                showEditor = false
            },
            onDismiss = { showEditor = false },
        )
    }
}

/**
 * "This looks monthly — make it a rule?"
 *
 * States what it saw rather than just asserting a conclusion: how many times,
 * how often, for how much. A suggestion the user cannot check is one they can
 * only guess at, and accepting it changes what the forecast says.
 */
@Composable
private fun SuggestionCard(
    candidate: dev.kosha.core.engine.forecast.RecurringDetector.Candidate,
    onAccept: () -> Unit,
    onDismiss: () -> Unit,
) {
    KoshaCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(candidate.label, style = KoshaType.Body, color = KoshaColors.OffWhite)
                Text(
                    text = stringResource(
                        R.string.recurring_found_detail,
                        candidate.occurrences,
                        stringResource(candidate.frequency.labelRes()),
                    ),
                    style = KoshaType.Caption,
                    color = KoshaColors.OffWhiteFaint,
                )
            }
            AmountText(
                amount = Money(candidate.typicalAmountPaise),
                style = KoshaType.AmountSmall,
                color = KoshaColors.OffWhite,
                withPaise = false,
            )
        }
        Spacer(Modifier.height(KoshaSpacing.xs))
        Row(horizontalArrangement = Arrangement.spacedBy(KoshaSpacing.xs)) {
            KoshaChip(
                label = stringResource(R.string.recurring_found_accept),
                onClick = onAccept,
                selected = true,
                accent = KoshaColors.AccentTeal,
            )
            KoshaChip(label = stringResource(R.string.recurring_found_no), onClick = onDismiss)
        }
    }
}

private fun dev.kosha.core.engine.forecast.RecurringEngine.Frequency.labelRes(): Int = when (this) {
    dev.kosha.core.engine.forecast.RecurringEngine.Frequency.DAILY -> R.string.recurring_freq_daily
    dev.kosha.core.engine.forecast.RecurringEngine.Frequency.WEEKLY -> R.string.recurring_freq_weekly
    dev.kosha.core.engine.forecast.RecurringEngine.Frequency.MONTHLY -> R.string.recurring_freq_monthly
    dev.kosha.core.engine.forecast.RecurringEngine.Frequency.QUARTERLY -> R.string.recurring_freq_quarterly
    dev.kosha.core.engine.forecast.RecurringEngine.Frequency.YEARLY -> R.string.recurring_freq_yearly
}

@Composable
private fun RuleCard(rule: RecurringRuleEntity, onRemove: () -> Unit) {
    val dueDate = Instant.ofEpochMilli(rule.nextDueDateMillis)
        .atZone(ZoneId.systemDefault()).toLocalDate()
    val daysAway = ChronoUnit.DAYS.between(LocalDate.now(ZoneId.systemDefault()), dueDate).toInt()

    KoshaCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(rule.label, style = KoshaType.Body, color = KoshaColors.OffWhite)
                Text(
                    text = if (daysAway <= 0) {
                        stringResource(R.string.recurring_due_now)
                    } else {
                        stringResource(R.string.recurring_next_due, daysAway)
                    },
                    style = KoshaType.Caption,
                    color = if (daysAway <= 0) KoshaColors.Amber else KoshaColors.OffWhiteFaint,
                )
                Text(
                    text = if (rule.autoLog) {
                        stringResource(R.string.recurring_auto_log)
                    } else {
                        stringResource(R.string.recurring_remind)
                    },
                    style = KoshaType.Caption,
                    color = KoshaColors.OffWhiteFaint,
                )
            }
            rule.amountPaise?.let {
                AmountText(amount = Money(it), style = KoshaType.AmountBody, withPaise = false)
            }
            TextButton(onClick = onRemove) {
                Text(stringResource(R.string.recurring_remove), color = KoshaColors.OffWhiteFaint, style = KoshaType.Caption)
            }
        }
    }
}

@Composable
private fun RecurringEditorSheet(
    accounts: List<AccountEntity>,
    onSave: (
        label: String,
        amount: String,
        accountId: Long,
        frequency: RecurringFrequency,
        autoLog: Boolean,
        isCardDue: Boolean,
    ) -> Unit,
    onDismiss: () -> Unit,
) {
    var label by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var accountId by remember { mutableStateOf(accounts.firstOrNull()?.id) }
    var frequency by remember { mutableStateOf(RecurringFrequency.MONTHLY) }
    var autoLog by remember { mutableStateOf(false) }
    var isCardDue by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = KoshaColors.CharcoalOverlay) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(KoshaSpacing.m),
            verticalArrangement = Arrangement.spacedBy(KoshaSpacing.s),
        ) {
            Text(stringResource(R.string.recurring_add), style = KoshaType.Title, color = KoshaColors.OffWhite)
            TextField(
                value = label,
                onValueChange = { label = it },
                placeholder = { Text(stringResource(R.string.recurring_label), color = KoshaColors.OffWhiteFaint) },
                colors = recurringFieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )
            TextField(
                value = amount,
                onValueChange = { text -> if (text.all { it.isDigit() || it == '.' }) amount = text },
                placeholder = { Text(stringResource(R.string.recurring_amount), color = KoshaColors.OffWhiteFaint) },
                colors = recurringFieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(KoshaSpacing.xs)) {
                accounts.take(3).forEach { account ->
                    KoshaChip(
                        label = account.name,
                        selected = accountId == account.id,
                        onClick = { accountId = account.id },
                        accent = KoshaColors.accountColor(account.colorToken),
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(KoshaSpacing.xs)) {
                listOf(
                    RecurringFrequency.WEEKLY to stringResource(R.string.recurring_freq_weekly),
                    RecurringFrequency.MONTHLY to stringResource(R.string.recurring_freq_monthly),
                    RecurringFrequency.QUARTERLY to stringResource(R.string.recurring_freq_quarterly),
                    RecurringFrequency.YEARLY to stringResource(R.string.recurring_freq_yearly),
                ).forEach { (f, text) ->
                    KoshaChip(label = text, selected = frequency == f, onClick = { frequency = f })
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(KoshaSpacing.xs)) {
                KoshaChip(
                    label = stringResource(R.string.recurring_auto_log),
                    selected = autoLog,
                    onClick = { autoLog = true },
                )
                KoshaChip(
                    label = stringResource(R.string.recurring_remind),
                    selected = !autoLog,
                    onClick = { autoLog = false },
                )
                KoshaChip(
                    label = stringResource(R.string.recurring_card_due),
                    selected = isCardDue,
                    onClick = { isCardDue = !isCardDue },
                )
            }
            TextButton(
                onClick = { accountId?.let { onSave(label.trim(), amount, it, frequency, autoLog, isCardDue) } },
                enabled = label.isNotBlank() && accountId != null,
            ) {
                Text(stringResource(R.string.recurring_save), color = KoshaColors.AccentTeal)
            }
            Spacer(Modifier.height(KoshaSpacing.l))
        }
    }
}

@Composable
private fun recurringFieldColors() = TextFieldDefaults.colors(
    focusedContainerColor = KoshaColors.CharcoalRaised,
    unfocusedContainerColor = KoshaColors.CharcoalRaised,
    focusedTextColor = KoshaColors.OffWhite,
    unfocusedTextColor = KoshaColors.OffWhite,
)

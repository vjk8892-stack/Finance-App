package dev.kosha.app.onboarding

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import dev.kosha.app.R
import dev.kosha.core.database.model.AccountType
import dev.kosha.core.designsystem.component.KoshaCard
import dev.kosha.core.designsystem.component.KoshaChip
import dev.kosha.core.designsystem.token.KoshaColors
import dev.kosha.core.designsystem.token.KoshaSpacing
import dev.kosha.core.designsystem.token.KoshaType
import dev.kosha.feature.ingest.sms.SmsReconcileWorker

@Composable
fun OnboardingScreen(
    onDone: () -> Unit,
    viewModel: OnboardingViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current

    val smsPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        val granted = grants[Manifest.permission.RECEIVE_SMS] == true &&
            grants[Manifest.permission.READ_SMS] == true
        viewModel.onSmsPermissionResult(granted)
        if (granted) SmsReconcileWorker.schedule(context)
        viewModel.next()
    }
    val notifPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { viewModel.next() }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(KoshaSpacing.screenPadding),
        verticalArrangement = Arrangement.spacedBy(KoshaSpacing.m),
    ) {
        Spacer(Modifier.height(KoshaSpacing.xl))
        when (state.step) {
            OnboardingStep.PHILOSOPHY -> StepCard(
                title = stringResource(R.string.onb_philosophy_title),
                body = stringResource(R.string.onb_philosophy_body),
                primary = stringResource(R.string.onb_continue),
                onPrimary = viewModel::next,
            )

            OnboardingStep.ACCOUNTS -> AccountsStep(viewModel)

            OnboardingStep.INCOME -> IncomeStep(viewModel)

            OnboardingStep.SMS -> StepCard(
                title = stringResource(R.string.onb_sms_title),
                body = stringResource(R.string.onb_sms_body),
                primary = stringResource(R.string.onb_sms_allow),
                onPrimary = {
                    smsPermissionLauncher.launch(
                        arrayOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS),
                    )
                },
                secondary = stringResource(R.string.onb_sms_skip),
                onSecondary = {
                    viewModel.onSmsPermissionResult(false)
                    viewModel.next()
                },
            )

            OnboardingStep.IMPORT -> ImportStep(state, viewModel)

            OnboardingStep.NOTIFICATIONS -> StepCard(
                title = stringResource(R.string.onb_notif_title),
                body = stringResource(R.string.onb_notif_body),
                primary = stringResource(R.string.onb_notif_allow),
                onPrimary = {
                    if (Build.VERSION.SDK_INT >= 33) {
                        notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        viewModel.next()
                    }
                },
                secondary = stringResource(R.string.onb_skip),
                onSecondary = viewModel::next,
            )

            OnboardingStep.APP_LOCK -> AppLockStep(viewModel, onDone)
        }
    }
}

@Composable
private fun StepCard(
    title: String,
    body: String,
    primary: String,
    onPrimary: () -> Unit,
    secondary: String? = null,
    onSecondary: (() -> Unit)? = null,
) {
    Text(title, style = KoshaType.Title, color = KoshaColors.OffWhite)
    Text(body, style = KoshaType.InsightSerif, color = KoshaColors.OffWhiteMuted)
    Spacer(Modifier.height(KoshaSpacing.s))
    TextButton(onClick = onPrimary) { Text(primary, color = KoshaColors.AccentTeal) }
    if (secondary != null && onSecondary != null) {
        TextButton(onClick = onSecondary) { Text(secondary, color = KoshaColors.OffWhiteMuted) }
    }
}

@Composable
private fun AccountsStep(viewModel: OnboardingViewModel) {
    var name by remember { mutableStateOf("") }
    var type by remember { mutableStateOf(AccountType.BANK) }
    var opening by remember { mutableStateOf("") }
    var addedCount by remember { mutableStateOf(0) }

    Text(stringResource(R.string.onb_accounts_title), style = KoshaType.Title, color = KoshaColors.OffWhite)
    Text(stringResource(R.string.onb_accounts_body), style = KoshaType.Body, color = KoshaColors.OffWhiteMuted)
    KoshaCard(modifier = Modifier.fillMaxWidth()) {
        TextField(
            value = name,
            onValueChange = { name = it },
            placeholder = { Text(stringResource(R.string.onb_account_name), color = KoshaColors.OffWhiteFaint) },
            colors = onbFieldColors(),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(KoshaSpacing.xs))
        Row(horizontalArrangement = Arrangement.spacedBy(KoshaSpacing.xxs)) {
            listOf(AccountType.BANK, AccountType.CASH, AccountType.CARD, AccountType.WALLET).forEach { t ->
                KoshaChip(
                    label = t.name.lowercase().replaceFirstChar { it.uppercase() },
                    selected = type == t,
                    onClick = { type = t },
                )
            }
        }
        Spacer(Modifier.height(KoshaSpacing.xs))
        TextField(
            value = opening,
            onValueChange = { text -> if (text.all { it.isDigit() || it == '.' }) opening = text },
            placeholder = { Text(stringResource(R.string.onb_account_opening), color = KoshaColors.OffWhiteFaint) },
            colors = onbFieldColors(),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(KoshaSpacing.xs))
        TextButton(
            onClick = {
                if (name.isNotBlank()) {
                    viewModel.addAccount(name.trim(), type, opening)
                    addedCount++
                    name = ""
                    opening = ""
                }
            },
            enabled = name.isNotBlank(),
        ) { Text(stringResource(R.string.onb_account_add), color = KoshaColors.AccentTeal) }
    }
    if (addedCount > 0) {
        Text(
            text = stringResource(R.string.onb_accounts_added, addedCount),
            style = KoshaType.Caption,
            color = KoshaColors.OffWhiteMuted,
        )
    }
    TextButton(onClick = viewModel::next, enabled = addedCount > 0) {
        Text(stringResource(R.string.onb_continue), color = if (addedCount > 0) KoshaColors.AccentTeal else KoshaColors.OffWhiteFaint)
    }
}

@Composable
private fun IncomeStep(viewModel: OnboardingViewModel) {
    var income by remember { mutableStateOf("") }
    var anchorDay by remember { mutableStateOf("1") }

    Text(stringResource(R.string.onb_income_title), style = KoshaType.Title, color = KoshaColors.OffWhite)
    Text(stringResource(R.string.onb_income_body), style = KoshaType.Body, color = KoshaColors.OffWhiteMuted)
    TextField(
        value = income,
        onValueChange = { text -> if (text.all { it.isDigit() || it == '.' }) income = text },
        placeholder = { Text(stringResource(R.string.onb_income_hint), color = KoshaColors.OffWhiteFaint) },
        colors = onbFieldColors(),
        modifier = Modifier.fillMaxWidth(),
    )
    TextField(
        value = anchorDay,
        onValueChange = { text -> if (text.length <= 2 && text.all(Char::isDigit)) anchorDay = text },
        placeholder = { Text(stringResource(R.string.onb_income_day_hint), color = KoshaColors.OffWhiteFaint) },
        colors = onbFieldColors(),
        modifier = Modifier.fillMaxWidth(),
    )
    Row {
        TextButton(
            onClick = {
                viewModel.setMonthlyIncome(income, anchorDay.toIntOrNull() ?: 1)
                viewModel.next()
            },
            enabled = income.isNotBlank(),
        ) { Text(stringResource(R.string.onb_continue), color = KoshaColors.AccentTeal) }
        TextButton(onClick = viewModel::next) {
            Text(stringResource(R.string.onb_skip), color = KoshaColors.OffWhiteMuted)
        }
    }
}

@Composable
private fun ImportStep(state: OnboardingUiState, viewModel: OnboardingViewModel) {
    Text(stringResource(R.string.onb_import_title), style = KoshaType.Title, color = KoshaColors.OffWhite)
    Text(stringResource(R.string.onb_import_body), style = KoshaType.Body, color = KoshaColors.OffWhiteMuted)

    val summary = state.importSummary
    if (summary != null) {
        Text(
            text = stringResource(
                R.string.onb_import_summary,
                summary.committed, summary.queuedForReview, summary.scanned,
            ),
            style = KoshaType.InsightSerif,
            color = KoshaColors.OffWhite,
        )
        TextButton(onClick = viewModel::next) {
            Text(stringResource(R.string.onb_continue), color = KoshaColors.AccentTeal)
        }
    } else if (state.importing) {
        val progress = state.importProgress
        if (progress != null && progress.second > 0) {
            LinearProgressIndicator(
                progress = { progress.first.toFloat() / progress.second },
                color = KoshaColors.AccentTeal,
                trackColor = KoshaColors.Outline,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            LinearProgressIndicator(
                color = KoshaColors.AccentTeal,
                trackColor = KoshaColors.Outline,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(KoshaSpacing.xs)) {
            listOf(3, 6, 12).forEach { months ->
                KoshaChip(
                    label = stringResource(R.string.onb_import_months, months),
                    onClick = { viewModel.runImport(months) },
                )
            }
        }
        TextButton(onClick = viewModel::next) {
            Text(stringResource(R.string.onb_skip), color = KoshaColors.OffWhiteMuted)
        }
    }
}

@Composable
private fun AppLockStep(viewModel: OnboardingViewModel, onDone: () -> Unit) {
    Text(stringResource(R.string.onb_lock_title), style = KoshaType.Title, color = KoshaColors.OffWhite)
    Text(stringResource(R.string.onb_lock_body), style = KoshaType.Body, color = KoshaColors.OffWhiteMuted)
    Column(verticalArrangement = Arrangement.spacedBy(KoshaSpacing.xs)) {
        TextButton(onClick = {
            viewModel.setAppLock(enabled = true, timeoutMillis = 0)
            viewModel.finish(onDone)
        }) { Text(stringResource(R.string.onb_lock_immediate), color = KoshaColors.AccentTeal) }
        TextButton(onClick = {
            viewModel.setAppLock(enabled = true, timeoutMillis = 60_000)
            viewModel.finish(onDone)
        }) { Text(stringResource(R.string.onb_lock_1min), color = KoshaColors.AccentTeal) }
        TextButton(onClick = {
            viewModel.setAppLock(enabled = false, timeoutMillis = 0)
            viewModel.finish(onDone)
        }) { Text(stringResource(R.string.onb_lock_skip), color = KoshaColors.OffWhiteMuted) }
    }
}

@Composable
private fun onbFieldColors() = TextFieldDefaults.colors(
    focusedContainerColor = KoshaColors.CharcoalRaised,
    unfocusedContainerColor = KoshaColors.CharcoalRaised,
    focusedTextColor = KoshaColors.OffWhite,
    unfocusedTextColor = KoshaColors.OffWhite,
)

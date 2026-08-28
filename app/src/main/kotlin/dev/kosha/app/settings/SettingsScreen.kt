package dev.kosha.app.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
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
import dev.kosha.core.designsystem.component.KoshaCard
import dev.kosha.core.designsystem.component.KoshaChip
import dev.kosha.core.designsystem.token.KoshaColors
import dev.kosha.core.designsystem.token.KoshaSpacing
import dev.kosha.core.designsystem.token.KoshaType
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Everything that was previously scattered or unreachable.
 *
 * The gear opened the Income screen, which is not settings — so the period
 * anchor, the app lock and SMS retention had no home at all, and export and
 * backup were only findable from a card on Home. This is the one place those
 * live, with the destructive-adjacent things (tracking date) explained rather
 * than presented as a bare control.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenExport: () -> Unit,
    onOpenBackup: () -> Unit,
    onOpenIncome: () -> Unit,
    onOpenBudgets: () -> Unit,
    onOpenRecurring: () -> Unit,
    onOpenGoals: () -> Unit,
    onOpenWarranties: () -> Unit,
    onOpenPermissions: () -> Unit,
    onScanSms: () -> Unit,
    onOpenAccounts: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val backfill by viewModel.backfill.collectAsState()
    val openingBalanceReminder by viewModel.openingBalanceReminder.collectAsState()
    var showDatePicker by remember { mutableStateOf(false) }

    if (showDatePicker) {
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = state.trackingStart
                ?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli()
                ?: System.currentTimeMillis(),
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        pickerState.selectedDateMillis?.let { picked ->
                            viewModel.setTrackingStart(
                                Instant.ofEpochMilli(picked).atZone(ZoneOffset.UTC).toLocalDate(),
                            )
                        }
                        showDatePicker = false
                    },
                ) {
                    Text(stringResource(R.string.settings_tracking_use), color = KoshaColors.AccentTealBright)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) {
                    Text(stringResource(R.string.settings_cancel), color = KoshaColors.OffWhiteMuted)
                }
            },
        ) {
            DatePicker(state = pickerState)
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = KoshaSpacing.xs, vertical = KoshaSpacing.s),
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = null,
                    tint = KoshaColors.OffWhiteMuted,
                )
            }
            Text(
                text = stringResource(R.string.settings_title),
                style = KoshaType.ScreenTitle,
                color = KoshaColors.OffWhite,
            )
        }

        Column(
            Modifier.padding(horizontal = KoshaSpacing.screenPadding),
            verticalArrangement = Arrangement.spacedBy(KoshaSpacing.s),
        ) {
            TrackingCard(
                state = state,
                backfill = backfill,
                onPickDate = { showDatePicker = true },
                onTrackEverything = { viewModel.setTrackingStart(null) },
                onScanSms = {
                    viewModel.dismissBackfill()
                    onScanSms()
                },
                onDismissBackfill = viewModel::dismissBackfill,
            )

            // Balances are stored as opening + tracked transactions, so moving
            // the boundary changes what the opening figure MEANS without
            // changing the figure. Every balance is wrong until they are
            // re-entered, and nothing else would ever say so.
            openingBalanceReminder?.let { reminder ->
                KoshaCard(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = stringResource(R.string.settings_opening_title),
                        style = KoshaType.SectionHeader,
                        color = KoshaColors.Amber,
                    )
                    Text(
                        text = stringResource(
                            R.string.settings_opening_body,
                            DAY.format(reminder.from),
                            reminder.accounts,
                        ),
                        style = KoshaType.Body,
                        color = KoshaColors.OffWhiteMuted,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(KoshaSpacing.s)) {
                        TextButton(
                            onClick = {
                                viewModel.dismissOpeningBalanceReminder()
                                onOpenAccounts()
                            },
                        ) {
                            Text(
                                text = stringResource(R.string.settings_opening_action),
                                style = KoshaType.LabelStrong,
                                color = KoshaColors.AccentTealBright,
                            )
                        }
                        TextButton(onClick = viewModel::dismissOpeningBalanceReminder) {
                            Text(
                                stringResource(R.string.settings_backfill_later),
                                color = KoshaColors.OffWhiteMuted,
                            )
                        }
                    }
                }
            }

            SectionCard(stringResource(R.string.settings_data)) {
                LinkRow(stringResource(R.string.settings_export), stringResource(R.string.settings_export_sub), onOpenExport)
                LinkRow(stringResource(R.string.settings_backup), stringResource(R.string.settings_backup_sub), onOpenBackup)
                LinkRow(stringResource(R.string.settings_scan_sms), stringResource(R.string.settings_scan_sms_sub), onScanSms)
            }

            SectionCard(stringResource(R.string.settings_money)) {
                LinkRow(stringResource(R.string.settings_income), null, onOpenIncome)
                LinkRow(stringResource(R.string.settings_budgets), null, onOpenBudgets)
                LinkRow(stringResource(R.string.settings_recurring), null, onOpenRecurring)
                LinkRow(stringResource(R.string.settings_goals), null, onOpenGoals)
                LinkRow(stringResource(R.string.settings_warranties), null, onOpenWarranties)
                Spacer(Modifier.height(KoshaSpacing.xs))
                Text(
                    text = stringResource(R.string.settings_anchor, state.settings.periodAnchorDay),
                    style = KoshaType.Body,
                    color = KoshaColors.OffWhite,
                )
                Text(
                    text = stringResource(R.string.settings_anchor_sub),
                    style = KoshaType.Caption,
                    color = KoshaColors.OffWhiteFaint,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(KoshaSpacing.xs),
                    modifier = Modifier.padding(top = KoshaSpacing.xxs),
                ) {
                    listOf(1, 5, 10, 25).forEach { day ->
                        KoshaChip(
                            label = stringResource(R.string.settings_anchor_day, day),
                            selected = state.settings.periodAnchorDay == day,
                            onClick = { viewModel.setPeriodAnchorDay(day) },
                            accent = KoshaColors.AccentTeal,
                        )
                    }
                }
            }

            // Several Android skins stop manifest-registered receivers unless
            // the app is on an "autostart" allowlist, and they do it silently —
            // background capture simply stops with nothing to indicate why.
            // Only shown on the manufacturers that actually do this.
            if (needsAutostartNote()) {
                KoshaCard(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = stringResource(R.string.settings_autostart_title),
                        style = KoshaType.SectionHeader,
                        color = KoshaColors.OffWhite,
                    )
                    Text(
                        text = stringResource(R.string.settings_autostart_body),
                        style = KoshaType.Body,
                        color = KoshaColors.OffWhiteMuted,
                    )
                }
            }

            SectionCard(stringResource(R.string.settings_privacy)) {
                LinkRow(stringResource(R.string.settings_permissions), stringResource(R.string.settings_permissions_sub), onOpenPermissions)
                ToggleRow(
                    label = stringResource(R.string.settings_app_lock),
                    sub = stringResource(R.string.settings_app_lock_sub),
                    checked = state.settings.appLockEnabled,
                    onChange = viewModel::setAppLock,
                )
                ToggleRow(
                    label = stringResource(R.string.settings_retain_sms),
                    sub = stringResource(R.string.settings_retain_sms_sub),
                    checked = state.settings.retainRawSms,
                    onChange = viewModel::setRetainRawSms,
                )
                Text(
                    text = stringResource(R.string.settings_no_internet),
                    style = KoshaType.Caption,
                    color = KoshaColors.AccentTeal,
                    modifier = Modifier.padding(top = KoshaSpacing.xs),
                )
            }

            Spacer(Modifier.height(KoshaSpacing.xxl))
        }
    }
}

/**
 * The tracking boundary, with what it will actually do stated next to it.
 *
 * A date picker labelled "start from" gives no clue whether the older entries
 * are being hidden or destroyed, and that is precisely the thing a person
 * needs to know before touching it. The hidden count is the reassurance: the
 * rows are still there, and the number proves it.
 */
@Composable
private fun TrackingCard(
    state: SettingsUiState,
    backfill: BackfillOffer?,
    onPickDate: () -> Unit,
    onTrackEverything: () -> Unit,
    onScanSms: () -> Unit,
    onDismissBackfill: () -> Unit,
) {
    KoshaCard(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.settings_tracking),
            style = KoshaType.SectionHeader,
            color = KoshaColors.OffWhite,
        )
        Text(
            text = stringResource(R.string.settings_tracking_sub),
            style = KoshaType.Body,
            color = KoshaColors.OffWhiteMuted,
        )

        Spacer(Modifier.height(KoshaSpacing.s))
        Row(horizontalArrangement = Arrangement.spacedBy(KoshaSpacing.xs)) {
            KoshaChip(
                label = state.trackingStart?.let { DAY.format(it) }
                    ?: stringResource(R.string.settings_tracking_all),
                selected = state.trackingStart != null,
                onClick = onPickDate,
                accent = KoshaColors.AccentTealBright,
            )
            if (state.trackingStart != null) {
                KoshaChip(
                    label = stringResource(R.string.settings_tracking_clear),
                    onClick = onTrackEverything,
                )
            }
        }

        if (state.trackingStart != null) {
            Spacer(Modifier.height(KoshaSpacing.xs))
            Text(
                text = stringResource(
                    R.string.settings_tracking_counts,
                    state.trackedTransactions,
                    state.hiddenTransactions,
                ),
                style = KoshaType.Caption,
                color = KoshaColors.OffWhiteFaint,
            )
            Text(
                text = stringResource(R.string.settings_tracking_kept),
                style = KoshaType.Caption,
                color = KoshaColors.AccentTeal,
            )
        }

        // Only shown when moving the date earlier has uncovered a window that
        // was never scanned — which is the only case where anything is
        // genuinely missing rather than merely hidden.
        backfill?.let { offer ->
            Spacer(Modifier.height(KoshaSpacing.s))
            Text(
                text = stringResource(
                    R.string.settings_backfill,
                    DAY.format(offer.from),
                    DAY.format(offer.to),
                ),
                style = KoshaType.Body,
                color = KoshaColors.Amber,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(KoshaSpacing.s)) {
                TextButton(onClick = onScanSms) {
                    Text(
                        text = stringResource(R.string.settings_backfill_scan),
                        style = KoshaType.LabelStrong,
                        color = KoshaColors.AccentTealBright,
                    )
                }
                TextButton(onClick = onDismissBackfill) {
                    Text(stringResource(R.string.settings_backfill_later), color = KoshaColors.OffWhiteMuted)
                }
            }
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    KoshaCard(modifier = Modifier.fillMaxWidth()) {
        Text(title, style = KoshaType.SectionHeader, color = KoshaColors.OffWhite)
        Spacer(Modifier.height(KoshaSpacing.xxs))
        content()
    }
}

@Composable
private fun LinkRow(label: String, sub: String?, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = KoshaSpacing.xs),
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = KoshaType.Body, color = KoshaColors.OffWhite)
            sub?.let {
                Text(it, style = KoshaType.Caption, color = KoshaColors.OffWhiteFaint)
            }
        }
        Icon(
            Icons.AutoMirrored.Outlined.KeyboardArrowRight,
            contentDescription = null,
            tint = KoshaColors.OffWhiteFaint,
        )
    }
}

@Composable
private fun ToggleRow(label: String, sub: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = KoshaSpacing.xs),
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = KoshaType.Body, color = KoshaColors.OffWhite)
            Text(sub, style = KoshaType.Caption, color = KoshaColors.OffWhiteFaint)
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = KoshaColors.Charcoal,
                checkedTrackColor = KoshaColors.AccentTealBright,
                uncheckedThumbColor = KoshaColors.OffWhiteFaint,
                uncheckedTrackColor = KoshaColors.CharcoalRaised,
            ),
        )
    }
}

private val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy")

/**
 * Manufacturers whose skins suspend background receivers by default. Kosha
 * cannot change that setting for the user — only tell them it exists, because
 * the alternative is capture quietly stopping with no explanation available
 * anywhere in the app.
 */
private fun needsAutostartNote(): Boolean {
    val make = android.os.Build.MANUFACTURER.lowercase()
    return listOf("xiaomi", "redmi", "poco", "oppo", "realme", "vivo", "iqoo", "huawei", "honor")
        .any { make.contains(it) }
}

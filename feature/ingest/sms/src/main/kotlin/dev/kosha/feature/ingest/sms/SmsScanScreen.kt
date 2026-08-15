package dev.kosha.feature.ingest.sms

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.SelectableDates
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import dev.kosha.core.designsystem.component.KoshaCard
import dev.kosha.core.designsystem.component.KoshaChip
import dev.kosha.core.designsystem.token.KoshaColors
import dev.kosha.core.designsystem.token.KoshaSpacing
import dev.kosha.core.designsystem.token.KoshaType
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Manual inbox re-scan (spec F risk register: "WorkManager periodic
 * reconcile scan of inbox as safety net").
 *
 * The scan also ran during onboarding and runs every 12 hours in the
 * background, but neither is reachable when you NOTICE a missing entry.
 * This is that door. Re-scanning is idempotent — anything already recorded
 * merges on UTR or the amount/time window rather than duplicating.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SmsScanScreen(
    onBack: () -> Unit,
    viewModel: SmsScanViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsState()
    val supported = SmsCapability.isSupportedByBuild(context)
    var showDatePicker by remember { mutableStateOf(false) }

    if (showDatePicker) {
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = state.customStartMillis ?: System.currentTimeMillis(),
            selectableDates = PastOnly,
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        // The picker reports UTC midnight; the user means
                        // midnight where they are, so re-anchor to the local
                        // day or an evening message gets missed.
                        viewModel.setCustomStart(pickerState.selectedDateMillis?.let(::startOfLocalDay))
                        showDatePicker = false
                    },
                ) {
                    Text(stringResource(R.string.sms_scan_date_confirm), color = KoshaColors.AccentTeal)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) {
                    Text(stringResource(R.string.sms_scan_date_cancel), color = KoshaColors.OffWhiteMuted)
                }
            },
        ) {
            DatePicker(state = pickerState)
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        val granted = grants[Manifest.permission.READ_SMS] == true
        viewModel.onPermissionResult(granted)
        if (granted) SmsReconcileWorker.schedule(context)
    }

    Column(Modifier.fillMaxSize()) {
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
                text = stringResource(R.string.sms_scan_title),
                style = KoshaType.Title,
                color = KoshaColors.OffWhite,
            )
        }

        Column(
            Modifier.padding(horizontal = KoshaSpacing.screenPadding),
            verticalArrangement = Arrangement.spacedBy(KoshaSpacing.s),
        ) {
            if (!supported) {
                // Lite build: be honest instead of offering a dead button.
                KoshaCard(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = stringResource(R.string.sms_scan_unsupported),
                        style = KoshaType.InsightSerif,
                        color = KoshaColors.OffWhiteMuted,
                    )
                }
                return@Column
            }

            KoshaCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.sms_scan_body),
                    style = KoshaType.Body,
                    color = KoshaColors.OffWhiteMuted,
                )
                Spacer(Modifier.height(KoshaSpacing.xs))

                when {
                    state.scanning -> {
                        val progress = state.progress
                        if (progress != null && progress.second > 0) {
                            LinearProgressIndicator(
                                progress = { progress.first.toFloat() / progress.second },
                                color = KoshaColors.AccentTeal,
                                trackColor = KoshaColors.Outline,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Text(
                                text = stringResource(
                                    R.string.sms_scan_progress,
                                    progress.first,
                                    progress.second,
                                ),
                                style = KoshaType.Caption,
                                color = KoshaColors.OffWhiteFaint,
                            )
                        } else {
                            LinearProgressIndicator(
                                color = KoshaColors.AccentTeal,
                                trackColor = KoshaColors.Outline,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }

                    !SmsCapability.isGranted(context) && !state.permissionGranted -> {
                        KoshaChip(
                            label = stringResource(R.string.sms_scan_grant),
                            onClick = {
                                permissionLauncher.launch(
                                    arrayOf(
                                        Manifest.permission.RECEIVE_SMS,
                                        Manifest.permission.READ_SMS,
                                    ),
                                )
                            },
                            accent = KoshaColors.AccentTeal,
                        )
                    }

                    else -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(KoshaSpacing.xs)) {
                            listOf(1, 3, 6, 12).forEach { months ->
                                KoshaChip(
                                    label = stringResource(R.string.sms_scan_months, months),
                                    onClick = { viewModel.scan(months) },
                                )
                            }
                        }
                        Spacer(Modifier.height(KoshaSpacing.xs))
                        // "Last N months" is the wrong frame when you know the
                        // date that matters — when the account was opened, or
                        // when you started keeping books here.
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(KoshaSpacing.xs),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            KoshaChip(
                                label = state.customStartMillis?.let {
                                    stringResource(R.string.sms_scan_from_date_set, formatDay(it))
                                } ?: stringResource(R.string.sms_scan_from_date),
                                selected = state.customStartMillis != null,
                                onClick = { showDatePicker = true },
                                accent = KoshaColors.AccentTeal,
                            )
                            state.customStartMillis?.let { since ->
                                KoshaChip(
                                    label = stringResource(R.string.sms_scan_run),
                                    onClick = { viewModel.scanSince(since) },
                                    accent = KoshaColors.AccentTeal,
                                )
                            }
                        }
                        // Scanning below the tracking boundary imports nothing,
                        // and "scanned 0" with no reason given reads as a
                        // broken scanner rather than a setting doing its job.
                        if (state.trackingStartMillis > 0) {
                            Text(
                                text = stringResource(
                                    R.string.sms_scan_tracking_note,
                                    formatDay(state.trackingStartMillis),
                                ),
                                style = KoshaType.Caption,
                                color = KoshaColors.OffWhiteFaint,
                            )
                        }
                    }
                }
            }

            // Diagnosing a bad parse needs the original text (spec B4 keeps
            // it off by default, so this is an explicit opt-in).
            KoshaCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.sms_keep_raw_title),
                    style = KoshaType.Body,
                    color = KoshaColors.OffWhite,
                )
                Spacer(Modifier.height(KoshaSpacing.xxs))
                Text(
                    text = stringResource(R.string.sms_keep_raw_body),
                    style = KoshaType.Caption,
                    color = KoshaColors.OffWhiteMuted,
                )
                Spacer(Modifier.height(KoshaSpacing.xs))
                KoshaChip(
                    label = if (state.retainRawSms) {
                        stringResource(R.string.sms_keep_raw_on)
                    } else {
                        stringResource(R.string.sms_keep_raw_off)
                    },
                    selected = state.retainRawSms,
                    onClick = { viewModel.setRetainRawSms(!state.retainRawSms) },
                    accent = KoshaColors.Amber,
                )
            }

            state.summary?.let { summary ->
                KoshaCard(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = stringResource(
                            R.string.sms_scan_summary,
                            summary.committed,
                            summary.queuedForReview,
                            summary.merged,
                            summary.scanned,
                        ),
                        style = KoshaType.InsightSerif,
                        color = KoshaColors.OffWhite,
                    )
                    Spacer(Modifier.height(KoshaSpacing.xxs))
                    Text(
                        text = stringResource(R.string.sms_scan_idempotent),
                        style = KoshaType.Caption,
                        color = KoshaColors.OffWhiteFaint,
                    )
                }
            }
        }
    }
}

/**
 * Material's date picker hands back UTC midnight for the day tapped. The user
 * means midnight in their own zone, so re-anchor — otherwise a scan "from
 * 1 August" silently skips messages received on the evening of 31 July in
 * IST, which is exactly the boundary people pick.
 */
private fun startOfLocalDay(utcMillis: Long): Long =
    Instant.ofEpochMilli(utcMillis)
        .atZone(ZoneOffset.UTC)
        .toLocalDate()
        .atStartOfDay(ZoneId.systemDefault())
        .toInstant()
        .toEpochMilli()

private val DAY_FORMAT = DateTimeFormatter.ofPattern("d MMM yyyy")

private fun formatDay(millis: Long): String =
    DAY_FORMAT.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate())

/** Nothing in the inbox was received tomorrow. */
@OptIn(ExperimentalMaterial3Api::class)
private object PastOnly : SelectableDates {
    override fun isSelectableDate(utcTimeMillis: Long): Boolean =
        utcTimeMillis <= System.currentTimeMillis()

    override fun isSelectableYear(year: Int): Boolean = year <= LocalDate.now().year
}

package dev.kosha.feature.export

import android.content.Intent
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import androidx.hilt.navigation.compose.hiltViewModel
import dev.kosha.core.designsystem.component.KoshaCard
import dev.kosha.core.designsystem.component.KoshaChip
import dev.kosha.core.designsystem.token.KoshaColors
import dev.kosha.core.designsystem.token.KoshaSpacing
import dev.kosha.core.designsystem.token.KoshaType

@Composable
fun ExportScreen(
    onBack: () -> Unit,
    /** Put backup first — Settings links here from two different rows. */
    focusBackup: Boolean = false,
    viewModel: ExportViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsState()

    val pickFolderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri -> uri?.let(viewModel::rememberBackupFolder) }

    val restoreLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(viewModel::performRestore) }

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
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = null, tint = KoshaColors.OffWhiteMuted)
            }
            Text(
                text = stringResource(R.string.export_title),
                style = KoshaType.Title,
                color = KoshaColors.OffWhite,
            )
        }

        Column(
            Modifier.padding(horizontal = KoshaSpacing.screenPadding),
            verticalArrangement = Arrangement.spacedBy(KoshaSpacing.s),
        ) {
            if (focusBackup) {
                BackupSection(
                    state = state,
                    viewModel = viewModel,
                    onPickFolder = { pickFolderLauncher.launch(null) },
                    onRestoreFromFile = { restoreLauncher.launch(arrayOf("*/*")) },
                )
                Spacer(Modifier.height(KoshaSpacing.m))
            }
            PdfSection(state, viewModel)
            CsvSection(state, viewModel)

            state.shareUri?.let { uri ->
                KoshaCard(modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.export_done), style = KoshaType.Body, color = KoshaColors.OffWhite)
                    TextButton(onClick = {
                        val share = Intent(Intent.ACTION_SEND).apply {
                            type = state.shareMimeType
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(share, null))
                    }) {
                        Text(stringResource(R.string.export_share), color = KoshaColors.AccentTeal)
                    }
                }
            }

            if (!focusBackup) {
                Spacer(Modifier.height(KoshaSpacing.m))
                BackupSection(
                    state = state,
                    viewModel = viewModel,
                    onPickFolder = { pickFolderLauncher.launch(null) },
                    onRestoreFromFile = { restoreLauncher.launch(arrayOf("*/*")) },
                )
            }

            // A restore leaves the app holding a database handle that has been
            // closed and a file that has been swapped underneath it. Anything
            // touched before the process restarts throws, so this is a wall
            // rather than a hint.
            if (state.restartRequired) {
                KoshaCard(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = stringResource(R.string.backup_restart_title),
                        style = KoshaType.SectionHeader,
                        color = KoshaColors.Amber,
                    )
                    Text(
                        text = stringResource(R.string.backup_restart_body),
                        style = KoshaType.Body,
                        color = KoshaColors.OffWhiteMuted,
                    )
                    TextButton(onClick = { restartKosha(context) }) {
                        Text(
                            text = stringResource(R.string.backup_restart_action),
                            style = KoshaType.LabelStrong,
                            color = KoshaColors.AccentTealBright,
                        )
                    }
                }
            }

            state.message?.let { message ->
                Text(message, style = KoshaType.Body, color = KoshaColors.Amber)
            }
            Spacer(Modifier.height(KoshaSpacing.xxl))
        }
    }
}

/**
 * Backup, reduced to the two things it has to be: one button, and a visible
 * list of what that button has produced. Everything that used to gate it — a
 * passphrase, typed twice, at least eight characters — is now optional and
 * folded away, because those gates were what made the feature do nothing.
 */
@Composable
private fun BackupSection(
    state: ExportUiState,
    viewModel: ExportViewModel,
    onPickFolder: () -> Unit,
    onRestoreFromFile: () -> Unit,
) {
    var showAdvanced by remember { mutableStateOf(false) }

    KoshaCard(modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.backup_title), style = KoshaType.SectionHeader, color = KoshaColors.OffWhite)
        Text(
            text = stringResource(R.string.backup_body),
            style = KoshaType.Body,
            color = KoshaColors.OffWhiteMuted,
        )

        Spacer(Modifier.height(KoshaSpacing.xs))
        Text(
            text = if (state.backupFolderReady) {
                stringResource(R.string.backup_folder_is, state.backupFolderName.orEmpty())
            } else {
                stringResource(R.string.backup_folder_none)
            },
            style = KoshaType.Caption,
            color = if (state.backupFolderReady) KoshaColors.OffWhiteFaint else KoshaColors.Amber,
        )
        if (state.lastBackupAtMillis > 0) {
            Text(
                text = stringResource(
                    R.string.backup_last_taken,
                    STAMP_FORMAT.format(
                        Instant.ofEpochMilli(state.lastBackupAtMillis).atZone(ZoneId.systemDefault()),
                    ),
                ),
                style = KoshaType.Caption,
                color = KoshaColors.OffWhiteFaint,
            )
        }

        Spacer(Modifier.height(KoshaSpacing.s))
        Row(horizontalArrangement = Arrangement.spacedBy(KoshaSpacing.s)) {
            if (state.backupFolderReady) {
                TextButton(onClick = viewModel::backupNow, enabled = !state.busy) {
                    Text(stringResource(R.string.backup_now), color = KoshaColors.AccentTealBright)
                }
            } else {
                TextButton(onClick = onPickFolder, enabled = !state.busy) {
                    Text(stringResource(R.string.backup_choose_folder), color = KoshaColors.AccentTealBright)
                }
            }
            TextButton(onClick = onRestoreFromFile, enabled = !state.busy) {
                Text(stringResource(R.string.backup_restore_from_file), color = KoshaColors.OffWhiteMuted)
            }
        }

        if (state.backups.isNotEmpty()) {
            Spacer(Modifier.height(KoshaSpacing.s))
            Text(
                text = stringResource(R.string.backup_existing),
                style = KoshaType.Label,
                color = KoshaColors.OffWhiteFaint,
            )
            state.backups.forEach { entry ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = KoshaSpacing.xxs),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(entry.name, style = KoshaType.Body, color = KoshaColors.OffWhite)
                        Text(
                            text = stringResource(
                                R.string.backup_entry_meta,
                                STAMP_FORMAT.format(
                                    Instant.ofEpochMilli(entry.modifiedAtMillis)
                                        .atZone(ZoneId.systemDefault()),
                                ),
                                entry.sizeBytes / 1_000_000.0,
                            ),
                            style = KoshaType.Caption,
                            color = KoshaColors.OffWhiteFaint,
                        )
                    }
                    TextButton(onClick = { viewModel.performRestore(entry.uri) }, enabled = !state.busy) {
                        Text(stringResource(R.string.backup_restore), color = KoshaColors.AccentTeal)
                    }
                    TextButton(onClick = { viewModel.deleteBackup(entry.uri) }, enabled = !state.busy) {
                        Text(stringResource(R.string.backup_delete), color = KoshaColors.OffWhiteFaint)
                    }
                }
            }
        }

        Spacer(Modifier.height(KoshaSpacing.xs))
        TextButton(onClick = { showAdvanced = !showAdvanced }) {
            Text(
                text = stringResource(
                    if (showAdvanced) R.string.backup_advanced_hide else R.string.backup_advanced_show,
                ),
                color = KoshaColors.OffWhiteFaint,
            )
        }

        if (showAdvanced) {
            Text(
                text = stringResource(R.string.backup_encryption_note),
                style = KoshaType.Caption,
                color = KoshaColors.OffWhiteFaint,
            )
            Spacer(Modifier.height(KoshaSpacing.xs))
            TextField(
                value = state.passphrase,
                onValueChange = viewModel::setPassphrase,
                placeholder = {
                    Text(stringResource(R.string.backup_passphrase_optional), color = KoshaColors.OffWhiteFaint)
                },
                visualTransformation = PasswordVisualTransformation(),
                colors = backupFieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = stringResource(R.string.backup_write_it_down),
                style = KoshaType.Caption,
                color = KoshaColors.Amber,
            )

            Spacer(Modifier.height(KoshaSpacing.xs))
            KoshaChip(
                label = stringResource(R.string.backup_include_vault),
                selected = state.includeVault,
                onClick = viewModel::toggleIncludeVault,
                accent = KoshaColors.Amber,
            )
            if (state.includeVault) {
                Text(
                    text = stringResource(R.string.backup_vault_warning),
                    style = KoshaType.Caption,
                    color = KoshaColors.Amber,
                )
            }
            if (state.backupFolderReady) {
                TextButton(onClick = onPickFolder, enabled = !state.busy) {
                    Text(stringResource(R.string.backup_change_folder), color = KoshaColors.OffWhiteMuted)
                }
            }
        }
    }
}

private val STAMP_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm")

/**
 * Relaunches Kosha from scratch. Ending the process is the point, not a side
 * effect: the singleton database was closed during the restore and every
 * injected copy of it in memory is now unusable, so only a fresh process can
 * open the file that was just written.
 */
private fun restartKosha(context: android.content.Context) {
    val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    if (intent != null) context.startActivity(intent)
    Runtime.getRuntime().exit(0)
}

@Composable
private fun backupFieldColors() = TextFieldDefaults.colors(
    focusedContainerColor = KoshaColors.CharcoalRaised,
    unfocusedContainerColor = KoshaColors.CharcoalRaised,
    focusedTextColor = KoshaColors.OffWhite,
    unfocusedTextColor = KoshaColors.OffWhite,
)

/**
 * The statement, section by section.
 *
 * Everything here used to be fixed: one button producing the same three pages
 * whatever you wanted. Charts in particular are the reason people export a
 * statement rather than a spreadsheet, and there was no way to ask for one.
 */
@Composable
private fun PdfSection(state: ExportUiState, viewModel: ExportViewModel) {
    val options = state.pdfOptions
    KoshaCard(modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.export_pdf), style = KoshaType.SectionHeader, color = KoshaColors.OffWhite)
        Text(
            stringResource(R.string.export_pdf_sub),
            style = KoshaType.Caption,
            color = KoshaColors.OffWhiteFaint,
        )

        Spacer(Modifier.height(KoshaSpacing.xs))
        RangePicker(options.range) { viewModel.setPdfOptions(options.copy(range = it)) }

        Spacer(Modifier.height(KoshaSpacing.xs))
        Text(stringResource(R.string.export_include), style = KoshaType.Label, color = KoshaColors.OffWhiteFaint)
        OptionChips(
            listOf(
                Triple(R.string.export_opt_summary, options.summary) {
                    viewModel.setPdfOptions(options.copy(summary = !options.summary))
                },
                Triple(R.string.export_opt_pie, options.pieChart) {
                    viewModel.setPdfOptions(options.copy(pieChart = !options.pieChart))
                },
                Triple(R.string.export_opt_trend, options.trendChart) {
                    viewModel.setPdfOptions(options.copy(trendChart = !options.trendChart))
                },
                Triple(R.string.export_opt_categories, options.categoryTable) {
                    viewModel.setPdfOptions(options.copy(categoryTable = !options.categoryTable))
                },
                Triple(R.string.export_opt_month_column, options.monthColumn) {
                    viewModel.setPdfOptions(options.copy(monthColumn = !options.monthColumn))
                },
                Triple(R.string.export_opt_merchants, options.topMerchants) {
                    viewModel.setPdfOptions(options.copy(topMerchants = !options.topMerchants))
                },
                Triple(R.string.export_opt_recurring, options.recurring) {
                    viewModel.setPdfOptions(options.copy(recurring = !options.recurring))
                },
                Triple(R.string.export_opt_full_ledger, options.fullLedger) {
                    viewModel.setPdfOptions(options.copy(fullLedger = !options.fullLedger))
                },
            ),
        )
        if (options.fullLedger) {
            Text(
                stringResource(R.string.export_full_ledger_note),
                style = KoshaType.Caption,
                color = KoshaColors.OffWhiteFaint,
            )
        }

        TextButton(
            onClick = { viewModel.exportPdf() },
            enabled = !state.busy && options.hasAnySection,
        ) {
            Text(
                text = stringResource(R.string.export_pdf_action),
                style = KoshaType.LabelStrong,
                color = if (options.hasAnySection) KoshaColors.AccentTealBright else KoshaColors.OffWhiteFaint,
            )
        }
    }
}

@Composable
private fun CsvSection(state: ExportUiState, viewModel: ExportViewModel) {
    val options = state.csvOptions
    KoshaCard(modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.export_csv), style = KoshaType.SectionHeader, color = KoshaColors.OffWhite)
        Text(
            stringResource(R.string.export_csv_sub),
            style = KoshaType.Caption,
            color = KoshaColors.OffWhiteFaint,
        )

        Spacer(Modifier.height(KoshaSpacing.xs))
        RangePicker(options.range) { viewModel.setCsvOptions(options.copy(range = it)) }

        Spacer(Modifier.height(KoshaSpacing.xs))
        OptionChips(
            listOf(
                Triple(R.string.export_csv_split, options.splitAmountColumns) {
                    viewModel.setCsvOptions(options.copy(splitAmountColumns = !options.splitAmountColumns))
                },
                Triple(R.string.export_csv_running, options.includeRunningBalance) {
                    viewModel.setCsvOptions(options.copy(includeRunningBalance = !options.includeRunningBalance))
                },
                Triple(R.string.export_csv_notes, options.includeNotesAndTags) {
                    viewModel.setCsvOptions(options.copy(includeNotesAndTags = !options.includeNotesAndTags))
                },
                Triple(R.string.export_csv_transfers, options.includeTransfers) {
                    viewModel.setCsvOptions(options.copy(includeTransfers = !options.includeTransfers))
                },
                Triple(R.string.export_csv_pending, options.includePending) {
                    viewModel.setCsvOptions(options.copy(includePending = !options.includePending))
                },
            ),
        )
        Text(
            text = stringResource(
                if (options.includeTransfers) {
                    R.string.export_csv_transfers_on
                } else {
                    R.string.export_csv_transfers_off
                },
            ),
            style = KoshaType.Caption,
            color = KoshaColors.OffWhiteFaint,
        )

        TextButton(onClick = { viewModel.exportCsv() }, enabled = !state.busy) {
            Text(
                text = stringResource(R.string.export_csv_action),
                style = KoshaType.LabelStrong,
                color = KoshaColors.AccentTealBright,
            )
        }
    }
}

@Composable
private fun RangePicker(selected: ExportRange, onPick: (ExportRange) -> Unit) {
    Text(stringResource(R.string.export_range), style = KoshaType.Label, color = KoshaColors.OffWhiteFaint)
    OptionChips(
        ExportRange.entries.map { range ->
            Triple(range.labelRes(), range == selected) { onPick(range) }
        },
    )
}

/** Two per row: chip labels here are phrases, and one per row wastes the page. */
@Composable
private fun OptionChips(options: List<Triple<Int, Boolean, () -> Unit>>) {
    options.chunked(2).forEach { pair ->
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(KoshaSpacing.xs),
        ) {
            pair.forEach { (labelRes, selected, onClick) ->
                KoshaChip(
                    label = stringResource(labelRes),
                    selected = selected,
                    onClick = onClick,
                    accent = KoshaColors.AccentTeal,
                    modifier = Modifier.weight(1f),
                )
            }
            if (pair.size == 1) Spacer(Modifier.weight(1f))
        }
    }
}

private fun ExportRange.labelRes(): Int = when (this) {
    ExportRange.THIS_PERIOD -> R.string.export_range_period
    ExportRange.LAST_3_MONTHS -> R.string.export_range_3m
    ExportRange.LAST_12_MONTHS -> R.string.export_range_12m
    ExportRange.THIS_FINANCIAL_YEAR -> R.string.export_range_fy
    ExportRange.EVERYTHING -> R.string.export_range_all
}

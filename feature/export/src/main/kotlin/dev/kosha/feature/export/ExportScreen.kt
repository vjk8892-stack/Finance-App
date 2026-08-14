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
            KoshaCard(modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.export_csv), style = KoshaType.Body, color = KoshaColors.OffWhite)
                Text(
                    stringResource(R.string.export_csv_sub),
                    style = KoshaType.Caption,
                    color = KoshaColors.OffWhiteFaint,
                )
                TextButton(onClick = { viewModel.exportCsv() }) {
                    Text(stringResource(R.string.export_csv), color = KoshaColors.AccentTeal)
                }
            }

            KoshaCard(modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.export_pdf), style = KoshaType.Body, color = KoshaColors.OffWhite)
                Text(
                    stringResource(R.string.export_pdf_sub),
                    style = KoshaType.Caption,
                    color = KoshaColors.OffWhiteFaint,
                )
                TextButton(onClick = { viewModel.exportPdf() }) {
                    Text(stringResource(R.string.export_pdf), color = KoshaColors.AccentTeal)
                }
            }

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

            Spacer(Modifier.height(KoshaSpacing.m))
            BackupSection(
                state = state,
                viewModel = viewModel,
                onPickFolder = { pickFolderLauncher.launch(null) },
                onRestoreFromFile = { restoreLauncher.launch(arrayOf("*/*")) },
            )

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

@Composable
private fun backupFieldColors() = TextFieldDefaults.colors(
    focusedContainerColor = KoshaColors.CharcoalRaised,
    unfocusedContainerColor = KoshaColors.CharcoalRaised,
    focusedTextColor = KoshaColors.OffWhite,
    unfocusedTextColor = KoshaColors.OffWhite,
)

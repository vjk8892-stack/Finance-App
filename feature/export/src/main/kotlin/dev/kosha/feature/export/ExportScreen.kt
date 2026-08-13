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

    val createBackupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri -> uri?.let(viewModel::performBackup) }

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
                onCreate = { createBackupLauncher.launch("kosha-backup.${BackupManager.FILE_EXTENSION}") },
                onRestore = { restoreLauncher.launch(arrayOf("*/*")) },
            )

            state.message?.let { message ->
                Text(message, style = KoshaType.Body, color = KoshaColors.Amber)
            }
            Spacer(Modifier.height(KoshaSpacing.xxl))
        }
    }
}

@Composable
private fun BackupSection(
    state: ExportUiState,
    viewModel: ExportViewModel,
    onCreate: () -> Unit,
    onRestore: () -> Unit,
) {
    var confirm by remember { mutableStateOf("") }

    KoshaCard(modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.backup_title), style = KoshaType.Title, color = KoshaColors.OffWhite)
        Text(
            text = stringResource(R.string.backup_body),
            style = KoshaType.Body,
            color = KoshaColors.OffWhiteMuted,
        )
        Spacer(Modifier.height(KoshaSpacing.xs))

        TextField(
            value = state.passphrase,
            onValueChange = viewModel::setPassphrase,
            placeholder = { Text(stringResource(R.string.backup_passphrase), color = KoshaColors.OffWhiteFaint) },
            visualTransformation = PasswordVisualTransformation(),
            colors = backupFieldColors(),
            modifier = Modifier.fillMaxWidth(),
        )
        TextField(
            value = confirm,
            onValueChange = { confirm = it },
            placeholder = {
                Text(stringResource(R.string.backup_passphrase_confirm), color = KoshaColors.OffWhiteFaint)
            },
            visualTransformation = PasswordVisualTransformation(),
            colors = backupFieldColors(),
            modifier = Modifier.fillMaxWidth(),
        )
        if (confirm.isNotEmpty() && confirm != state.passphrase) {
            Text(
                text = stringResource(R.string.backup_passphrase_mismatch),
                style = KoshaType.Caption,
                color = KoshaColors.Amber,
            )
        }

        Spacer(Modifier.height(KoshaSpacing.xs))
        Text(
            text = stringResource(R.string.backup_write_it_down),
            style = KoshaType.Caption,
            color = KoshaColors.OffWhiteFaint,
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

        Spacer(Modifier.height(KoshaSpacing.xs))
        Row(horizontalArrangement = Arrangement.spacedBy(KoshaSpacing.s)) {
            TextButton(
                onClick = onCreate,
                enabled = state.passphrase.length >= 8 && confirm == state.passphrase && !state.busy,
            ) {
                Text(stringResource(R.string.backup_create), color = KoshaColors.AccentTeal)
            }
            TextButton(onClick = onRestore, enabled = state.passphrase.isNotEmpty() && !state.busy) {
                Text(stringResource(R.string.backup_restore), color = KoshaColors.OffWhiteMuted)
            }
        }
    }
}

@Composable
private fun backupFieldColors() = TextFieldDefaults.colors(
    focusedContainerColor = KoshaColors.CharcoalRaised,
    unfocusedContainerColor = KoshaColors.CharcoalRaised,
    focusedTextColor = KoshaColors.OffWhite,
    unfocusedTextColor = KoshaColors.OffWhite,
)

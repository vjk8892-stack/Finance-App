package dev.kosha.feature.vault

import android.view.WindowManager
import androidx.activity.compose.LocalActivity
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import dev.kosha.core.database.model.VaultEntryEntity
import dev.kosha.core.designsystem.component.KoshaCard
import dev.kosha.core.designsystem.component.KoshaChip
import dev.kosha.core.designsystem.theme.KoshaTheme
import dev.kosha.core.designsystem.token.KoshaColors
import dev.kosha.core.designsystem.token.KoshaMotion
import dev.kosha.core.designsystem.token.KoshaSpacing
import dev.kosha.core.designsystem.token.KoshaType
import kotlinx.coroutines.launch

/**
 * Vault (spec C6): FLAG_SECURE on, biometric BEFORE any content renders,
 * darker skin, per-field reveal with a 20s countdown, copy with a 30s
 * clipboard wipe.
 */
@Composable
fun VaultScreen(viewModel: VaultViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val state by viewModel.state.collectAsState()
    val entries by viewModel.entries.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var showEditor by remember { mutableStateOf(false) }
    val copiedLabel = stringResource(R.string.vault_copied)

    // Screenshot blocking while the vault is on screen (spec B4).
    DisposableEffect(activity) {
        activity?.window?.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE,
        )
        onDispose {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            viewModel.lock()
        }
    }

    val promptTitle = stringResource(R.string.vault_prompt_title)
    val promptSubtitle = stringResource(R.string.vault_prompt_subtitle)
    fun authenticate(onSuccess: () -> Unit) {
        val host = activity as? FragmentActivity ?: return
        val prompt = BiometricPrompt(
            host,
            ContextCompat.getMainExecutor(host),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    onSuccess()
                }
            },
        )
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle(promptTitle)
                .setSubtitle(promptSubtitle)
                .setAllowedAuthenticators(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)
                .build(),
        )
    }

    KoshaTheme(vaultSkin = true) {
        // Lock-forming transition: content fades in only once unlocked.
        val contentAlpha by animateFloatAsState(
            targetValue = if (state.unlocked) 1f else 0f,
            animationSpec = androidx.compose.animation.core.tween(KoshaMotion.VaultTransitionMs),
            label = "vaultReveal",
        )

        Box(
            Modifier
                .fillMaxSize()
                .padding(horizontal = KoshaSpacing.screenPadding),
        ) {
            if (!state.unlocked) {
                LockedGate(onUnlock = { authenticate(viewModel::onUnlocked) })
            } else {
                Column(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer { alpha = contentAlpha },
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = KoshaSpacing.s),
                    ) {
                        Text(
                            text = stringResource(R.string.vault_title),
                            style = KoshaType.Title,
                            color = KoshaColors.OffWhite,
                            modifier = Modifier.weight(1f),
                        )
                        KoshaChip(
                            label = stringResource(R.string.vault_add),
                            onClick = { showEditor = true },
                        )
                    }

                    if (state.keyInvalidated) {
                        Text(
                            text = stringResource(R.string.vault_key_invalidated),
                            style = KoshaType.Body,
                            color = KoshaColors.Amber,
                        )
                        Spacer(Modifier.height(KoshaSpacing.s))
                    }

                    if (entries.isEmpty()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                text = stringResource(R.string.vault_empty),
                                style = KoshaType.InsightSerif,
                                color = KoshaColors.OffWhiteMuted,
                            )
                        }
                    } else {
                        LazyColumn(
                            contentPadding = PaddingValues(vertical = KoshaSpacing.xs),
                            verticalArrangement = Arrangement.spacedBy(KoshaSpacing.s),
                        ) {
                            items(entries.size) { index ->
                                VaultEntryCard(
                                    entry = entries[index],
                                    revealed = state.revealed,
                                    viewModel = viewModel,
                                    onReveal = { fieldName ->
                                        authenticate { viewModel.reveal(entries[index], fieldName) }
                                    },
                                    onCopy = { value ->
                                        ClipboardClearWorker.copySensitive(context, value)
                                        scope.launch { snackbar.showSnackbar(copiedLabel) }
                                    },
                                    onDelete = { viewModel.delete(entries[index]) },
                                )
                            }
                            item {
                                Spacer(Modifier.height(KoshaSpacing.m))
                                Text(
                                    text = stringResource(R.string.vault_export_excluded),
                                    style = KoshaType.Caption,
                                    color = KoshaColors.OffWhiteFaint,
                                )
                                Text(
                                    text = stringResource(R.string.vault_key_warning),
                                    style = KoshaType.Caption,
                                    color = KoshaColors.OffWhiteFaint,
                                )
                                Spacer(Modifier.height(KoshaSpacing.xxl))
                            }
                        }
                    }
                }
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
        }
    }

    if (showEditor) {
        VaultEditorSheet(
            onSave = { label, kind, fields ->
                viewModel.addEntry(label, kind, fields)
                showEditor = false
            },
            onDismiss = { showEditor = false },
        )
    }
}

@Composable
private fun LockedGate(onUnlock: () -> Unit) {
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.vault_locked_title),
            style = KoshaType.Title,
            color = KoshaColors.OffWhite,
        )
        Spacer(Modifier.height(KoshaSpacing.xs))
        Text(
            text = stringResource(R.string.vault_locked_body),
            style = KoshaType.InsightSerif,
            color = KoshaColors.OffWhiteMuted,
        )
        Spacer(Modifier.height(KoshaSpacing.m))
        TextButton(onClick = onUnlock) {
            Text(stringResource(R.string.vault_unlock), color = KoshaColors.AccentTeal)
        }
    }
}

@Composable
private fun VaultEntryCard(
    entry: VaultEntryEntity,
    revealed: RevealedField?,
    viewModel: VaultViewModel,
    onReveal: (String) -> Unit,
    onCopy: (String) -> Unit,
    onDelete: () -> Unit,
) {
    var fieldNames by remember(entry.id) { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(entry.id, entry.updatedAtMillis) {
        fieldNames = viewModel.fieldNames(entry)
    }

    KoshaCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = entry.label,
                style = KoshaType.Body,
                color = KoshaColors.OffWhite,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onDelete) {
                Text(
                    text = stringResource(R.string.vault_delete),
                    style = KoshaType.Caption,
                    color = KoshaColors.OffWhiteFaint,
                )
            }
        }
        fieldNames.forEach { fieldName ->
            val isRevealed = revealed?.entryId == entry.id && revealed.fieldName == fieldName
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text(fieldName, style = KoshaType.Caption, color = KoshaColors.OffWhiteFaint)
                    Text(
                        // Masked by default, everywhere (spec B4).
                        text = if (isRevealed) revealed.value else MASK,
                        style = KoshaType.AmountBody,
                        color = KoshaColors.OffWhite,
                    )
                }
                if (isRevealed) {
                    Text(
                        text = stringResource(R.string.vault_remask_countdown, revealed.secondsRemaining),
                        style = KoshaType.Caption,
                        color = KoshaColors.Amber,
                    )
                    Spacer(Modifier.width(KoshaSpacing.xs))
                    TextButton(onClick = { onCopy(revealed.value) }) {
                        Text(stringResource(R.string.vault_copy), color = KoshaColors.AccentTeal)
                    }
                    TextButton(onClick = viewModel::remask) {
                        Text(stringResource(R.string.vault_remask), color = KoshaColors.OffWhiteMuted)
                    }
                } else {
                    TextButton(onClick = { onReveal(fieldName) }) {
                        Text(stringResource(R.string.vault_reveal), color = KoshaColors.AccentTeal)
                    }
                }
            }
        }
    }
}

@Composable
private fun VaultEditorSheet(
    onSave: (label: String, kind: String, fields: Map<String, String>) -> Unit,
    onDismiss: () -> Unit,
) {
    var label by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf("account") }
    var fields by remember { mutableStateOf(listOf("" to "")) }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = KoshaColors.VaultRaised) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(KoshaSpacing.m),
            verticalArrangement = Arrangement.spacedBy(KoshaSpacing.s),
        ) {
            Text(stringResource(R.string.vault_add), style = KoshaType.Title, color = KoshaColors.OffWhite)
            TextField(
                value = label,
                onValueChange = { label = it },
                placeholder = { Text(stringResource(R.string.vault_label), color = KoshaColors.OffWhiteFaint) },
                colors = vaultFieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(KoshaSpacing.xs)) {
                listOf(
                    "account" to stringResource(R.string.vault_kind_account),
                    "card" to stringResource(R.string.vault_kind_card),
                    "custom" to stringResource(R.string.vault_kind_custom),
                ).forEach { (value, text) ->
                    KoshaChip(label = text, selected = kind == value, onClick = { kind = value })
                }
            }
            fields.forEachIndexed { index, (name, value) ->
                TextField(
                    value = name,
                    onValueChange = { newName ->
                        fields = fields.toMutableList().also { it[index] = it[index].copy(first = newName) }
                    },
                    placeholder = { Text(stringResource(R.string.vault_field_name), color = KoshaColors.OffWhiteFaint) },
                    colors = vaultFieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
                TextField(
                    value = value,
                    onValueChange = { newValue ->
                        fields = fields.toMutableList().also { it[index] = it[index].copy(second = newValue) }
                    },
                    placeholder = { Text(stringResource(R.string.vault_field_value), color = KoshaColors.OffWhiteFaint) },
                    colors = vaultFieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            TextButton(onClick = { fields = fields + ("" to "") }) {
                Text(stringResource(R.string.vault_add_field), color = KoshaColors.OffWhiteMuted)
            }
            TextButton(
                onClick = {
                    onSave(
                        label.trim(),
                        kind,
                        fields.filter { it.first.isNotBlank() }.toMap(),
                    )
                },
                enabled = label.isNotBlank() && fields.any { it.first.isNotBlank() },
            ) {
                Text(stringResource(R.string.vault_save), color = KoshaColors.AccentTeal)
            }
            Spacer(Modifier.height(KoshaSpacing.l))
        }
    }
}

@Composable
private fun vaultFieldColors() = TextFieldDefaults.colors(
    focusedContainerColor = KoshaColors.VaultBackground,
    unfocusedContainerColor = KoshaColors.VaultBackground,
    focusedTextColor = KoshaColors.OffWhite,
    unfocusedTextColor = KoshaColors.OffWhite,
)

private const val MASK = "•••• ••••"

package dev.kosha.feature.ledger.accounts

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.kosha.core.common.Money
import dev.kosha.core.database.model.AccountEntity
import dev.kosha.core.database.model.AccountType
import dev.kosha.core.designsystem.component.AmountText
import dev.kosha.core.designsystem.component.KoshaCard
import dev.kosha.core.designsystem.component.KoshaChip
import dev.kosha.core.designsystem.token.KoshaColors
import dev.kosha.core.designsystem.token.KoshaSpacing
import dev.kosha.core.designsystem.token.KoshaType
import dev.kosha.feature.ledger.R

@Composable
fun AccountsScreen(
    onBack: () -> Unit,
    viewModel: AccountsViewModel = hiltViewModel(),
) {
    val accounts by viewModel.accounts.collectAsState()
    var showEditor by remember { mutableStateOf(false) }

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
                text = stringResource(R.string.accounts_title),
                style = KoshaType.Title,
                color = KoshaColors.OffWhite,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { showEditor = true }) {
                Icon(
                    Icons.Outlined.Add,
                    contentDescription = stringResource(R.string.accounts_add),
                    tint = KoshaColors.OffWhite,
                )
            }
        }

        if (accounts.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(R.string.accounts_empty),
                    style = KoshaType.InsightSerif,
                    color = KoshaColors.OffWhiteMuted,
                    modifier = Modifier.padding(KoshaSpacing.xl),
                )
            }
        } else {
            LazyColumn(
                contentPadding = androidx.compose.foundation.layout.PaddingValues(KoshaSpacing.screenPadding),
                verticalArrangement = Arrangement.spacedBy(KoshaSpacing.s),
            ) {
                items(accounts.size) { i ->
                    AccountCard(accounts[i])
                }
            }
        }
    }

    if (showEditor) {
        AccountEditorSheet(
            onSave = { name, type, last4, opening ->
                viewModel.create(name, type, last4, opening)
                showEditor = false
            },
            onDismiss = { showEditor = false },
        )
    }
}

@Composable
private fun AccountCard(account: AccountEntity) {
    KoshaCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(KoshaColors.accountColor(account.colorToken)),
            )
            Spacer(Modifier.width(KoshaSpacing.s))
            Column(Modifier.weight(1f)) {
                Text(
                    text = account.name + (account.last4?.let { " ·· $it" } ?: ""),
                    style = KoshaType.Body,
                    color = KoshaColors.OffWhite,
                )
                Text(
                    text = account.type.name.lowercase().replaceFirstChar { it.uppercase() },
                    style = KoshaType.Caption,
                    color = KoshaColors.OffWhiteFaint,
                )
            }
            AmountText(
                amount = Money(account.currentBalancePaise),
                style = KoshaType.AmountBody,
            )
        }
    }
}

@Composable
private fun AccountEditorSheet(
    onSave: (name: String, type: AccountType, last4: String, openingRupees: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var type by remember { mutableStateOf(AccountType.BANK) }
    var last4 by remember { mutableStateOf("") }
    var opening by remember { mutableStateOf("") }

    val typeLabels = listOf(
        AccountType.BANK to stringResource(R.string.accounts_type_bank),
        AccountType.CASH to stringResource(R.string.accounts_type_cash),
        AccountType.CARD to stringResource(R.string.accounts_type_card),
        AccountType.WALLET to stringResource(R.string.accounts_type_wallet),
        AccountType.MEALCARD to stringResource(R.string.accounts_type_mealcard),
    )

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = KoshaColors.CharcoalOverlay) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(KoshaSpacing.m),
            verticalArrangement = Arrangement.spacedBy(KoshaSpacing.s),
        ) {
            Text(
                text = stringResource(R.string.accounts_add),
                style = KoshaType.Title,
                color = KoshaColors.OffWhite,
            )
            TextField(
                value = name,
                onValueChange = { name = it },
                placeholder = { Text(stringResource(R.string.accounts_name), color = KoshaColors.OffWhiteFaint) },
                colors = editorFieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(KoshaSpacing.xs)) {
                typeLabels.take(3).forEach { (t, label) ->
                    KoshaChip(label = label, selected = type == t, onClick = { type = t })
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(KoshaSpacing.xs)) {
                typeLabels.drop(3).forEach { (t, label) ->
                    KoshaChip(label = label, selected = type == t, onClick = { type = t })
                }
            }
            TextField(
                value = last4,
                onValueChange = { if (it.length <= 4 && it.all(Char::isDigit)) last4 = it },
                placeholder = { Text(stringResource(R.string.accounts_last4), color = KoshaColors.OffWhiteFaint) },
                colors = editorFieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )
            TextField(
                value = opening,
                onValueChange = { text ->
                    if (text.all { it.isDigit() || it == '.' }) opening = text
                },
                placeholder = { Text(stringResource(R.string.accounts_opening_balance), color = KoshaColors.OffWhiteFaint) },
                colors = editorFieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )
            TextButton(
                onClick = { if (name.isNotBlank()) onSave(name.trim(), type, last4, opening) },
                enabled = name.isNotBlank(),
            ) {
                Text(stringResource(R.string.accounts_save), color = KoshaColors.AccentTeal)
            }
            Spacer(Modifier.height(KoshaSpacing.l))
        }
    }
}

@Composable
private fun editorFieldColors() = TextFieldDefaults.colors(
    focusedContainerColor = KoshaColors.CharcoalRaised,
    unfocusedContainerColor = KoshaColors.CharcoalRaised,
    focusedTextColor = KoshaColors.OffWhite,
    unfocusedTextColor = KoshaColors.OffWhite,
)

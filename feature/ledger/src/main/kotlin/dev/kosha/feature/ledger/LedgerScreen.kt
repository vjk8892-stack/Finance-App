package dev.kosha.feature.ledger

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
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
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
import dev.kosha.core.database.dao.LedgerRow
import dev.kosha.core.database.model.CategoryEntity
import dev.kosha.core.database.model.TxnSource
import dev.kosha.core.database.model.TxnType
import dev.kosha.core.designsystem.component.AmountText
import dev.kosha.core.designsystem.component.KoshaChip
import dev.kosha.core.designsystem.component.KoshaIcons
import dev.kosha.core.designsystem.token.KoshaColors
import dev.kosha.core.designsystem.token.KoshaSpacing
import dev.kosha.core.designsystem.token.KoshaType

/** Source glyphs, spec G12: ⌁ SMS · ▣ photo · ✎ manual · ⟳ recurring. */
internal fun TxnSource.glyph(): String = when (this) {
    TxnSource.SMS -> "⌁"
    TxnSource.OCR -> "▣"
    TxnSource.MANUAL -> "✎"
    TxnSource.RECURRING -> "⟳"
}

@Composable
fun LedgerScreen(
    onOpenAccounts: () -> Unit,
    onOpenReview: () -> Unit = {},
    viewModel: LedgerViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    var recategorizing by remember { mutableStateOf<LedgerRow?>(null) }
    var acting by remember { mutableStateOf<LedgerRow?>(null) }

    Column(Modifier.fillMaxSize()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = KoshaSpacing.screenPadding, vertical = KoshaSpacing.s),
        ) {
            Text(
                text = stringResource(R.string.ledger_title),
                style = KoshaType.Title,
                color = KoshaColors.OffWhite,
                modifier = Modifier.weight(1f),
            )
            if (state.reviewCount > 0) {
                KoshaChip(
                    label = stringResource(R.string.ledger_review_chip, state.reviewCount),
                    onClick = onOpenReview,
                    accent = KoshaColors.Amber,
                )
            }
            IconButton(onClick = onOpenAccounts) {
                Icon(
                    Icons.Outlined.AccountBalanceWallet,
                    contentDescription = stringResource(R.string.ledger_accounts),
                    tint = KoshaColors.OffWhiteMuted,
                )
            }
        }

        if (state.isEmpty) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(R.string.ledger_empty),
                    style = KoshaType.InsightSerif,
                    color = KoshaColors.OffWhiteMuted,
                    modifier = Modifier.padding(KoshaSpacing.xl),
                )
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                state.months.forEach { month ->
                    item(key = "month-${month.monthLabel}") {
                        MonthHeader(month.monthLabel, month.totalSpend)
                    }
                    month.days.forEach { day ->
                        item(key = "day-${day.date}") {
                            Text(
                                text = day.label,
                                style = KoshaType.Label,
                                color = KoshaColors.OffWhiteFaint,
                                modifier = Modifier.padding(
                                    horizontal = KoshaSpacing.screenPadding,
                                    vertical = KoshaSpacing.xs,
                                ),
                            )
                        }
                        items(day.rows.size) { i ->
                            val row = day.rows[i]
                            TransactionRow(
                                row = row,
                                onRecategorize = { recategorizing = row },
                                onActions = { acting = row },
                            )
                        }
                    }
                }
                item { Spacer(Modifier.height(KoshaSpacing.xxl)) }
            }
        }
    }

    recategorizing?.let { row ->
        RecategorizeSheet(
            categories = state.categories.filter { !it.isSystem },
            onPick = { category ->
                viewModel.recategorize(row.txn.id, category.id)
                recategorizing = null
            },
            onDismiss = { recategorizing = null },
        )
    }

    acting?.let { row ->
        ActionsSheet(
            row = row,
            onDelete = {
                viewModel.delete(row.txn.id)
                acting = null
            },
            onDismiss = { acting = null },
        )
    }
}

@Composable
private fun MonthHeader(label: String, totalSpend: Money) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(KoshaColors.Charcoal)
            .padding(horizontal = KoshaSpacing.screenPadding, vertical = KoshaSpacing.s),
    ) {
        Text(
            text = label,
            style = KoshaType.Title,
            color = KoshaColors.OffWhite,
            modifier = Modifier.weight(1f),
        )
        AmountText(amount = totalSpend, style = KoshaType.AmountSmall, color = KoshaColors.OffWhiteMuted, withPaise = false)
    }
}

@Composable
private fun TransactionRow(
    row: LedgerRow,
    onRecategorize: () -> Unit,
    onActions: () -> Unit,
) {
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            when (value) {
                SwipeToDismissBoxValue.StartToEnd -> onRecategorize()
                SwipeToDismissBoxValue.EndToStart -> onActions()
                SwipeToDismissBoxValue.Settled -> Unit
            }
            false // never actually dismiss the row
        },
    )
    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxSize()
                    .background(KoshaColors.CharcoalOverlay)
                    .padding(horizontal = KoshaSpacing.screenPadding),
            ) {
                Text(
                    text = stringResource(R.string.ledger_recategorize),
                    style = KoshaType.Label,
                    color = KoshaColors.OffWhiteMuted,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = stringResource(R.string.ledger_edit),
                    style = KoshaType.Label,
                    color = KoshaColors.OffWhiteMuted,
                )
            }
        },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .background(KoshaColors.Charcoal)
                .padding(horizontal = KoshaSpacing.screenPadding, vertical = KoshaSpacing.s),
        ) {
            // Account color tick (spec G3: ledger row left-edge tick)
            Box(
                Modifier
                    .size(width = 3.dp, height = 32.dp)
                    .background(KoshaColors.accountColor(row.accountColorToken)),
            )
            Spacer(Modifier.width(KoshaSpacing.s))
            Icon(
                imageVector = KoshaIcons.forToken(row.categoryIcon),
                contentDescription = row.categoryName,
                tint = KoshaColors.OffWhiteMuted,
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(KoshaColors.CharcoalRaised)
                    .padding(7.dp),
            )
            Spacer(Modifier.width(KoshaSpacing.s))
            Column(Modifier.weight(1f)) {
                Text(
                    text = row.txn.merchantRaw ?: row.categoryName ?: "—",
                    style = KoshaType.Body,
                    color = KoshaColors.OffWhite,
                    maxLines = 1,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = row.txn.source.glyph(),
                        style = KoshaType.Caption,
                        color = KoshaColors.OffWhiteFaint,
                    )
                    Spacer(Modifier.width(KoshaSpacing.xxs))
                    Text(
                        text = row.categoryName ?: "",
                        style = KoshaType.Caption,
                        color = KoshaColors.OffWhiteFaint,
                        maxLines = 1,
                    )
                }
            }
            AmountText(
                amount = if (row.txn.type == TxnType.DEBIT) Money(-row.txn.amountPaise) else Money(row.txn.amountPaise),
                style = KoshaType.AmountBody,
                color = if (row.txn.type == TxnType.CREDIT) KoshaColors.AccentTeal else KoshaColors.OffWhite,
                signed = row.txn.type == TxnType.CREDIT,
            )
        }
    }
}

@Composable
private fun RecategorizeSheet(
    categories: List<CategoryEntity>,
    onPick: (CategoryEntity) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = KoshaColors.CharcoalOverlay,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(KoshaSpacing.m),
            verticalArrangement = Arrangement.spacedBy(KoshaSpacing.xs),
        ) {
            Text(
                text = stringResource(R.string.ledger_recategorize),
                style = KoshaType.Title,
                color = KoshaColors.OffWhite,
            )
            Spacer(Modifier.height(KoshaSpacing.xs))
            CategoryFlowGrid(categories = categories, onPick = onPick)
            Spacer(Modifier.height(KoshaSpacing.l))
        }
    }
}

@Composable
internal fun CategoryFlowGrid(
    categories: List<CategoryEntity>,
    onPick: (CategoryEntity) -> Unit,
    selectedId: Long? = null,
) {
    categories.chunked(2).forEach { rowCats ->
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(KoshaSpacing.xs),
        ) {
            rowCats.forEach { cat ->
                KoshaChip(
                    label = cat.name,
                    selected = cat.id == selectedId,
                    onClick = { onPick(cat) },
                    leading = {
                        Icon(
                            KoshaIcons.forToken(cat.icon),
                            contentDescription = null,
                            tint = KoshaColors.OffWhiteMuted,
                            modifier = Modifier.size(16.dp),
                        )
                    },
                    modifier = Modifier.weight(1f),
                )
            }
            if (rowCats.size == 1) Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
private fun ActionsSheet(
    row: LedgerRow,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    var confirmingDelete by remember { mutableStateOf(false) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = KoshaColors.CharcoalOverlay,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(KoshaSpacing.m),
            verticalArrangement = Arrangement.spacedBy(KoshaSpacing.s),
        ) {
            Text(
                text = row.txn.merchantRaw ?: row.categoryName ?: "—",
                style = KoshaType.Title,
                color = KoshaColors.OffWhite,
            )
            AmountText(
                amount = Money(row.txn.amountPaise),
                style = KoshaType.AmountLarge,
            )
            if (!confirmingDelete) {
                TextButton(onClick = { confirmingDelete = true }) {
                    Text(stringResource(R.string.ledger_delete), color = KoshaColors.Amber)
                }
            } else {
                Text(
                    text = stringResource(R.string.ledger_delete_confirm_body),
                    style = KoshaType.Body,
                    color = KoshaColors.OffWhiteMuted,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(KoshaSpacing.s)) {
                    TextButton(onClick = onDelete) {
                        Text(stringResource(R.string.ledger_delete), color = KoshaColors.Amber)
                    }
                    TextButton(onClick = { confirmingDelete = false }) {
                        Text(stringResource(R.string.ledger_cancel), color = KoshaColors.OffWhiteMuted)
                    }
                }
            }
            Spacer(Modifier.height(KoshaSpacing.l))
        }
    }
}

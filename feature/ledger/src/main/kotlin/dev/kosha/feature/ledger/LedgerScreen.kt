package dev.kosha.feature.ledger

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.Sync
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
import androidx.compose.runtime.rememberUpdatedState
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
import dev.kosha.feature.ledger.query.QueryAnswerCard
import dev.kosha.feature.ledger.query.QuerySearchBar

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
    onScanSms: () -> Unit = {},
    onOpenBudgets: () -> Unit = {},
    /** Set when a chart slice sent the user here. */
    incomingCategory: String? = null,
    incomingMonth: String? = null,
    incomingFrom: String? = null,
    incomingTo: String? = null,
    viewModel: LedgerViewModel = hiltViewModel(),
) {
    androidx.compose.runtime.LaunchedEffect(incomingCategory, incomingMonth, incomingFrom) {
        viewModel.applyIncomingFilter(incomingCategory, incomingMonth, incomingFrom, incomingTo)
    }
    val state by viewModel.uiState.collectAsState()
    val queryState by viewModel.query.collectAsState()
    val detail by viewModel.detail.collectAsState()
    var recategorizing by remember { mutableStateOf<LedgerRow?>(null) }
    var acting by remember { mutableStateOf<LedgerRow?>(null) }
    var filtersOpen by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<LedgerRow?>(null) }
    val retroResult by viewModel.retroResult.collectAsState()

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
            // Noticed a missing entry? Re-scan the inbox from here.
            IconButton(onClick = onScanSms) {
                Icon(
                    Icons.Outlined.Sync,
                    contentDescription = stringResource(R.string.ledger_scan_sms),
                    tint = KoshaColors.OffWhiteMuted,
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

        // Search bar doubles as the query assistant (spec C3).
        QuerySearchBar(
            text = queryState.text,
            onTextChange = viewModel::onQueryTextChange,
            onSubmit = viewModel::submitQuery,
            modifier = Modifier.padding(horizontal = KoshaSpacing.screenPadding),
        )
        if (queryState.isFiltering) {
            Spacer(Modifier.height(KoshaSpacing.xs))
            QueryAnswerCard(
                state = queryState,
                onDismiss = viewModel::clearQuery,
                onOpenBuilder = viewModel::openBuilder,
                modifier = Modifier.padding(horizontal = KoshaSpacing.screenPadding),
            )
        }
        Spacer(Modifier.height(KoshaSpacing.xs))

        // Direction filter, plus budgets within reach of the numbers they
        // are about.
        Row(
            horizontalArrangement = Arrangement.spacedBy(KoshaSpacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = KoshaSpacing.screenPadding),
        ) {
            LedgerFilter.entries.forEach { option ->
                KoshaChip(
                    label = stringResource(option.labelRes()),
                    selected = state.filters.direction == option,
                    onClick = { viewModel.setDirection(option) },
                )
            }
            // Month / account / category live behind this so the list itself
            // keeps the screen.
            KoshaChip(
                label = if (state.filters.activeCount > 0) {
                    stringResource(R.string.ledger_filters_active, state.filters.activeCount)
                } else {
                    stringResource(R.string.ledger_filters_title)
                },
                selected = state.filters.activeCount > 0,
                onClick = { filtersOpen = true },
            )
            KoshaChip(
                label = stringResource(R.string.ledger_budgets),
                onClick = onOpenBudgets,
                accent = KoshaColors.AccentTeal,
            )
            // Categorization improved after these rows were captured, and it
            // only runs at capture time — so history stays Uncategorized until
            // something re-applies the rules to it.
            KoshaChip(
                label = stringResource(R.string.ledger_categorize_existing),
                onClick = viewModel::categorizeExisting,
            )
        }
        Spacer(Modifier.height(KoshaSpacing.xs))

        retroResult?.let { result ->
            Text(
                text = if (result.categorized > 0) {
                    stringResource(R.string.ledger_categorize_done, result.categorized)
                } else {
                    stringResource(R.string.ledger_categorize_none)
                },
                style = KoshaType.Caption,
                color = KoshaColors.AccentTeal,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = viewModel::clearRetroResult)
                    .padding(horizontal = KoshaSpacing.screenPadding, vertical = KoshaSpacing.xs),
            )
        }

        if (queryState.isFiltering) {
            // Query results replace the grouped ledger while a query is live.
            LazyColumn(Modifier.fillMaxSize()) {
                items(
                    count = queryState.rows.size,
                    key = { i -> queryState.rows[i].txn.id },
                ) { i ->
                    val row = queryState.rows[i]
                    TransactionRow(
                        row = row,
                        onOpen = { viewModel.openDetail(row) },
                        onRecategorize = { recategorizing = row },
                        onActions = { acting = row },
                    )
                }
            }
        } else if (state.isEmpty) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = if (state.hiddenByFilter) {
                        stringResource(R.string.ledger_empty_filtered)
                    } else {
                        stringResource(R.string.ledger_empty)
                    },
                    style = KoshaType.InsightSerif,
                    color = KoshaColors.OffWhiteMuted,
                    modifier = Modifier.padding(KoshaSpacing.xl),
                )
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                state.months.forEach { month ->
                    item(key = "month-${month.monthLabel}") {
                        MonthHeader(month.monthLabel, month.total, month.excludedTransfers)
                    }
                    month.days.forEach { day ->
                        item(key = "day-${day.date}") {
                            DayHeader(day.label, day.total)
                        }
                        items(
                            count = day.rows.size,
                            key = { i -> day.rows[i].txn.id },
                        ) { i ->
                            val row = day.rows[i]
                            TransactionRow(
                                row = row,
                                onOpen = { viewModel.openDetail(row) },
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
            merchantName = row.txn.merchantRaw,
            onPick = { category ->
                viewModel.recategorize(row.txn.id, category.id)
                recategorizing = null
            },
            onPickForMerchant = { category ->
                viewModel.recategorizeMerchant(row, category.id)
                recategorizing = null
            },
            onDismiss = { recategorizing = null },
        )
    }

    editing?.let { row ->
        EditTransactionSheet(
            row = row,
            categories = state.categories.filter { !it.isSystem },
            transfersCategoryId = state.transfersCategoryId,
            onSave = { edited ->
                viewModel.saveEdit(edited)
                editing = null
            },
            onDismiss = { editing = null },
        )
    }

    if (filtersOpen) {
        LedgerFilterSheet(
            filters = state.filters,
            accounts = state.accounts,
            months = state.availableMonths,
            categories = state.categories.filter { !it.isSystem },
            onSetAccount = viewModel::setAccount,
            onSetMonth = viewModel::setMonth,
            onSetCategory = viewModel::setCategory,
            onClearAll = {
                viewModel.clearFilters()
                filtersOpen = false
            },
            onDismiss = { filtersOpen = false },
        )
    }

    detail?.let { open ->
        TransactionDetailSheet(
            detail = open,
            onDismiss = viewModel::closeDetail,
            onRecategorize = {
                recategorizing = open.row
                viewModel.closeDetail()
            },
            onEdit = {
                editing = open.row
                viewModel.closeDetail()
            },
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

/** Filter chip label. */
private fun LedgerFilter.labelRes(): Int = when (this) {
    LedgerFilter.ALL -> R.string.ledger_filter_all
    LedgerFilter.OUT -> R.string.ledger_filter_out
    LedgerFilter.IN -> R.string.ledger_filter_in
}

/**
 * The date is how you navigate a ledger, so it has to be readable. It was
 * drawn in `OffWhiteFaint` — the hint/disabled tone — which made the spine of
 * the list the dimmest thing on screen. Today and Yesterday get full contrast,
 * older days one step down, and a rule separates each day from the last.
 */
@Composable
private fun DayHeader(label: String, total: Money) {
    Column(Modifier.fillMaxWidth()) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(KoshaColors.Outline),
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = KoshaSpacing.screenPadding, vertical = KoshaSpacing.s),
        ) {
            // A teal tick and full-contrast text. Dimming older days was still
            // dimming the thing you scan the list by, so every date now reads
            // at full strength.
            Box(
                Modifier
                    .size(width = 3.dp, height = 14.dp)
                    .background(KoshaColors.AccentTeal),
            )
            Spacer(Modifier.width(KoshaSpacing.xs))
            Text(
                text = label,
                style = KoshaType.SectionHeader,
                color = KoshaColors.OffWhite,
                modifier = Modifier.weight(1f),
            )
            AmountText(
                amount = total,
                style = KoshaType.AmountSmall,
                color = KoshaColors.OffWhiteMuted,
                withPaise = false,
            )
        }
    }
}

@Composable
private fun MonthHeader(label: String, total: Money, excludedTransfers: Money) {
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
        Column(horizontalAlignment = androidx.compose.ui.Alignment.End) {
            AmountText(
                amount = total,
                style = KoshaType.AmountSmall,
                color = if (total.isNegative) KoshaColors.OffWhiteMuted else KoshaColors.AccentTeal,
                withPaise = false,
                signed = !total.isNegative,
            )
            // Say what was left out, so this never silently disagrees with the
            // savings gap the way it used to.
            if (excludedTransfers.paise > 0) {
                Text(
                    text = stringResource(
                        R.string.ledger_excludes_transfers,
                        excludedTransfers.format(withPaise = false),
                    ),
                    style = KoshaType.Caption,
                    color = KoshaColors.OffWhiteFaint,
                )
            }
        }
    }
}

@Composable
private fun TransactionRow(
    row: LedgerRow,
    onOpen: () -> Unit,
    onRecategorize: () -> Unit,
    onActions: () -> Unit,
) {
    // `rememberSwipeToDismissBoxState` keeps the callback it was FIRST given.
    // A LazyColumn reuses a row's slot for whatever scrolls into it, so the
    // retained callback went on referring to the row that used to be there —
    // swiping one transaction opened the action sheet for a different one,
    // with Delete in it. `rememberUpdatedState` makes the retained lambda read
    // the current handlers instead of the ones captured at first composition;
    // the stable item keys at the call sites are the other half of the fix.
    val currentRecategorize by rememberUpdatedState(onRecategorize)
    val currentActions by rememberUpdatedState(onActions)
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            when (value) {
                SwipeToDismissBoxValue.StartToEnd -> currentRecategorize()
                SwipeToDismissBoxValue.EndToStart -> currentActions()
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
                .clickable(onClick = onOpen)
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
                    // Falling back to the category name printed "Uncategorized"
                    // as the merchant AND again as the category underneath,
                    // which reads like a bug. Say what is actually true.
                    text = row.txn.merchantRaw ?: stringResource(R.string.ledger_no_name),
                    style = KoshaType.Body,
                    color = if (row.txn.merchantRaw != null) {
                        KoshaColors.OffWhite
                    } else {
                        KoshaColors.OffWhiteMuted
                    },
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
    merchantName: String?,
    onPick: (CategoryEntity) -> Unit,
    onPickForMerchant: (CategoryEntity) -> Unit,
    onDismiss: () -> Unit,
) {
    // Categorizing one row of a merchant you have twenty of is not really a
    // per-row decision, so offer to settle the merchant in one go.
    var applyToMerchant by remember { mutableStateOf(merchantName != null) }

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
            if (merchantName != null) {
                Spacer(Modifier.height(KoshaSpacing.xxs))
                KoshaChip(
                    label = if (applyToMerchant) {
                        stringResource(R.string.ledger_apply_all_merchant, merchantName)
                    } else {
                        stringResource(R.string.ledger_apply_this_one)
                    },
                    selected = applyToMerchant,
                    onClick = { applyToMerchant = !applyToMerchant },
                    accent = KoshaColors.AccentTeal,
                )
            }
            Spacer(Modifier.height(KoshaSpacing.xs))
            CategoryFlowGrid(
                categories = categories,
                onPick = { category ->
                    if (applyToMerchant && merchantName != null) {
                        onPickForMerchant(category)
                    } else {
                        onPick(category)
                    }
                },
            )
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
                text = row.txn.merchantRaw ?: stringResource(R.string.ledger_no_name),
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

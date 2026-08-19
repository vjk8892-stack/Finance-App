package dev.kosha.feature.ledger

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.Add
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
import androidx.compose.ui.draw.alpha
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
import dev.kosha.core.designsystem.component.KoshaLocalImage
import dev.kosha.core.designsystem.component.KoshaUndoBar
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
    onAddTransaction: () -> Unit = {},
    /** Set when a chart slice sent the user here. */
    incomingCategory: String? = null,
    incomingMonth: String? = null,
    incomingFrom: String? = null,
    incomingTo: String? = null,
    incomingSearch: String? = null,
    viewModel: LedgerViewModel = hiltViewModel(),
) {
    androidx.compose.runtime.LaunchedEffect(incomingCategory, incomingMonth, incomingFrom, incomingSearch) {
        viewModel.applyIncomingFilter(
            incomingCategory,
            incomingMonth,
            incomingFrom,
            incomingTo,
            incomingSearch,
        )
    }
    val state by viewModel.uiState.collectAsState()
    val queryState by viewModel.query.collectAsState()
    val detail by viewModel.detail.collectAsState()
    var recategorizing by remember { mutableStateOf<LedgerRow?>(null) }
    var bulkRecategorizing by remember { mutableStateOf(false) }
    var acting by remember { mutableStateOf<LedgerRow?>(null) }
    var filtersOpen by remember { mutableStateOf(false) }
    var sortOpen by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<LedgerRow?>(null) }
    val retroResult by viewModel.retroResult.collectAsState()
    val undo by viewModel.undo.collectAsState()

    // The CSV goes straight into the share sheet rather than to a file the
    // user then has to go and find: everything they would do with it —
    // mail it, open it in a spreadsheet, drop it in a chat — starts there.
    val context = androidx.compose.ui.platform.LocalContext.current
    val shareTitle = stringResource(R.string.ledger_export_share)
    val shareCsv: (android.net.Uri) -> Unit = remember(context, shareTitle) {
        { uri ->
            val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(android.content.Intent.createChooser(send, shareTitle))
        }
    }

    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = KoshaSpacing.screenPadding, vertical = KoshaSpacing.s),
        ) {
            Text(
                text = stringResource(R.string.ledger_title),
                style = KoshaType.ScreenTitle,
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
            // Noticing a missing entry happens HERE, so adding one should not
            // require finding another tab first.
            IconButton(onClick = onAddTransaction) {
                Icon(
                    Icons.Outlined.Add,
                    contentDescription = stringResource(R.string.ledger_add),
                    tint = KoshaColors.AccentTealBright,
                )
            }
        }

        // Search bar doubles as the query assistant (spec C3).
        QuerySearchBar(
            text = queryState.text,
            onTextChange = {
                viewModel.onQueryTextChange(it)
                // Filter as you type. The NLU still runs on submit, but it
                // needs a full known merchant name in the phrase, so on its
                // own the bar did nothing for "swig" and read as broken.
                viewModel.setSearchText(it)
            },
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
                label = stringResource(state.sort.labelRes()),
                selected = state.sort != LedgerSort.NEWEST,
                onClick = { sortOpen = true },
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
        // What is actually narrowing the list, named, each one removable on
        // its own. A count behind a sheet ("Filters · 3") tells you that
        // something is hiding rows without telling you what — and a ledger
        // quietly showing a third of itself is how a total comes to look
        // wrong for no visible reason.
        // Replaces the filter bar while a selection is running: two bars of
        // controls at once is a screen arguing with itself about what you are
        // in the middle of doing.
        if (state.selectedIds.isNotEmpty()) {
            SelectionBar(
                count = state.selectedIds.size,
                onSelectAll = viewModel::selectAllVisible,
                onRecategorize = { bulkRecategorizing = true },
                onDelete = viewModel::deleteSelected,
                onExport = { viewModel.exportVisible(shareCsv) },
                onCancel = viewModel::clearSelection,
            )
        } else ActiveFilterBar(
            state = state,
            onClearDirection = { viewModel.setDirection(LedgerFilter.ALL) },
            onClearAccount = { viewModel.setAccount(null) },
            onClearMonth = { viewModel.setMonth(null) },
            onClearCategory = { viewModel.setCategory(null) },
            onClearText = { viewModel.setSearchText("") },
            onClearRange = viewModel::clearDateRange,
            onClearAll = viewModel::clearFilters,
            onExport = { viewModel.exportVisible(shareCsv) },
        )
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
                        isExcluded = row.txn.categoryId in state.excludedCategoryIds,
                        runningBalance = state.runningBalances[row.txn.id],
                        selected = row.txn.id in state.selectedIds,
                        selectionActive = state.selectedIds.isNotEmpty(),
                        onToggleSelected = { viewModel.toggleSelected(row.txn.id) },
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
        } else if (!state.sort.groupsByDate) {
            // Sorted by amount or name: no date sections, or the sections
            // would put the biggest transaction back wherever its date fell.
            LazyColumn(Modifier.fillMaxSize()) {
                item(key = "flat-header") {
                    FlatSortHeader(state.sort, state.flatRows.size, state.flatTotal)
                }
                items(
                    count = state.flatRows.size,
                    key = { i -> state.flatRows[i].txn.id },
                ) { i ->
                    val row = state.flatRows[i]
                    TransactionRow(
                        row = row,
                        isExcluded = row.txn.categoryId in state.excludedCategoryIds,
                        showDate = true,
                        runningBalance = state.runningBalances[row.txn.id],
                        selected = row.txn.id in state.selectedIds,
                        selectionActive = state.selectedIds.isNotEmpty(),
                        onToggleSelected = { viewModel.toggleSelected(row.txn.id) },
                        onOpen = { viewModel.openDetail(row) },
                        onRecategorize = { recategorizing = row },
                        onActions = { acting = row },
                    )
                }
                item { Spacer(Modifier.height(KoshaSpacing.xxl)) }
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
                                isExcluded = row.txn.categoryId in state.excludedCategoryIds,
                                runningBalance = state.runningBalances[row.txn.id],
                                selected = row.txn.id in state.selectedIds,
                                selectionActive = state.selectedIds.isNotEmpty(),
                                onToggleSelected = { viewModel.toggleSelected(row.txn.id) },
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

    if (bulkRecategorizing) {
        RecategorizeSheet(
            categories = state.categories.filter { !it.isSystem },
            merchantName = null,
            onPick = { category ->
                viewModel.recategorizeSelected(category.id)
                bulkRecategorizing = false
            },
            onPickForMerchant = { },
            onDismiss = { bulkRecategorizing = false },
        )
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

    KoshaUndoBar(
        visible = undo != null,
        message = stringResource(undo?.kind?.messageRes() ?: R.string.undo_deleted),
        actionLabel = stringResource(R.string.undo_action),
        onUndo = viewModel::performUndo,
        onDismiss = viewModel::dismissUndo,
        // Identity of the action, so each one gets a fresh countdown.
        token = undo,
        modifier = Modifier.align(Alignment.BottomCenter),
    )
    }

    if (sortOpen) {
        SortSheet(
            current = state.sort,
            onPick = {
                viewModel.setSort(it)
                sortOpen = false
            },
            onDismiss = { sortOpen = false },
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

private fun LedgerSort.labelRes(): Int = when (this) {
    LedgerSort.NEWEST -> R.string.ledger_sort_newest
    LedgerSort.OLDEST -> R.string.ledger_sort_oldest
    LedgerSort.LARGEST -> R.string.ledger_sort_largest
    LedgerSort.SMALLEST -> R.string.ledger_sort_smallest
    LedgerSort.NAME -> R.string.ledger_sort_name
}

private fun UndoKind.messageRes(): Int = when (this) {
    UndoKind.DELETED -> R.string.undo_deleted
    UndoKind.RECATEGORIZED -> R.string.undo_recategorized
    UndoKind.APPROVED -> R.string.undo_approved
    UndoKind.DISCARDED -> R.string.undo_discarded
}

@Composable
private fun SortSheet(
    current: LedgerSort,
    onPick: (LedgerSort) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = KoshaColors.CharcoalOverlay) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(KoshaSpacing.m),
            verticalArrangement = Arrangement.spacedBy(KoshaSpacing.xs),
        ) {
            Text(
                text = stringResource(R.string.ledger_sort_title),
                style = KoshaType.Title,
                color = KoshaColors.OffWhite,
            )
            Spacer(Modifier.height(KoshaSpacing.xs))
            LedgerSort.entries.forEach { option ->
                KoshaChip(
                    label = stringResource(option.labelRes()),
                    selected = option == current,
                    onClick = { onPick(option) },
                    modifier = Modifier.fillMaxWidth(),
                    accent = KoshaColors.AccentTeal,
                )
            }
            Spacer(Modifier.height(KoshaSpacing.xl))
        }
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TransactionRow(
    row: LedgerRow,
    /** True when this row sits in a category the totals leave out. */
    isExcluded: Boolean,
    /**
     * Print the date on the row itself. Needed by the flat orderings, which
     * have no day headers — without it a list sorted by amount gives no way to
     * tell when anything happened.
     */
    showDate: Boolean = false,
    /** Balance on this account after this transaction; null in mixed views. */
    runningBalance: Money? = null,
    selected: Boolean = false,
    selectionActive: Boolean = false,
    onToggleSelected: () -> Unit = {},
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
                .background(if (selected) KoshaColors.CharcoalOverlay else KoshaColors.Charcoal)
                // Long-press starts a selection; once one is running a plain
                // tap extends it rather than opening a row, because opening a
                // detail sheet mid-selection loses the selection.
                .combinedClickable(
                    onClick = { if (selectionActive) onToggleSelected() else onOpen() },
                    onLongClick = onToggleSelected,
                )
                .padding(horizontal = KoshaSpacing.screenPadding, vertical = KoshaSpacing.s),
        ) {
            // An excluded row still moved money, so it belongs in the list —
            // but it is NOT part of the total printed above it, and a row that
            // looks identical to its neighbours while being counted differently
            // is how the month header comes to look wrong. Dim everything and
            // say why in words; there is no colour that means "ignored".
            val dim = if (isExcluded) EXCLUDED_ALPHA else 1f
            // Account color tick (spec G3: ledger row left-edge tick)
            Box(
                Modifier
                    .size(width = 3.dp, height = 32.dp)
                    .background(KoshaColors.accountColor(row.accountColorToken).copy(alpha = dim)),
            )
            Spacer(Modifier.width(KoshaSpacing.s))
            // Every row used the same grey disc, so a screen of thirty rows
            // had nothing to scan by. A stable per-category colour gives the
            // eye something to group on before it reads a single word.
            val categoryTint = KoshaColors.categoryColor(row.categoryName).copy(alpha = dim)
            Icon(
                imageVector = KoshaIcons.forToken(row.categoryIcon),
                contentDescription = row.categoryName,
                tint = categoryTint,
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(categoryTint.copy(alpha = 0.16f * dim))
                    .padding(8.dp),
            )
            Spacer(Modifier.width(KoshaSpacing.s))
            // The receipt this row came from. A photo you deliberately took as
            // proof is only evidence if you can SEE it is still attached —
            // otherwise the capture is an act of faith.
            row.photoUri?.let { uri ->
                KoshaLocalImage(
                    uri = uri,
                    contentDescription = stringResource(R.string.ledger_has_photo),
                    targetSize = 36.dp,
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(KoshaSpacing.xxs))
                        .alpha(dim),
                )
                Spacer(Modifier.width(KoshaSpacing.s))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    // Falling back to the category name printed "Uncategorized"
                    // as the merchant AND again as the category underneath,
                    // which reads like a bug. Say what is actually true.
                    text = row.txn.merchantRaw ?: stringResource(R.string.ledger_no_name),
                    style = KoshaType.Body,
                    color = (
                        if (row.txn.merchantRaw != null) {
                            KoshaColors.OffWhite
                        } else {
                            KoshaColors.OffWhiteMuted
                        }
                        ).copy(alpha = dim),
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
                        color = categoryTint.copy(alpha = 0.85f * dim),
                        maxLines = 1,
                    )
                    if (showDate) {
                        Spacer(Modifier.width(KoshaSpacing.xxs))
                        Text(
                            text = "· " + ROW_DATE.format(
                                java.time.Instant.ofEpochMilli(row.txn.timestampMillis)
                                    .atZone(java.time.ZoneId.systemDefault()),
                            ),
                            style = KoshaType.Caption,
                            color = KoshaColors.OffWhiteMuted,
                            maxLines = 1,
                        )
                    }
                    if (isExcluded) {
                        Spacer(Modifier.width(KoshaSpacing.xxs))
                        Text(
                            text = stringResource(R.string.ledger_not_in_total),
                            style = KoshaType.Caption,
                            color = KoshaColors.OffWhiteFaint,
                            maxLines = 1,
                        )
                    }
                }
            }
            Column(horizontalAlignment = Alignment.End) {
            AmountText(
                amount = if (row.txn.type == TxnType.DEBIT) Money(-row.txn.amountPaise) else Money(row.txn.amountPaise),
                style = KoshaType.AmountBody,
                color = (
                    if (row.txn.type == TxnType.CREDIT) {
                        KoshaColors.AccentTealBright
                    } else {
                        KoshaColors.OffWhite
                    }
                    ).copy(alpha = dim),
                signed = row.txn.type == TxnType.CREDIT,
            )
            // What the account held AFTER this row. Only present on a
            // single-account view, where it is a real number rather than a
            // sum of unrelated accounts.
            runningBalance?.let { balance ->
                AmountText(
                    amount = balance,
                    style = KoshaType.Caption,
                    color = KoshaColors.OffWhiteFaint,
                    withPaise = false,
                )
            }
            }
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
            // Picking a category applies immediately, so there is no Save
            // here — but the way out belongs at the top with it, not below a
            // grid of twenty categories.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = stringResource(R.string.ledger_recategorize),
                    style = KoshaType.SectionHeader,
                    color = KoshaColors.OffWhite,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onDismiss) {
                    Text(
                        text = stringResource(R.string.ledger_done),
                        style = KoshaType.LabelStrong,
                        color = KoshaColors.AccentTealBright,
                    )
                }
            }
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

/**
 * How far an excluded row fades. Enough to read as "set aside" at a glance,
 * not so far that the amount becomes unreadable — these rows still have to be
 * checkable, since deciding a transfer was wrongly marked is the whole reason
 * for opening one.
 */
private const val EXCLUDED_ALPHA = 0.45f

/**
 * What is selected, and what can be done with it. Sits where the filter bar
 * normally is, so the row of controls always describes the mode you are in.
 */
@Composable
private fun SelectionBar(
    count: Int,
    onSelectAll: () -> Unit,
    onRecategorize: () -> Unit,
    onDelete: () -> Unit,
    onExport: () -> Unit,
    onCancel: () -> Unit,
) {
    Spacer(Modifier.height(KoshaSpacing.xs))
    Row(
        horizontalArrangement = Arrangement.spacedBy(KoshaSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = KoshaSpacing.screenPadding),
    ) {
        Text(
            text = stringResource(R.string.ledger_selected, count),
            style = KoshaType.LabelStrong,
            color = KoshaColors.AccentTealBright,
        )
        KoshaChip(label = stringResource(R.string.ledger_select_all), onClick = onSelectAll)
        KoshaChip(
            label = stringResource(R.string.ledger_recategorize),
            onClick = onRecategorize,
            accent = KoshaColors.AccentTeal,
            selected = true,
        )
        KoshaChip(
            label = stringResource(R.string.ledger_delete),
            onClick = onDelete,
            accent = KoshaColors.Amber,
            selected = true,
        )
        KoshaChip(label = stringResource(R.string.ledger_export), onClick = onExport)
        KoshaChip(label = stringResource(R.string.ledger_cancel), onClick = onCancel)
    }
}

private val ROW_DATE: java.time.format.DateTimeFormatter =
    java.time.format.DateTimeFormatter.ofPattern("d MMM")

/**
 * Stands in for the month header when the ordering is not chronological, so
 * the list still says what it is showing and what it adds up to.
 */
@Composable
private fun FlatSortHeader(sort: LedgerSort, count: Int, total: Money) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(KoshaColors.Charcoal)
            .padding(horizontal = KoshaSpacing.screenPadding, vertical = KoshaSpacing.s),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = stringResource(sort.labelRes()),
                style = KoshaType.SectionHeader,
                color = KoshaColors.OffWhite,
            )
            Text(
                text = stringResource(R.string.ledger_flat_count, count),
                style = KoshaType.Caption,
                color = KoshaColors.OffWhiteFaint,
            )
        }
        AmountText(
            amount = total,
            style = KoshaType.AmountSmall,
            color = if (total.isNegative) KoshaColors.OffWhiteMuted else KoshaColors.AccentTeal,
            withPaise = false,
            signed = !total.isNegative,
        )
    }
}

/**
 * The filters currently in force, spelled out.
 *
 * Renders nothing when nothing is filtered, so the ledger keeps its full
 * height in the normal case. Each chip removes only itself — dropping one
 * narrowing should never silently drop the others — and "Clear all" is only
 * offered once there is more than one thing to clear.
 */
@Composable
private fun ActiveFilterBar(
    state: LedgerUiState,
    onClearDirection: () -> Unit,
    onClearAccount: () -> Unit,
    onClearMonth: () -> Unit,
    onClearCategory: () -> Unit,
    onClearText: () -> Unit,
    onClearRange: () -> Unit,
    onClearAll: () -> Unit,
    onExport: () -> Unit,
) {
    val filters = state.filters
    if (filters.activeCount == 0) return

    Spacer(Modifier.height(KoshaSpacing.xs))
    Row(
        horizontalArrangement = Arrangement.spacedBy(KoshaSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = KoshaSpacing.screenPadding),
    ) {
        Text(
            text = stringResource(R.string.ledger_showing),
            style = KoshaType.Caption,
            color = KoshaColors.OffWhiteFaint,
        )
        if (filters.direction != LedgerFilter.ALL) {
            RemovableFilterChip(stringResource(filters.direction.labelRes()), onClearDirection)
        }
        filters.accountId?.let { id ->
            val name = state.accounts.firstOrNull { it.id == id }?.name
                ?: stringResource(R.string.ledger_filter_account)
            RemovableFilterChip(name, onClearAccount)
        }
        filters.month?.let { month ->
            RemovableFilterChip(FILTER_MONTH.format(month.atDay(1)), onClearMonth)
        }
        filters.categoryId?.let { id ->
            val name = state.categories.firstOrNull { it.id == id }?.name
                ?: stringResource(R.string.ledger_filter_category)
            RemovableFilterChip(name, onClearCategory)
        }
        filters.from?.let { from ->
            // One day and a span read very differently; say which this is.
            val label = if (filters.to == from) {
                FILTER_DAY.format(from)
            } else {
                FILTER_DAY.format(from) + " – " + filters.to?.let(FILTER_DAY::format).orEmpty()
            }
            RemovableFilterChip(label, onClearRange)
        }
        filters.text.takeIf { it.isNotBlank() }?.let { text ->
            RemovableFilterChip("“$text”", onClearText)
        }
        if (filters.activeCount > 1) {
            KoshaChip(
                label = stringResource(R.string.ledger_filters_clear),
                onClick = onClearAll,
                accent = KoshaColors.Amber,
            )
        }
        // Offered here rather than in Export because this is the moment the
        // list IS the thing worth exporting — the narrowing has just been done.
        KoshaChip(label = stringResource(R.string.ledger_export), onClick = onExport)
    }
}

@Composable
private fun RemovableFilterChip(label: String, onRemove: () -> Unit) {
    KoshaChip(
        label = "$label  ✕",
        selected = true,
        onClick = onRemove,
        accent = KoshaColors.AccentTeal,
    )
}

private val FILTER_MONTH: java.time.format.DateTimeFormatter =
    java.time.format.DateTimeFormatter.ofPattern("MMM yyyy")
private val FILTER_DAY: java.time.format.DateTimeFormatter =
    java.time.format.DateTimeFormatter.ofPattern("d MMM")

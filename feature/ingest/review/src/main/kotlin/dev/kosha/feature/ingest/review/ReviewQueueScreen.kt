package dev.kosha.feature.ingest.review

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import dev.kosha.core.common.Money
import dev.kosha.core.database.dao.LedgerRow
import dev.kosha.core.database.model.CategoryEntity
import dev.kosha.core.database.model.TxnType
import dev.kosha.core.designsystem.component.AmountText
import dev.kosha.core.designsystem.component.KoshaCard
import dev.kosha.core.designsystem.component.KoshaChip
import dev.kosha.core.designsystem.token.KoshaColors
import dev.kosha.core.designsystem.token.KoshaSpacing
import dev.kosha.core.designsystem.token.KoshaType
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Unified review queue (spec B2: shared by SMS + OCR). Each card shows the
 * parsed fields; approving commits, duplicates offer merge/keep, junk is
 * discarded. Calm design: no red, no urgency.
 */
@Composable
fun ReviewQueueScreen(
    onBack: () -> Unit,
    viewModel: ReviewQueueViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()

    Column(Modifier.fillMaxSize()) {
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
                text = stringResource(R.string.review_title),
                style = KoshaType.Title,
                color = KoshaColors.OffWhite,
                modifier = Modifier.weight(1f),
            )
            if (state.total > 0) {
                Text(
                    text = stringResource(R.string.review_remaining, state.total),
                    style = KoshaType.Caption,
                    color = KoshaColors.OffWhiteFaint,
                    modifier = Modifier.padding(end = KoshaSpacing.s),
                )
            }
        }

        if (state.items.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(R.string.review_empty),
                    style = KoshaType.InsightSerif,
                    color = KoshaColors.OffWhiteMuted,
                )
            }
        } else {
            LazyColumn(
                contentPadding = androidx.compose.foundation.layout.PaddingValues(KoshaSpacing.screenPadding),
                verticalArrangement = Arrangement.spacedBy(KoshaSpacing.s),
            ) {
                state.groups.forEach { group ->
                    item(key = "header-${group.key}") {
                        GroupHeader(
                            group = group,
                            onApproveAll = { viewModel.approveAll(group) },
                            onDiscardAll = { viewModel.discardAll(group) },
                        )
                    }
                    items(group.rows.size, key = { i -> group.rows[i].txn.id }) { i ->
                        val row = group.rows[i]
                        ReviewCard(
                            row = row,
                            categories = state.categories,
                            originalMessage = state.evidenceByTxnId[row.txn.id],
                            onApprove = { categoryId -> viewModel.approve(row.txn.id, categoryId) },
                            onMerge = { viewModel.mergeDuplicate(row.txn.id) },
                            onDiscard = { viewModel.discard(row.txn.id) },
                        )
                    }
                }
            }
        }
    }
}

/**
 * Header for one reason, with the bulk actions for it.
 *
 * A queue this long is only drainable in groups — and everything in it is
 * excluded from every total until cleared, so an unread queue quietly makes
 * the rest of the app wrong. The count and net are on the header so approving
 * a group is an informed action rather than a leap.
 */
@Composable
private fun GroupHeader(
    group: ReviewGroup,
    onApproveAll: () -> Unit,
    onDiscardAll: () -> Unit,
) {
    var confirming by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth().padding(top = KoshaSpacing.s)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                text = groupTitle(group),
                style = KoshaType.SectionHeader,
                color = KoshaColors.OffWhite,
                modifier = Modifier.weight(1f),
            )
            AmountText(
                amount = group.total,
                style = KoshaType.AmountSmall,
                color = KoshaColors.OffWhiteMuted,
                withPaise = false,
            )
        }
        Text(
            text = stringResource(R.string.review_group_count, group.rows.size),
            style = KoshaType.Caption,
            color = KoshaColors.OffWhiteFaint,
        )
        // Duplicates are a judgement about two specific rows, so they are
        // never offered as a bulk approval.
        if (!group.isDuplicateGroup) {
            Spacer(Modifier.height(KoshaSpacing.xxs))
            if (!confirming) {
                Row(horizontalArrangement = Arrangement.spacedBy(KoshaSpacing.xs)) {
                    KoshaChip(
                        label = stringResource(R.string.review_approve_all, group.rows.size),
                        onClick = { confirming = true },
                        accent = KoshaColors.AccentTeal,
                    )
                }
            } else {
                Text(
                    text = stringResource(R.string.review_approve_all_confirm, group.rows.size),
                    style = KoshaType.Caption,
                    color = KoshaColors.Amber,
                )
                Spacer(Modifier.height(KoshaSpacing.xxs))
                Row(horizontalArrangement = Arrangement.spacedBy(KoshaSpacing.xs)) {
                    KoshaChip(
                        label = stringResource(R.string.review_approve_all_yes),
                        onClick = {
                            confirming = false
                            onApproveAll()
                        },
                        accent = KoshaColors.AccentTeal,
                    )
                    KoshaChip(
                        label = stringResource(R.string.review_discard_all),
                        onClick = {
                            confirming = false
                            onDiscardAll()
                        },
                        accent = KoshaColors.Amber,
                    )
                    KoshaChip(
                        label = stringResource(R.string.ledger_cancel_generic),
                        onClick = { confirming = false },
                    )
                }
            }
        }
    }
}

@Composable
private fun groupTitle(group: ReviewGroup): String = stringResource(
    when (group.key) {
        ReviewQueueViewModel.KEY_NEW_ACCOUNT -> R.string.review_group_new_account
        ReviewQueueViewModel.KEY_ACCOUNT_TAIL -> R.string.review_group_account_tail
        ReviewQueueViewModel.KEY_ACCOUNT_UNKNOWN -> R.string.review_group_account_unknown
        ReviewQueueViewModel.DUPLICATES_KEY -> R.string.review_group_duplicates
        else -> R.string.review_group_low_confidence
    },
)

/**
 * "Parsed with low confidence" for everything tells the reader nothing about
 * what to check. The committer records WHY a row is waiting, so say it.
 */
@Composable
private fun reviewReasonText(reason: String?, isPossibleDuplicate: Boolean): String = when {
    isPossibleDuplicate -> stringResource(R.string.review_duplicate_hint)
    reason == null -> stringResource(R.string.review_reason_low_confidence)
    reason.startsWith(NEW_ACCOUNT_PREFIX) ->
        stringResource(R.string.review_reason_new_account, reason.removePrefix(NEW_ACCOUNT_PREFIX))
    reason.startsWith(ACCOUNT_TAIL_PREFIX) ->
        stringResource(R.string.review_reason_account_tail, reason.removePrefix(ACCOUNT_TAIL_PREFIX))
    reason == ACCOUNT_UNKNOWN -> stringResource(R.string.review_reason_account_unknown)
    else -> stringResource(R.string.review_reason_low_confidence)
}

/** Kept in step with PipelineCommitter's attribution reasons. */
private const val NEW_ACCOUNT_PREFIX = "new-account-"
private const val ACCOUNT_TAIL_PREFIX = "account-tail-"
private const val ACCOUNT_UNKNOWN = "account-unknown"

@Composable
private fun ReviewCard(
    row: LedgerRow,
    categories: List<CategoryEntity>,
    originalMessage: String?,
    onApprove: (Long?) -> Unit,
    onMerge: () -> Unit,
    onDiscard: () -> Unit,
) {
    val isPossibleDuplicate = row.txn.possibleDuplicateOfId != null
    var pickedCategory by remember { mutableStateOf<Long?>(null) }
    val timeLabel = remember(row.txn.timestampMillis) {
        DateTimeFormatter.ofPattern("d MMM, HH:mm")
            .format(Instant.ofEpochMilli(row.txn.timestampMillis).atZone(ZoneId.systemDefault()))
    }

    KoshaCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = row.txn.merchantRaw ?: row.accountName,
                    style = KoshaType.Body,
                    color = KoshaColors.OffWhite,
                )
                Text(
                    text = "$timeLabel · ${row.accountName}",
                    style = KoshaType.Caption,
                    color = KoshaColors.OffWhiteFaint,
                )
            }
            AmountText(
                amount = if (row.txn.type == TxnType.DEBIT) Money(-row.txn.amountPaise) else Money(row.txn.amountPaise),
                style = KoshaType.AmountBody,
            )
        }

        Spacer(Modifier.height(KoshaSpacing.xs))
        Text(
            text = reviewReasonText(row.txn.reviewReason, isPossibleDuplicate),
            style = KoshaType.Caption,
            color = KoshaColors.Amber,
        )
        // Only present when raw retention is on — the message that produced
        // this row, so a wrong reading is obvious rather than mysterious.
        originalMessage?.let { message ->
            Spacer(Modifier.height(KoshaSpacing.xxs))
            Text(
                text = message,
                style = KoshaType.Caption,
                color = KoshaColors.OffWhiteFaint,
                maxLines = 6,
            )
        }

        Spacer(Modifier.height(KoshaSpacing.xs))

        if (!isPossibleDuplicate) {
            // Quick category row before approving
            // All of them, scrollable. Showing the first three meant the
            // right category usually was not offered, so approving a row left
            // it uncategorized anyway.
            Row(
                horizontalArrangement = Arrangement.spacedBy(KoshaSpacing.xxs),
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
            ) {
                categories.forEach { cat ->
                    KoshaChip(
                        label = cat.name,
                        selected = pickedCategory == cat.id,
                        onClick = { pickedCategory = if (pickedCategory == cat.id) null else cat.id },
                    )
                }
            }
            Spacer(Modifier.height(KoshaSpacing.xs))
        }

        Row(horizontalArrangement = Arrangement.spacedBy(KoshaSpacing.s)) {
            if (isPossibleDuplicate) {
                TextButton(onClick = onMerge) {
                    Text(stringResource(R.string.review_duplicate_merge), color = KoshaColors.AccentTeal)
                }
                TextButton(onClick = { onApprove(null) }) {
                    Text(stringResource(R.string.review_duplicate_keep), color = KoshaColors.OffWhiteMuted)
                }
            } else {
                TextButton(onClick = { onApprove(pickedCategory) }) {
                    Text(stringResource(R.string.review_approve), color = KoshaColors.AccentTeal)
                }
                TextButton(onClick = onDiscard) {
                    Text(stringResource(R.string.review_discard), color = KoshaColors.OffWhiteMuted)
                }
            }
        }
    }
}

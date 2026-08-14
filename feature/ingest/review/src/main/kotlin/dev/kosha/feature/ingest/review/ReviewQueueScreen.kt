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
import androidx.compose.foundation.lazy.LazyColumn
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
            )
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
                items(state.items.size) { i ->
                    ReviewCard(
                        row = state.items[i],
                        categories = state.categories,
                        originalMessage = state.evidenceByTxnId[state.items[i].txn.id],
                        onApprove = { categoryId -> viewModel.approve(state.items[i].txn.id, categoryId) },
                        onMerge = { viewModel.mergeDuplicate(state.items[i].txn.id) },
                        onDiscard = { viewModel.discard(state.items[i].txn.id) },
                    )
                }
            }
        }
    }
}

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
            text = if (isPossibleDuplicate) {
                stringResource(R.string.review_duplicate_hint)
            } else {
                stringResource(R.string.review_reason_low_confidence)
            },
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
            Row(
                horizontalArrangement = Arrangement.spacedBy(KoshaSpacing.xxs),
                modifier = Modifier.fillMaxWidth(),
            ) {
                categories.take(3).forEach { cat ->
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

package dev.kosha.feature.ledger

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import dev.kosha.core.common.Money
import dev.kosha.core.database.model.CategoryEntity
import dev.kosha.core.designsystem.component.AmountText
import dev.kosha.core.designsystem.component.KoshaChip
import dev.kosha.core.designsystem.token.KoshaColors
import dev.kosha.core.designsystem.token.KoshaSpacing
import dev.kosha.core.designsystem.token.KoshaType

/** One editable line while the split is being built. */
private data class DraftLine(val categoryId: Long?, val text: String)

/**
 * Editable text for an amount. Paise are kept only when the transaction has
 * them: dropping them off a ₹1,500.50 bill would make the lines unable to
 * reach the total no matter what the user typed.
 */
private fun Money.editable(): String =
    format(withSymbol = false, withPaise = paise % 100 != 0L)

/**
 * Divides one transaction across categories.
 *
 * The schema and every total have handled splits since Phase 1 — nothing could
 * create one. A supermarket bill that is half groceries and half a present had
 * to be filed as one or the other.
 *
 * The lines must add up to the transaction EXACTLY, and Save stays off until
 * they do. Kosha's category breakdown is a breakdown of the total; letting it
 * cover only part of one would put two numbers on screen that cannot both be
 * right. The remainder is shown at all times so getting there is arithmetic
 * the app does, not the user.
 */
@Composable
fun SplitSheet(
    total: Money,
    categories: List<CategoryEntity>,
    existing: List<Pair<Long?, Money>>,
    onDismiss: () -> Unit,
    onSave: (List<Pair<Long?, Money>>) -> Unit,
    onUnsplit: () -> Unit,
) {
    val lines = remember {
        mutableStateListOf<DraftLine>().apply {
            if (existing.isEmpty()) {
                // Start from the whole amount on one line and nothing on the
                // second: the first thing to decide is what the OTHER part is.
                add(DraftLine(null, total.editable()))
                add(DraftLine(null, ""))
            } else {
                existing.forEach { (categoryId, amount) ->
                    add(DraftLine(categoryId, amount.editable()))
                }
            }
        }
    }
    var picking by remember { mutableStateOf<Int?>(null) }

    val entered = lines.sumOf { Money.parseOrNull(it.text)?.paise ?: 0L }
    val remaining = total.paise - entered
    val complete = remaining == 0L &&
        lines.count { (Money.parseOrNull(it.text)?.paise ?: 0L) > 0 } >= 2

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = KoshaColors.CharcoalOverlay) {
        Column(
            Modifier
                .fillMaxWidth()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = KoshaSpacing.m),
            verticalArrangement = Arrangement.spacedBy(KoshaSpacing.s),
        ) {
            // Save at the top, where every other sheet in the app now puts it,
            // and above the keyboard rather than under it.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.split_title),
                    style = KoshaType.Title,
                    color = KoshaColors.OffWhite,
                    modifier = Modifier.weight(1f),
                )
                KoshaChip(
                    label = stringResource(R.string.split_save),
                    selected = complete,
                    accent = if (complete) KoshaColors.AccentTeal else KoshaColors.Outline,
                    onClick = {
                        if (!complete) return@KoshaChip
                        onSave(
                            lines.mapNotNull { line ->
                                Money.parseOrNull(line.text)
                                    ?.takeIf { it.paise > 0 }
                                    ?.let { line.categoryId to it }
                            },
                        )
                    },
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.split_of_total),
                    style = KoshaType.Caption,
                    color = KoshaColors.OffWhiteFaint,
                    modifier = Modifier.weight(1f),
                )
                AmountText(
                    amount = total,
                    style = KoshaType.AmountSmall,
                    color = KoshaColors.OffWhite,
                    withPaise = false,
                )
            }

            lines.forEachIndexed { index, line ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(KoshaSpacing.xs),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    KoshaChip(
                        label = categories.firstOrNull { it.id == line.categoryId }?.name
                            ?: stringResource(R.string.split_pick_category),
                        selected = line.categoryId != null,
                        accent = KoshaColors.AccentTeal,
                        onClick = { picking = index },
                        modifier = Modifier.weight(1f),
                    )
                    TextField(
                        value = line.text,
                        onValueChange = { lines[index] = line.copy(text = it) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = KoshaColors.CharcoalRaised,
                            unfocusedContainerColor = KoshaColors.CharcoalRaised,
                            focusedTextColor = KoshaColors.OffWhite,
                            unfocusedTextColor = KoshaColors.OffWhite,
                        ),
                        modifier = Modifier.weight(1f),
                    )
                    if (lines.size > 2) {
                        KoshaChip(label = "✕", onClick = { lines.removeAt(index) })
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(KoshaSpacing.xs)) {
                KoshaChip(
                    label = stringResource(R.string.split_add_line),
                    onClick = { lines.add(DraftLine(null, "")) },
                )
                // One tap to finish: whatever is left goes on the last line.
                if (remaining > 0) {
                    KoshaChip(
                        label = stringResource(R.string.split_fill_rest),
                        onClick = {
                            val last = lines.lastIndex
                            val current = Money.parseOrNull(lines[last].text)?.paise ?: 0L
                            lines[last] = lines[last].copy(
                                text = Money(current + remaining).editable(),
                            )
                        },
                    )
                }
                if (existing.isNotEmpty()) {
                    KoshaChip(
                        label = stringResource(R.string.split_remove),
                        onClick = onUnsplit,
                        accent = KoshaColors.Amber,
                    )
                }
            }

            Text(
                text = when {
                    remaining > 0 -> stringResource(
                        R.string.split_left,
                        Money(remaining).format(withPaise = false),
                    )
                    remaining < 0 -> stringResource(
                        R.string.split_over,
                        Money(-remaining).format(withPaise = false),
                    )
                    complete -> stringResource(R.string.split_balanced)
                    else -> stringResource(R.string.split_need_two)
                },
                style = KoshaType.Caption,
                // Amber, never red: this is a "not yet", not a failure.
                color = if (complete) KoshaColors.AccentTeal else KoshaColors.Amber,
            )

            Spacer(Modifier.height(KoshaSpacing.xl))
        }
    }

    picking?.let { index ->
        ModalBottomSheet(
            onDismissRequest = { picking = null },
            containerColor = KoshaColors.CharcoalOverlay,
        ) {
            Column(Modifier.padding(horizontal = KoshaSpacing.m)) {
                CategoryFlowGrid(
                    categories = categories,
                    selectedId = lines[index].categoryId,
                    onPick = { category ->
                        lines[index] = lines[index].copy(categoryId = category.id)
                        picking = null
                    },
                )
                Spacer(Modifier.height(KoshaSpacing.xl))
            }
        }
    }
}

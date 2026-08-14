package dev.kosha.feature.ledger

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import dev.kosha.core.common.Money
import dev.kosha.core.database.dao.LedgerRow
import dev.kosha.core.database.model.CategoryEntity
import dev.kosha.core.database.model.TxnType
import dev.kosha.core.designsystem.component.KoshaChip
import dev.kosha.core.designsystem.token.KoshaColors
import dev.kosha.core.designsystem.token.KoshaSpacing
import dev.kosha.core.designsystem.token.KoshaType
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Correct what Kosha read: amount, direction, date, name, category.
 *
 * Everything in the ledger arrived from a parser, and no parser is right every
 * time. Without this the only remedies were recategorize or delete — so a row
 * with the wrong amount or a mangled merchant name had to be thrown away and
 * retyped, which loses the link back to the message it came from.
 *
 * Editing an SMS-captured row is not "faking" data: the message is the source
 * of truth and the row is our reading of it, so the user correcting the
 * reading is exactly the intended flow.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditTransactionSheet(
    row: LedgerRow,
    categories: List<CategoryEntity>,
    onSave: (EditedTransaction) -> Unit,
    onDismiss: () -> Unit,
) {
    val txn = row.txn
    var merchant by remember { mutableStateOf(txn.merchantRaw.orEmpty()) }
    var note by remember { mutableStateOf(txn.note.orEmpty()) }
    var type by remember { mutableStateOf(txn.type) }
    var categoryId by remember { mutableStateOf(txn.categoryId) }
    var timestamp by remember { mutableStateOf(txn.timestampMillis) }
    var showDatePicker by remember { mutableStateOf(false) }
    // Rupees as typed, so the field behaves like a text field rather than
    // fighting the user over paise while they are mid-edit.
    var amountText by remember {
        mutableStateOf(
            if (txn.amountPaise % 100 == 0L) {
                (txn.amountPaise / 100).toString()
            } else {
                String.format("%.2f", txn.amountPaise / 100.0)
            },
        )
    }

    val parsedAmount = Money.parseOrNull(amountText)
    val canSave = parsedAmount != null && parsedAmount.paise > 0

    if (showDatePicker) {
        val pickerState = rememberDatePickerState(initialSelectedDateMillis = timestamp)
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        pickerState.selectedDateMillis?.let { picked ->
                            // Keep the original time of day: the picker only
                            // changes which day, and a transaction silently
                            // jumping to midnight breaks dedup windows.
                            timestamp = withDateFrom(picked, timestamp)
                        }
                        showDatePicker = false
                    },
                ) {
                    Text(stringResource(R.string.edit_date_confirm), color = KoshaColors.AccentTeal)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) {
                    Text(stringResource(R.string.ledger_cancel), color = KoshaColors.OffWhiteMuted)
                }
            },
        ) {
            DatePicker(state = pickerState)
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = KoshaColors.CharcoalOverlay) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = KoshaSpacing.m),
            verticalArrangement = Arrangement.spacedBy(KoshaSpacing.s),
        ) {
            Text(
                text = stringResource(R.string.edit_title),
                style = KoshaType.Title,
                color = KoshaColors.OffWhite,
            )

            TextField(
                value = amountText,
                onValueChange = { text ->
                    if (text.all { it.isDigit() || it == '.' || it == ',' }) amountText = text
                },
                label = { Text(stringResource(R.string.edit_amount), color = KoshaColors.OffWhiteFaint) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                isError = !canSave,
                colors = editFieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(KoshaSpacing.xs)) {
                KoshaChip(
                    label = stringResource(R.string.edit_money_out),
                    selected = type == TxnType.DEBIT,
                    onClick = { type = TxnType.DEBIT },
                )
                KoshaChip(
                    label = stringResource(R.string.edit_money_in),
                    selected = type == TxnType.CREDIT,
                    onClick = { type = TxnType.CREDIT },
                    accent = KoshaColors.AccentTeal,
                )
            }

            TextField(
                value = merchant,
                onValueChange = { merchant = it },
                label = { Text(stringResource(R.string.edit_merchant), color = KoshaColors.OffWhiteFaint) },
                colors = editFieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )

            KoshaChip(
                label = stringResource(R.string.edit_date, DAY_FORMAT.format(localDate(timestamp))),
                onClick = { showDatePicker = true },
            )

            TextField(
                value = note,
                onValueChange = { note = it },
                label = { Text(stringResource(R.string.edit_note), color = KoshaColors.OffWhiteFaint) },
                colors = editFieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )

            Text(
                text = stringResource(R.string.edit_category),
                style = KoshaType.Label,
                color = KoshaColors.OffWhiteFaint,
            )
            CategoryFlowGrid(
                categories = categories,
                selectedId = categoryId,
                onPick = { picked -> categoryId = if (categoryId == picked.id) null else picked.id },
            )

            Spacer(Modifier.height(KoshaSpacing.xs))
            Row(horizontalArrangement = Arrangement.spacedBy(KoshaSpacing.s)) {
                TextButton(
                    enabled = canSave,
                    onClick = {
                        val amount = parsedAmount ?: return@TextButton
                        onSave(
                            EditedTransaction(
                                id = txn.id,
                                amountPaise = amount.paise,
                                type = type,
                                merchantRaw = merchant.trim().takeIf { it.isNotBlank() },
                                note = note.trim().takeIf { it.isNotBlank() },
                                timestampMillis = timestamp,
                                categoryId = categoryId,
                            ),
                        )
                    },
                ) {
                    Text(
                        text = stringResource(R.string.edit_save),
                        color = if (canSave) KoshaColors.AccentTeal else KoshaColors.OffWhiteFaint,
                    )
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.ledger_cancel), color = KoshaColors.OffWhiteMuted)
                }
            }
            Spacer(Modifier.height(KoshaSpacing.xl))
        }
    }
}

/** The user's corrections, ready to apply. */
data class EditedTransaction(
    val id: Long,
    val amountPaise: Long,
    val type: TxnType,
    val merchantRaw: String?,
    val note: String?,
    val timestampMillis: Long,
    val categoryId: Long?,
)

@Composable
private fun editFieldColors() = TextFieldDefaults.colors(
    focusedContainerColor = KoshaColors.CharcoalRaised,
    unfocusedContainerColor = KoshaColors.CharcoalRaised,
    focusedTextColor = KoshaColors.OffWhite,
    unfocusedTextColor = KoshaColors.OffWhite,
)

private val DAY_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy")

private fun localDate(millis: Long) =
    Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate()

/** Swap the DAY of [originalMillis] for the one picked, keeping its time. */
private fun withDateFrom(pickedUtcMillis: Long, originalMillis: Long): Long {
    val zone = ZoneId.systemDefault()
    val pickedDay = Instant.ofEpochMilli(pickedUtcMillis).atZone(ZoneOffset.UTC).toLocalDate()
    val originalTime: LocalTime = Instant.ofEpochMilli(originalMillis).atZone(zone).toLocalTime()
    return pickedDay.atTime(originalTime).atZone(zone).toInstant().toEpochMilli()
}

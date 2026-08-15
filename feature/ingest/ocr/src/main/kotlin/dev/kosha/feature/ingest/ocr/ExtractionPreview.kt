package dev.kosha.feature.ingest.ocr

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
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.kosha.core.common.Money
import dev.kosha.core.designsystem.component.AmountText
import dev.kosha.core.designsystem.component.KoshaCard
import dev.kosha.core.designsystem.component.KoshaChip
import dev.kosha.core.designsystem.token.KoshaColors
import dev.kosha.core.designsystem.token.KoshaSpacing
import dev.kosha.core.designsystem.token.KoshaType
import dev.kosha.core.engine.pipeline.ParsedTransaction
import dev.kosha.core.engine.pipeline.TxnType

/**
 * Extraction preview (spec C4): parsed fields, editable, with confidence
 * highlights. Amber marks a field worth a second look — never red, never a
 * warning icon.
 */
@Composable
fun ExtractionPreview(
    preview: IngestPhotoUseCase.Preview,
    onAmountChange: (String) -> Unit,
    onMerchantChange: (String) -> Unit,
    onTypeChange: (TxnType) -> Unit,
    onConfirm: () -> Unit,
    onDiscard: () -> Unit,
) {
    var amountText by remember(preview.uri) {
        mutableStateOf(preview.amount?.format(withSymbol = false, withPaise = false) ?: "")
    }
    var merchantText by remember(preview.uri) { mutableStateOf(preview.merchant.orEmpty()) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(KoshaSpacing.screenPadding),
        verticalArrangement = Arrangement.spacedBy(KoshaSpacing.s),
    ) {
        Text(
            text = stringResource(R.string.preview_title),
            style = KoshaType.Title,
            color = KoshaColors.OffWhite,
        )

        // An unreadable capture used to be discarded outright. It now opens
        // here with empty fields, so a photo Kosha cannot parse still becomes
        // a transaction the user can type — with the image kept as evidence.
        if (preview.nothingExtracted) {
            Text(
                text = stringResource(R.string.preview_nothing_read),
                style = KoshaType.Body,
                color = KoshaColors.Amber,
            )
        }

        FieldLabel(
            label = stringResource(R.string.preview_amount),
            lowConfidence = ParsedTransaction.Field.AMOUNT in preview.lowConfidenceFields,
        )
        TextField(
            value = amountText,
            onValueChange = { text ->
                if (text.all { it.isDigit() || it == '.' || it == ',' }) {
                    amountText = text
                    onAmountChange(text)
                }
            },
            colors = previewFieldColors(),
            modifier = Modifier.fillMaxWidth(),
        )

        Row(horizontalArrangement = Arrangement.spacedBy(KoshaSpacing.xs)) {
            KoshaChip(
                label = "Spent",
                selected = preview.type == TxnType.DEBIT,
                onClick = { onTypeChange(TxnType.DEBIT) },
            )
            KoshaChip(
                label = "Received",
                selected = preview.type == TxnType.CREDIT,
                onClick = { onTypeChange(TxnType.CREDIT) },
                accent = KoshaColors.AccentTeal,
            )
        }

        FieldLabel(
            label = stringResource(R.string.preview_merchant),
            lowConfidence = ParsedTransaction.Field.MERCHANT in preview.lowConfidenceFields,
        )
        TextField(
            value = merchantText,
            onValueChange = {
                merchantText = it
                onMerchantChange(it)
            },
            colors = previewFieldColors(),
            modifier = Modifier.fillMaxWidth(),
        )

        if (preview.reference != null) {
            Text(
                text = stringResource(R.string.preview_reference) + ": " + preview.reference,
                style = KoshaType.Caption,
                color = KoshaColors.OffWhiteFaint,
            )
        }

        if (preview.lineItems.isNotEmpty()) {
            Spacer(Modifier.height(KoshaSpacing.xs))
            Text(
                text = stringResource(R.string.preview_line_items),
                style = KoshaType.Label,
                color = KoshaColors.OffWhiteFaint,
            )
            KoshaCard(modifier = Modifier.fillMaxWidth()) {
                preview.lineItems.forEach { item ->
                    Row(Modifier.fillMaxWidth()) {
                        Text(
                            text = item.name,
                            style = KoshaType.Body,
                            color = KoshaColors.OffWhiteMuted,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                        )
                        AmountText(
                            amount = item.amount,
                            style = KoshaType.AmountSmall,
                            color = KoshaColors.OffWhiteMuted,
                            withPaise = false,
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(KoshaSpacing.s))
        Row(horizontalArrangement = Arrangement.spacedBy(KoshaSpacing.s)) {
            TextButton(onClick = onConfirm, enabled = Money.parseOrNull(amountText) != null) {
                Text(stringResource(R.string.preview_confirm), color = KoshaColors.AccentTeal)
            }
            TextButton(onClick = onDiscard) {
                Text(stringResource(R.string.preview_discard), color = KoshaColors.OffWhiteMuted)
            }
        }
        Spacer(Modifier.height(KoshaSpacing.xxl))
    }
}

@Composable
private fun FieldLabel(label: String, lowConfidence: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(KoshaSpacing.xxs)) {
        Text(label, style = KoshaType.Label, color = KoshaColors.OffWhiteFaint)
        if (lowConfidence) {
            Text(
                text = stringResource(R.string.preview_low_confidence),
                style = KoshaType.Caption,
                color = KoshaColors.Amber,
            )
        }
    }
}

@Composable
fun WarrantyPromptSheet(
    prompt: WarrantyPrompt,
    onSave: (months: Int) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = KoshaColors.CharcoalOverlay) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(KoshaSpacing.m),
            verticalArrangement = Arrangement.spacedBy(KoshaSpacing.s),
        ) {
            Text(
                text = stringResource(R.string.warranty_prompt_title),
                style = KoshaType.Title,
                color = KoshaColors.OffWhite,
            )
            Text(
                text = stringResource(R.string.warranty_prompt_body, prompt.itemName),
                style = KoshaType.Body,
                color = KoshaColors.OffWhiteMuted,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(KoshaSpacing.xs)) {
                listOf(6, 12, 24, 36).forEach { months ->
                    KoshaChip(
                        label = stringResource(R.string.warranty_months, months),
                        onClick = { onSave(months) },
                    )
                }
            }
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.warranty_skip), color = KoshaColors.OffWhiteMuted)
            }
            Spacer(Modifier.height(KoshaSpacing.l))
        }
    }
}

@Composable
private fun previewFieldColors() = TextFieldDefaults.colors(
    focusedContainerColor = KoshaColors.CharcoalRaised,
    unfocusedContainerColor = KoshaColors.CharcoalRaised,
    focusedTextColor = KoshaColors.OffWhite,
    unfocusedTextColor = KoshaColors.OffWhite,
)

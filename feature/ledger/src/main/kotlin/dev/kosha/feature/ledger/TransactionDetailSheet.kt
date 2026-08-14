package dev.kosha.feature.ledger

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import dev.kosha.core.common.Money
import dev.kosha.core.database.model.TxnSource
import dev.kosha.core.database.model.TxnStatus
import dev.kosha.core.database.model.TxnType
import dev.kosha.core.designsystem.component.AmountText
import dev.kosha.core.designsystem.component.KoshaChip
import dev.kosha.core.designsystem.token.KoshaColors
import dev.kosha.core.designsystem.token.KoshaSpacing
import dev.kosha.core.designsystem.token.KoshaType
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * What is behind one ledger row, with the original bank message.
 *
 * A row that reads "a/c no" or carries the wrong merchant is untrustworthy
 * AND unfixable without the source text — you cannot tell whether Kosha
 * misread the message or the bank wrote it that way. Showing the message turns
 * "this app is wrong" into "this message is phrased unusually", which is
 * something either of us can act on.
 *
 * The message is only here when raw retention was on at capture time (spec B4
 * keeps it off by default). When it is missing we say so plainly and offer the
 * toggle, while being clear that it only affects FUTURE scans.
 */
@Composable
fun TransactionDetailSheet(
    detail: TransactionDetail,
    retainRawSms: Boolean,
    onDismiss: () -> Unit,
    onRecategorize: () -> Unit,
    onSetRetainRawSms: (Boolean) -> Unit,
) {
    val row = detail.row
    val txn = row.txn
    val timestamp = remembered(txn.timestampMillis)

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = KoshaColors.CharcoalOverlay) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = KoshaSpacing.m),
            verticalArrangement = Arrangement.spacedBy(KoshaSpacing.s),
        ) {
            Text(
                text = txn.merchantRaw ?: stringResource(R.string.detail_no_merchant),
                style = KoshaType.Title,
                color = KoshaColors.OffWhite,
            )
            AmountText(
                amount = if (txn.type == TxnType.DEBIT) Money(-txn.amountPaise) else Money(txn.amountPaise),
                style = KoshaType.AmountLarge,
                color = if (txn.type == TxnType.CREDIT) KoshaColors.AccentTeal else KoshaColors.OffWhite,
            )

            DetailRow(stringResource(R.string.detail_when), timestamp)
            DetailRow(stringResource(R.string.detail_account), "${row.accountName} · ${txn.type.label()}")
            DetailRow(
                stringResource(R.string.detail_category),
                row.categoryName ?: stringResource(R.string.detail_uncategorized),
            )
            DetailRow(stringResource(R.string.detail_captured_by), txn.source.sourceLabel())
            txn.reference?.let { DetailRow(stringResource(R.string.detail_reference), it) }
            txn.note?.let { DetailRow(stringResource(R.string.detail_note), it) }
            if (txn.status == TxnStatus.PENDING_REVIEW) {
                DetailRow(
                    stringResource(R.string.detail_status),
                    stringResource(R.string.detail_status_review),
                )
            }

            Spacer(Modifier.height(KoshaSpacing.xs))

            when {
                detail.originalMessage != null -> {
                    Text(
                        text = stringResource(R.string.detail_original_message),
                        style = KoshaType.Label,
                        color = KoshaColors.OffWhiteMuted,
                    )
                    // Verbatim, monospaced-ish block so line breaks and the
                    // exact wording are visible — that is the whole value.
                    Text(
                        text = detail.originalMessage,
                        style = KoshaType.Body,
                        color = KoshaColors.OffWhite,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(KoshaSpacing.cardRadius))
                            .background(KoshaColors.CharcoalRaised)
                            .padding(KoshaSpacing.s),
                    )
                }

                detail.photoUri != null -> {
                    DetailRow(stringResource(R.string.detail_photo), detail.photoUri)
                }

                detail.messageNotRetained -> {
                    Text(
                        text = stringResource(R.string.detail_message_missing),
                        style = KoshaType.Caption,
                        color = KoshaColors.OffWhiteMuted,
                    )
                    KoshaChip(
                        label = if (retainRawSms) {
                            stringResource(R.string.detail_keep_messages_on)
                        } else {
                            stringResource(R.string.detail_keep_messages_off)
                        },
                        selected = retainRawSms,
                        onClick = { onSetRetainRawSms(!retainRawSms) },
                        accent = KoshaColors.Amber,
                    )
                }

                else -> {
                    Text(
                        text = stringResource(R.string.detail_no_evidence),
                        style = KoshaType.Caption,
                        color = KoshaColors.OffWhiteFaint,
                    )
                }
            }

            Spacer(Modifier.height(KoshaSpacing.xs))
            KoshaChip(
                label = stringResource(R.string.ledger_recategorize),
                onClick = onRecategorize,
                accent = KoshaColors.AccentTeal,
            )
            Spacer(Modifier.height(KoshaSpacing.xl))
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = KoshaType.Caption,
            color = KoshaColors.OffWhiteFaint,
            modifier = Modifier.weight(0.38f),
        )
        Text(
            text = value,
            style = KoshaType.Caption,
            color = KoshaColors.OffWhiteMuted,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(0.62f),
        )
    }
}

private val DETAIL_FORMAT = DateTimeFormatter.ofPattern("EEE d MMM yyyy, HH:mm")

private fun remembered(millis: Long): String =
    DETAIL_FORMAT.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))

private fun TxnType.label(): String = when (this) {
    TxnType.DEBIT -> "money out"
    TxnType.CREDIT -> "money in"
}

private fun TxnSource.sourceLabel(): String = when (this) {
    TxnSource.SMS -> "bank message"
    TxnSource.OCR -> "photo"
    TxnSource.MANUAL -> "entered by hand"
    TxnSource.RECURRING -> "recurring rule"
}

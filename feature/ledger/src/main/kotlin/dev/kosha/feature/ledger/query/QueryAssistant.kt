package dev.kosha.feature.ledger.query

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import dev.kosha.core.common.Money
import dev.kosha.core.designsystem.component.AmountText
import dev.kosha.core.designsystem.component.KoshaCard
import dev.kosha.core.designsystem.component.KoshaChip
import dev.kosha.core.designsystem.token.KoshaColors
import dev.kosha.core.designsystem.token.KoshaSpacing
import dev.kosha.core.designsystem.token.KoshaType
import dev.kosha.core.engine.query.QueryAnswer
import dev.kosha.feature.ledger.R

/**
 * The search bar doubles as the query assistant (spec C3): typed natural
 * questions are parsed by the template NLU into the same filter the builder
 * produces, and answered with a card.
 */
@Composable
fun QuerySearchBar(
    text: String,
    onTextChange: (String) -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TextField(
        value = text,
        onValueChange = onTextChange,
        placeholder = {
            Text(stringResource(R.string.query_hint), color = KoshaColors.OffWhiteFaint)
        },
        leadingIcon = {
            Icon(Icons.Outlined.Search, contentDescription = null, tint = KoshaColors.OffWhiteFaint)
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = KoshaColors.CharcoalRaised,
            unfocusedContainerColor = KoshaColors.CharcoalRaised,
            focusedTextColor = KoshaColors.OffWhite,
            unfocusedTextColor = KoshaColors.OffWhite,
        ),
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
fun QueryAnswerCard(
    state: QueryUiState,
    onDismiss: () -> Unit,
    onOpenBuilder: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val answer = state.answer ?: return
    KoshaCard(modifier = modifier.fillMaxWidth()) {
        when (answer) {
            is QueryAnswer.Sum -> AnswerRow(
                label = stringResource(R.string.query_answer_sum, answer.count),
                amount = answer.total,
            )
            is QueryAnswer.Average -> AnswerRow(
                label = stringResource(R.string.query_answer_avg, answer.count),
                amount = answer.average,
            )
            is QueryAnswer.Max -> AnswerRow(
                label = answer.merchant ?: stringResource(R.string.query_answer_max),
                amount = answer.amount,
            )
            is QueryAnswer.Count -> Text(
                text = stringResource(R.string.query_answer_count, answer.count),
                style = KoshaType.InsightSerif,
                color = KoshaColors.OffWhite,
            )
            is QueryAnswer.Listing -> Text(
                text = stringResource(R.string.query_answer_list, answer.count),
                style = KoshaType.InsightSerif,
                color = KoshaColors.OffWhite,
            )
            QueryAnswer.Empty -> Text(
                text = stringResource(R.string.query_answer_empty),
                style = KoshaType.InsightSerif,
                color = KoshaColors.OffWhiteMuted,
            )
        }

        if (state.fellBackToBuilder) {
            Spacer(Modifier.height(KoshaSpacing.xs))
            // Graceful failure (spec G8): offer the builder, pre-filled.
            Text(
                text = stringResource(R.string.query_unparsed),
                style = KoshaType.Body,
                color = KoshaColors.Amber,
            )
        }

        Spacer(Modifier.height(KoshaSpacing.xs))
        Row(horizontalArrangement = Arrangement.spacedBy(KoshaSpacing.xs)) {
            KoshaChip(label = stringResource(R.string.query_builder), onClick = onOpenBuilder)
            KoshaChip(label = stringResource(R.string.query_clear), onClick = onDismiss)
        }
    }
}

@Composable
private fun AnswerRow(label: String, amount: Money) {
    Column {
        Text(label, style = KoshaType.Caption, color = KoshaColors.OffWhiteFaint)
        AmountText(
            amount = amount,
            style = KoshaType.AmountLarge,
            withPaise = false,
            countUp = true,
        )
    }
}

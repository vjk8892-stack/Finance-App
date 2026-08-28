package dev.kosha.feature.goals

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.kosha.core.designsystem.token.KoshaColors
import dev.kosha.core.designsystem.token.KoshaSpacing
import dev.kosha.core.designsystem.token.KoshaType

/** Shared between Goals, Debt and Net Worth — the three screens Goals used to be. */
@Composable
internal fun EmptyNote(text: String) {
    Text(text, style = KoshaType.InsightSerif, color = KoshaColors.OffWhiteMuted)
}

@Composable
internal fun EditorSheet(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = KoshaColors.CharcoalOverlay) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(KoshaSpacing.m),
            verticalArrangement = Arrangement.spacedBy(KoshaSpacing.s),
        ) {
            content()
            Spacer(Modifier.height(KoshaSpacing.l))
        }
    }
}

@Composable
internal fun GoalField(value: String, onValueChange: (String) -> Unit, placeholder: String) {
    TextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = { Text(placeholder, color = KoshaColors.OffWhiteFaint) },
        colors = TextFieldDefaults.colors(
            focusedContainerColor = KoshaColors.CharcoalRaised,
            unfocusedContainerColor = KoshaColors.CharcoalRaised,
            focusedTextColor = KoshaColors.OffWhite,
            unfocusedTextColor = KoshaColors.OffWhite,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

package dev.kosha.core.designsystem.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.kosha.core.designsystem.token.KoshaColors
import dev.kosha.core.designsystem.token.KoshaSpacing
import dev.kosha.core.designsystem.token.KoshaType

/**
 * Pill chip (Kosha DS "Bioluminescent Deep-Space"). Selection is a FILLED,
 * glowing accent gradient, not a slightly different grey.
 *
 * The quiet version was unreadable in practice: on a charcoal card, "selected"
 * differed from "not selected" by one step of grey on both border and fill, so
 * users could not tell which filter was active — and an invisible active filter
 * looks exactly like missing data.
 */
@Composable
fun KoshaChip(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    leading: (@Composable () -> Unit)? = null,
    accent: Color = KoshaColors.OffWhite,
) {
    val shape = RoundedCornerShape(percent = 50)
    val border = BorderStroke(
        if (selected) 1.5.dp else 1.dp,
        if (selected) accent else KoshaColors.HudBorderDim,
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .then(
                if (selected) {
                    Modifier.shadow(8.dp, shape, ambientColor = accent.copy(alpha = 0.35f), spotColor = accent.copy(alpha = 0.35f))
                } else {
                    Modifier
                },
            )
            .clip(shape)
            // A gradient fill (not a flat tint) reads clearly as "on" while
            // staying calm enough for a row of six chips.
            .background(
                if (selected) {
                    Brush.horizontalGradient(listOf(accent.copy(alpha = 0.30f), accent.copy(alpha = 0.14f)))
                } else {
                    Brush.horizontalGradient(listOf(KoshaColors.CharcoalRaised, KoshaColors.CharcoalRaised))
                },
            )
            .border(border, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = KoshaSpacing.s, vertical = KoshaSpacing.xs),
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(KoshaSpacing.xxs))
        }
        Text(
            text = label,
            style = if (selected) KoshaType.LabelStrong else KoshaType.Label,
            color = if (selected) accent else KoshaColors.OffWhiteMuted,
        )
    }
}

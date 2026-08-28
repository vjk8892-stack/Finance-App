package dev.kosha.core.designsystem.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.kosha.core.designsystem.token.KoshaColors
import dev.kosha.core.designsystem.token.KoshaShapes
import dev.kosha.core.designsystem.token.KoshaSpacing
import dev.kosha.core.designsystem.token.KoshaType

/**
 * Chamfered chip (Kosha DS v2). Selection is a FILLED accent panel, not a
 * slightly different grey.
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
    val shape = KoshaShapes.chamfered(KoshaSpacing.chipCut)
    val border = BorderStroke(
        if (selected) 1.5.dp else 1.dp,
        if (selected) accent else KoshaColors.HudBorderDim,
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .clip(shape)
            // 18% accent reads clearly as "on" while staying calm enough for a
            // row of six chips.
            .background(if (selected) accent.copy(alpha = 0.18f) else KoshaColors.CharcoalRaised)
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

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.kosha.core.designsystem.token.KoshaColors
import dev.kosha.core.designsystem.token.KoshaSpacing
import dev.kosha.core.designsystem.token.KoshaType

/** Quiet monochrome chip; selection is shown by outline+fill, never color shouting. */
@Composable
fun KoshaChip(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    leading: (@Composable () -> Unit)? = null,
    accent: Color = KoshaColors.OffWhite,
) {
    val shape = RoundedCornerShape(KoshaSpacing.chipRadius)
    val border = BorderStroke(
        1.dp,
        if (selected) KoshaColors.OffWhiteFaint else KoshaColors.Outline,
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .clip(shape)
            .background(if (selected) KoshaColors.CharcoalOverlay else KoshaColors.CharcoalRaised)
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
            style = KoshaType.Label,
            color = if (selected) accent else KoshaColors.OffWhiteMuted,
        )
    }
}

package dev.kosha.core.designsystem.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp
import dev.kosha.core.designsystem.token.KoshaColors
import dev.kosha.core.designsystem.token.KoshaSpacing

/**
 * Glowing glass panel (Kosha DS "Bioluminescent Deep-Space"): rounded
 * corners, a teal-tinted gradient wash, a visibly lit border, and a soft
 * colored glow standing in for elevation on charcoal. The colored glow only
 * renders on API 28+ (RenderNode ambient/spot tinting); below that it falls
 * back to a plain shadow, an acceptable cosmetic-only degradation.
 */
@Composable
fun KoshaCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    contentPadding: androidx.compose.ui.unit.Dp = KoshaSpacing.m,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(KoshaSpacing.cardRadius)
    Column(
        modifier = modifier
            .shadow(
                elevation = 12.dp,
                shape = shape,
                ambientColor = KoshaColors.AccentTeal.copy(alpha = 0.25f),
                spotColor = KoshaColors.AccentViolet.copy(alpha = 0.25f),
            )
            .clip(shape)
            .background(Brush.linearGradient(listOf(KoshaColors.GlassTop, KoshaColors.GlassBottom)))
            .border(BorderStroke(1.dp, KoshaColors.HudBorder), shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(contentPadding),
        content = content,
    )
}

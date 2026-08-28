package dev.kosha.core.designsystem.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp
import dev.kosha.core.designsystem.token.KoshaColors
import dev.kosha.core.designsystem.token.KoshaShapes
import dev.kosha.core.designsystem.token.KoshaSpacing

/**
 * Flat raised "glass panel" surface (Kosha DS v2) — chamfered corners, a
 * thin teal-tinted hairline, and a barely-there top-to-bottom gradient
 * standing in for a glass edge highlight. No elevation shadows on charcoal.
 */
@Composable
fun KoshaCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    contentPadding: androidx.compose.ui.unit.Dp = KoshaSpacing.m,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = KoshaShapes.chamfered(KoshaSpacing.cardCut)
    Column(
        modifier = modifier
            .clip(shape)
            .background(Brush.verticalGradient(listOf(KoshaColors.GlassTop, KoshaColors.GlassBottom)))
            .border(BorderStroke(1.dp, KoshaColors.HudBorderDim), shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(contentPadding),
        content = content,
    )
}

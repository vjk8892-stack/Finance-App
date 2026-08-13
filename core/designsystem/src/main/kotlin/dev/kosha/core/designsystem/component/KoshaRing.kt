package dev.kosha.core.designsystem.component

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.kosha.core.designsystem.token.KoshaColors
import dev.kosha.core.designsystem.token.KoshaMotion

/**
 * Progress ring. Two modes:
 *  - plain track+progress in a caller-chosen color (budget rings — amber at
 *    threshold, per spec C2);
 *  - [gradient]=true sweeps the teal→violet accent (Pulse ring ONLY).
 *  - [breathing]=true adds the 4s stroke-width "breath" (Pulse hero).
 */
@Composable
fun KoshaRing(
    progress: Float,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    strokeWidth: Dp = 5.dp,
    color: Color = KoshaColors.OffWhiteMuted,
    trackColor: Color = KoshaColors.Outline,
    gradient: Boolean = false,
    breathing: Boolean = false,
) {
    val clamped = progress.coerceIn(0f, 1f)
    val breathScale by if (breathing) {
        rememberInfiniteTransition(label = "pulseBreath").animateFloat(
            initialValue = 0.92f,
            targetValue = 1.08f,
            animationSpec = infiniteRepeatable(
                animation = tween(KoshaMotion.PulseBreatheMs / 2, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "pulseBreathValue",
        )
    } else {
        androidx.compose.runtime.mutableFloatStateOf(1f)
    }

    Canvas(modifier = modifier.size(size)) {
        val stroke = Stroke(width = strokeWidth.toPx() * breathScale, cap = StrokeCap.Round)
        val inset = stroke.width / 2
        val arcSize = Size(this.size.width - stroke.width, this.size.height - stroke.width)
        val topLeft = Offset(inset, inset)

        drawArc(
            color = trackColor,
            startAngle = -90f,
            sweepAngle = 360f,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = stroke,
        )
        if (clamped > 0f) {
            val brush = if (gradient) {
                Brush.sweepGradient(listOf(KoshaColors.AccentTeal, KoshaColors.AccentViolet, KoshaColors.AccentTeal))
            } else {
                Brush.linearGradient(listOf(color, color))
            }
            drawArc(
                brush = brush,
                startAngle = -90f,
                sweepAngle = 360f * clamped,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = stroke,
            )
        }
    }
}

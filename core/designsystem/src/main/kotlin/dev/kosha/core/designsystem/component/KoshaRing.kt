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
import kotlin.math.cos
import kotlin.math.sin

/**
 * Progress ring. Two modes:
 *  - plain track+progress in a caller-chosen color (budget rings — amber at
 *    threshold, per spec C2);
 *  - [gradient]=true sweeps the teal→violet accent (Pulse ring ONLY).
 *  - [breathing]=true adds the 4s stroke-width "breath" (Pulse hero).
 *  - [dial]=true adds a radar-style tick ring and a faint outer glow arc
 *    (Kosha DS v2) — reserved for hero use (the Pulse ring), so a 48dp budget
 *    ring in a horizontal scroll doesn't pick up the same weight.
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
    dial: Boolean = false,
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

        if (dial) {
            // Radar-dial tick marks around the circumference, plus a faint
            // outer glow ring — the one deliberate "instrument" flourish on
            // the app's single hero element.
            val center = Offset(this.size.width / 2, this.size.height / 2)
            val tickOuter = this.size.minDimension / 2
            val tickInner = tickOuter - stroke.width * 0.9f
            for (i in 0 until 48) {
                val angle = Math.toRadians((i * 7.5) - 90.0)
                val major = i % 6 == 0
                val from = Offset(
                    center.x + (tickInner - if (major) 3f else 0f).toFloat() * cos(angle).toFloat(),
                    center.y + (tickInner - if (major) 3f else 0f).toFloat() * sin(angle).toFloat(),
                )
                val to = Offset(
                    center.x + tickOuter * cos(angle).toFloat(),
                    center.y + tickOuter * sin(angle).toFloat(),
                )
                drawLine(
                    color = KoshaColors.HudBorderDim,
                    start = from,
                    end = to,
                    strokeWidth = if (major) 2f else 1f,
                )
            }
            drawArc(
                color = KoshaColors.AccentTeal.copy(alpha = 0.22f),
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = Offset(inset - 10f, inset - 10f),
                size = Size(arcSize.width + 20f, arcSize.height + 20f),
                style = Stroke(width = stroke.width + 18f, cap = StrokeCap.Round),
            )
        }

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

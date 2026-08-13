package dev.kosha.feature.insights.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import dev.kosha.core.common.Money
import dev.kosha.core.designsystem.token.KoshaColors
import dev.kosha.core.designsystem.token.KoshaType
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Spending DNA radar (spec C5.3): this month's category fingerprint over the
 * 3-month average. Each axis is normalized against the larger of the two
 * series, so shape — not absolute size — is what the eye compares.
 */
data class RadarAxis(
    val label: String,
    val current: Money,
    val baseline: Money,
)

@Composable
fun SpendingDnaRadar(
    axes: List<RadarAxis>,
    modifier: Modifier = Modifier,
) {
    if (axes.size < 3) return // a radar needs at least a triangle
    val textMeasurer = rememberTextMeasurer()
    val max = axes.maxOf { maxOf(it.current.paise, it.baseline.paise) }.coerceAtLeast(1)

    val description = "Spending fingerprint. " + axes.joinToString {
        "${it.label} ${it.current.format(withPaise = false)} versus average ${it.baseline.format(withPaise = false)}"
    }

    Canvas(
        modifier
            .fillMaxWidth()
            .height(260.dp)
            .semantics { contentDescription = description },
    ) {
        val center = Offset(size.width / 2, size.height / 2)
        val radius = min(size.width, size.height) / 2 - 28.dp.toPx()

        // Web rings — quiet, evenly spaced.
        for (ring in 1..RINGS) {
            drawRadarPolygon(
                center = center,
                radii = List(axes.size) { radius * ring / RINGS },
                color = KoshaColors.Outline,
                filled = false,
                strokeWidth = 1f,
            )
        }

        // Baseline (3-month average) — muted outline.
        drawRadarPolygon(
            center = center,
            radii = axes.map { radius * (it.baseline.paise.toFloat() / max) },
            color = KoshaColors.OffWhiteFaint,
            filled = false,
            strokeWidth = 2f,
        )

        // This month — accent fill.
        drawRadarPolygon(
            center = center,
            radii = axes.map { radius * (it.current.paise.toFloat() / max) },
            color = KoshaColors.AccentTeal,
            filled = true,
            strokeWidth = 2.5f,
        )

        axes.forEachIndexed { index, axis ->
            val angle = angleFor(index, axes.size)
            val labelRadius = radius + 14.dp.toPx()
            val layout = textMeasurer.measure(
                text = axis.label,
                style = KoshaType.Caption.copy(color = KoshaColors.OffWhiteMuted),
            )
            drawText(
                textLayoutResult = layout,
                topLeft = Offset(
                    x = center.x + cos(angle) * labelRadius - layout.size.width / 2,
                    y = center.y + sin(angle) * labelRadius - layout.size.height / 2,
                ),
            )
        }
    }
}

private const val RINGS = 3

private fun angleFor(index: Int, count: Int): Float =
    (-PI / 2 + 2 * PI * index / count).toFloat()

private fun DrawScope.drawRadarPolygon(
    center: Offset,
    radii: List<Float>,
    color: androidx.compose.ui.graphics.Color,
    filled: Boolean,
    strokeWidth: Float,
) {
    val path = Path()
    radii.forEachIndexed { index, radius ->
        val angle = angleFor(index, radii.size)
        val point = Offset(center.x + cos(angle) * radius, center.y + sin(angle) * radius)
        if (index == 0) path.moveTo(point.x, point.y) else path.lineTo(point.x, point.y)
    }
    path.close()
    if (filled) {
        drawPath(path, color = color.copy(alpha = 0.18f))
    }
    drawPath(
        path = path,
        color = color,
        style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
    )
}

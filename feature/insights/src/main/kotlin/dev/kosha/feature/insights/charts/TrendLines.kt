package dev.kosha.feature.insights.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import dev.kosha.core.database.repo.InsightsRepository
import dev.kosha.core.designsystem.token.KoshaColors
import dev.kosha.core.designsystem.token.KoshaSpacing
import dev.kosha.core.designsystem.token.KoshaType

/**
 * Trajectory (spec C5.4): 12-month income / expense / savings-gap lines.
 * Drawn on Canvas rather than pulled from a chart library — three polylines
 * do not justify the dependency, and this keeps the DS palette exact.
 */
@Composable
fun TrendLines(
    points: List<InsightsRepository.TrendPoint>,
    modifier: Modifier = Modifier,
    onSelect: ((InsightsRepository.TrendPoint) -> Unit)? = null,
) {
    if (points.isEmpty()) return

    val incomes = points.map { it.income.paise }
    val expenses = points.map { it.expense.paise }
    val gaps = points.map { it.savingsGap.paise }
    val maxValue = maxOf(incomes.max(), expenses.max(), gaps.max(), 1L)
    val minValue = minOf(gaps.min(), 0L)
    val span = (maxValue - minValue).coerceAtLeast(1)

    val textMeasurer = rememberTextMeasurer()
    val axisStyle = KoshaType.Caption.copy(color = KoshaColors.OffWhiteFaint)

    val last = points.last()
    val description = "Twelve month trajectory. Latest income " +
        "${last.income.format(withPaise = false)}, spending " +
        "${last.expense.format(withPaise = false)}, savings gap " +
        last.savingsGap.format(withPaise = false)

    Column(modifier.fillMaxWidth()) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(180.dp)
                // Every other chart opens the rows behind it; this one and the
                // radar were the two that still just sat there. A month is the
                // obvious unit here, so tapping anywhere in a month's column
                // opens it.
                .then(
                    if (onSelect != null && points.isNotEmpty()) {
                        Modifier.pointerInput(points) {
                            detectTapGestures { offset ->
                                val slot = size.width / points.size
                                val index = (offset.x / slot).toInt().coerceIn(0, points.lastIndex)
                                onSelect(points[index])
                            }
                        }
                    } else {
                        Modifier
                    },
                )
                .semantics { contentDescription = description },
        ) {
            fun yFor(value: Long) = size.height - ((value - minValue).toFloat() / span) * size.height
            val stepX = if (points.size > 1) size.width / (points.size - 1) else size.width

            if (minValue < 0) {
                val zeroY = yFor(0)
                drawLine(
                    color = KoshaColors.Outline,
                    start = Offset(0f, zeroY),
                    end = Offset(size.width, zeroY),
                    strokeWidth = 1f,
                )
            }

            drawSeries(incomes, stepX, KoshaColors.AccentTeal, ::yFor)
            drawSeries(expenses, stepX, KoshaColors.OffWhiteMuted, ::yFor)
            drawSeries(gaps, stepX, KoshaColors.AccentViolet, ::yFor)

            // Axis labels. Three unlabelled lines over twelve unnamed months
            // is a shape, not a chart — there was no way to tell which end was
            // now, let alone which month a bend belonged to.
            val firstLabel = textMeasurer.measure(MONTH_AXIS.format(points.first().period.start), axisStyle)
            val lastLabel = textMeasurer.measure(MONTH_AXIS.format(points.last().period.start), axisStyle)
            drawText(firstLabel, topLeft = Offset(0f, size.height - firstLabel.size.height))
            drawText(
                lastLabel,
                topLeft = Offset(size.width - lastLabel.size.width, size.height - lastLabel.size.height),
            )
        }
        Spacer(Modifier.height(KoshaSpacing.xs))
        Row(horizontalArrangement = Arrangement.spacedBy(KoshaSpacing.s)) {
            LegendDot("Income", KoshaColors.AccentTeal)
            LegendDot("Spent", KoshaColors.OffWhiteMuted)
            LegendDot("Gap", KoshaColors.AccentViolet)
        }
    }
}

private val MONTH_AXIS: java.time.format.DateTimeFormatter =
    java.time.format.DateTimeFormatter.ofPattern("MMM yy")

private fun DrawScope.drawSeries(
    values: List<Long>,
    stepX: Float,
    color: Color,
    yFor: (Long) -> Float,
) {
    val path = Path()
    values.forEachIndexed { index, value ->
        val x = index * stepX
        val y = yFor(value)
        if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    drawPath(path, color = color, style = Stroke(width = 2.5f, cap = StrokeCap.Round))
}

@Composable
private fun LegendDot(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(8.dp)
                .then(Modifier),
        ) {
            Canvas(Modifier.size(8.dp)) { drawCircle(color) }
        }
        Spacer(Modifier.width(KoshaSpacing.xxs))
        Text(label, style = KoshaType.Caption, color = KoshaColors.OffWhiteFaint)
    }
}

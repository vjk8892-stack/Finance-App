package dev.kosha.feature.insights.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import dev.kosha.core.common.Money
import dev.kosha.core.designsystem.token.KoshaColors
import dev.kosha.core.designsystem.token.KoshaType

data class MonthBar(
    /** Short month label, e.g. "Aug". */
    val label: String,
    val spent: Money,
    val income: Money,
    val isCurrent: Boolean,
)

/**
 * Month-by-month spending against a budget line.
 *
 * The Sankey, treemap and radar all answer "how is this month divided?", which
 * is a question you can only ask once you have categories. The question people
 * actually start with is "am I spending more than usual, and more than I meant
 * to?" — that needs bars over time and a line to compare them against, so this
 * sits at the top of the Insights tab.
 *
 * Deliberately plain: labelled bars, a dashed budget line, and the value on
 * the tallest bar. No gradient — the accent is reserved for money-flow visuals.
 */
@Composable
fun MonthlyBars(
    months: List<MonthBar>,
    budget: Money?,
    modifier: Modifier = Modifier,
) {
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = KoshaType.Caption.copy(color = KoshaColors.OffWhiteFaint)
    val valueStyle = KoshaType.Caption.copy(color = KoshaColors.OffWhiteMuted)

    val peak = maxOf(
        months.maxOfOrNull { it.spent.paise } ?: 0L,
        budget?.paise ?: 0L,
    ).coerceAtLeast(1L)

    val description = buildString {
        append("Monthly spending. ")
        months.forEach { append("${it.label} ${it.spent.format(withPaise = false)}. ") }
        budget?.let { append("Budget ${it.format(withPaise = false)}.") }
    }

    Canvas(
        modifier
            .fillMaxWidth()
            .height(200.dp)
            .semantics { contentDescription = description },
    ) {
        if (months.isEmpty()) return@Canvas

        val labelHeight = textMeasurer.measure("Aug", labelStyle).size.height + 8.dp.toPx()
        val valueHeight = textMeasurer.measure("₹0", valueStyle).size.height + 6.dp.toPx()
        val plotHeight = size.height - labelHeight - valueHeight
        val slot = size.width / months.size
        val barWidth = (slot * 0.56f).coerceAtMost(40.dp.toPx())

        months.forEachIndexed { index, month ->
            val fraction = month.spent.paise.toFloat() / peak
            val barHeight = plotHeight * fraction
            val left = index * slot + (slot - barWidth) / 2f
            val top = valueHeight + (plotHeight - barHeight)

            // The current month is the one being judged, so it reads brighter
            // than the history it is being compared against.
            val overBudget = budget != null && month.spent.paise > budget.paise
            drawRect(
                color = when {
                    overBudget -> KoshaColors.Amber
                    month.isCurrent -> KoshaColors.OffWhiteMuted
                    else -> KoshaColors.Outline
                },
                topLeft = Offset(left, top),
                size = Size(barWidth, barHeight.coerceAtLeast(1f)),
            )

            // Value only on the current month; every bar labelled is noise.
            if (month.isCurrent && month.spent.paise > 0) {
                val layout = textMeasurer.measure(month.spent.format(withPaise = false), valueStyle)
                drawText(
                    layout,
                    topLeft = Offset(
                        (left + barWidth / 2f - layout.size.width / 2f).coerceIn(0f, size.width - layout.size.width),
                        (top - layout.size.height - 2.dp.toPx()).coerceAtLeast(0f),
                    ),
                )
            }

            val labelLayout = textMeasurer.measure(month.label, labelStyle)
            drawText(
                labelLayout,
                topLeft = Offset(
                    left + barWidth / 2f - labelLayout.size.width / 2f,
                    size.height - labelHeight + 6.dp.toPx(),
                ),
            )
        }

        // Budget line last so it sits over the bars it is judging.
        budget?.takeIf { it.paise > 0 }?.let { limit ->
            val y = valueHeight + plotHeight * (1f - limit.paise.toFloat() / peak)
            drawLine(
                color = KoshaColors.AccentTeal,
                start = Offset(0f, y),
                end = Offset(size.width, y),
                strokeWidth = 1.5.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(
                    floatArrayOf(8.dp.toPx(), 6.dp.toPx()),
                ),
            )
            val layout = textMeasurer.measure(
                limit.format(withPaise = false),
                KoshaType.Caption.copy(color = KoshaColors.AccentTeal),
            )
            drawRect(
                color = KoshaColors.CharcoalRaised,
                topLeft = Offset(size.width - layout.size.width - 4.dp.toPx(), y - layout.size.height - 2.dp.toPx()),
                size = Size(layout.size.width + 4.dp.toPx(), layout.size.height.toFloat()),
            )
            drawText(
                layout,
                topLeft = Offset(
                    size.width - layout.size.width - 2.dp.toPx(),
                    (y - layout.size.height - 2.dp.toPx()).coerceAtLeast(0f),
                ),
            )
        }

        // Baseline, so bars sit on something rather than floating.
        drawLine(
            color = KoshaColors.Outline,
            start = Offset(0f, valueHeight + plotHeight),
            end = Offset(size.width, valueHeight + plotHeight),
            strokeWidth = 1.dp.toPx(),
        )
    }
}

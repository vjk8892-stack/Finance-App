package dev.kosha.feature.insights.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import dev.kosha.core.common.Money
import dev.kosha.core.designsystem.token.KoshaColors
import dev.kosha.core.designsystem.token.KoshaType
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Calendar heatmap (spec C5.2): daily spend intensity. Intensity is rendered
 * as teal→violet saturation on charcoal — no red-to-green scale anywhere.
 */
@Composable
fun CalendarHeatmap(
    dailySpend: Map<LocalDate, Money>,
    monthStart: LocalDate,
    monthEnd: LocalDate,
    modifier: Modifier = Modifier,
) {
    val max = dailySpend.values.maxOfOrNull { it.paise } ?: 0L
    val days = ChronoUnit.DAYS.between(monthStart, monthEnd).toInt() + 1
    val busiest = dailySpend.maxByOrNull { it.value.paise }

    val description = if (busiest == null) {
        "Daily spending heatmap, no spending recorded"
    } else {
        "Daily spending heatmap. Busiest day ${busiest.key}, " +
            busiest.value.format(withPaise = false)
    }

    Canvas(
        modifier
            .fillMaxWidth()
            .height(140.dp)
            .semantics { contentDescription = description },
    ) {
        val columns = 7
        val rows = ((days + (monthStart.dayOfWeek.value - 1) + columns - 1) / columns).coerceAtLeast(1)
        val cellGap = 3.dp.toPx()
        val cellWidth = (size.width - cellGap * (columns - 1)) / columns
        val cellHeight = ((size.height - cellGap * (rows - 1)) / rows).coerceAtMost(cellWidth)
        val leadingBlanks = monthStart.dayOfWeek.value - 1 // Monday-first grid

        for (dayIndex in 0 until days) {
            val date = monthStart.plusDays(dayIndex.toLong())
            val slot = leadingBlanks + dayIndex
            val column = slot % columns
            val row = slot / columns
            val spend = dailySpend[date]?.paise ?: 0L
            val intensity = if (max <= 0) 0f else (spend.toFloat() / max)

            drawRect(
                color = heatColor(intensity),
                topLeft = Offset(column * (cellWidth + cellGap), row * (cellHeight + cellGap)),
                size = Size(cellWidth, cellHeight),
            )
        }
    }
}

private fun heatColor(intensity: Float): Color = when {
    intensity <= 0f -> KoshaColors.CharcoalRaised
    intensity < 0.5f -> lerp(KoshaColors.CharcoalOverlay, KoshaColors.AccentTeal, intensity * 2f)
    else -> lerp(KoshaColors.AccentTeal, KoshaColors.AccentViolet, (intensity - 0.5f) * 2f)
}

/**
 * Category treemap (spec C5.3): squarified-ish slice-and-dice layout. Areas
 * are proportional to spend; the palette stays monochrome so the accent
 * gradient remains reserved for money-flow visuals.
 */
data class TreemapSlice(val label: String, val amount: Money)

@Composable
fun CategoryTreemap(
    slices: List<TreemapSlice>,
    modifier: Modifier = Modifier,
) {
    val textMeasurer = rememberTextMeasurer()
    val ordered = slices.filter { it.amount.paise > 0 }.sortedByDescending { it.amount.paise }
    val total = ordered.sumOf { it.amount.paise }.coerceAtLeast(1)

    val description = "Category treemap: " +
        ordered.joinToString { "${it.label} ${it.amount.format(withPaise = false)}" }

    Canvas(
        modifier
            .fillMaxWidth()
            .height(220.dp)
            .semantics { contentDescription = description },
    ) {
        var x = 0f
        var y = 0f
        var remainingWidth = size.width
        var remainingHeight = size.height
        var horizontal = true
        var remainingTotal = total
        val gap = 2.dp.toPx()

        ordered.forEachIndexed { index, slice ->
            val fraction = slice.amount.paise.toFloat() / remainingTotal
            val isLast = index == ordered.lastIndex

            val rectWidth: Float
            val rectHeight: Float
            if (isLast) {
                rectWidth = remainingWidth
                rectHeight = remainingHeight
            } else if (horizontal) {
                rectWidth = remainingWidth * fraction
                rectHeight = remainingHeight
            } else {
                rectWidth = remainingWidth
                rectHeight = remainingHeight * fraction
            }

            // Alternating tones keep neighbours distinguishable without color.
            drawRect(
                color = if (index % 2 == 0) KoshaColors.CharcoalRaised else KoshaColors.CharcoalOverlay,
                topLeft = Offset(x, y),
                size = Size((rectWidth - gap).coerceAtLeast(0f), (rectHeight - gap).coerceAtLeast(0f)),
            )

            if (rectWidth > 56.dp.toPx() && rectHeight > 26.dp.toPx()) {
                val layout = textMeasurer.measure(
                    text = slice.label,
                    style = KoshaType.Caption.copy(color = KoshaColors.OffWhiteMuted),
                )
                drawText(layout, topLeft = Offset(x + 6.dp.toPx(), y + 6.dp.toPx()))
            }

            if (!isLast) {
                if (horizontal) {
                    x += rectWidth
                    remainingWidth -= rectWidth
                } else {
                    y += rectHeight
                    remainingHeight -= rectHeight
                }
                remainingTotal -= slice.amount.paise
                horizontal = !horizontal
            }
        }
    }
}

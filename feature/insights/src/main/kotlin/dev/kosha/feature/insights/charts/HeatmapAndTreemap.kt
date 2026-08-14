package dev.kosha.feature.insights.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
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

    val textMeasurer = rememberTextMeasurer()
    val weekdayStyle = KoshaType.Caption.copy(color = KoshaColors.OffWhiteFaint)

    Canvas(
        modifier
            .fillMaxWidth()
            .height(184.dp)
            .semantics { contentDescription = description },
    ) {
        val columns = 7
        val rows = ((days + (monthStart.dayOfWeek.value - 1) + columns - 1) / columns).coerceAtLeast(1)
        val cellGap = 3.dp.toPx()
        val cellWidth = (size.width - cellGap * (columns - 1)) / columns

        // Without a weekday strip the grid reads as scattered squares rather
        // than a month — the whole point is spotting "my weekends are heavy".
        val headerHeight = textMeasurer.measure("M", weekdayStyle).size.height + 6.dp.toPx()
        WEEKDAYS.forEachIndexed { index, initial ->
            val layout = textMeasurer.measure(initial, weekdayStyle)
            drawText(
                layout,
                topLeft = Offset(
                    index * (cellWidth + cellGap) + (cellWidth - layout.size.width) / 2f,
                    0f,
                ),
            )
        }

        val gridHeight = size.height - headerHeight
        val cellHeight = ((gridHeight - cellGap * (rows - 1)) / rows).coerceAtMost(cellWidth)
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
                topLeft = Offset(
                    column * (cellWidth + cellGap),
                    headerHeight + row * (cellHeight + cellGap),
                ),
                size = Size(cellWidth, cellHeight),
            )
        }
    }
}

private val WEEKDAYS = listOf("M", "T", "W", "T", "F", "S", "S")

/**
 * A no-spend day still has to be VISIBLE, or the month has holes in it and
 * the grid stops reading as a calendar. The empty tone was `CharcoalRaised` —
 * the card's own background — so those cells were invisible, and the lower
 * half of the ramp started from `CharcoalOverlay`, barely better. The ramp now
 * starts above the card surface.
 */
private fun heatColor(intensity: Float): Color = when {
    intensity <= 0f -> KoshaColors.Outline
    intensity < 0.5f -> lerp(KoshaColors.Outline, KoshaColors.AccentTeal, intensity * 2f)
    else -> lerp(KoshaColors.AccentTeal, KoshaColors.AccentViolet, (intensity - 0.5f) * 2f)
}

/**
 * Category treemap (spec C5.3): squarified-ish slice-and-dice layout. Areas
 * are proportional to spend; the palette stays monochrome so the accent
 * gradient remains reserved for money-flow visuals.
 */
data class TreemapSlice(val label: String, val amount: Money)

/** Lightest treemap tone — clearly above the card, still monochrome. */
private val SLICE_TOP = Color(0xFF3B424B)

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

            val sliceSize = Size(
                (rectWidth - gap).coerceAtLeast(0f),
                (rectHeight - gap).coerceAtLeast(0f),
            )
            // Graduated tones ABOVE the card surface. The previous alternation
            // used CharcoalRaised, which IS the card background, so every
            // even-index slice was invisible — with a single category the
            // whole chart rendered as an empty box. Accent colours stay
            // reserved for money-flow visuals, so this ramps grey and leans on
            // an outline to separate neighbours.
            // A lone slice sits mid-ramp: the bottom of the ramp is close
            // enough to the card that a single-category chart would look
            // blank again.
            val step = if (ordered.size <= 1) 0.5f else index.toFloat() / (ordered.size - 1)
            drawRect(
                color = lerp(KoshaColors.CharcoalOverlay, SLICE_TOP, step),
                topLeft = Offset(x, y),
                size = sliceSize,
            )
            drawRect(
                color = KoshaColors.Outline,
                topLeft = Offset(x, y),
                size = sliceSize,
                style = Stroke(width = 1.dp.toPx()),
            )

            if (rectWidth > 56.dp.toPx() && rectHeight > 26.dp.toPx()) {
                val layout = textMeasurer.measure(
                    text = slice.label,
                    style = KoshaType.Caption.copy(color = KoshaColors.OffWhite),
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

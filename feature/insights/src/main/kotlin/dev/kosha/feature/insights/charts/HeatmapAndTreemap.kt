package dev.kosha.feature.insights.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    onSelectDay: ((LocalDate) -> Unit)? = null,
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
    val dayStyle = KoshaType.Caption.copy(fontSize = 9.sp)

    Canvas(
        modifier
            .fillMaxWidth()
            .height(184.dp)
            // A dark cell is a claim that a lot happened that day; tapping it
            // should show what.
            .then(
                if (onSelectDay != null) {
                    Modifier.pointerInput(monthStart, days) {
                        detectTapGestures { offset ->
                            val columns = 7
                            val cellGap = 3.dp.toPx()
                            val cellWidth = (size.width - cellGap * (columns - 1)) / columns
                            val header = size.height * HEADER_FRACTION
                            val rows = ((days + (monthStart.dayOfWeek.value - 1) + columns - 1) / columns)
                                .coerceAtLeast(1)
                            val cellHeight = ((size.height - header - cellGap * (rows - 1)) / rows)
                                .coerceAtMost(cellWidth)
                            if (offset.y < header) return@detectTapGestures
                            val column = (offset.x / (cellWidth + cellGap)).toInt()
                            val rowIndex = ((offset.y - header) / (cellHeight + cellGap)).toInt()
                            val slot = rowIndex * columns + column
                            val dayIndex = slot - (monthStart.dayOfWeek.value - 1)
                            if (dayIndex in 0 until days) {
                                onSelectDay(monthStart.plusDays(dayIndex.toLong()))
                            }
                        }
                    }
                } else {
                    Modifier
                },
            )
            .semantics { contentDescription = description },
    ) {
        val columns = 7
        val rows = ((days + (monthStart.dayOfWeek.value - 1) + columns - 1) / columns).coerceAtLeast(1)
        val cellGap = 3.dp.toPx()
        val cellWidth = (size.width - cellGap * (columns - 1)) / columns

        // Without a weekday strip the grid reads as scattered squares rather
        // than a month — the whole point is spotting "my weekends are heavy".
        val headerHeight = size.height * HEADER_FRACTION
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

            val cellLeft = column * (cellWidth + cellGap)
            val cellTop = headerHeight + row * (cellHeight + cellGap)
            drawRect(
                color = heatColor(intensity),
                topLeft = Offset(cellLeft, cellTop),
                size = Size(cellWidth, cellHeight),
            )

            // The day number, quietly. Without it the grid says "some day in
            // the third week was heavy" and stops there — the whole reason to
            // tap a cell is to know WHICH day, and having to count squares to
            // work that out is not reading a calendar.
            val label = textMeasurer.measure(
                text = date.dayOfMonth.toString(),
                style = dayStyle.copy(
                    // Dark cells carry a bright number and pale ones a dim
                    // number, so the date stays legible at both ends of the
                    // ramp rather than being tuned for the middle.
                    color = KoshaColors.OffWhite.copy(alpha = if (intensity > 0.35f) 0.85f else 0.4f),
                ),
            )
            if (label.size.width < cellWidth && label.size.height < cellHeight) {
                drawText(
                    label,
                    topLeft = Offset(
                        cellLeft + (cellWidth - label.size.width) / 2f,
                        cellTop + (cellHeight - label.size.height) / 2f,
                    ),
                )
            }
        }
    }
}

private val WEEKDAYS = listOf("M", "T", "W", "T", "F", "S", "S")

/**
 * Share of the canvas the weekday strip takes. Hit-testing runs outside the
 * DrawScope that measures it, so the two agree via this constant rather than
 * by both guessing.
 */
private const val HEADER_FRACTION = 0.14f

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

@Composable
fun CategoryTreemap(
    slices: List<TreemapSlice>,
    modifier: Modifier = Modifier,
    onSelect: ((String) -> Unit)? = null,
) {
    val textMeasurer = rememberTextMeasurer()
    val ordered = slices.filter { it.amount.paise > 0 }.sortedByDescending { it.amount.paise }
    val total = ordered.sumOf { it.amount.paise }.coerceAtLeast(1)

    val description = "Category treemap: " +
        ordered.joinToString { "${it.label} ${it.amount.format(withPaise = false)}" }

    // Slice geometry is computed during the draw pass; hit-testing reads the
    // same rectangles rather than recomputing the layout and drifting from it.
    val hitBoxes = remember(ordered) { mutableStateListOf<Pair<String, Rect>>() }

    Canvas(
        modifier
            .fillMaxWidth()
            .height(220.dp)
            .then(
                if (onSelect != null) {
                    Modifier.pointerInput(ordered) {
                        detectTapGestures { offset ->
                            hitBoxes.firstOrNull { it.second.contains(offset) }
                                ?.let { onSelect(it.first) }
                        }
                    }
                } else {
                    Modifier
                },
            )
            .semantics { contentDescription = description },
    ) {
        hitBoxes.clear()
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
            hitBoxes += slice.label to Rect(x, y, x + rectWidth, y + rectHeight)
            // Same colour the category wears in the ledger, so the eye can
            // carry a category between screens without re-reading labels.
            drawRect(
                color = KoshaColors.categoryColor(slice.label).copy(alpha = 0.55f),
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

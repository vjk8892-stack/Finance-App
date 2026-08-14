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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import dev.kosha.core.common.Money
import dev.kosha.core.designsystem.token.KoshaColors
import dev.kosha.core.designsystem.token.KoshaType

/**
 * Sankey flow (spec C5.1): Income → Categories → Savings. The accent
 * gradient showpiece — hand-rolled because no Compose Sankey exists.
 *
 * Layout: income column on the left, category ribbons on the right, sized
 * proportionally, with savings rendered as the final ribbon.
 */
data class SankeyFlow(val label: String, val amount: Money)

@Composable
fun SankeyChart(
    income: Money,
    flows: List<SankeyFlow>,
    savings: Money,
    modifier: Modifier = Modifier,
    onSelect: ((SankeyFlow) -> Unit)? = null,
) {
    val textMeasurer = rememberTextMeasurer()
    val allFlows = remember(flows, savings) {
        (flows + SankeyFlow(SAVINGS_LABEL, savings)).filter { it.amount.paise > 0 }
    }
    // Nothing has flowed yet — an income bar pointing at nothing reads as a
    // rendering fault, so draw nothing and let the caller show its empty note.
    if (allFlows.isEmpty()) return
    val total = allFlows.sumOf { it.amount.paise }.coerceAtLeast(1)

    val description = "Money flow: income ${income.format(withPaise = false)} splits into " +
        allFlows.joinToString { "${it.label} ${it.amount.format(withPaise = false)}" }

    // Each flow's band on the destination side, recorded while drawing so a tap
    // resolves against what is actually on screen. Bands are stacked and never
    // overlap, so a tap at any height maps to exactly one flow — including the
    // thin ones, which are the whole reason ribbons get tapped at all.
    val bands = remember(allFlows) { mutableStateListOf<Pair<SankeyFlow, ClosedFloatingPointRange<Float>>>() }

    Canvas(
        modifier
            .fillMaxWidth()
            .height(260.dp)
            .then(
                if (onSelect != null) {
                    Modifier.pointerInput(allFlows) {
                        detectTapGestures { offset ->
                            bands.firstOrNull { offset.y in it.second }?.let { onSelect(it.first) }
                        }
                    }
                } else {
                    Modifier
                },
            )
            .semantics { contentDescription = description },
    ) {
        bands.clear()
        val nodeWidth = 14.dp.toPx()
        val gap = 4.dp.toPx()
        val leftX = 0f
        val rightX = size.width - nodeWidth
        val availableHeight = size.height - gap * (allFlows.size - 1).coerceAtLeast(0)

        // Income node — one solid bar spanning the full height.
        drawRect(
            brush = Brush.verticalGradient(listOf(KoshaColors.AccentTeal, KoshaColors.AccentViolet)),
            topLeft = Offset(leftX, 0f),
            size = Size(nodeWidth, size.height),
        )

        var sourceY = 0f
        var targetY = 0f
        allFlows.forEachIndexed { index, flow ->
            val fraction = flow.amount.paise.toFloat() / total
            val ribbonHeight = availableHeight * fraction
            val sourceHeight = size.height * fraction

            drawRibbon(
                startX = leftX + nodeWidth,
                startY = sourceY,
                startHeight = sourceHeight,
                endX = rightX,
                endY = targetY,
                endHeight = ribbonHeight,
                isSavings = flow.label == SAVINGS_LABEL,
            )

            // Category node.
            drawRect(
                color = if (flow.label == SAVINGS_LABEL) {
                    KoshaColors.AccentTeal
                } else {
                    KoshaColors.OffWhiteFaint
                },
                topLeft = Offset(rightX, targetY),
                size = Size(nodeWidth, ribbonHeight),
            )

            bands += flow to (targetY..(targetY + ribbonHeight + gap))

            drawFlowLabel(textMeasurer, flow, rightX, targetY, ribbonHeight)

            sourceY += sourceHeight
            targetY += ribbonHeight + gap
        }
    }
}

private fun DrawScope.drawRibbon(
    startX: Float,
    startY: Float,
    startHeight: Float,
    endX: Float,
    endY: Float,
    endHeight: Float,
    isSavings: Boolean,
) {
    val controlOffset = (endX - startX) * 0.5f
    val path = Path().apply {
        moveTo(startX, startY)
        cubicTo(startX + controlOffset, startY, endX - controlOffset, endY, endX, endY)
        lineTo(endX, endY + endHeight)
        cubicTo(
            endX - controlOffset, endY + endHeight,
            startX + controlOffset, startY + startHeight,
            startX, startY + startHeight,
        )
        close()
    }
    drawPath(
        path = path,
        brush = Brush.horizontalGradient(
            colors = if (isSavings) {
                listOf(KoshaColors.AccentViolet.copy(alpha = 0.55f), KoshaColors.AccentTeal.copy(alpha = 0.75f))
            } else {
                listOf(KoshaColors.AccentTeal.copy(alpha = 0.35f), KoshaColors.AccentViolet.copy(alpha = 0.25f))
            },
            startX = startX,
            endX = endX,
        ),
    )
}

private fun DrawScope.drawFlowLabel(
    textMeasurer: TextMeasurer,
    flow: SankeyFlow,
    nodeX: Float,
    nodeY: Float,
    nodeHeight: Float,
) {
    // Skip labels on ribbons too thin to hold text — no clutter.
    if (nodeHeight < 14.dp.toPx()) return
    // A flow diagram without figures is decoration: always show the amount.
    val layout = textMeasurer.measure(
        text = "${flow.label}  ${flow.amount.format(withPaise = false)}",
        style = KoshaType.Caption.copy(color = KoshaColors.OffWhiteMuted),
    )
    drawText(
        textLayoutResult = layout,
        topLeft = Offset(
            x = (nodeX - layout.size.width - 6.dp.toPx()).coerceAtLeast(0f),
            y = nodeY + (nodeHeight - layout.size.height) / 2,
        ),
    )
}

private const val SAVINGS_LABEL = "Saved"

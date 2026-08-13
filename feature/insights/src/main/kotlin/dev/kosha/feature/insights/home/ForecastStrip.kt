package dev.kosha.feature.insights.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.kosha.core.common.Money
import dev.kosha.core.designsystem.component.AmountText
import dev.kosha.core.designsystem.token.KoshaColors
import dev.kosha.core.designsystem.token.KoshaSpacing
import dev.kosha.core.designsystem.token.KoshaType
import dev.kosha.core.engine.forecast.ForecastEngine
import dev.kosha.feature.insights.R

/**
 * Forecast strip (spec C2.6): a 30-day sparkline in the accent gradient —
 * one of the few money-flow visuals allowed to use it — with a quiet amber
 * dot if the projection dips below zero before the next expected income.
 */
@Composable
fun ForecastStrip(forecast: ForecastEngine.Forecast?) {
    if (forecast == null || forecast.points.isEmpty()) return

    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                text = if (forecast.earlyEstimate) {
                    stringResource(R.string.forecast_early_estimate)
                } else {
                    stringResource(R.string.forecast_title)
                },
                style = KoshaType.Label,
                color = KoshaColors.OffWhiteFaint,
                modifier = Modifier.weight(1f),
            )
            AmountText(
                amount = forecast.points.last().balance,
                style = KoshaType.AmountSmall,
                color = if (forecast.points.last().balance.isNegative) {
                    KoshaColors.Amber
                } else {
                    KoshaColors.OffWhiteMuted
                },
                withPaise = false,
            )
        }
        Spacer(Modifier.height(KoshaSpacing.xs))
        Sparkline(forecast)
        if (forecast.negativeBeforeNextCredit) {
            Spacer(Modifier.height(KoshaSpacing.xxs))
            Text(
                text = stringResource(R.string.forecast_dips_negative),
                style = KoshaType.Caption,
                color = KoshaColors.Amber,
            )
        }
    }
}

@Composable
private fun Sparkline(forecast: ForecastEngine.Forecast) {
    val balances = forecast.points.map { it.balance.paise }
    val minValue = minOf(balances.min(), 0L)
    val maxValue = maxOf(balances.max(), 0L)
    val span = (maxValue - minValue).coerceAtLeast(1L)

    Canvas(
        Modifier
            .fillMaxWidth()
            .height(56.dp),
    ) {
        fun yFor(value: Long): Float =
            size.height - ((value - minValue).toFloat() / span) * size.height

        val stepX = if (balances.size > 1) size.width / (balances.size - 1) else size.width

        // Zero baseline — only drawn when the curve actually crosses it.
        if (minValue < 0) {
            val zeroY = yFor(0)
            drawLine(
                color = KoshaColors.Outline,
                start = Offset(0f, zeroY),
                end = Offset(size.width, zeroY),
                strokeWidth = 1f,
            )
        }

        val path = Path()
        balances.forEachIndexed { index, value ->
            val x = index * stepX
            val y = yFor(value)
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(
            path = path,
            brush = Brush.horizontalGradient(
                listOf(KoshaColors.AccentTeal, KoshaColors.AccentViolet),
            ),
            style = Stroke(width = 2.5f, cap = StrokeCap.Round),
        )

        // Quiet amber dot at the first negative day.
        forecast.firstNegativeDate?.let { negativeDate ->
            val index = forecast.points.indexOfFirst { it.date == negativeDate }
            if (index >= 0) {
                drawCircle(
                    color = KoshaColors.Amber,
                    radius = 4f,
                    center = Offset(index * stepX, yFor(balances[index])),
                )
            }
        }
    }
}

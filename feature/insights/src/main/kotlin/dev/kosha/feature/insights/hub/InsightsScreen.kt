package dev.kosha.feature.insights.hub

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.kosha.core.common.Money
import dev.kosha.core.database.repo.InsightsRepository
import dev.kosha.core.designsystem.component.AmountText
import dev.kosha.core.designsystem.component.KoshaCard
import dev.kosha.core.designsystem.component.KoshaChip
import dev.kosha.core.designsystem.component.KoshaRing
import dev.kosha.core.designsystem.token.KoshaColors
import dev.kosha.core.designsystem.token.KoshaSpacing
import dev.kosha.core.designsystem.token.KoshaType
import dev.kosha.core.engine.insight.HealthScore
import dev.kosha.feature.insights.R
import dev.kosha.feature.insights.charts.CalendarHeatmap
import dev.kosha.feature.insights.charts.CategoryTreemap
import dev.kosha.feature.insights.charts.RadarAxis
import dev.kosha.feature.insights.charts.SankeyChart
import dev.kosha.feature.insights.charts.SankeyFlow
import dev.kosha.feature.insights.charts.SpendingDnaRadar
import dev.kosha.feature.insights.charts.TreemapSlice
import dev.kosha.feature.insights.charts.TrendLines
import kotlin.math.roundToInt

/**
 * Insights hub (spec C5): a single sectioned scroll —
 * Flow · Rhythm · Shape · Trajectory · Health · Advisor · Leaks & Anomalies ·
 * What-If · Opportunity Cost. Each section expands in place.
 */
@Composable
fun InsightsScreen(viewModel: InsightsViewModel = hiltViewModel()) {
    val insights by viewModel.insights.collectAsState()
    val whatIf by viewModel.whatIf.collectAsState()
    val opportunity by viewModel.opportunityCost.collectAsState()
    val data = insights

    if (data == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = stringResource(R.string.insights_loading),
                style = KoshaType.InsightSerif,
                color = KoshaColors.OffWhiteMuted,
            )
        }
        return
    }

    LazyColumn(
        contentPadding = PaddingValues(KoshaSpacing.screenPadding),
        verticalArrangement = Arrangement.spacedBy(KoshaSpacing.m),
    ) {
        item {
            Text(
                text = stringResource(R.string.insights_title),
                style = KoshaType.Title,
                color = KoshaColors.OffWhite,
            )
        }

        item { FlowSection(data) }
        item { RhythmSection(data) }
        item { ShapeSection(data) }
        item { TrajectorySection(data) }
        item { HealthSection(data) }
        item { AdvisorSection(data) }
        item { LeaksAndAnomaliesSection(data) }
        item { WhatIfSection(data, whatIf, viewModel) }
        item { OpportunityCostSection(data, opportunity, viewModel) }
        item { Spacer(Modifier.height(KoshaSpacing.xxl)) }
    }
}

@Composable
private fun Section(
    title: String,
    subtitle: String? = null,
    content: @Composable () -> Unit,
) {
    var expanded by remember { mutableStateOf(true) }
    KoshaCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded },
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = KoshaType.Title, color = KoshaColors.OffWhite)
                if (subtitle != null) {
                    Text(subtitle, style = KoshaType.Caption, color = KoshaColors.OffWhiteFaint)
                }
            }
            Text(
                text = if (expanded) "−" else "+",
                style = KoshaType.Title,
                color = KoshaColors.OffWhiteFaint,
            )
        }
        AnimatedVisibility(visible = expanded) {
            Column(Modifier.fillMaxWidth()) {
                Spacer(Modifier.height(KoshaSpacing.s))
                content()
            }
        }
    }
}

/** A chart with no data reads as a broken chart — say so in words instead. */
@Composable
private fun EmptyNote(text: String) {
    Text(
        text = text,
        style = KoshaType.InsightSerif,
        color = KoshaColors.OffWhiteMuted,
        modifier = Modifier.padding(vertical = KoshaSpacing.s),
    )
}

/** Figures beside every chart: the picture shows shape, this shows amounts. */
@Composable
private fun AmountLine(label: String, amount: Money, color: androidx.compose.ui.graphics.Color) {
    Row(Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = KoshaType.Body,
            color = KoshaColors.OffWhiteMuted,
            modifier = Modifier.weight(1f),
        )
        AmountText(amount = amount, style = KoshaType.AmountBody, color = color, withPaise = false)
    }
}

@Composable
private fun FlowSection(data: InsightsRepository.Insights) {
    Section(
        title = stringResource(R.string.insights_flow),
        subtitle = stringResource(R.string.insights_flow_sub),
    ) {
        val hasFlow = data.income.paise > 0 || data.spendByCategoryName.isNotEmpty()
        if (!hasFlow) {
            EmptyNote(stringResource(R.string.insights_empty_flow))
            return@Section
        }

        // Totals first, so the numbers are readable without decoding ribbons.
        AmountLine(stringResource(R.string.pulse_income), data.income, KoshaColors.AccentTeal)
        AmountLine(stringResource(R.string.pulse_expense), data.expense, KoshaColors.OffWhiteMuted)
        AmountLine(
            stringResource(R.string.pulse_gap),
            data.savings,
            if (data.savings.isNegative) KoshaColors.Amber else KoshaColors.OffWhite,
        )
        Spacer(Modifier.height(KoshaSpacing.s))

        SankeyChart(
            income = data.income,
            flows = data.spendByCategoryName.take(6).map { SankeyFlow(it.first, it.second) },
            savings = if (data.savings.isNegative) Money.ZERO else data.savings,
        )
    }
}

@Composable
private fun RhythmSection(data: InsightsRepository.Insights) {
    Section(
        title = stringResource(R.string.insights_rhythm),
        subtitle = stringResource(R.string.insights_rhythm_sub),
    ) {
        if (data.dailySpend.isEmpty()) {
            EmptyNote(stringResource(R.string.insights_empty_rhythm))
            return@Section
        }
        CalendarHeatmap(
            dailySpend = data.dailySpend,
            monthStart = data.period.start,
            monthEnd = data.period.endInclusive,
        )
        Spacer(Modifier.height(KoshaSpacing.xs))
        data.dailySpend.maxByOrNull { it.value.paise }?.let { (date, amount) ->
            AmountLine(
                stringResource(R.string.insights_busiest_day, date.dayOfMonth),
                amount,
                KoshaColors.OffWhite,
            )
        }
    }
}

@Composable
private fun ShapeSection(data: InsightsRepository.Insights) {
    Section(
        title = stringResource(R.string.insights_shape),
        subtitle = stringResource(R.string.insights_shape_sub),
    ) {
        if (data.spendByCategoryName.isEmpty()) {
            EmptyNote(stringResource(R.string.insights_empty_shape))
            return@Section
        }

        CategoryTreemap(
            slices = data.spendByCategoryName.map { TreemapSlice(it.first, it.second) },
        )
        Spacer(Modifier.height(KoshaSpacing.s))
        // The treemap shows proportion; this shows what each slice cost.
        data.spendByCategoryName.take(6).forEach { (name, amount) ->
            AmountLine(name, amount, KoshaColors.OffWhite)
        }

        // A radar needs at least three axes to be a shape at all.
        if (data.dnaCurrent.size >= 3) {
            Spacer(Modifier.height(KoshaSpacing.m))
            val baseline = data.dnaBaseline.toMap()
            SpendingDnaRadar(
                axes = data.dnaCurrent.map { (name, amount) ->
                    RadarAxis(name, amount, baseline[name] ?: Money.ZERO)
                },
            )
        }
    }
}

@Composable
private fun TrajectorySection(data: InsightsRepository.Insights) {
    Section(
        title = stringResource(R.string.insights_trajectory),
        subtitle = stringResource(R.string.insights_trajectory_sub),
    ) {
        val hasHistory = data.trend.any {
            it.income.paise != 0L || it.expense.paise != 0L
        }
        if (!hasHistory) {
            EmptyNote(stringResource(R.string.insights_empty_trajectory))
            return@Section
        }
        TrendLines(data.trend)
    }
}

@Composable
private fun HealthSection(data: InsightsRepository.Insights) {
    Section(title = stringResource(R.string.insights_health)) {
        when (val health = data.health) {
            is HealthScore.Result.CollectingData -> Text(
                text = stringResource(R.string.insights_health_collecting),
                style = KoshaType.InsightSerif,
                color = KoshaColors.OffWhiteMuted,
            )

            is HealthScore.Result.Score -> Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(contentAlignment = Alignment.Center) {
                        KoshaRing(
                            progress = health.value / 100f,
                            size = 92.dp,
                            strokeWidth = 7.dp,
                            gradient = true,
                        )
                        Text(
                            text = health.value.toString(),
                            style = KoshaType.AmountLarge,
                            color = KoshaColors.OffWhite,
                        )
                    }
                    Spacer(Modifier.width(KoshaSpacing.m))
                    Text(
                        text = stringResource(R.string.insights_health_active, health.breakdown.size),
                        style = KoshaType.Caption,
                        color = KoshaColors.OffWhiteFaint,
                    )
                }
                Spacer(Modifier.height(KoshaSpacing.s))
                // Transparency is the feature (spec G4): show the formula.
                health.breakdown.forEach { component ->
                    Row(Modifier.fillMaxWidth()) {
                        Text(
                            text = component.component.name.lowercase()
                                .replace('_', ' ')
                                .replaceFirstChar { it.uppercase() },
                            style = KoshaType.Body,
                            color = KoshaColors.OffWhiteMuted,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = "${(component.normalized * component.appliedWeight).roundToInt()}" +
                                " / ${component.appliedWeight.roundToInt()}",
                            style = KoshaType.AmountSmall,
                            color = KoshaColors.OffWhite,
                        )
                    }
                    Text(
                        text = component.explanation,
                        style = KoshaType.Caption,
                        color = KoshaColors.OffWhiteFaint,
                    )
                    Spacer(Modifier.height(KoshaSpacing.xs))
                }
            }
        }
    }
}

@Composable
private fun AdvisorSection(data: InsightsRepository.Insights) {
    Section(title = stringResource(R.string.insights_advisor)) {
        Text(
            text = data.advice.reasoning,
            style = KoshaType.InsightSerif,
            color = KoshaColors.OffWhite,
        )
        Spacer(Modifier.height(KoshaSpacing.s))
        data.advice.allocations.forEach { allocation ->
            Row(Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text(allocation.label, style = KoshaType.Body, color = KoshaColors.OffWhite)
                    Text(allocation.reason, style = KoshaType.Caption, color = KoshaColors.OffWhiteFaint)
                }
                AmountText(
                    amount = allocation.amount,
                    style = KoshaType.AmountBody,
                    color = KoshaColors.AccentTeal,
                    withPaise = false,
                )
            }
            Spacer(Modifier.height(KoshaSpacing.xs))
        }
        data.advice.monthsToEmergencyTarget?.takeIf { it > 0 }?.let { months ->
            Text(
                text = stringResource(R.string.insights_advisor_months, months),
                style = KoshaType.Body,
                color = KoshaColors.OffWhiteMuted,
            )
        }
        Spacer(Modifier.height(KoshaSpacing.xs))
        Text(
            text = stringResource(R.string.insights_advisor_boundary),
            style = KoshaType.Caption,
            color = KoshaColors.OffWhiteFaint,
        )
    }
}

@Composable
private fun LeaksAndAnomaliesSection(data: InsightsRepository.Insights) {
    Section(title = stringResource(R.string.insights_leaks)) {
        if (data.leaks.isEmpty() && data.anomalies.isEmpty()) {
            Text(
                text = stringResource(R.string.insights_leaks_none),
                style = KoshaType.InsightSerif,
                color = KoshaColors.OffWhiteMuted,
            )
        }
        data.leaks.take(3).forEach { leak ->
            Row(Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text(leak.merchant, style = KoshaType.Body, color = KoshaColors.OffWhite)
                    Text(
                        text = stringResource(
                            R.string.insights_leak_detail,
                            leak.occurrences,
                            leak.averageAmount.format(withPaise = false),
                        ),
                        style = KoshaType.Caption,
                        color = KoshaColors.OffWhiteFaint,
                    )
                }
                AmountText(
                    amount = leak.annualized,
                    style = KoshaType.AmountBody,
                    color = KoshaColors.Amber,
                    withPaise = false,
                )
            }
            Spacer(Modifier.height(KoshaSpacing.xs))
        }
        data.anomalies.forEach { flag ->
            Row(Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text(
                        // Name the spend. Three rows all reading "Bigger than
                        // usual" tell the reader nothing about which one.
                        text = flag.label,
                        style = KoshaType.Body,
                        color = KoshaColors.OffWhite,
                    )
                    Text(flag.explanation, style = KoshaType.Caption, color = KoshaColors.OffWhiteFaint)
                }
                AmountText(
                    amount = flag.amount,
                    style = KoshaType.AmountBody,
                    color = KoshaColors.Amber,
                    withPaise = false,
                )
            }
            Spacer(Modifier.height(KoshaSpacing.xs))
        }
    }
}

@Composable
private fun WhatIfSection(
    data: InsightsRepository.Insights,
    state: WhatIfState,
    viewModel: InsightsViewModel,
) {
    Section(title = stringResource(R.string.insights_whatif)) {
        if (data.spendByCategoryName.isEmpty()) {
            EmptyNote(stringResource(R.string.insights_empty_shape))
            return@Section
        }
        // Without this the card is a bare row of chips with no hint that it
        // does anything until one is tapped.
        Text(
            text = stringResource(R.string.insights_pick_category),
            style = KoshaType.Caption,
            color = KoshaColors.OffWhiteFaint,
        )
        Spacer(Modifier.height(KoshaSpacing.xs))
        Row(horizontalArrangement = Arrangement.spacedBy(KoshaSpacing.xs)) {
            data.spendByCategoryName.take(3).forEach { (name, amount) ->
                KoshaChip(
                    label = name,
                    selected = state.categoryName == name,
                    onClick = { viewModel.selectWhatIfCategory(name, amount) },
                )
            }
        }
        if (state.categoryName != null) {
            Spacer(Modifier.height(KoshaSpacing.s))
            Text(
                text = stringResource(R.string.insights_whatif_cut, state.cutPercent),
                style = KoshaType.Label,
                color = KoshaColors.OffWhiteMuted,
            )
            Slider(
                value = state.cutPercent.toFloat(),
                onValueChange = { viewModel.setCutPercent(it.roundToInt()) },
                valueRange = 0f..50f,
                colors = SliderDefaults.colors(
                    thumbColor = KoshaColors.AccentTeal,
                    activeTrackColor = KoshaColors.AccentTeal,
                    inactiveTrackColor = KoshaColors.Outline,
                ),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.insights_whatif_annual),
                    style = KoshaType.Body,
                    color = KoshaColors.OffWhiteMuted,
                    modifier = Modifier.weight(1f),
                )
                AmountText(
                    amount = state.result.annualSaving,
                    style = KoshaType.AmountLarge,
                    color = KoshaColors.AccentTeal,
                    withPaise = false,
                    countUp = true,
                )
            }
        }
    }
}

@Composable
private fun OpportunityCostSection(
    data: InsightsRepository.Insights,
    state: OpportunityCostState,
    viewModel: InsightsViewModel,
) {
    Section(
        title = stringResource(R.string.insights_opportunity),
        subtitle = stringResource(R.string.insights_opportunity_sub),
    ) {
        if (data.spendByCategoryName.isEmpty()) {
            EmptyNote(stringResource(R.string.insights_empty_shape))
            return@Section
        }
        Text(
            text = stringResource(R.string.insights_pick_category),
            style = KoshaType.Caption,
            color = KoshaColors.OffWhiteFaint,
        )
        Spacer(Modifier.height(KoshaSpacing.xs))
        Row(horizontalArrangement = Arrangement.spacedBy(KoshaSpacing.xs)) {
            data.spendByCategoryName.take(3).forEach { (name, amount) ->
                KoshaChip(
                    label = name,
                    selected = state.categoryName == name,
                    onClick = { viewModel.selectOpportunityCategory(name, amount) },
                )
            }
        }
        if (state.categoryName != null) {
            Spacer(Modifier.height(KoshaSpacing.s))
            Text(
                text = stringResource(
                    R.string.insights_opportunity_rate,
                    state.ratePercent.roundToInt(),
                ),
                style = KoshaType.Label,
                color = KoshaColors.OffWhiteMuted,
            )
            Slider(
                value = state.ratePercent.toFloat(),
                onValueChange = { viewModel.setBenchmarkRate(it.toDouble()) },
                valueRange = 0f..15f,
                colors = SliderDefaults.colors(
                    thumbColor = KoshaColors.AccentTeal,
                    activeTrackColor = KoshaColors.AccentTeal,
                    inactiveTrackColor = KoshaColors.Outline,
                ),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.insights_opportunity_would_be),
                    style = KoshaType.Body,
                    color = KoshaColors.OffWhiteMuted,
                    modifier = Modifier.weight(1f),
                )
                AmountText(
                    amount = state.result.hypotheticalValue,
                    style = KoshaType.AmountLarge,
                    color = KoshaColors.OffWhite,
                    withPaise = false,
                    countUp = true,
                )
            }
            Text(
                text = stringResource(R.string.insights_opportunity_hypothetical),
                style = KoshaType.Caption,
                color = KoshaColors.OffWhiteFaint,
            )
        }
    }
}

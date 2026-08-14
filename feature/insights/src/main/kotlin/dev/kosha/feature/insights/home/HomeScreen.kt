package dev.kosha.feature.insights.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import dev.kosha.core.designsystem.component.AmountText
import dev.kosha.core.designsystem.component.KoshaCard
import dev.kosha.core.designsystem.component.KoshaChip
import dev.kosha.core.designsystem.component.KoshaIcons
import dev.kosha.core.designsystem.component.KoshaRing
import dev.kosha.core.designsystem.token.KoshaColors
import dev.kosha.core.designsystem.token.KoshaSpacing
import dev.kosha.core.designsystem.token.KoshaType
import dev.kosha.core.engine.period.PeriodMath
import dev.kosha.feature.insights.R
import java.time.format.DateTimeFormatter

/**
 * Home v1 (spec C2), top → bottom: weather line · Pulse · quick add ·
 * review chip (only when non-empty) · budget rings. Forecast strip and the
 * rotating insight card arrive in Phases 5 and 6.
 */
@Composable
fun HomeScreen(
    onOpenBudgets: () -> Unit,
    onOpenIncome: () -> Unit,
    onQuickAdd: (categoryId: Long) -> Unit,
    onOpenReview: () -> Unit,
    onOpenRecurring: () -> Unit = {},
    onOpenExport: () -> Unit = {},
    onOpenGoals: () -> Unit = {},
    onOpenPermissions: () -> Unit = {},
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    var pulseExpanded by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = KoshaSpacing.screenPadding),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = state.period?.let {
                    DateTimeFormatter.ofPattern("MMMM").format(it.start)
                } ?: "",
                style = KoshaType.Label,
                color = KoshaColors.OffWhiteFaint,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onOpenIncome) {
                Icon(
                    Icons.Outlined.Settings,
                    contentDescription = stringResource(R.string.home_settings),
                    tint = KoshaColors.OffWhiteFaint,
                )
            }
        }

        WeatherLine(state)
        Spacer(Modifier.height(KoshaSpacing.l))
        Pulse(state, expanded = pulseExpanded, onToggle = { pulseExpanded = !pulseExpanded })
        Spacer(Modifier.height(KoshaSpacing.l))

        // Review queue chip — renders ONLY when non-empty (spec A2/C2.4)
        AnimatedVisibility(visible = state.reviewCount > 0) {
            Column {
                KoshaChip(
                    label = if (state.oldestReviewAgeDays > 0) {
                        stringResource(R.string.home_review_chip_age, state.reviewCount, state.oldestReviewAgeDays)
                    } else {
                        stringResource(R.string.home_review_chip, state.reviewCount)
                    },
                    onClick = onOpenReview,
                    accent = KoshaColors.Amber,
                )
                Spacer(Modifier.height(KoshaSpacing.m))
            }
        }

        QuickAddRow(state, onQuickAdd)
        Spacer(Modifier.height(KoshaSpacing.l))
        BudgetRings(state, onOpenBudgets)
        Spacer(Modifier.height(KoshaSpacing.l))
        ForecastStrip(state.forecast)
        Spacer(Modifier.height(KoshaSpacing.s))
        Row(horizontalArrangement = Arrangement.spacedBy(KoshaSpacing.xs)) {
            KoshaChip(
                label = stringResource(R.string.home_recurring),
                onClick = onOpenRecurring,
            )
            KoshaChip(
                label = stringResource(R.string.home_goals),
                onClick = onOpenGoals,
            )
            KoshaChip(
                label = stringResource(R.string.home_export),
                onClick = onOpenExport,
            )
            KoshaChip(
                label = stringResource(R.string.home_permissions),
                onClick = onOpenPermissions,
            )
        }
        Spacer(Modifier.height(KoshaSpacing.xxl))
    }
}

@Composable
private fun WeatherLine(state: HomeUiState) {
    val text = when {
        !state.hasData -> stringResource(R.string.weather_no_data)
        state.tone == PeriodMath.WeatherTone.AHEAD ->
            stringResource(R.string.weather_ahead, state.savingsGap.format(withPaise = false))
        state.tone == PeriodMath.WeatherTone.HEADS_UP ->
            stringResource(R.string.weather_heads_up, state.savingsGap.abs.format(withPaise = false))
        else -> stringResource(R.string.weather_on_track)
    }
    Text(
        text = text,
        style = KoshaType.InsightSerif,
        // Amber only for heads-up; never red, never alarmist.
        color = if (state.tone == PeriodMath.WeatherTone.HEADS_UP && state.hasData) {
            KoshaColors.Amber
        } else {
            KoshaColors.OffWhite
        },
    )
}

@Composable
private fun Pulse(state: HomeUiState, expanded: Boolean, onToggle: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle),
        contentAlignment = Alignment.Center,
    ) {
        // The one place the teal→violet accent gradient appears on Home.
        KoshaRing(
            progress = state.pulseFraction,
            size = 236.dp,
            strokeWidth = 10.dp,
            gradient = true,
            breathing = true,
        )
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = stringResource(R.string.pulse_label),
                style = KoshaType.Label,
                color = KoshaColors.OffWhiteFaint,
            )
            Spacer(Modifier.height(KoshaSpacing.xxs))
            AmountText(
                amount = state.savingsGap,
                style = KoshaType.AmountHero,
                color = if (state.savingsGap.isNegative) KoshaColors.Amber else KoshaColors.OffWhite,
                withPaise = false,
                countUp = true,
            )
            if (expanded) {
                Spacer(Modifier.height(KoshaSpacing.s))
                PulseBreakdown(state)
            } else {
                Spacer(Modifier.height(KoshaSpacing.xxs))
                Text(
                    text = stringResource(R.string.pulse_tap_hint),
                    style = KoshaType.Caption,
                    color = KoshaColors.OffWhiteFaint,
                )
            }
        }
    }
}

@Composable
private fun PulseBreakdown(state: HomeUiState) {
    Row(horizontalArrangement = Arrangement.spacedBy(KoshaSpacing.m)) {
        BreakdownItem(stringResource(R.string.pulse_income), state.income, KoshaColors.AccentTeal)
        BreakdownItem(stringResource(R.string.pulse_expense), state.expense, KoshaColors.OffWhiteMuted)
    }
}

@Composable
private fun BreakdownItem(label: String, amount: Money, color: androidx.compose.ui.graphics.Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = KoshaType.Caption, color = KoshaColors.OffWhiteFaint)
        AmountText(amount = amount, style = KoshaType.AmountSmall, color = color, withPaise = false, countUp = true)
    }
}

@Composable
private fun QuickAddRow(state: HomeUiState, onQuickAdd: (Long) -> Unit) {
    if (state.quickCategories.isEmpty()) return
    Text(
        text = stringResource(R.string.home_quick_add),
        style = KoshaType.Label,
        color = KoshaColors.OffWhiteFaint,
    )
    Spacer(Modifier.height(KoshaSpacing.xs))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(KoshaSpacing.xs),
    ) {
        state.quickCategories.forEach { category ->
            KoshaChip(
                label = category.name,
                onClick = { onQuickAdd(category.id) },
                leading = {
                    Icon(
                        KoshaIcons.forToken(category.icon),
                        contentDescription = null,
                        tint = KoshaColors.OffWhiteMuted,
                        modifier = Modifier.size(16.dp),
                    )
                },
            )
        }
    }
}

@Composable
private fun BudgetRings(state: HomeUiState, onOpenBudgets: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.home_budgets),
            style = KoshaType.Label,
            color = KoshaColors.OffWhiteFaint,
            modifier = Modifier.weight(1f),
        )
    }
    Spacer(Modifier.height(KoshaSpacing.xs))

    if (state.budgetRings.isEmpty()) {
        KoshaChip(label = stringResource(R.string.home_budgets_empty), onClick = onOpenBudgets)
        return
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(KoshaSpacing.s),
    ) {
        state.budgetRings.forEach { ring ->
            KoshaCard(
                onClick = onOpenBudgets,
                contentPadding = KoshaSpacing.s,
                modifier = Modifier.width(112.dp),
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxWidth()) {
                    KoshaRing(
                        progress = ring.progress.fraction,
                        size = 56.dp,
                        strokeWidth = 4.dp,
                        // Ring turns amber at alertThresholdPct (spec C2.5)
                        color = if (ring.progress.isAtThreshold) KoshaColors.Amber else KoshaColors.OffWhiteMuted,
                    )
                    Text(
                        text = "${ring.progress.pct}%",
                        style = KoshaType.AmountSmall,
                        color = if (ring.progress.isAtThreshold) KoshaColors.Amber else KoshaColors.OffWhiteMuted,
                    )
                }
                Spacer(Modifier.height(KoshaSpacing.xxs))
                Text(
                    text = ring.label.ifEmpty { stringResource(R.string.home_budgets) },
                    style = KoshaType.Caption,
                    color = KoshaColors.OffWhiteMuted,
                    maxLines = 1,
                )
            }
        }
    }
}

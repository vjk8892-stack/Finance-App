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
    /** Opens the ledger showing exactly the rows behind this period's figures. */
    onOpenLedger: (fromIso: String?, toIso: String?) -> Unit,
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
            // A period is anchored on payday, so "August" here can mean
            // 5 Aug – 4 Sep while the ledger's "August 2026" means the
            // calendar month. Same word, different windows — which is how two
            // screens end up quoting different totals for the same month. Say
            // the range whenever it is not simply a calendar month.
            Text(
                text = state.period?.let { period ->
                    val monthName = DateTimeFormatter.ofPattern("MMMM").format(period.start)
                    if (period.start.dayOfMonth == 1) {
                        monthName
                    } else {
                        monthName + " · " + RANGE_FORMAT.format(period.start) +
                            " – " + RANGE_FORMAT.format(period.endInclusive)
                    }
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
        Pulse(state, onOpenLedger = { openPeriodInLedger(state, onOpenLedger) })
        Spacer(Modifier.height(KoshaSpacing.s))
        // The two things anyone wants after reading the gap: see what made it,
        // and do something about it. Both belong here, not further down.
        Row(
            horizontalArrangement = Arrangement.spacedBy(KoshaSpacing.xs),
            modifier = Modifier.fillMaxWidth(),
        ) {
            KoshaChip(
                label = stringResource(R.string.home_see_transactions),
                onClick = { openPeriodInLedger(state, onOpenLedger) },
                modifier = Modifier.weight(1f),
            )
            KoshaChip(
                label = stringResource(
                    if (state.budgetRings.isEmpty()) R.string.home_budgets_empty else R.string.home_budgets,
                ),
                onClick = onOpenBudgets,
                modifier = Modifier.weight(1f),
            )
        }
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

private val RANGE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM")

/**
 * Hand the ledger this period's exact window, so the figure above can be
 * checked against the rows that produced it rather than taken on trust.
 */
private fun openPeriodInLedger(state: HomeUiState, onOpenLedger: (String?, String?) -> Unit) {
    val period = state.period
    onOpenLedger(period?.start?.toString(), period?.endInclusive?.toString())
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
private fun Pulse(state: HomeUiState, onOpenLedger: () -> Unit) {
    // Money OUT is the hero (not the income-minus-expense gap): it's the
    // number that needs a decision, where the gap made you do the
    // subtraction in your head every time. Money in stays on screen, just
    // small, beneath it — context for the hero figure, not competing with it.
    // The ring itself now reads as "how much of this period's income is
    // gone", and turns amber (never red) the moment spend passes income,
    // same language the budget rings already use.
    val reference = maxOf(state.expectedIncome.paise, state.income.paise, 1L)
    val overspent = state.hasData && state.expense.paise > reference
    Box(
        Modifier
            .fillMaxWidth()
            // The figure is a question — "where did it go?" — so tapping it
            // goes to the answer rather than being a dead end.
            .clickable(onClick = onOpenLedger),
        contentAlignment = Alignment.Center,
    ) {
        // The one place the teal→violet accent gradient appears on Home —
        // unless spend has outrun income, when it turns the same amber the
        // budget rings use for the same condition.
        KoshaRing(
            progress = state.spendFraction,
            size = 236.dp,
            strokeWidth = 10.dp,
            gradient = !overspent,
            color = KoshaColors.Amber,
            breathing = true,
            dial = true,
        )
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = stringResource(R.string.pulse_label),
                style = KoshaType.Label,
                color = KoshaColors.OffWhiteFaint,
            )
            Spacer(Modifier.height(KoshaSpacing.xxs))
            AmountText(
                amount = state.expense,
                style = KoshaType.AmountHero,
                color = if (overspent) KoshaColors.Amber else KoshaColors.OffWhite,
                withPaise = false,
                countUp = true,
            )
            Spacer(Modifier.height(KoshaSpacing.s))
            BreakdownItem(stringResource(R.string.pulse_income), state.income, KoshaColors.AccentTealBright)
            Spacer(Modifier.height(KoshaSpacing.xxs))
            Text(
                text = stringResource(R.string.pulse_tap_hint),
                style = KoshaType.Caption,
                color = KoshaColors.OffWhiteFaint,
            )
        }
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

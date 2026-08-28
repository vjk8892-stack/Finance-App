package dev.kosha.app.tour

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.PieChart
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.kosha.app.R
import dev.kosha.core.designsystem.component.KoshaCard
import dev.kosha.core.designsystem.token.KoshaColors
import dev.kosha.core.designsystem.token.KoshaShapes
import dev.kosha.core.designsystem.token.KoshaSpacing
import dev.kosha.core.designsystem.token.KoshaType

/**
 * One page of the tour. Order matches the app's own information architecture
 * — bottom-nav destinations first, then the things reached from them — so
 * the tour doubles as a map of where everything lives, not just a features
 * list.
 */
private enum class TourPage(val icon: ImageVector, val titleRes: Int, val bodyRes: Int) {
    WELCOME(Icons.Outlined.CloudOff, R.string.tour_welcome_title, R.string.tour_welcome_body),
    HOME(Icons.Outlined.Insights, R.string.tour_home_title, R.string.tour_home_body),
    ADD(Icons.Outlined.PhotoCamera, R.string.tour_add_title, R.string.tour_add_body),
    LEDGER(Icons.Outlined.AccountBalanceWallet, R.string.tour_ledger_title, R.string.tour_ledger_body),
    INSIGHTS(Icons.Outlined.PieChart, R.string.tour_insights_title, R.string.tour_insights_body),
    GOALS(Icons.Outlined.Flag, R.string.tour_goals_title, R.string.tour_goals_body),
    VAULT(Icons.Outlined.Lock, R.string.tour_vault_title, R.string.tour_vault_body),
    UPKEEP(Icons.Outlined.Repeat, R.string.tour_upkeep_title, R.string.tour_upkeep_body),
}

@Composable
fun FeatureTourScreen(
    onDone: () -> Unit,
    viewModel: FeatureTourViewModel = hiltViewModel(),
) {
    val pageIndex by viewModel.page.collectAsState()
    val pages = TourPage.entries
    val page = pages[pageIndex]
    val isLast = pageIndex == pages.lastIndex

    Column(
        Modifier
            .fillMaxSize()
            .padding(KoshaSpacing.screenPadding),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(KoshaSpacing.xxs)) {
                pages.indices.forEach { i ->
                    Box(
                        Modifier
                            .size(if (i == pageIndex) 18.dp else 6.dp, 6.dp)
                            .clip(CircleShape)
                            .background(
                                if (i == pageIndex) KoshaColors.AccentTealBright else KoshaColors.HudBorderDim,
                            ),
                    )
                }
            }
            if (!isLast) {
                TextButton(onClick = { viewModel.finish(onDone) }) {
                    Text(
                        stringResource(R.string.tour_skip),
                        style = KoshaType.Label,
                        color = KoshaColors.OffWhiteFaint,
                    )
                }
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.Center,
        ) {
            AnimatedContent(targetState = page, label = "tourPage") { shown ->
                Column {
                    Box(
                        Modifier
                            .size(96.dp)
                            .clip(KoshaShapes.chamfered(KoshaSpacing.cardCut))
                            .background(KoshaColors.GlassTop),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            shown.icon,
                            contentDescription = null,
                            tint = KoshaColors.AccentTealBright,
                            modifier = Modifier.size(40.dp),
                        )
                    }
                    Spacer(Modifier.height(KoshaSpacing.l))
                    Text(
                        text = stringResource(shown.titleRes),
                        style = KoshaType.ScreenTitle,
                        color = KoshaColors.OffWhite,
                    )
                    Spacer(Modifier.height(KoshaSpacing.s))
                    Text(
                        text = stringResource(shown.bodyRes),
                        style = KoshaType.Body,
                        color = KoshaColors.OffWhiteMuted,
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (pageIndex > 0) {
                TextButton(onClick = viewModel::back) {
                    Text(stringResource(R.string.tour_back), color = KoshaColors.OffWhiteMuted)
                }
            } else {
                Spacer(Modifier.height(KoshaSpacing.minTouchTarget))
            }
            KoshaCard(
                onClick = { if (isLast) viewModel.finish(onDone) else viewModel.next() },
                contentPadding = KoshaSpacing.m,
            ) {
                Text(
                    text = stringResource(if (isLast) R.string.tour_start else R.string.tour_next),
                    style = KoshaType.LabelStrong,
                    color = KoshaColors.AccentTealBright,
                )
            }
        }
    }
}

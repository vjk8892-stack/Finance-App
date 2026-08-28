package dev.kosha.feature.goals

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.kosha.core.common.Money
import dev.kosha.core.database.model.NetWorthSnapshotEntity
import dev.kosha.core.designsystem.component.AmountText
import dev.kosha.core.designsystem.component.KoshaCard
import dev.kosha.core.designsystem.component.KoshaChip
import dev.kosha.core.designsystem.token.KoshaColors
import dev.kosha.core.designsystem.token.KoshaSpacing
import dev.kosha.core.designsystem.token.KoshaType

/**
 * Its own destination (design review: net worth deserves a screen, and a
 * trend line — the old inline card only ever showed today's figure).
 */
@Composable
fun NetWorthScreen(
    onBack: () -> Unit,
    viewModel: NetWorthViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    var editor by remember { mutableStateOf<NetWorthEditor?>(null) }

    Column(Modifier.fillMaxSize()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = KoshaSpacing.xs, vertical = KoshaSpacing.s),
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = null, tint = KoshaColors.OffWhiteMuted)
            }
            Text(
                text = stringResource(R.string.networth_section),
                style = KoshaType.Title,
                color = KoshaColors.OffWhite,
                modifier = Modifier.weight(1f),
            )
        }

        LazyColumn(
            contentPadding = PaddingValues(KoshaSpacing.screenPadding),
            verticalArrangement = Arrangement.spacedBy(KoshaSpacing.s),
        ) {
            item {
                NetWorthSummaryCard(
                    state = state,
                    onAddAsset = { editor = NetWorthEditor.Asset },
                    onAddLiability = { editor = NetWorthEditor.Liability },
                )
            }
            item {
                Spacer(Modifier.height(KoshaSpacing.s))
                NetWorthTrendCard(state.history)
                Spacer(Modifier.height(KoshaSpacing.xxl))
            }
        }
    }

    when (editor) {
        NetWorthEditor.Asset, NetWorthEditor.Liability -> AssetEditorSheet(
            isLiability = editor == NetWorthEditor.Liability,
            onSave = { name, value ->
                viewModel.addAssetLiability(name, value, editor == NetWorthEditor.Liability)
                editor = null
            },
            onDismiss = { editor = null },
        )
        null -> Unit
    }
}

private enum class NetWorthEditor { Asset, Liability }

@Composable
private fun NetWorthSummaryCard(
    state: NetWorthUiState,
    onAddAsset: () -> Unit,
    onAddLiability: () -> Unit,
) {
    val netWorth = state.netWorth ?: return
    KoshaCard(modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth()) {
            LabeledAmount(stringResource(R.string.networth_assets), netWorth.assets, KoshaColors.AccentTeal)
            Spacer(Modifier.width(KoshaSpacing.m))
            LabeledAmount(stringResource(R.string.networth_liabilities), netWorth.liabilities, KoshaColors.OffWhiteMuted)
        }
        Spacer(Modifier.height(KoshaSpacing.xs))
        Text(stringResource(R.string.networth_net), style = KoshaType.Label, color = KoshaColors.OffWhiteFaint)
        AmountText(
            amount = netWorth.net,
            style = KoshaType.AmountLarge,
            color = if (netWorth.net.isNegative) KoshaColors.Amber else KoshaColors.OffWhite,
            withPaise = false,
            countUp = true,
        )
        Spacer(Modifier.height(KoshaSpacing.xs))
        Text(
            text = stringResource(R.string.networth_loan_hint),
            style = KoshaType.Caption,
            color = KoshaColors.OffWhiteFaint,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(KoshaSpacing.xs)) {
            KoshaChip(label = stringResource(R.string.networth_add_asset), onClick = onAddAsset)
            KoshaChip(label = stringResource(R.string.networth_add_liability), onClick = onAddLiability)
        }
    }
}

@Composable
private fun NetWorthTrendCard(history: List<NetWorthSnapshotEntity>) {
    KoshaCard(modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.networth_trend_title), style = KoshaType.Label, color = KoshaColors.OffWhiteFaint)
        Spacer(Modifier.height(KoshaSpacing.xs))
        if (history.size < 2) {
            Text(
                text = stringResource(R.string.networth_trend_empty),
                style = KoshaType.Body,
                color = KoshaColors.OffWhiteMuted,
            )
        } else {
            NetWorthSparkline(history)
        }
    }
}

/**
 * A point is recorded at most once a day, so this genuinely fills in over
 * real visits rather than being backfilled — an honest trend line, even
 * when it starts as just two or three points.
 */
@Composable
private fun NetWorthSparkline(history: List<NetWorthSnapshotEntity>) {
    val values = history.map { it.netPaise }
    val minValue = values.min()
    val maxValue = values.max()
    val span = (maxValue - minValue).coerceAtLeast(1L)

    Canvas(
        Modifier
            .fillMaxWidth()
            .height(64.dp),
    ) {
        fun yFor(value: Long): Float =
            size.height - ((value - minValue).toFloat() / span) * size.height

        val stepX = if (values.size > 1) size.width / (values.size - 1) else size.width
        val path = Path()
        values.forEachIndexed { index, value ->
            val x = index * stepX
            val y = yFor(value)
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(
            path = path,
            brush = Brush.horizontalGradient(listOf(KoshaColors.AccentTeal, KoshaColors.AccentViolet)),
            style = Stroke(width = 2.5f, cap = StrokeCap.Round),
        )
        val lastX = (values.lastIndex) * stepX
        drawCircle(
            color = if (values.last() < 0) KoshaColors.Amber else KoshaColors.AccentVioletBright,
            radius = 4f,
            center = Offset(lastX, yFor(values.last())),
        )
    }
}

@Composable
private fun LabeledAmount(label: String, amount: Money, color: Color) {
    Column {
        Text(label, style = KoshaType.Caption, color = KoshaColors.OffWhiteFaint)
        AmountText(amount = amount, style = KoshaType.AmountBody, color = color, withPaise = false)
    }
}

@Composable
private fun AssetEditorSheet(
    isLiability: Boolean,
    onSave: (name: String, value: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var value by remember { mutableStateOf("") }

    EditorSheet(onDismiss) {
        Text(
            text = stringResource(
                if (isLiability) R.string.networth_add_liability else R.string.networth_add_asset,
            ),
            style = KoshaType.Title,
            color = KoshaColors.OffWhite,
        )
        if (isLiability) {
            Text(
                text = stringResource(R.string.networth_loan_hint),
                style = KoshaType.Caption,
                color = KoshaColors.Amber,
            )
        }
        GoalField(name, { name = it }, stringResource(R.string.networth_item_name))
        GoalField(value, { if (it.all { c -> c.isDigit() || c == '.' }) value = it }, stringResource(R.string.networth_value))
        TextButton(
            onClick = { onSave(name.trim(), value) },
            enabled = name.isNotBlank() && value.isNotBlank(),
        ) {
            Text(stringResource(R.string.goals_save), color = KoshaColors.AccentTeal)
        }
    }
}

package dev.kosha.feature.ledger.accounts

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.kosha.core.common.Money
import dev.kosha.core.designsystem.component.AmountText
import dev.kosha.core.designsystem.component.KoshaCard
import dev.kosha.core.designsystem.component.KoshaChip
import dev.kosha.core.designsystem.component.KoshaIcons
import dev.kosha.core.designsystem.token.KoshaColors
import dev.kosha.core.designsystem.token.KoshaSpacing
import dev.kosha.core.designsystem.token.KoshaType
import dev.kosha.feature.ledger.R
import dev.kosha.feature.ledger.displayName
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * One account's transactions, with its balance shown as arithmetic.
 *
 * The accounts screen listed balances and stopped there — several of them
 * looked wrong and there was no way to find out why, which is the worst
 * possible combination: a number you distrust and cannot audit. A balance is
 * `opening + credits − debits`, so the statement states that sum with its real
 * figures and then lists every row that went into it.
 */
@Composable
fun AccountStatementScreen(
    accountId: Long,
    onBack: () -> Unit,
    viewModel: AccountStatementViewModel = hiltViewModel(),
) {
    androidx.compose.runtime.LaunchedEffect(accountId) { viewModel.load(accountId) }
    val state by viewModel.state.collectAsState()
    val account = state.account

    Column(Modifier.fillMaxSize()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = KoshaSpacing.xs, vertical = KoshaSpacing.s),
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = null,
                    tint = KoshaColors.OffWhiteMuted,
                )
            }
            Text(
                text = account?.displayName() ?: stringResource(R.string.accounts_title),
                style = KoshaType.ScreenTitle,
                color = KoshaColors.OffWhite,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
        }

        if (account == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(R.string.statement_missing),
                    style = KoshaType.InsightSerif,
                    color = KoshaColors.OffWhiteMuted,
                )
            }
            return@Column
        }

        LazyColumn(
            contentPadding = androidx.compose.foundation.layout.PaddingValues(KoshaSpacing.screenPadding),
            verticalArrangement = Arrangement.spacedBy(KoshaSpacing.s),
        ) {
            item {
                KoshaCard(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = stringResource(R.string.statement_balance_now),
                        style = KoshaType.Label,
                        color = KoshaColors.OffWhiteFaint,
                    )
                    AmountText(
                        amount = Money(account.currentBalancePaise),
                        style = KoshaType.AmountHero,
                        color = if (account.currentBalancePaise < 0) {
                            KoshaColors.AmberBright
                        } else {
                            KoshaColors.OffWhite
                        },
                    )
                    Spacer(Modifier.height(KoshaSpacing.s))
                    // The sum, spelled out. A balance nobody can check is a
                    // balance nobody believes.
                    WorkingLine(stringResource(R.string.statement_opening), state.opening)
                    WorkingLine(stringResource(R.string.statement_in), state.credits)
                    WorkingLine(stringResource(R.string.statement_out), Money(-state.debits.paise))
                    Spacer(Modifier.height(KoshaSpacing.xxs))
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(KoshaColors.Outline),
                    )
                    Spacer(Modifier.height(KoshaSpacing.xxs))
                    WorkingLine(
                        stringResource(R.string.statement_result),
                        Money(account.currentBalancePaise),
                        strong = true,
                    )
                    Spacer(Modifier.height(KoshaSpacing.xs))
                    Text(
                        text = stringResource(R.string.statement_count, state.rows.size),
                        style = KoshaType.Caption,
                        color = KoshaColors.OffWhiteFaint,
                    )
                }
            }

            item {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(KoshaSpacing.xs),
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                ) {
                    StatementSort.entries.forEach { option ->
                        KoshaChip(
                            label = stringResource(option.labelRes()),
                            selected = state.sort == option,
                            onClick = { viewModel.setSort(option) },
                            accent = KoshaColors.AccentTeal,
                        )
                    }
                }
            }

            items(state.rows.size) { i ->
                val row = state.rows[i]
                StatementRow(
                    merchant = row.txn.merchantRaw ?: stringResource(R.string.ledger_no_name),
                    categoryName = row.categoryName,
                    categoryIcon = row.categoryIcon,
                    timestampMillis = row.txn.timestampMillis,
                    amount = if (row.txn.type == dev.kosha.core.database.model.TxnType.DEBIT) {
                        Money(-row.txn.amountPaise)
                    } else {
                        Money(row.txn.amountPaise)
                    },
                )
            }

            item { Spacer(Modifier.height(KoshaSpacing.xxl)) }
        }
    }
}

@Composable
private fun WorkingLine(label: String, amount: Money, strong: Boolean = false) {
    Row(Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = if (strong) KoshaType.Body else KoshaType.Caption,
            color = if (strong) KoshaColors.OffWhite else KoshaColors.OffWhiteMuted,
            modifier = Modifier.weight(1f),
        )
        AmountText(
            amount = amount,
            style = if (strong) KoshaType.AmountBody else KoshaType.AmountSmall,
            color = if (strong) KoshaColors.OffWhite else KoshaColors.OffWhiteMuted,
            withPaise = false,
        )
    }
}

@Composable
private fun StatementRow(
    merchant: String,
    categoryName: String?,
    categoryIcon: String?,
    timestampMillis: Long,
    amount: Money,
) {
    val tint = KoshaColors.categoryColor(categoryName)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = KoshaSpacing.xs),
    ) {
        Icon(
            imageVector = KoshaIcons.forToken(categoryIcon),
            contentDescription = categoryName,
            tint = tint,
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(tint.copy(alpha = 0.16f))
                .padding(8.dp),
        )
        Spacer(Modifier.width(KoshaSpacing.s))
        Column(Modifier.weight(1f)) {
            Text(merchant, style = KoshaType.Body, color = KoshaColors.OffWhite, maxLines = 1)
            Text(
                text = DAY_TIME.format(Instant.ofEpochMilli(timestampMillis).atZone(ZoneId.systemDefault())),
                style = KoshaType.Caption,
                color = KoshaColors.OffWhiteFaint,
            )
        }
        AmountText(
            amount = amount,
            style = KoshaType.AmountBody,
            color = if (amount.isNegative) KoshaColors.OffWhite else KoshaColors.AccentTealBright,
            signed = !amount.isNegative,
        )
    }
}

private fun StatementSort.labelRes(): Int = when (this) {
    StatementSort.NEWEST -> R.string.ledger_sort_newest
    StatementSort.OLDEST -> R.string.ledger_sort_oldest
    StatementSort.LARGEST -> R.string.ledger_sort_largest
}

private val DAY_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm")

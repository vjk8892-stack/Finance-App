package dev.kosha.feature.ledger

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.kosha.core.database.model.AccountEntity
import dev.kosha.core.database.model.CategoryEntity
import dev.kosha.core.designsystem.component.KoshaChip
import dev.kosha.core.designsystem.token.KoshaColors
import dev.kosha.core.designsystem.token.KoshaSpacing
import dev.kosha.core.designsystem.token.KoshaType
import java.time.YearMonth
import java.time.format.DateTimeFormatter

/**
 * Narrow the ledger by month, account or category.
 *
 * These live in a sheet rather than inline because there are as many chips as
 * the user has accounts and months — three scrolling rows above the list would
 * push the transactions themselves off screen, which defeats the point. The
 * direction filter stays inline since it is the one people flip constantly.
 */
@Composable
fun LedgerFilterSheet(
    filters: LedgerFilters,
    accounts: List<AccountEntity>,
    months: List<YearMonth>,
    categories: List<CategoryEntity>,
    onSetAccount: (Long?) -> Unit,
    onSetMonth: (YearMonth?) -> Unit,
    onSetCategory: (Long?) -> Unit,
    onClearAll: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = KoshaColors.CharcoalOverlay) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = KoshaSpacing.m),
            verticalArrangement = Arrangement.spacedBy(KoshaSpacing.s),
        ) {
            Row(Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.ledger_filters_title),
                    style = KoshaType.Title,
                    color = KoshaColors.OffWhite,
                    modifier = Modifier.weight(1f),
                )
                if (filters.activeCount > 0) {
                    TextButton(onClick = onClearAll) {
                        Text(
                            stringResource(R.string.ledger_filters_clear),
                            color = KoshaColors.AccentTeal,
                        )
                    }
                }
            }

            if (months.isNotEmpty()) {
                FilterGroup(stringResource(R.string.ledger_filter_month)) {
                    KoshaChip(
                        label = stringResource(R.string.ledger_filter_any),
                        selected = filters.month == null,
                        onClick = { onSetMonth(null) },
                    )
                    months.forEach { month ->
                        KoshaChip(
                            label = MONTH_FORMAT.format(month),
                            selected = filters.month == month,
                            onClick = { onSetMonth(month.takeIf { it != filters.month }) },
                        )
                    }
                }
            }

            if (accounts.size > 1) {
                FilterGroup(stringResource(R.string.ledger_filter_account)) {
                    KoshaChip(
                        label = stringResource(R.string.ledger_filter_any),
                        selected = filters.accountId == null,
                        onClick = { onSetAccount(null) },
                    )
                    accounts.forEach { account ->
                        KoshaChip(
                            label = account.displayName(),
                            selected = filters.accountId == account.id,
                            onClick = { onSetAccount(account.id.takeIf { it != filters.accountId }) },
                        )
                    }
                }
            }

            if (categories.isNotEmpty()) {
                FilterGroup(stringResource(R.string.ledger_filter_category)) {
                    KoshaChip(
                        label = stringResource(R.string.ledger_filter_any),
                        selected = filters.categoryId == null,
                        onClick = { onSetCategory(null) },
                    )
                    categories.forEach { category ->
                        KoshaChip(
                            label = category.name,
                            selected = filters.categoryId == category.id,
                            onClick = { onSetCategory(category.id.takeIf { it != filters.categoryId }) },
                        )
                    }
                }
            }

            Spacer(Modifier.height(KoshaSpacing.xl))
        }
    }
}

@Composable
private fun FilterGroup(title: String, content: @Composable () -> Unit) {
    Column {
        Text(title, style = KoshaType.Label, color = KoshaColors.OffWhiteFaint)
        Spacer(Modifier.height(KoshaSpacing.xxs))
        Row(
            horizontalArrangement = Arrangement.spacedBy(KoshaSpacing.xs),
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
        ) {
            content()
        }
    }
}

private val MONTH_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM yyyy")

/**
 * Accounts Kosha discovered from a message are already named "•• 1234", so
 * appending the tail again produced "•• 5272 ·· 5272" on the accounts screen.
 * One place decides how an account is written.
 */
fun AccountEntity.displayName(): String {
    val tail = last4?.takeIf { it.isNotBlank() } ?: return name
    return if (name.contains(tail)) name else "$name ·· $tail"
}

package dev.kosha.feature.ledger.add

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import dev.kosha.core.common.Money
import dev.kosha.core.database.model.MoodTag
import dev.kosha.core.database.model.TxnType
import dev.kosha.core.designsystem.component.AmountText
import dev.kosha.core.designsystem.component.KeypadKey
import dev.kosha.core.designsystem.component.KoshaChip
import dev.kosha.core.designsystem.component.KoshaKeypad
import dev.kosha.core.designsystem.token.KoshaColors
import dev.kosha.core.designsystem.token.KoshaSpacing
import dev.kosha.core.designsystem.token.KoshaType
import dev.kosha.feature.ledger.CategoryFlowGrid
import dev.kosha.feature.ledger.R

/**
 * Add screen, spec C4: Scan · Manual · Import tabs. Phase 1 ships Manual;
 * Scan/Import render their arrival note until Phase 4.
 */
@Composable
fun AddScreen(
    quickCategoryId: Long? = null,
    scanTab: @Composable () -> Unit = {},
    importTab: @Composable () -> Unit = {},
    viewModel: AddViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    // Camera-first (spec C4), unless the user arrived via a quick-add chip.
    var tab by remember { mutableIntStateOf(if (quickCategoryId != null) 1 else 0) }
    val snackbar = remember { SnackbarHostState() }
    val savedLabel = stringResource(R.string.add_saved)

    LaunchedEffect(quickCategoryId) {
        viewModel.presetCategory(quickCategoryId)
    }

    LaunchedEffect(Unit) {
        viewModel.saved.collect { amount ->
            snackbar.showSnackbar("$savedLabel ${amount.format(withPaise = false)}")
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            TabRow(
                selectedTabIndex = tab,
                containerColor = KoshaColors.Charcoal,
                contentColor = KoshaColors.OffWhite,
            ) {
                listOf(
                    stringResource(R.string.add_tab_scan),
                    stringResource(R.string.add_tab_manual),
                    stringResource(R.string.add_tab_import),
                ).forEachIndexed { index, label ->
                    Tab(
                        selected = tab == index,
                        onClick = { tab = index },
                        text = { Text(label, style = KoshaType.Label) },
                    )
                }
            }

            when (tab) {
                0 -> scanTab()
                1 -> ManualTab(state, viewModel)
                else -> importTab()
            }
        }
        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

@Composable
private fun ManualTab(state: AddUiState, viewModel: AddViewModel) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = KoshaSpacing.screenPadding),
        verticalArrangement = Arrangement.spacedBy(KoshaSpacing.s),
    ) {
        Spacer(Modifier.height(KoshaSpacing.s))

        // Debit / credit toggle
        Row(horizontalArrangement = Arrangement.spacedBy(KoshaSpacing.xs)) {
            KoshaChip(
                label = stringResource(R.string.add_expense),
                selected = state.form.entryType == TxnType.DEBIT,
                onClick = { viewModel.setEntryType(TxnType.DEBIT) },
            )
            KoshaChip(
                label = stringResource(R.string.add_income),
                selected = state.form.entryType == TxnType.CREDIT,
                onClick = { viewModel.setEntryType(TxnType.CREDIT) },
                accent = KoshaColors.AccentTeal,
            )
        }

        // Amount display
        AmountText(
            amount = state.form.amount ?: Money.ZERO,
            style = KoshaType.AmountHero,
            color = if (state.form.canSave) KoshaColors.OffWhite else KoshaColors.OffWhiteFaint,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )

        KoshaKeypad(onKey = { key ->
            when (key) {
                is KeypadKey.Digit -> viewModel.onDigit(key.value)
                KeypadKey.Decimal -> viewModel.onDecimal()
                KeypadKey.Backspace -> viewModel.onBackspace()
            }
        })

        // Accounts
        if (state.accounts.isEmpty()) {
            Text(
                text = stringResource(R.string.add_no_account),
                style = KoshaType.Body,
                color = KoshaColors.Amber,
            )
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(KoshaSpacing.xs)) {
                state.accounts.take(4).forEach { account ->
                    KoshaChip(
                        label = account.name,
                        selected = state.form.selectedAccountId == account.id,
                        onClick = { viewModel.selectAccount(account.id) },
                        accent = KoshaColors.accountColor(account.colorToken),
                    )
                }
            }
        }

        // Mood tags (spec: mood-tagged manual entries)
        Row(horizontalArrangement = Arrangement.spacedBy(KoshaSpacing.xs)) {
            listOf(
                MoodTag.IMPULSE to stringResource(R.string.add_mood_impulse),
                MoodTag.PLANNED to stringResource(R.string.add_mood_planned),
                MoodTag.NECESSARY to stringResource(R.string.add_mood_necessary),
            ).forEach { (mood, label) ->
                KoshaChip(
                    label = label,
                    selected = state.form.moodTag == mood,
                    onClick = {
                        viewModel.setMood(if (state.form.moodTag == mood) null else mood)
                    },
                )
            }
        }

        TextField(
            value = state.form.note,
            onValueChange = viewModel::setNote,
            placeholder = {
                Text(stringResource(R.string.add_note_hint), color = KoshaColors.OffWhiteFaint)
            },
            colors = TextFieldDefaults.colors(
                focusedContainerColor = KoshaColors.CharcoalRaised,
                unfocusedContainerColor = KoshaColors.CharcoalRaised,
                focusedTextColor = KoshaColors.OffWhite,
                unfocusedTextColor = KoshaColors.OffWhite,
            ),
            modifier = Modifier.fillMaxWidth(),
        )

        Text(
            text = stringResource(R.string.add_pick_category),
            style = KoshaType.Label,
            color = if (state.form.canSave) KoshaColors.OffWhiteMuted else KoshaColors.OffWhiteFaint,
        )
        CategoryFlowGrid(
            categories = state.categories,
            selectedId = state.form.presetCategoryId,
            onPick = { if (state.form.canSave) viewModel.saveWithCategory(it) },
        )
        Spacer(Modifier.height(KoshaSpacing.xxl))
    }
}

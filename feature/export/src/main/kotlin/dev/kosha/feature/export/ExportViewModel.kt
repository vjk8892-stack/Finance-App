package dev.kosha.feature.export

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.kosha.core.common.Money
import dev.kosha.core.database.dao.CategoryDao
import dev.kosha.core.database.dao.PlanningDao
import dev.kosha.core.database.repo.InsightsRepository
import dev.kosha.core.database.repo.PeriodRepository
import dev.kosha.core.database.settings.SettingsRepository
import dev.kosha.core.engine.insight.HealthScore
import dev.kosha.core.engine.period.PeriodMath
import java.io.File
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

data class ExportUiState(
    val busy: Boolean = false,
    val shareUri: Uri? = null,
    val shareMimeType: String = "text/csv",
    val passphrase: String = "",
    val includeVault: Boolean = false,
    val message: String? = null,
)

@HiltViewModel
class ExportViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val exportRepository: ExportRepository,
    private val pdfWriter: PdfStatementWriter,
    private val backupManager: BackupManager,
    private val periodRepository: PeriodRepository,
    private val insightsRepository: InsightsRepository,
    private val planningDao: PlanningDao,
    private val categoryDao: CategoryDao,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(ExportUiState())
    val state: StateFlow<ExportUiState> = _state.asStateFlow()

    fun setPassphrase(value: String) {
        _state.value = _state.value.copy(passphrase = value)
    }

    fun toggleIncludeVault() {
        _state.value = _state.value.copy(includeVault = !_state.value.includeVault)
    }

    fun exportCsv() {
        run {
            viewModelScope.launch {
                _state.value = _state.value.copy(busy = true, message = null)
                val anchor = settingsRepository.settings.first().periodAnchorDay
                val period = periodRepository.currentPeriod(anchor)
                val result = runCatching { exportRepository.exportCsv(period) }
                _state.value = _state.value.copy(
                    busy = false,
                    shareUri = result.getOrNull(),
                    shareMimeType = "text/csv",
                    message = result.exceptionOrNull()?.let {
                        context.getString(R.string.export_failed)
                    },
                )
            }
        }
    }

    fun exportPdf() {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, message = null)
            val settings = settingsRepository.settings.first()
            val result = runCatching {
                val period = periodRepository.currentPeriod(settings.periodAnchorDay)
                val snapshot = periodRepository.snapshot(period)
                val insights = insightsRepository.load(
                    settings.periodAnchorDay,
                    settings.emergencyFundMonths,
                )
                // Budget limits are keyed by category id; the statement lists
                // categories by name, so bridge the two.
                val categoryNames = categoryDao.observeAll().first().associate { it.id to it.name }
                val limitByCategoryName = planningDao.budgetsOnce()
                    .mapNotNull { budget ->
                        val name = budget.categoryId?.let { categoryNames[it] } ?: return@mapNotNull null
                        name to Money(budget.limitPaise)
                    }
                    .toMap()

                val statement = PdfStatementWriter.Statement(
                    period = period,
                    weatherSentence = weatherSentence(snapshot.tone, snapshot.totals.savingsGap),
                    income = snapshot.totals.actualIncome,
                    expense = snapshot.totals.totalExpense,
                    savingsGap = snapshot.totals.savingsGap,
                    healthScore = (insights.health as? HealthScore.Result.Score)?.value,
                    categories = insights.spendByCategoryName.map { (name, actual) ->
                        PdfStatementWriter.CategoryLine(
                            name = name,
                            budgeted = limitByCategoryName[name],
                            actual = actual,
                        )
                    },
                    topMerchants = insights.leaks.map { it.merchant to it.total },
                    recurring = planningDao.activeRecurringRules().map {
                        it.label to Money(it.amountPaise ?: 0)
                    },
                    trendChart = null,
                )
                val name = DateTimeFormatter.ofPattern("yyyy-MM").format(period.start)
                pdfWriter.write(statement, File(exportRepository.exportDir(), "kosha-$name.pdf"))
            }
            _state.value = _state.value.copy(
                busy = false,
                shareUri = result.getOrNull()?.let(exportRepository::fileUri),
                shareMimeType = "application/pdf",
                message = result.exceptionOrNull()?.let { context.getString(R.string.export_failed) },
            )
        }
    }

    fun performBackup(destination: Uri) {
        val passphrase = _state.value.passphrase
        if (passphrase.isBlank()) return
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, message = null)
            val result = runCatching {
                backupManager.backup(destination, passphrase.toCharArray(), _state.value.includeVault)
            }
            _state.value = _state.value.copy(
                busy = false,
                passphrase = "",
                message = result.fold(
                    onSuccess = { context.getString(R.string.backup_success) },
                    onFailure = { it.message ?: context.getString(R.string.export_failed) },
                ),
            )
        }
    }

    fun performRestore(source: Uri) {
        val passphrase = _state.value.passphrase
        if (passphrase.isBlank()) return
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, message = null)
            val result = runCatching { backupManager.restore(source, passphrase.toCharArray()) }
            _state.value = _state.value.copy(
                busy = false,
                passphrase = "",
                message = result.fold(
                    onSuccess = { context.getString(R.string.backup_restored) },
                    onFailure = { it.message ?: context.getString(R.string.export_failed) },
                ),
            )
        }
    }

    private fun weatherSentence(tone: PeriodMath.WeatherTone, gap: Money): String = when (tone) {
        PeriodMath.WeatherTone.AHEAD -> "Calm skies — ${gap.format(withPaise = false)} ahead this period."
        PeriodMath.WeatherTone.ON_TRACK -> "Steady — income and spending are close this period."
        PeriodMath.WeatherTone.HEADS_UP ->
            "Heads-up — spending is ${gap.abs.format(withPaise = false)} past income this period."
    }
}

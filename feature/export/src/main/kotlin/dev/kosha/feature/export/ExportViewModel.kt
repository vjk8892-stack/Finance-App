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

private val MONTH_LABEL: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM yy")
private val DAY_LABEL: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy")

data class ExportUiState(
    val busy: Boolean = false,
    val shareUri: Uri? = null,
    val shareMimeType: String = "text/csv",
    val passphrase: String = "",
    val includeVault: Boolean = false,
    val message: String? = null,
    /** Backup folder state, so the screen can show where backups go and what is there. */
    val backupFolderName: String? = null,
    val backupFolderReady: Boolean = false,
    val backups: List<BackupFolder.Entry> = emptyList(),
    val lastBackupAtMillis: Long = 0,
    val csvOptions: CsvOptions = CsvOptions(),
    val pdfOptions: PdfOptions = PdfOptions(),
)

@HiltViewModel
class ExportViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val exportRepository: ExportRepository,
    private val pdfWriter: PdfStatementWriter,
    private val backupManager: BackupManager,
    private val backupFolder: BackupFolder,
    private val periodRepository: PeriodRepository,
    private val insightsRepository: InsightsRepository,
    private val planningDao: PlanningDao,
    private val categoryDao: CategoryDao,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(ExportUiState())
    val state: StateFlow<ExportUiState> = _state.asStateFlow()

    init {
        refreshBackups()
    }

    fun setPassphrase(value: String) {
        _state.value = _state.value.copy(passphrase = value)
    }

    fun toggleIncludeVault() {
        _state.value = _state.value.copy(includeVault = !_state.value.includeVault)
    }

    fun setCsvOptions(options: CsvOptions) {
        _state.value = _state.value.copy(csvOptions = options)
    }

    fun setPdfOptions(options: PdfOptions) {
        _state.value = _state.value.copy(pdfOptions = options)
    }

    fun exportCsv() {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, message = null, shareUri = null)
            val anchor = settingsRepository.settings.first().periodAnchorDay
            val period = periodRepository.currentPeriod(anchor)
            val result = runCatching { exportRepository.exportCsv(period, _state.value.csvOptions) }
            _state.value = _state.value.copy(
                busy = false,
                shareUri = result.getOrNull(),
                shareMimeType = "text/csv",
                // Producing a file and saying nothing looked exactly like the
                // button not working. Say what came out, then offer to share it.
                message = result.fold(
                    onSuccess = { context.getString(R.string.export_ready_csv) },
                    onFailure = { context.getString(R.string.export_failed) },
                ),
            )
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

                val options = _state.value.pdfOptions
                // `trend` runs oldest → newest; the chart reads left to right.
                val trendBars = insights.trend.map { point ->
                    PdfStatementWriter.TrendBar(
                        label = MONTH_LABEL.format(point.period.start),
                        spent = point.expense,
                        budget = insights.monthlyBudget,
                    )
                }

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
                            month = MONTH_LABEL.format(period.start),
                        )
                    },
                    topMerchants = insights.leaks.map { it.merchant to it.total },
                    recurring = planningDao.activeRecurringRules().map {
                        it.label to Money(it.amountPaise ?: 0)
                    },
                    trendChart = null,
                    trendBars = trendBars,
                    ledger = if (options.fullLedger) ledgerLines(period, options.range) else emptyList(),
                    options = options,
                    rangeLabel = rangeLabel(period, options.range),
                )
                val name = DateTimeFormatter.ofPattern("yyyy-MM").format(period.start)
                pdfWriter.write(statement, File(exportRepository.exportDir(), "kosha-$name.pdf"))
            }
            _state.value = _state.value.copy(
                busy = false,
                shareUri = result.getOrNull()?.let(exportRepository::fileUri),
                shareMimeType = "application/pdf",
                message = result.fold(
                    onSuccess = { context.getString(R.string.export_ready_pdf) },
                    onFailure = { context.getString(R.string.export_failed) },
                ),
            )
        }
    }

    /** Called when the user picks their backup folder; remembered from then on. */
    fun rememberBackupFolder(treeUri: Uri) {
        viewModelScope.launch {
            backupFolder.remember(treeUri)
            refreshBackups()
        }
    }

    fun refreshBackups() {
        viewModelScope.launch {
            _state.value = _state.value.copy(
                backupFolderName = backupFolder.chosenFolderName(),
                backupFolderReady = backupFolder.isReady(),
                backups = backupFolder.list(),
                lastBackupAtMillis = settingsRepository.settings.first().lastBackupAtMillis,
            )
        }
    }

    /**
     * One tap. No passphrase gate: the old version returned here without doing
     * anything at all when the field was empty, which is how "backup" came to
     * mean "nothing happens".
     */
    fun backupNow() {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, message = null)
            val result = runCatching {
                val destination = backupFolder.newBackupFile()
                    ?: throw BackupManager.RestoreFailed(
                        context.getString(R.string.backup_folder_unavailable),
                    )
                backupManager.backup(
                    destination = destination,
                    passphrase = _state.value.passphrase.takeIf { it.isNotBlank() }?.toCharArray(),
                    includeVault = _state.value.includeVault,
                )
                settingsRepository.setLastBackupAt(System.currentTimeMillis())
            }
            _state.value = _state.value.copy(
                busy = false,
                message = result.fold(
                    onSuccess = { context.getString(R.string.backup_success) },
                    onFailure = { it.message ?: context.getString(R.string.export_failed) },
                ),
            )
            refreshBackups()
        }
    }

    /** Asks first, so a restore of a passphrase-protected file fails loudly and usefully. */
    fun performRestore(source: Uri) {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, message = null)
            val typed = _state.value.passphrase.takeIf { it.isNotBlank() }?.toCharArray()
            val result = runCatching {
                if (backupManager.needsPassphrase(source) && typed == null) {
                    throw BackupManager.RestoreFailed(
                        context.getString(R.string.backup_needs_passphrase),
                    )
                }
                backupManager.restore(source, typed)
            }
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

    fun deleteBackup(uri: Uri) {
        viewModelScope.launch {
            backupFolder.delete(uri)
            refreshBackups()
        }
    }

    /** The ledger section reuses the CSV row builder, so the two always agree. */
    private suspend fun ledgerLines(
        period: dev.kosha.core.common.Period,
        range: ExportRange,
    ): List<PdfStatementWriter.LedgerLine> =
        exportRepository.buildRows(period, CsvOptions(range = range)).map { row ->
            PdfStatementWriter.LedgerLine(
                date = row.date,
                merchant = row.merchant.ifBlank { "—" },
                category = row.category,
                account = row.account,
                amount = row.amount,
                isCredit = row.isCredit,
            )
        }

    private fun rangeLabel(period: dev.kosha.core.common.Period, range: ExportRange): String {
        val today = java.time.LocalDate.now()
        val start = range.startDate(period, today) ?: return "Everything up to ${DAY_LABEL.format(today)}"
        val end = range.endDateExclusive(period)?.minusDays(1) ?: today
        return "${DAY_LABEL.format(start)} — ${DAY_LABEL.format(end)}"
    }

    private fun weatherSentence(tone: PeriodMath.WeatherTone, gap: Money): String = when (tone) {
        PeriodMath.WeatherTone.AHEAD -> "Calm skies — ${gap.format(withPaise = false)} ahead this period."
        PeriodMath.WeatherTone.ON_TRACK -> "Steady — income and spending are close this period."
        PeriodMath.WeatherTone.HEADS_UP ->
            "Heads-up — spending is ${gap.abs.format(withPaise = false)} past income this period."
    }
}

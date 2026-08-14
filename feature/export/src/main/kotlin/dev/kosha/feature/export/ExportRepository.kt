package dev.kosha.feature.export

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.kosha.core.common.Money
import dev.kosha.core.common.Period
import dev.kosha.core.database.dao.AccountDao
import dev.kosha.core.database.dao.CategoryDao
import dev.kosha.core.database.dao.TransactionDao
import dev.kosha.core.database.model.SystemCategoryKey
import dev.kosha.core.database.model.TxnStatus
import dev.kosha.core.engine.export.CsvWriter
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Export (spec Phase 10 / G10).
 *
 * INVARIANT: this module has no dependency path to the vault — it injects
 * only the transaction, account and category DAOs, so there is literally no
 * code path from an export artifact to Ring-2 data (spec B4/G10).
 */
@Singleton
class ExportRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val transactionDao: TransactionDao,
    private val accountDao: AccountDao,
    private val categoryDao: CategoryDao,
) {
    private val zone: ZoneId = ZoneId.systemDefault()
    private val dateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    suspend fun exportCsv(
        period: Period,
        options: CsvOptions = CsvOptions(),
    ): Uri = withContext(Dispatchers.IO) {
        val rows = buildRows(period, options)
        val csv = CsvWriter.write(
            rows,
            CsvWriter.Options(
                splitAmountColumns = options.splitAmountColumns,
                includeNotesAndTags = options.includeNotesAndTags,
                includeRunningBalance = options.includeRunningBalance,
            ),
        )
        val file = File(exportDir(), "kosha-${dateFormat.format(period.start)}.csv")
        file.writeText(csv)
        fileUri(file)
    }

    /**
     * The window [options] asks for, as epoch millis. `null` bounds mean "all
     * of history" and "up to now" respectively.
     */
    fun window(period: Period, range: ExportRange): Pair<Long, Long> {
        val start = range.startDate(period, LocalDate.now(zone))
        val endExclusive = range.endDateExclusive(period)
        return Pair(
            start?.atStartOfDay(zone)?.toInstant()?.toEpochMilli() ?: 0L,
            endExclusive?.atStartOfDay(zone)?.toInstant()?.toEpochMilli() ?: Long.MAX_VALUE,
        )
    }

    suspend fun buildRows(
        period: Period,
        options: CsvOptions = CsvOptions(),
    ): List<CsvWriter.Row> {
        val accounts = accountDao.activeAccounts().associateBy { it.id }
        val categories = categoryDao.observeAll().first().associateBy { it.id }
        val excludedIds = categories.values
            .filter {
                it.systemKey == SystemCategoryKey.TRANSFERS ||
                    it.systemKey == SystemCategoryKey.CASH_WITHDRAWAL
            }
            .map { it.id }
            .toSet()
        val (from, until) = window(period, options.range)
        return transactionDao
            .inWindow(from, until)
            .filter {
                // Split children would double-count against their parent.
                it.parentTransactionId == null &&
                    (
                        it.status == TxnStatus.COMMITTED ||
                            (options.includePending && it.status == TxnStatus.PENDING_REVIEW)
                        ) &&
                    (options.includeTransfers || it.categoryId !in excludedIds)
            }
            .sortedBy { it.timestampMillis }
            .map { txn ->
                CsvWriter.Row(
                    date = dateFormat.format(
                        Instant.ofEpochMilli(txn.timestampMillis).atZone(zone).toLocalDate(),
                    ),
                    merchant = txn.merchantRaw.orEmpty(),
                    category = txn.categoryId?.let { categories[it]?.name }.orEmpty(),
                    account = accounts[txn.accountId]?.name.orEmpty(),
                    type = txn.type.name.lowercase(),
                    amount = Money(txn.amountPaise),
                    note = txn.note.orEmpty(),
                    source = txn.source.name.lowercase(),
                    tags = listOfNotNull(
                        txn.moodTag?.name?.lowercase(),
                        txn.taxTag?.name?.lowercase()?.removePrefix("tax_"),
                    ).joinToString(" "),
                )
            }
    }

    fun exportDir(): File = File(context.cacheDir, "exports").apply { mkdirs() }

    fun fileUri(file: File): Uri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        file,
    )
}

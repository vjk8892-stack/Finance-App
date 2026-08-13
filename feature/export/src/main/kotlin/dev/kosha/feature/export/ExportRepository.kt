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
import dev.kosha.core.database.model.TxnStatus
import dev.kosha.core.engine.export.CsvWriter
import java.io.File
import java.time.Instant
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

    suspend fun exportCsv(period: Period): Uri = withContext(Dispatchers.IO) {
        val rows = buildRows(period)
        val csv = CsvWriter.write(rows)
        val file = File(exportDir(), "kosha-${dateFormat.format(period.start)}.csv")
        file.writeText(csv)
        fileUri(file)
    }

    suspend fun buildRows(period: Period): List<CsvWriter.Row> {
        val accounts = accountDao.activeAccounts().associateBy { it.id }
        val categories = categoryDao.observeAll().first().associateBy { it.id }
        return transactionDao
            .inWindow(period.startEpochMillis(zone), period.endEpochMillisExclusive(zone))
            .filter { it.status == TxnStatus.COMMITTED }
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

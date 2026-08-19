package dev.kosha.feature.ingest.sms

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.kosha.core.common.Money
import dev.kosha.core.database.dao.AccountDao
import dev.kosha.core.database.dao.CategoryDao
import dev.kosha.core.database.dao.TransactionDao
import dev.kosha.core.database.model.TxnStatus
import dev.kosha.core.database.model.TxnType
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tells you a transaction was captured, the moment it happens.
 *
 * Kosha reads bank messages in the background whether or not it is open, and
 * until now said nothing at all when it did — the only way to learn that a
 * payment had been recorded was to open the app and go looking. An automatic
 * ledger that never speaks is indistinguishable from one that is not running,
 * which is exactly the doubt this removes.
 *
 * DELIBERATELY LOUD, unlike the budget and recurring channels. Those are
 * ambient reminders and are set to IMPORTANCE_LOW on purpose. This one is a
 * statement of fact about money that has just moved, and the whole point is
 * that it appears without being sought — so it is IMPORTANCE_HIGH, which is
 * what makes Android show it as a heads-up banner over whatever is on screen.
 *
 * On height: Android does not expose a way to ask for a share of the screen.
 * An expanded notification is capped at roughly 256dp by the platform, which
 * on a normal phone is about a fifth of it. Using the big-text style with all
 * four detail lines gets as close to that as the system permits.
 */
@Singleton
class CaptureNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val transactionDao: TransactionDao,
    private val accountDao: AccountDao,
    private val categoryDao: CategoryDao,
) {
    /**
     * @param needsReview true when the row landed in the review queue rather
     * than the ledger — a different thing to be told, and the reason the
     * wording and the action both change.
     */
    suspend fun notifyCaptured(transactionId: Long, needsReview: Boolean) {
        if (!canNotify()) return
        val txn = transactionDao.byId(transactionId) ?: return
        // A row hidden by the tracking boundary is not news.
        if (txn.status != TxnStatus.COMMITTED && !needsReview) return

        val account = accountDao.byId(txn.accountId)?.name.orEmpty()
        val category = txn.categoryId?.let { categoryDao.byId(it)?.name }
        val amount = Money(txn.amountPaise).format(withPaise = false)
        val merchant = txn.merchantRaw?.takeIf { it.isNotBlank() }
            ?: context.getString(R.string.capture_no_name)

        val title = if (txn.type == TxnType.CREDIT) {
            context.getString(R.string.capture_title_in, amount)
        } else {
            context.getString(R.string.capture_title_out, amount)
        }

        // Every detail on its own line: the expanded form is the tall one, and
        // the point of the height is that nothing needs opening to be checked.
        val detail = buildString {
            appendLine(merchant)
            appendLine(context.getString(R.string.capture_account, account))
            appendLine(
                context.getString(
                    R.string.capture_category,
                    category ?: context.getString(R.string.capture_uncategorized),
                ),
            )
            append(
                if (needsReview) {
                    context.getString(R.string.capture_needs_review)
                } else {
                    context.getString(R.string.capture_in_ledger)
                },
            )
        }

        ensureChannel()
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_save)
            .setContentTitle(title)
            .setContentText(merchant)
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setContentIntent(openTransaction(transactionId, txn.timestampMillis))
            .build()

        NotificationManagerCompat.from(context)
            .notify(NOTIFICATION_BASE + transactionId.toInt(), notification)
    }

    /**
     * Opens the app on the row this is about. Uses the launch intent rather
     * than a direct activity reference because this module cannot see `:app` —
     * the extra is read on the other side.
     */
    private fun openTransaction(transactionId: Long, timestampMillis: Long): PendingIntent? {
        // The DAY, not just the id: the ledger can already filter to a date
        // range, so handing it one gets you to the row itself rather than to a
        // list you then have to search. Promising "tap to check it" and landing
        // on an unfiltered ledger would be the notification overselling itself.
        val day = java.time.Instant.ofEpochMilli(timestampMillis)
            .atZone(java.time.ZoneId.systemDefault())
            .toLocalDate()
            .toString()
        val intent = context.packageManager
            .getLaunchIntentForPackage(context.packageName)
            ?.apply {
                action = ACTION_OPEN_TRANSACTION
                putExtra(EXTRA_TRANSACTION_ID, transactionId)
                putExtra(EXTRA_TRANSACTION_DAY, day)
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            } ?: return null
        return PendingIntent.getActivity(
            context,
            transactionId.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun canNotify(): Boolean =
        android.os.Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun ensureChannel() {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.capture_channel_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.capture_channel_desc)
            enableVibration(true)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_ID = "kosha_captured"
        const val ACTION_OPEN_TRANSACTION = "dev.kosha.action.OPEN_TRANSACTION"
        const val EXTRA_TRANSACTION_ID = "dev.kosha.extra.TRANSACTION_ID"
        const val EXTRA_TRANSACTION_DAY = "dev.kosha.extra.TRANSACTION_DAY"
        private const val NOTIFICATION_BASE = 7000
    }
}

package dev.kosha.feature.vault

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import androidx.work.Constraints
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Clipboard hygiene (spec B4): copies from the vault are flagged sensitive
 * so the OS does not surface a preview, and are cleared 30 seconds later.
 */
class ClipboardClearWorker(
    private val appContext: Context,
    params: WorkerParameters,
) : Worker(appContext, params) {

    override fun doWork(): Result {
        val clipboard = appContext.getSystemService(ClipboardManager::class.java)
            ?: return Result.success()
        // Only clear if what we put there is still on the clipboard.
        val current = clipboard.primaryClip
        val marker = inputData.getString(KEY_MARKER)
        val stillOurs = current != null &&
            current.itemCount > 0 &&
            current.description?.label == marker
        if (stillOurs) {
            if (Build.VERSION.SDK_INT >= 28) {
                clipboard.clearPrimaryClip()
            } else {
                clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
            }
        }
        return Result.success()
    }

    companion object {
        const val CLEAR_AFTER_SECONDS = 30L
        private const val KEY_MARKER = "marker"
        private const val CLIP_LABEL = "Kosha vault"

        /** Copies [value] with the sensitive flag and schedules the wipe. */
        fun copySensitive(context: Context, value: String) {
            val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
            val clip = ClipData.newPlainText(CLIP_LABEL, value).apply {
                description.extras = PersistableBundle().apply {
                    putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
                }
            }
            clipboard.setPrimaryClip(clip)

            WorkManager.getInstance(context).enqueue(
                OneTimeWorkRequestBuilder<ClipboardClearWorker>()
                    .setInitialDelay(CLEAR_AFTER_SECONDS, TimeUnit.SECONDS)
                    .setInputData(
                        androidx.work.Data.Builder().putString(KEY_MARKER, CLIP_LABEL).build(),
                    )
                    .setConstraints(Constraints.NONE)
                    .build(),
            )
        }
    }
}

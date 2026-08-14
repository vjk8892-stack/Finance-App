package dev.kosha.feature.ingest.sms

import android.content.Context
import android.provider.Telephony
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.kosha.core.database.repo.OriginalMessageSource
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Reads the original message straight from the inbox at display time.
 *
 * A captured transaction stores the receipt time as its timestamp, so the
 * message can be found again by date without keeping a copy of the text —
 * which is the whole point: spec B4's promise that Kosha stores only the
 * parsed fields stays intact, and the message is still there when you ask.
 *
 * A small window covers the rounding between the provider's timestamp and the
 * one we recorded; the closest match inside it wins.
 */
@Singleton
class InboxMessageSource @Inject constructor(
    @ApplicationContext private val context: Context,
) : OriginalMessageSource {

    override suspend fun messageAt(timestampMillis: Long): String? = withContext(Dispatchers.IO) {
        if (!SmsCapability.isUsable(context)) return@withContext null

        runCatching {
            context.contentResolver.query(
                Telephony.Sms.Inbox.CONTENT_URI,
                arrayOf(Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.ADDRESS),
                "${Telephony.Sms.DATE} BETWEEN ? AND ?",
                arrayOf(
                    (timestampMillis - MATCH_WINDOW_MILLIS).toString(),
                    (timestampMillis + MATCH_WINDOW_MILLIS).toString(),
                ),
                "${Telephony.Sms.DATE} ASC",
            )?.use { cursor ->
                val bodyIdx = cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)
                val dateIdx = cursor.getColumnIndexOrThrow(Telephony.Sms.DATE)
                var best: String? = null
                var bestDelta = Long.MAX_VALUE
                while (cursor.moveToNext()) {
                    val delta = kotlin.math.abs(cursor.getLong(dateIdx) - timestampMillis)
                    if (delta < bestDelta) {
                        bestDelta = delta
                        best = cursor.getString(bodyIdx)
                    }
                }
                best?.takeIf { it.isNotBlank() }
            }
        }.getOrNull()
    }

    private companion object {
        /** Generous enough for clock rounding, tight enough to stay unambiguous. */
        const val MATCH_WINDOW_MILLIS = 2_000L
    }
}

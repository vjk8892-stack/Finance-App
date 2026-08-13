package dev.kosha.feature.ingest.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Live SMS capture. Multipart messages are concatenated per originating
 * address before parsing. Runs off the main thread via goAsync().
 */
@AndroidEntryPoint
class SmsReceiver : BroadcastReceiver() {

    @Inject
    lateinit var ingest: IngestSmsUseCase

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        if (messages.isEmpty()) return

        val bySender = messages.groupBy { it.displayOriginatingAddress ?: "" }
        val pending = goAsync()
        scope.launch {
            try {
                for ((sender, parts) in bySender) {
                    if (sender.isBlank()) continue
                    val body = parts.joinToString("") { it.displayMessageBody ?: "" }
                    if (body.isBlank()) continue
                    ingest.ingest(sender, body, parts.first().timestampMillis)
                }
            } finally {
                pending.finish()
            }
        }
    }
}

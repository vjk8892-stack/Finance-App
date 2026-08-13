package dev.kosha.feature.ingest.ocr

import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/**
 * ML Kit Text Recognition v2, Latin script, fully on-device (spec B1/G1).
 * There is no cloud fallback and no INTERNET permission to reach one.
 */
@Singleton
class OcrTextRecognizer @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val recognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    /** Recognized text with lines in reading order, newline-separated. */
    suspend fun recognize(uri: Uri): String = suspendCoroutine { continuation ->
        try {
            val image = InputImage.fromFilePath(context, uri)
            recognizer.process(image)
                .addOnSuccessListener { result ->
                    val text = result.textBlocks
                        .flatMap { block -> block.lines }
                        .joinToString("\n") { it.text }
                    continuation.resume(text)
                }
                .addOnFailureListener { continuation.resumeWithException(it) }
        } catch (e: Exception) {
            continuation.resumeWithException(e)
        }
    }
}

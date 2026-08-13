package dev.kosha.core.engine.ocr

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class OcrTemplate(
    val id: String,
    /** phonepe | gpay | paytm | generic-bill */
    val appLabel: String,
    val anchorKeywords: List<String>,
    val minAnchorHits: Int = 1,
    val baseConfidence: Double,
)

@Serializable
data class OcrTemplateFile(
    val schemaVersion: Int,
    val libraryVersion: Int,
    val comment: String = "",
    val templates: List<OcrTemplate>,
)

class OcrTemplateLibrary(val file: OcrTemplateFile) {

    /** UPI templates first; the bill template is the catch-all fallback. */
    val templates: List<OcrTemplate> = file.templates.filter { it.appLabel != "generic-bill" }

    val genericBill: OcrTemplate = file.templates.first { it.appLabel == "generic-bill" }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        const val DEFAULT_RESOURCE = "kosha/patterns/ocr-templates-v1.json"

        fun fromJson(text: String): OcrTemplateLibrary =
            OcrTemplateLibrary(json.decodeFromString<OcrTemplateFile>(text))

        fun bundled(): OcrTemplateLibrary {
            val stream = requireNotNull(
                OcrTemplateLibrary::class.java.classLoader.getResourceAsStream(DEFAULT_RESOURCE),
            ) { "Bundled OCR template library missing: $DEFAULT_RESOURCE" }
            return fromJson(stream.bufferedReader().readText())
        }
    }
}

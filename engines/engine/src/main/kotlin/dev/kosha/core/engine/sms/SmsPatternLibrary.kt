package dev.kosha.core.engine.sms

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Versioned SMS pattern library (spec Part E: patterns are data, not code).
 * Ships as a JSON resource inside the engine jar; new bank formats are data
 * PRs with corpus tests.
 */
@Serializable
data class SmsPatternSpec(
    val id: String,
    val bank: String,
    val senderCodes: List<String>,
    /** "debit" | "credit" */
    val type: String,
    val regex: String,
    val baseConfidence: Double,
    @SerialName("isAtmWithdrawal") val isAtmWithdrawal: Boolean = false,
)

@Serializable
data class SmsPatternFile(
    val schemaVersion: Int,
    val libraryVersion: Int,
    val comment: String = "",
    val patterns: List<SmsPatternSpec>,
)

class SmsPatternLibrary(val file: SmsPatternFile) {

    val compiled: List<CompiledPattern> = file.patterns.map {
        CompiledPattern(it, Regex(it.regex))
    }

    /** Union of all known bank sender codes — the allowlist gate (spec B4). */
    val allowedSenderCodes: Set<String> =
        file.patterns.flatMap { it.senderCodes }.toSet()

    data class CompiledPattern(val spec: SmsPatternSpec, val regex: Regex)

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        const val DEFAULT_RESOURCE = "kosha/patterns/sms-patterns-v1.json"

        fun fromJson(text: String): SmsPatternLibrary =
            SmsPatternLibrary(json.decodeFromString<SmsPatternFile>(text))

        /** Loads the library bundled with the engine jar. */
        fun bundled(): SmsPatternLibrary {
            val stream = requireNotNull(
                SmsPatternLibrary::class.java.classLoader.getResourceAsStream(DEFAULT_RESOURCE),
            ) { "Bundled SMS pattern library missing: $DEFAULT_RESOURCE" }
            return fromJson(stream.bufferedReader().readText())
        }

        /**
         * "VM-HDFCBK-S" → "HDFCBK": strips the telco route prefix (2 chars)
         * and DLT category suffix, leaving the sender's core code.
         */
        fun normalizeSender(sender: String): String {
            var s = sender.trim().uppercase()
            if (s.length > 3 && s[2] == '-') s = s.substring(3)
            if (s.length > 2 && s[s.length - 2] == '-') s = s.substring(0, s.length - 2)
            return s
        }
    }
}

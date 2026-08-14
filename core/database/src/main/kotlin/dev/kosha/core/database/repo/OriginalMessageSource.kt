package dev.kosha.core.database.repo

/**
 * Reads back the bank message a transaction came from, on demand.
 *
 * Spec B4 keeps raw SMS text out of the database by default, which is right —
 * but it also meant "show me the message behind this row" only worked if you
 * had turned on a debug setting BEFORE the message arrived, which is exactly
 * backwards from when you need it. Since the inbox still holds the message,
 * it can be read at display time instead: nothing extra is stored, and the
 * text is available for every SMS-sourced row rather than only future ones.
 *
 * Implemented in the SMS feature (which owns the permission) and bound in the
 * app module; the lite build and the no-permission case bind [Unavailable],
 * so callers never need to know which build they are in.
 */
interface OriginalMessageSource {

    /**
     * The message received at [timestampMillis], or null when it cannot be
     * read — no permission, lite build, or the user deleted it.
     */
    suspend fun messageAt(timestampMillis: Long): String?

    /** No SMS access in this build, or the permission was never granted. */
    object Unavailable : OriginalMessageSource {
        override suspend fun messageAt(timestampMillis: Long): String? = null
    }
}

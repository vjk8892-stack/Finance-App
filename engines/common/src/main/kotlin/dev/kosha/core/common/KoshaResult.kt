package dev.kosha.core.common

/** Lightweight result wrapper for engine and repository boundaries. */
sealed interface KoshaResult<out T> {
    data class Success<T>(val value: T) : KoshaResult<T>
    data class Failure(val error: Throwable, val message: String? = null) : KoshaResult<Nothing>

    fun getOrNull(): T? = (this as? Success)?.value

    companion object {
        inline fun <T> runCatching(block: () -> T): KoshaResult<T> = try {
            Success(block())
        } catch (t: Throwable) {
            Failure(t)
        }
    }
}

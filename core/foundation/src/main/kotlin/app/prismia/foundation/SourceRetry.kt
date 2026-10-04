package app.prismia.foundation

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * Bounded retry primitive for source transports. It retries only when the
 * caller classifies the failure as transient and always propagates coroutine
 * cancellation immediately.
 */
data class SourceRetryPolicy(
    val maxAttempts: Int = 3,
    val initialDelayMillis: Long = 250,
    val maxDelayMillis: Long = 2_000,
) {
    init {
        require(maxAttempts >= 1) { "maxAttempts must be positive" }
        require(initialDelayMillis >= 0) { "initialDelayMillis must be non-negative" }
        require(maxDelayMillis >= initialDelayMillis) { "maxDelayMillis must not be smaller than initialDelayMillis" }
    }
}

suspend fun <T> withSourceRetry(
    policy: SourceRetryPolicy = SourceRetryPolicy(),
    isRetryable: (Exception) -> Boolean,
    block: suspend () -> T,
): T {
    var attempt = 1
    var delayMillis = policy.initialDelayMillis
    while (true) {
        try {
            return block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            if (attempt >= policy.maxAttempts || !isRetryable(failure)) throw failure
            if (delayMillis > 0) delay(delayMillis)
            attempt++
            delayMillis = if (delayMillis > policy.maxDelayMillis / 2) {
                policy.maxDelayMillis
            } else {
                delayMillis * 2
            }
        }
    }
}

package dev.jmx.client.core.network

import dev.jmx.client.core.result.JmxError

data class RetryDecision(
    val shouldRetry: Boolean,
    val shouldFailover: Boolean
)

interface RetryPolicy {
    val maxAttempts: Int

    fun decide(error: JmxError, attemptIndex: Int): RetryDecision
}

class DefaultRetryPolicy(
    override val maxAttempts: Int = 3,
    private val failoverOnRetryable: Boolean = true
) : RetryPolicy {
    override fun decide(error: JmxError, attemptIndex: Int): RetryDecision {
        val hasNextAttempt = attemptIndex + 1 < maxAttempts.coerceAtLeast(1)
        val shouldRetry = hasNextAttempt && error.retryable
        val shouldFailover = shouldRetry && failoverOnRetryable && error.shouldTryNextEndpoint()
        return RetryDecision(
            shouldRetry = shouldRetry,
            shouldFailover = shouldFailover
        )
    }

    private fun JmxError.shouldTryNextEndpoint(): Boolean {
        return when (this) {
            is JmxError.Network,
            is JmxError.Domain -> true

            is JmxError.Http -> code >= 500 || code == 408 || code == 429 || code == 403

            is JmxError.Decode -> retryable
            is JmxError.Api,
            is JmxError.Schema,
            is JmxError.Unknown -> false
        }
    }
}

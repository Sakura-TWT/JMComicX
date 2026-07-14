package dev.jmx.client.core.network

import dev.jmx.client.core.result.JmxError
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RetryPolicyTest {
    @Test
    fun retryableHttpFailureCanFailoverUntilAttemptLimit() {
        val policy = DefaultRetryPolicy(maxAttempts = 2)

        val first = policy.decide(JmxError.Http(code = 502, message = "bad gateway"), attemptIndex = 0)
        val second = policy.decide(JmxError.Http(code = 502, message = "bad gateway"), attemptIndex = 1)

        assertTrue(first.shouldRetry)
        assertTrue(first.shouldFailover)
        assertFalse(second.shouldRetry)
        assertFalse(second.shouldFailover)
    }

    @Test
    fun schemaFailuresDoNotRetry() {
        val policy = DefaultRetryPolicy(maxAttempts = 3)

        val decision = policy.decide(JmxError.Schema("missing data"), attemptIndex = 0)

        assertFalse(decision.shouldRetry)
        assertFalse(decision.shouldFailover)
    }

    @Test
    fun forbiddenAndRetryableDecodeTriggerFailover() {
        val policy = DefaultRetryPolicy(maxAttempts = 3)

        val forbidden = policy.decide(
            JmxError.Http(code = 403, message = "denied", retryable = true),
            attemptIndex = 0
        )
        val nonJson = policy.decide(
            JmxError.Decode(message = "not json", retryable = true),
            attemptIndex = 0
        )
        val decodeHard = policy.decide(
            JmxError.Decode(message = "bad padding"),
            attemptIndex = 0
        )

        assertTrue(forbidden.shouldRetry)
        assertTrue(forbidden.shouldFailover)
        assertTrue(nonJson.shouldRetry)
        assertTrue(nonJson.shouldFailover)
        assertFalse(decodeHard.shouldRetry)
        assertFalse(decodeHard.shouldFailover)
    }
}

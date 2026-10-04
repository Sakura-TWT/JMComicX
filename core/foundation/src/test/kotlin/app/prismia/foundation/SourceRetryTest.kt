package app.prismia.foundation

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.currentTime
import org.junit.Assert.assertEquals
import org.junit.Test

class SourceRetryTest {
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun largeBackoffNeverOverflowsAndSkipsTheNextDelay() = runTest {
        var attempts = 0
        withSourceRetry(
            // delay(Long.MAX_VALUE) deliberately means "forever" to coroutines.
            // Use the largest finite delay while still crossing multiplication overflow.
            policy = SourceRetryPolicy(3, Long.MAX_VALUE / 2 + 1, Long.MAX_VALUE - 1),
            isRetryable = { true },
        ) {
            attempts++
            if (attempts < 3) throw IllegalStateException("transient")
        }
        assertEquals(3, attempts)
        assertEquals(Long.MAX_VALUE, currentTime)
    }

    @Test
    fun retriesOnlyClassifiedFailures() = runBlocking {
        var attempts = 0
        val result = withSourceRetry(
            policy = SourceRetryPolicy(maxAttempts = 3, initialDelayMillis = 0, maxDelayMillis = 0),
            isRetryable = { it is IllegalStateException },
        ) {
            attempts++
            if (attempts < 3) throw IllegalStateException("temporary")
            "ok"
        }

        assertEquals("ok", result)
        assertEquals(3, attempts)
    }
}

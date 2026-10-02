package app.prismia.foundation

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class SourceRetryTest {
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

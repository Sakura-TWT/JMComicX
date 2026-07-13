package dev.jmx.client.core.network

import dev.jmx.client.core.result.JmxResult
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiResponseDecoderTest {
    @Test
    fun schemaFailureIncludesSampleAndRedactsSensitiveValues() {
        val body = """{"password":"secret","token":"abc","data":[]}"""

        val result = ApiResponseDecoder().decodeEncryptedEnvelope(body, tokenTimestampSeconds = 1L)

        assertTrue(result is JmxResult.Failure)
        val message = (result as JmxResult.Failure).error.message
        assertTrue(message.contains("password=<redacted>"))
        assertTrue(message.contains("token=<redacted>"))
        assertFalse(message.contains("secret"))
        assertFalse(message.contains("abc"))
    }

    @Test
    fun bodySamplerTruncatesLongBodies() {
        val sample = BodySampler(maxChars = 8).sample("1234567890")

        assertTrue(sample.length <= 8)
    }
}

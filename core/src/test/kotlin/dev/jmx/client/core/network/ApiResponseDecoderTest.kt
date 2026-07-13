package dev.jmx.client.core.network

import dev.jmx.client.core.result.JmxResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiResponseDecoderTest {
    @Test
    fun plainEnvelopeAcceptsStatusAliasAndMessageFields() {
        val result = ApiResponseDecoder().decodePlainEnvelope(
            """{"status_code":403,"message":"denied","data":{"ignored":true}}"""
        )

        assertTrue(result is JmxResult.Success)
        val envelope = (result as JmxResult.Success).value
        assertEquals(403, envelope.code)
        assertEquals("denied", envelope.errorMessage)
    }

    @Test
    fun encryptedEnvelopeUsesDefaultMessageWhenErrorMessageIsBlank() {
        val result = ApiResponseDecoder().decodeEncryptedEnvelope(
            """{"status":429,"error_msg":"   "}""",
            tokenTimestampSeconds = 1L
        )

        assertTrue(result is JmxResult.Success)
        val envelope = (result as JmxResult.Success).value
        assertEquals(429, envelope.code)
        assertEquals("接口返回错误：429", envelope.errorMessage)
    }

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

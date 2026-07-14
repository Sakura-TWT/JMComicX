package dev.jmx.client.core.network

import dev.jmx.client.core.crypto.AesEcbPkcs7
import dev.jmx.client.core.crypto.JmxHash
import dev.jmx.client.core.protocol.JmxProtocolConstants
import dev.jmx.client.core.result.JmxError
import dev.jmx.client.core.result.JmxResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiResponseDecoderTest {
    @Test
    fun encryptedEnvelopeDecodesEncryptedStringData() {
        val ts = 1700566805L
        val encrypted = encryptData(ts, """{"name":"demo"}""")

        val result = ApiResponseDecoder().decodeEncryptedEnvelope("""{"code":200,"data":"$encrypted"}""", ts)

        assertTrue(result is JmxResult.Success)
        val envelope = (result as JmxResult.Success).value
        assertEquals("demo", envelope.data!!.asJsonObject["name"].asString)
    }

    @Test
    fun encryptedEnvelopeAcceptsAlreadyPlainObjectData() {
        val result = ApiResponseDecoder().decodeEncryptedEnvelope(
            """{"code":200,"data":{"name":"plain"}}""",
            tokenTimestampSeconds = 1L
        )

        assertTrue(result is JmxResult.Success)
        val envelope = (result as JmxResult.Success).value
        assertEquals("plain", envelope.data!!.asJsonObject["name"].asString)
    }

    @Test
    fun encryptedEnvelopeAcceptsAlreadyPlainArrayData() {
        val result = ApiResponseDecoder().decodeEncryptedEnvelope(
            """{"code":200,"data":[{"id":"1"}]}""",
            tokenTimestampSeconds = 1L
        )

        assertTrue(result is JmxResult.Success)
        val envelope = (result as JmxResult.Success).value
        assertEquals("1", envelope.data!!.asJsonArray[0].asJsonObject["id"].asString)
    }

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
    fun encryptedEnvelopeToleratesLeadingNoiseBeforeJson() {
        val ts = 1700566805L
        val encrypted = encryptData(ts, """{"name":"noisy"}""")
        val result = ApiResponseDecoder().decodeEncryptedEnvelope(
            body = """garbage prefix {"code":200,"data":"$encrypted"}""",
            tokenTimestampSeconds = ts
        )

        assertTrue(result is JmxResult.Success)
        val envelope = (result as JmxResult.Success).value
        assertEquals("noisy", envelope.data!!.asJsonObject["name"].asString)
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
    fun encryptedEnvelopeRejectsNullOrBlankDataClearly() {
        val nullResult = ApiResponseDecoder().decodeEncryptedEnvelope("""{"code":200,"data":null}""", 1L)
        val blankResult = ApiResponseDecoder().decodeEncryptedEnvelope("""{"code":200,"data":"   "}""", 1L)

        assertTrue(nullResult is JmxResult.Failure)
        assertTrue(blankResult is JmxResult.Failure)
        assertEquals("data", ((nullResult as JmxResult.Failure).error as JmxError.Schema).field)
        assertTrue((blankResult as JmxResult.Failure).error.message.contains("加密 data 为空"))
    }

    @Test
    fun encryptedEnvelopeReportsDecryptedNonJsonSample() {
        val ts = 1700566805L
        val encrypted = encryptData(ts, "not json")

        val result = ApiResponseDecoder().decodeEncryptedEnvelope("""{"code":200,"data":"$encrypted"}""", ts)

        assertTrue(result is JmxResult.Failure)
        val error = (result as JmxResult.Failure).error
        assertTrue(error is JmxError.Schema)
        assertTrue(error.message.contains("JSON 解析失败"))
        assertTrue(error.message.contains("not json"))
    }

    @Test
    fun encryptedEnvelopeFailsWhenTimestampDoesNotMatchEncryptionKey() {
        val encryptTs = 1700566805L
        val decryptTs = 1700566806L
        val encrypted = encryptData(encryptTs, """{"name":"mismatch"}""")

        val result = ApiResponseDecoder().decodeEncryptedEnvelope(
            """{"code":200,"data":"$encrypted"}""",
            tokenTimestampSeconds = decryptTs
        )

        assertTrue(result is JmxResult.Failure)
        assertTrue((result as JmxResult.Failure).error is JmxError.Decode)
    }

    @Test
    fun bodySamplerTruncatesLongBodies() {
        val sample = BodySampler(maxChars = 8).sample("1234567890")

        assertTrue(sample.length <= 8)
    }

    private fun encryptData(ts: Long, json: String): String {
        return AesEcbPkcs7.encryptStringToBase64(
            plain = json,
            key = JmxHash.md5Hex("$ts${JmxProtocolConstants.DataSecret}")
        )
    }
}

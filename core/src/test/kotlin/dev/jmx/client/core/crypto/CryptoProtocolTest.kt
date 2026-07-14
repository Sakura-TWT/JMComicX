package dev.jmx.client.core.crypto

import dev.jmx.client.core.protocol.JmxProtocolConstants
import dev.jmx.client.core.result.JmxError
import dev.jmx.client.core.result.JmxResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CryptoProtocolTest {
    @Test
    fun md5MatchesPythonHexDigest() {

        assertEquals(
            "cce2cb071cd0cf371a2e34fd5ad66fd6",
            JmxHash.md5Hex("1700566805${JmxProtocolConstants.AppTokenSecret}")
        )

        assertEquals(
            JmxHash.md5Hex("1700566805${JmxProtocolConstants.AppTokenSecret}"),
            JmxHash.md5Hex("1700566805${JmxProtocolConstants.DataSecret}")
        )
    }

    @Test
    fun aesRoundTripWithTimestampBoundKey() {
        val ts = 1700566805L
        val key = JmxHash.md5Hex("$ts${JmxProtocolConstants.DataSecret}")
        val plain = """{"name":"demo","id":1}"""

        val encrypted = AesEcbPkcs7.encryptStringToBase64(plain, key)
        val decrypted = AesEcbPkcs7.decryptBase64ToString(encrypted, key)

        assertTrue(decrypted is JmxResult.Success)
        assertEquals(plain, (decrypted as JmxResult.Success).value)
    }

    @Test
    fun wrongTimestampKeyFailsDecrypt() {
        val plain = """{"name":"bound"}"""
        val goodKey = JmxHash.md5Hex("100${JmxProtocolConstants.DataSecret}")
        val badKey = JmxHash.md5Hex("101${JmxProtocolConstants.DataSecret}")
        val encrypted = AesEcbPkcs7.encryptStringToBase64(plain, goodKey)

        val result = AesEcbPkcs7.decryptBase64ToString(encrypted, badKey)

        assertTrue(result is JmxResult.Failure)
        assertTrue((result as JmxResult.Failure).error is JmxError.Decode)
    }
}

package dev.jmx.client.core.security

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.spec.SecretKeySpec

class SecretKeyAesGcmCipherTest {
    @Test
    fun encryptDecryptUsesSecretKeyWithoutExportingRawMaterialInApi() {
        val keyBytes = AesGcmByteCipher.randomKey(32)
        val secretKey = SecretKeySpec(keyBytes, "AES")
        val cipher = SecretKeyAesGcmCipher(secretKey)
        val plain = "avs-super-secret-value-xyz".toByteArray(Charsets.UTF_8)

        val encrypted = cipher.encrypt(plain)
        assertTrue(encrypted.size > SecretKeyAesGcmCipher.IV_BYTES)
        assertFalse(encrypted.contentEquals(plain))

        val decrypted = cipher.decrypt(encrypted)
        assertArrayEquals(plain, decrypted)
    }

    @Test
    fun encryptProducesDistinctIvEachCall() {
        val secretKey = SecretKeySpec(AesGcmByteCipher.randomKey(32), "AES")
        val cipher = SecretKeyAesGcmCipher(secretKey)
        val plain = byteArrayOf(1, 2, 3, 4, 5)
        val a = cipher.encrypt(plain)
        val b = cipher.encrypt(plain)
        val ivA = a.copyOfRange(0, SecretKeyAesGcmCipher.IV_BYTES)
        val ivB = b.copyOfRange(0, SecretKeyAesGcmCipher.IV_BYTES)
        assertFalse(ivA.contentEquals(ivB))
        assertArrayEquals(plain, cipher.decrypt(a))
        assertArrayEquals(plain, cipher.decrypt(b))
    }

    @Test
    fun aesGcmByteCipherDelegatesToSecretKeyPath() {
        val key = AesGcmByteCipher.randomKey()
        val cipher = AesGcmByteCipher(key)
        val plain = "round-trip".toByteArray()
        assertArrayEquals(plain, cipher.decrypt(cipher.encrypt(plain)))
    }
}

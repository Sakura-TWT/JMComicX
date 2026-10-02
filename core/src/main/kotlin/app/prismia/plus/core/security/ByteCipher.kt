package app.prismia.plus.core.security

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

interface ByteCipher {
    fun encrypt(plain: ByteArray): ByteArray
    fun decrypt(cipherText: ByteArray): ByteArray
}

class SecretKeyAesGcmCipher(
    private val secretKey: SecretKey
) : ByteCipher {
    override fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        val iv = cipher.iv
        require(iv != null && iv.isNotEmpty()) { "GCM IV missing after encrypt init" }
        val encrypted = cipher.doFinal(plain)
        return iv + encrypted
    }

    override fun decrypt(cipherText: ByteArray): ByteArray {
        require(cipherText.size > IV_BYTES) { "cipher text too short" }
        val iv = cipherText.copyOfRange(0, IV_BYTES)
        val payload = cipherText.copyOfRange(IV_BYTES, cipherText.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(TAG_BITS, iv))
        return cipher.doFinal(payload)
    }

    companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}

class AesGcmByteCipher(
    keyBytes: ByteArray
) : ByteCipher by SecretKeyAesGcmCipher(SecretKeySpec(requireValidKey(keyBytes), "AES")) {
    companion object {
        private fun requireValidKey(keyBytes: ByteArray): ByteArray {
            require(keyBytes.size == 16 || keyBytes.size == 24 || keyBytes.size == 32) {
                "AES key must be 16/24/32 bytes"
            }
            return keyBytes
        }

        fun randomKey(sizeBytes: Int = 32): ByteArray =
            ByteArray(sizeBytes).also { SecureRandom().nextBytes(it) }
    }
}

class Base64TextCodec {
    fun encode(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
    fun decode(text: String): ByteArray = Base64.getDecoder().decode(text)
}

package dev.jmx.v2.foundation.crypto

import dev.jmx.v2.foundation.result.JmxError
import dev.jmx.v2.foundation.result.JmxResult
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

object AesEcbPkcs7 {
    fun decryptBase64ToString(base64: String, key: String): JmxResult<String> {
        return runCatching {
            val encryptedBytes = Base64.getDecoder().decode(base64)
            val secretKey = SecretKeySpec(key.toByteArray(Charsets.UTF_8), "AES")
            val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey)
            cipher.doFinal(encryptedBytes).toString(Charsets.UTF_8)
        }.fold(
            onSuccess = { JmxResult.Success(it) },
            onFailure = { JmxResult.Failure(JmxError.Decode("响应数据解密失败", it)) }
        )
    }

    fun encryptStringToBase64(plain: String, key: String): String {
        val secretKey = SecretKeySpec(key.toByteArray(Charsets.UTF_8), "AES")
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        return Base64.getEncoder().encodeToString(cipher.doFinal(plain.toByteArray(Charsets.UTF_8)))
    }
}

package dev.jmx.client.secure

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import dev.jmx.client.core.security.ByteCipher
import dev.jmx.client.core.security.SecretKeyAesGcmCipher
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

object AndroidKeystoreCipherFactory {
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val DEFAULT_ALIAS = "jmx_core_aes_key"

    fun getOrCreateAesCipher(alias: String = DEFAULT_ALIAS): ByteCipher {
        val secret = getOrCreateSecretKey(alias)
        return SecretKeyAesGcmCipher(secret)
    }

    fun getOrCreateSecretKey(alias: String = DEFAULT_ALIAS): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = keyStore.getEntry(alias, null) as? KeyStore.SecretKeyEntry
        if (existing != null) {
            return existing.secretKey
        }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }
}

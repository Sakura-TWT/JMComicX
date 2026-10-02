package app.prismia.plus

import android.content.Context
import android.util.Base64
import app.prismia.data.VideoSession
import app.prismia.data.VideoSessionCodec
import app.prismia.data.VideoSessionFormatException
import app.prismia.data.VideoSessionStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties

/**
 * Android backed session store. The preference file contains only a versioned
 * AES-GCM envelope; access and refresh tokens never reach disk in plaintext.
 */
internal class AndroidKeystoreVideoSessionStore(context: Context) : VideoSessionStore {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val lock = Any()

    override suspend fun load(): VideoSession? = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val encoded = preferences.getString(KEY_ENVELOPE, null)
            if (encoded == null) {
                return@synchronized try {
                    migrateLegacySession()
                } catch (failure: GeneralSecurityException) {
                    throw VideoSessionStoreException("legacy video session cannot be encrypted", failure)
                } catch (failure: RuntimeException) {
                    throw VideoSessionStoreException("legacy video session cannot be migrated", failure)
                }
            }
            try {
                VideoSessionCodec.decode(decrypt(Base64.decode(encoded, Base64.DEFAULT)))
            } catch (failure: VideoSessionFormatException) {
                throw VideoSessionStoreException("stored video session is invalid", failure)
            } catch (failure: IllegalArgumentException) {
                throw VideoSessionStoreException("stored video session is not valid base64", failure)
            } catch (failure: GeneralSecurityException) {
                throw VideoSessionStoreException("stored video session cannot be decrypted", failure)
            }
        }
    }

    override suspend fun save(session: VideoSession) = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val encrypted = encrypt(VideoSessionCodec.encode(session))
            val encoded = Base64.encodeToString(encrypted, Base64.NO_WRAP)
            check(preferences.edit().putString(KEY_ENVELOPE, encoded).commit()) {
                "unable to persist video session"
            }
        }
    }

    override suspend fun clear() = withContext(Dispatchers.IO) {
        synchronized(lock) {
            check(preferences.edit().remove(KEY_ENVELOPE).commit()) {
                "unable to clear video session"
            }
        }
    }

    private fun encrypt(plainText: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val nonce = cipher.iv
        require(nonce.size == NONCE_BYTES) { "unexpected AES-GCM nonce size" }
        cipher.updateAAD(ASSOCIATED_DATA)
        return byteArrayOf(STORAGE_VERSION.toByte()) + nonce + cipher.doFinal(plainText)
    }

    private fun decrypt(envelope: ByteArray): ByteArray {
        val minimumSize = 1 + NONCE_BYTES + TAG_BYTES
        require(envelope.size >= minimumSize) { "encrypted session envelope is truncated" }
        require(envelope[0].toInt() == STORAGE_VERSION) { "unsupported encrypted session version" }
        val nonce = envelope.copyOfRange(1, 1 + NONCE_BYTES)
        val cipherText = envelope.copyOfRange(1 + NONCE_BYTES, envelope.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(ASSOCIATED_DATA)
        return cipher.doFinal(cipherText)
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_SIZE_BITS)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    private fun migrateLegacySession(): VideoSession? {
        val access = preferences.getString(LEGACY_ACCESS_TOKEN, null)?.takeIf(String::isNotBlank) ?: return null
        val session = VideoSession(
            accessToken = access,
            refreshToken = preferences.getString(LEGACY_REFRESH_TOKEN, null)?.takeIf(String::isNotBlank),
            accessTokenExpiresAtEpochSeconds = preferences.getLong(LEGACY_EXPIRES_AT, 0L).takeIf { it > 0L },
        )
        val encrypted = Base64.encodeToString(encrypt(VideoSessionCodec.encode(session)), Base64.NO_WRAP)
        check(
            preferences.edit()
                .putString(KEY_ENVELOPE, encrypted)
                .remove(LEGACY_ACCESS_TOKEN)
                .remove(LEGACY_REFRESH_TOKEN)
                .remove(LEGACY_EXPIRES_AT)
                .commit(),
        ) { "unable to migrate legacy video session" }
        return session
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_ALIAS = "prismia.video.session.aes.v1"
        const val PREFERENCES_NAME = "prismia_video_session"
        const val KEY_ENVELOPE = "encrypted_session"
        const val LEGACY_ACCESS_TOKEN = "access_token"
        const val LEGACY_REFRESH_TOKEN = "refresh_token"
        const val LEGACY_EXPIRES_AT = "access_token_expires_at"
        const val STORAGE_VERSION = 1
        const val KEY_SIZE_BITS = 256
        const val NONCE_BYTES = 12
        const val TAG_BITS = 128
        const val TAG_BYTES = TAG_BITS / 8
        val ASSOCIATED_DATA = "Prismia/video-session/v1".toByteArray(Charsets.UTF_8)
    }
}

internal class VideoSessionStoreException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

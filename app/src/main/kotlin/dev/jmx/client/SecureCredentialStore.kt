package dev.jmx.client

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal data class AccountCredentials(
    val username: String,
    val password: String,
)

internal class SecureCredentialStore(
    context: Context,
    private val gson: Gson = Gson(),
) {
    private val applicationContext = context.applicationContext
    private val preferences = applicationContext.getSharedPreferences(
        CREDENTIAL_PREFERENCES,
        Context.MODE_PRIVATE,
    )
    private val legacyPreferences = applicationContext.getSharedPreferences(
        LEGACY_SECURE_PREFERENCES,
        Context.MODE_PRIVATE,
    )

    fun load(): AccountCredentials? {
        preferences.getString(CREDENTIAL_KEY, null)?.let { encrypted ->
            decrypt(encrypted, CREDENTIAL_KEY_ALIAS)?.let { json ->
                return runCatching { gson.fromJson(json, AccountCredentials::class.java) }
                    .getOrNull()
                    ?.takeIf { it.username.isNotBlank() && it.password.isNotBlank() }
            }
        }
        if (preferences.getBoolean(LEGACY_MIGRATION_COMPLETE_KEY, false)) return null

        val migrated = readLegacyCredentials()
        preferences.edit { putBoolean(LEGACY_MIGRATION_COMPLETE_KEY, true) }
        if (migrated != null) save(migrated)
        return migrated
    }

    fun save(credentials: AccountCredentials) {
        if (credentials.username.isBlank() || credentials.password.isBlank()) return
        val encrypted = encrypt(gson.toJson(credentials), CREDENTIAL_KEY_ALIAS)
        preferences.edit {
            putString(CREDENTIAL_KEY, encrypted)
            putBoolean(LEGACY_MIGRATION_COMPLETE_KEY, true)
        }
    }

    fun clear() {
        preferences.edit {
            remove(CREDENTIAL_KEY)
            putBoolean(LEGACY_MIGRATION_COMPLETE_KEY, true)
        }
    }

    fun readLegacySearchHistory(): List<String> {
        val encrypted = legacyPreferences.getString(LEGACY_SEARCH_HISTORY_KEY, null) ?: return emptyList()
        val json = decrypt(encrypted, LEGACY_KEY_ALIAS) ?: return emptyList()
        return runCatching {
            val type = object : TypeToken<List<String>>() {}.type
            gson.fromJson<List<String>>(json, type)
        }.getOrDefault(emptyList())
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinctBy(String::lowercase)
    }

    private fun readLegacyCredentials(): AccountCredentials? {
        val encrypted = legacyPreferences.getString(LEGACY_USER_KEY, null) ?: return null
        val json = decrypt(encrypted, LEGACY_KEY_ALIAS) ?: return null
        return runCatching {
            val root = JsonParser.parseString(json).asJsonObject
            AccountCredentials(
                username = root["username"]?.asString.orEmpty().trim(),
                password = root["password"]?.asString.orEmpty(),
            )
        }.getOrNull()?.takeIf { it.username.isNotBlank() && it.password.isNotBlank() }
    }

    private fun encrypt(value: String, alias: String): String {
        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey(alias))
        val payload = cipher.doFinal(value.toByteArray(Charsets.UTF_8)) + cipher.iv
        return Base64.encodeToString(payload, Base64.NO_WRAP)
    }

    private fun decrypt(value: String, alias: String): String? = runCatching {
        val payload = Base64.decode(value, Base64.NO_WRAP)
        if (payload.size <= GCM_IV_SIZE_BYTES) return null
        val encrypted = payload.copyOfRange(0, payload.size - GCM_IV_SIZE_BYTES)
        val iv = payload.copyOfRange(payload.size - GCM_IV_SIZE_BYTES, payload.size)
        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(alias), GCMParameterSpec(128, iv))
        String(cipher.doFinal(encrypted), Charsets.UTF_8)
    }.getOrNull()

    private fun secretKey(alias: String): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        (keyStore.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(false)
                .build(),
        )
        return generator.generateKey()
    }
}

private const val CREDENTIAL_PREFERENCES = "jmx_account_credentials"
private const val CREDENTIAL_KEY = "account"
private const val LEGACY_MIGRATION_COMPLETE_KEY = "legacy_migration_complete"
private const val CREDENTIAL_KEY_ALIAS = "jmx_v2_account_credentials"
private const val LEGACY_SECURE_PREFERENCES = "jmx-secure-data"
private const val LEGACY_KEY_ALIAS = "app_master_key"
private const val LEGACY_USER_KEY = "user"
private const val LEGACY_SEARCH_HISTORY_KEY = "historySearch"
private const val ANDROID_KEY_STORE = "AndroidKeyStore"
private const val CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"
private const val GCM_IV_SIZE_BYTES = 12

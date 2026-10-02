package app.prismia.plus

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

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
        preferences.getString(CREDENTIAL_KEY, null)?.let { stored ->
            decryptCredentials(stored)?.let { json -> return parseCredentials(json) }
            // 解不开的密文必须清掉，不能留着装作"本地有凭据"：
            // hasCredentials() 只看键在不在，留着它会让每次冷启动都按已登录渲染，
            // 然后每个需要登录的请求都栽在同一处（这正是"点收藏又要重新登录"的表现）。
            discardUnreadableBlob(stored)
            return null
        }
        if (preferences.getBoolean(LEGACY_MIGRATION_COMPLETE_KEY, false)) return null

        val migrated = readLegacyCredentials()
        preferences.edit { putBoolean(LEGACY_MIGRATION_COMPLETE_KEY, true) }
        if (migrated != null) save(migrated)
        return migrated
    }

    /**
     * 本地是否留有可用于静默重登的凭据。
     *
     * 只看 preference 键、不解密：这一步要在冷启动的组合阶段（主线程）回答
     * "该不该按已登录渲染"，而 AndroidKeyStore 的 AES-GCM 初始化在部分机型上要几十毫秒。
     * 极少数"键在但解不开"的情况由 [load] 兜底——它会把死密文删掉，退回手动登录。
     */
    fun hasCredentials(): Boolean {
        if (preferences.contains(CREDENTIAL_KEY)) return true
        if (preferences.getBoolean(LEGACY_MIGRATION_COMPLETE_KEY, false)) return false
        return legacyPreferences.contains(LEGACY_USER_KEY)
    }

    fun save(credentials: AccountCredentials) {
        if (credentials.username.isBlank() || credentials.password.isBlank()) return
        // 凭据持久化为尽力而为：登录成功后写入失败不应导致闪退，最坏情况是这台设备记不住密码。
        val encrypted = encryptCredentials(gson.toJson(credentials)) ?: return
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
        val json = keystoreDecrypt(encrypted, LEGACY_KEY_ALIAS) ?: return emptyList()
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
        val json = keystoreDecrypt(encrypted, LEGACY_KEY_ALIAS) ?: return null
        return runCatching {
            val root = JsonParser.parseString(json).asJsonObject
            AccountCredentials(
                username = root["username"]?.asString.orEmpty().trim(),
                password = root["password"]?.asString.orEmpty(),
            )
        }.getOrNull()?.takeIf { it.username.isNotBlank() && it.password.isNotBlank() }
    }

    private fun parseCredentials(json: String): AccountCredentials? =
        runCatching { gson.fromJson(json, AccountCredentials::class.java) }
            .getOrNull()
            ?.takeIf { it.username.isNotBlank() && it.password.isNotBlank() }

    // region 密文编解码

    /**
     * 加密凭据：硬件密钥优先，不可用时退回本地软密钥。
     *
     * 软密钥（随机 256 位、与密文同存于应用私有 preference）显然弱于 AndroidKeyStore——
     * 拿到这个文件的人也就拿到了钥匙。但它换来的是"这台设备还能记住登录"：
     * 部分机型（改锁屏、系统升级、某些 OEM ROM）会作废连"需要用户认证"都没开的密钥，
     * 硬件密钥在那里根本不可用，坚持只用它的结果就是每次冷启动都要用户重新输密码。
     * 应用已声明 android:allowBackup="false"，密文不会随系统备份离开设备；
     * 门槛降到"root 或已 root 的 adb 能读应用私有目录"，这个代价换可用性是划算的。
     */
    private fun encryptCredentials(json: String): String? {
        if (!preferences.getBoolean(KEYSTORE_UNUSABLE_KEY, false)) {
            keystoreEncrypt(json)?.let { return it }
            // 记下"这台设备的 AndroidKeyStore 靠不住"，之后直接走软密钥，
            // 免得每次登录都再花几十毫秒撞一次同样的墙。
            preferences.edit { putBoolean(KEYSTORE_UNUSABLE_KEY, true) }
        }
        return softwareEncrypt(json)
    }

    private fun decryptCredentials(stored: String): String? = when {
        stored.startsWith(SOFTWARE_SCHEME_PREFIX) ->
            softwareDecrypt(stored.removePrefix(SOFTWARE_SCHEME_PREFIX))
        stored.startsWith(KEYSTORE_SCHEME_PREFIX) ->
            keystoreDecrypt(stored.removePrefix(KEYSTORE_SCHEME_PREFIX), CREDENTIAL_KEY_ALIAS)
        // 早于分方案标记的旧密文：一律是硬件密钥写的。
        else -> keystoreDecrypt(stored, CREDENTIAL_KEY_ALIAS)
    }

    private fun keystoreEncrypt(json: String): String? {
        val failure = runCatching { encryptWith(keystoreKey(CREDENTIAL_KEY_ALIAS), json) }
            .fold(onSuccess = { return verifiedKeystoreBlob(it) }, onFailure = { it })
        android.util.Log.w("JmxCredentials", "硬件密钥加密失败", failure)
        // 关键：被系统作废的密钥用 getEntry 仍然取得回来，只有 Cipher.init 才会抛
        // KeyPermanentlyInvalidatedException，所以自愈只能放在这一层——
        // 放在 keystoreKey() 里等 getEntry 抛异常，永远等不到触发条件。
        if (failure.isKeyPermanentlyInvalidated()) {
            // 重建能救回这一次写入，却救不了下一次冷启动：会作废一次的设备就会再作废。
            // 直接判定硬件密钥不可靠，把这条路让给软密钥。
            runCatching { deleteKeystoreAlias(CREDENTIAL_KEY_ALIAS) }
            return null
        }
        if (runCatching { deleteKeystoreAlias(CREDENTIAL_KEY_ALIAS) }.isFailure) return null
        return runCatching { encryptWith(keystoreKey(CREDENTIAL_KEY_ALIAS), json) }
            .onFailure { android.util.Log.w("JmxCredentials", "重建硬件密钥后仍无法加密", it) }
            .getOrNull()
            ?.let { verifiedKeystoreBlob(it) }
    }

    /**
     * 写盘前先自己解一遍。
     *
     * 少数 ROM 的 keystore 能加密却解不开；那种密文留在本地，效果等同于"看起来已登录、
     * 实际恢复不了"。解不开就当硬件密钥不可用，返回 null 让调用方退到软密钥。
     */
    private fun verifiedKeystoreBlob(blob: String): String? {
        if (keystoreDecrypt(blob, CREDENTIAL_KEY_ALIAS) == null) {
            android.util.Log.w("JmxCredentials", "硬件密钥写得进解不开，改用本地密钥")
            return null
        }
        return KEYSTORE_SCHEME_PREFIX + blob
    }

    private fun keystoreDecrypt(value: String, alias: String): String? =
        runCatching { decryptWith(keystoreKey(alias), value) }
            .onFailure { android.util.Log.w("JmxCredentials", "硬件密钥解密失败（$alias）", it) }
            .getOrNull()

    private fun softwareEncrypt(json: String): String? =
        runCatching { SOFTWARE_SCHEME_PREFIX + encryptWith(softwareKey(), json) }
            .onFailure { android.util.Log.w("JmxCredentials", "本地密钥加密失败，放弃记住登录", it) }
            .getOrNull()

    private fun softwareDecrypt(value: String): String? =
        runCatching { decryptWith(softwareKey(), value) }
            .onFailure { android.util.Log.w("JmxCredentials", "本地密钥解密失败", it) }
            .getOrNull()

    /**
     * 丢掉解不开的密文。
     *
     * 若它本是硬件密钥写的，说明这台设备确实会把密钥作废（写的时候还能解开，
     * 见 [verifiedKeystoreBlob]），因此顺手把硬件密钥标为不可用：
     * 用户这次仍要手动登录一次，但那次登录会写成软密钥密文，之后冷启动就不再掉登录了。
     */
    private fun discardUnreadableBlob(stored: String) {
        val wasKeystore = !stored.startsWith(SOFTWARE_SCHEME_PREFIX)
        preferences.edit {
            remove(CREDENTIAL_KEY)
            if (wasKeystore) putBoolean(KEYSTORE_UNUSABLE_KEY, true)
        }
        if (wasKeystore) runCatching { deleteKeystoreAlias(CREDENTIAL_KEY_ALIAS) }
    }

    // endregion

    // region 密钥与原始 AES-GCM

    private fun encryptWith(key: SecretKey, value: String): String {
        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val payload = cipher.doFinal(value.toByteArray(Charsets.UTF_8)) + cipher.iv
        return Base64.encodeToString(payload, Base64.NO_WRAP)
    }

    private fun decryptWith(key: SecretKey, value: String): String? {
        val payload = Base64.decode(value, Base64.NO_WRAP)
        if (payload.size <= GCM_IV_SIZE_BYTES) return null
        val encrypted = payload.copyOfRange(0, payload.size - GCM_IV_SIZE_BYTES)
        val iv = payload.copyOfRange(payload.size - GCM_IV_SIZE_BYTES, payload.size)
        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_SIZE_BITS, iv))
        return String(cipher.doFinal(encrypted), Charsets.UTF_8)
    }

    private fun keystoreKey(alias: String): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        // getEntry 在别名存在但不可恢复时会抛异常；此时删除损坏别名并重新生成，实现自愈。
        // 注意它只挡得住"取不出密钥"，挡不住"取得出但已被作废"——后者见 [keystoreEncrypt]。
        runCatching { keyStore.getEntry(alias, null) as? KeyStore.SecretKeyEntry }
            .getOrNull()
            ?.let { return it.secretKey }
        runCatching { keyStore.deleteEntry(alias) }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(AES_KEY_SIZE_BITS)
                .setUserAuthenticationRequired(false)
                .build(),
        )
        return generator.generateKey()
    }

    private fun deleteKeystoreAlias(alias: String) {
        KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }.deleteEntry(alias)
    }

    private fun softwareKey(): SecretKey {
        preferences.getString(SOFTWARE_KEY_KEY, null)
            ?.let { runCatching { Base64.decode(it, Base64.NO_WRAP) }.getOrNull() }
            ?.takeIf { it.size == AES_KEY_SIZE_BITS / 8 }
            ?.let { return SecretKeySpec(it, KeyProperties.KEY_ALGORITHM_AES) }

        val raw = ByteArray(AES_KEY_SIZE_BITS / 8).also { SecureRandom().nextBytes(it) }
        preferences.edit { putString(SOFTWARE_KEY_KEY, Base64.encodeToString(raw, Base64.NO_WRAP)) }
        return SecretKeySpec(raw, KeyProperties.KEY_ALGORITHM_AES)
    }

    // endregion
}

private fun Throwable.isKeyPermanentlyInvalidated(): Boolean =
    this is KeyPermanentlyInvalidatedException || cause is KeyPermanentlyInvalidatedException

private const val CREDENTIAL_PREFERENCES = "jmx_account_credentials"
private const val CREDENTIAL_KEY = "account"
private const val LEGACY_MIGRATION_COMPLETE_KEY = "legacy_migration_complete"
private const val KEYSTORE_UNUSABLE_KEY = "keystore_unusable"
private const val SOFTWARE_KEY_KEY = "local_key"
private const val CREDENTIAL_KEY_ALIAS = "jmx_v2_account_credentials"
private const val LEGACY_SECURE_PREFERENCES = "jmx-secure-data"
private const val LEGACY_KEY_ALIAS = "app_master_key"
private const val LEGACY_USER_KEY = "user"
private const val LEGACY_SEARCH_HISTORY_KEY = "historySearch"
private const val ANDROID_KEY_STORE = "AndroidKeyStore"
private const val CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"
private const val KEYSTORE_SCHEME_PREFIX = "ks1:"
private const val SOFTWARE_SCHEME_PREFIX = "sw1:"
private const val GCM_IV_SIZE_BYTES = 12
private const val GCM_TAG_SIZE_BITS = 128
private const val AES_KEY_SIZE_BITS = 256

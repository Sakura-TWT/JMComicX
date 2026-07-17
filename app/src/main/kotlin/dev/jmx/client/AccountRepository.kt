package dev.jmx.client

import android.content.Context
import androidx.core.content.edit
import com.google.gson.Gson
import com.github.houbb.opencc4j.util.ZhConverterUtil
import dev.jmx.client.core.api.UserProfile
import dev.jmx.client.core.result.JmxError
import dev.jmx.client.core.result.JmxResult
import dev.jmx.client.core.runtime.JmxCore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal data class AccountProfile(
    val id: Int?,
    val username: String,
    val email: String?,
    val avatar: String?,
    val level: Int?,
    val levelName: String?,
    val currentLevelExp: Int?,
    val nextLevelExp: Int?,
    val expPercent: Double?,
    val currentFavoriteCount: Int?,
    val maxFavoriteCount: Int?,
    val coin: Int?,
)

internal class AccountRepository(
    context: Context,
    private val core: JmxCore,
    private val gson: Gson = Gson(),
) {
    private val preferences = context.applicationContext.getSharedPreferences(
        ACCOUNT_PREFERENCES,
        Context.MODE_PRIVATE,
    )
    private val credentialStore = SecureCredentialStore(context, gson)
    private val authenticationMutex = Mutex()
    @Volatile
    private var authenticationGeneration = 0L

    fun restore(): AccountProfile? {
        if (!core.sessionManager.hasAvs()) return null
        return cachedProfile()
    }

    private fun cachedProfile(): AccountProfile? {
        val encoded = preferences.getString(ACCOUNT_PROFILE_KEY, null) ?: return null
        return runCatching { gson.fromJson(encoded, AccountProfile::class.java) }
            .getOrNull()
            ?.takeIf { it.username.isNotBlank() }
    }

    fun lastUsername(): String = preferences.getString(ACCOUNT_LAST_USERNAME_KEY, "").orEmpty()

    fun update(profile: AccountProfile) {
        preferences.edit { putString(ACCOUNT_PROFILE_KEY, gson.toJson(profile)) }
    }

    suspend fun login(username: String, password: String): JmxResult<AccountProfile> =
        authenticationMutex.withLock {
            loginLocked(AccountCredentials(username.trim(), password), persistCredentials = true)
        }

    suspend fun restoreSession(): AccountProfile? = withContext(Dispatchers.IO) {
        restore()?.let { return@withContext it }
        val credentials = credentialStore.load() ?: return@withContext null
        authenticationMutex.withLock {
            restore() ?: (loginLocked(credentials, persistCredentials = true) as? JmxResult.Success)?.value
        }
    }

    suspend fun <T> withSessionRecovery(block: suspend () -> JmxResult<T>): JmxResult<T> {
        val generation = authenticationGeneration
        val first = block()
        if (first !is JmxResult.Failure || !first.error.requiresSessionRecovery()) return first
        val credentials = credentialStore.load() ?: return first

        return authenticationMutex.withLock {
            if (generation == authenticationGeneration) {
                when (val login = loginLocked(credentials, persistCredentials = true)) {
                    is JmxResult.Success -> Unit
                    is JmxResult.Failure -> return@withLock login
                }
            }
            block()
        }
    }

    private suspend fun loginLocked(
        credentials: AccountCredentials,
        persistCredentials: Boolean,
    ): JmxResult<AccountProfile> = withContext(Dispatchers.IO) {
            val username = credentials.username.trim()
            val password = credentials.password
            core.initializer.initialize()
            when (val result = core.userApi.login(username, password)) {
                is JmxResult.Success -> {
                    val profile = result.value.profile?.toAccountProfile()
                    if (profile == null) {
                        core.userApi.logout()
                        JmxResult.Failure(JmxError.Schema("登录成功但响应缺少用户资料"))
                    } else {
                        update(profile)
                        preferences.edit {
                            putString(ACCOUNT_LAST_USERNAME_KEY, username)
                        }
                        if (persistCredentials) credentialStore.save(AccountCredentials(username, password))
                        authenticationGeneration++
                        JmxResult.Success(profile)
                    }
                }
                is JmxResult.Failure -> result
            }
        }

    fun logout() {
        core.userApi.logout()
        credentialStore.clear()
        authenticationGeneration++
        preferences.edit { remove(ACCOUNT_PROFILE_KEY) }
    }
}

internal fun JmxError.requiresSessionRecovery(): Boolean = when (this) {
    is JmxError.Http -> code == 401
    is JmxError.Api -> code == 401
    else -> false
}

internal fun resolveUserAvatarUrl(imageHost: String, avatar: String?): String? {
    val source = avatar?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    if (source.startsWith("https://") || source.startsWith("http://")) return source
    return "${imageHost.trimEnd('/')}/media/users/${source.trimStart('/')}"
}

internal fun accountProgress(current: Int?, maximum: Int?, percent: Double? = null): Float {
    val reported = percent?.takeIf { it.isFinite() && it >= 0.0 }?.let {
        if (it > 1.0) it / 100.0 else it
    }
    return (reported ?: if (current != null && maximum != null && maximum > 0) {
        current.toDouble() / maximum
    } else {
        0.0
    }).toFloat().coerceIn(0f, 1f)
}

private fun UserProfile.toAccountProfile(): AccountProfile? {
    val safeUsername = username?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return AccountProfile(
        id = id,
        username = safeUsername,
        email = email,
        avatar = avatar,
        level = level,
        levelName = levelName?.let { runCatching { ZhConverterUtil.toSimple(it) }.getOrDefault(it) },
        currentLevelExp = currentLevelExp,
        nextLevelExp = nextLevelExp,
        expPercent = expPercent,
        currentFavoriteCount = currentFavoriteCount,
        maxFavoriteCount = maxFavoriteCount,
        coin = coin,
    )
}

private const val ACCOUNT_PREFERENCES = "jmx_account"
private const val ACCOUNT_PROFILE_KEY = "profile"
private const val ACCOUNT_LAST_USERNAME_KEY = "last_username"

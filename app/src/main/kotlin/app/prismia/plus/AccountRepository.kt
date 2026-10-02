package app.prismia.plus

import android.content.Context
import androidx.core.content.edit
import com.google.gson.Gson
import com.github.houbb.opencc4j.util.ZhConverterUtil
import app.prismia.plus.core.api.UserProfile
import app.prismia.plus.core.result.JmxError
import app.prismia.plus.core.result.JmxResult
import app.prismia.plus.core.runtime.JmxCore
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

    /**
     * 已经因"空载荷"重登过一次、且重登后依然是空载荷的登录态编号。
     * 见 [withSessionRecovery] 的 treatEmptyDataAsSessionLoss。
     */
    @Volatile
    private var emptyDataVerifiedAtGeneration = -1L

    /**
     * 冷启动时立即可用的登录态。
     *
     * 判据从"有 AVS Cookie"放宽为"有 AVS Cookie **或**本地留有凭据"。
     * AVS 只是传输层的会话票据：服务端过期、域名表换代、某个响应带来一条删除指令，
     * 都会让它消失，而这些都不代表用户退出了登录。原来只要 AVS 不在冷启动就当未登录，
     * 于是"划掉后台再进来点收藏"必然弹登录框——即使凭据还在本地，
     * [withSessionRecovery] 本来完全有能力在后台悄悄换回一个新会话。
     * 现在把凭据当作持久身份、AVS 当作可再生的票据：先按已登录渲染，
     * 真发请求时若确实失效，恢复逻辑会重登一次，用户看不到中断。
     */
    fun restore(): AccountProfile? {
        if (!core.sessionManager.hasAvs() && !credentialStore.hasCredentials()) return null
        return cachedProfile()
    }

    /** 本地是否留有可静默重登的凭据。界面用它判断"会话还在恢复中"值不值得等，而不是直接弹登录框。 */
    fun hasStoredCredentials(): Boolean = credentialStore.hasCredentials()

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

    /**
     * 拉起一个真正可用的会话，并返回登录态资料。
     *
     * 与 [restore] 的分工：[restore] 只回答"界面该不该按已登录渲染"（同步、不联网），
     * 这里还要保证 AVS 真的在——冷启动时它可能已经没了，若等到用户点进收藏再靠 401 补登录，
     * 那一次点击就要多背一个登录往返。有 AVS 时不做任何网络动作，热启动路径不受影响。
     */
    suspend fun restoreSession(): AccountProfile? = withContext(Dispatchers.IO) {
        val cached = restore()
        if (core.sessionManager.hasAvs()) return@withContext cached
        val credentials = credentialStore.load() ?: return@withContext cached
        authenticationMutex.withLock {
            if (core.sessionManager.hasAvs()) return@withLock restore()
            (loginLocked(credentials, persistCredentials = true) as? JmxResult.Success)?.value ?: cached
        }
    }

    /**
     * 执行 [block]，若失败原因是会话失效则重新登录并重试一次。
     *
     * @param treatEmptyDataAsSessionLoss 是否把 [JmxError.EmptyData] 也当作会话失效的信号。
     *   签到接口（/daily）在会话过期时并不回 401，而是回 `code=200` + 空 data，
     *   与"当期确实没有活动"长得一模一样，因此默认不认它——收藏夹为空之类的正常空结果
     *   不该触发重登。只有本来就必须带登录态、且空载荷几乎只可能是掉登录的调用才打开。
     *   打开后同一个登录态最多因此重登一次：重登后仍是空载荷就认定"服务端真的没内容"，
     *   否则每次打开签到页都会白重登一次。
     */
    suspend fun <T> withSessionRecovery(
        treatEmptyDataAsSessionLoss: Boolean = false,
        block: suspend () -> JmxResult<T>,
    ): JmxResult<T> {
        val generation = authenticationGeneration
        val first = block()
        if (first !is JmxResult.Failure) return first
        val emptyDataSuspected = treatEmptyDataAsSessionLoss && first.error is JmxError.EmptyData
        if (!first.error.requiresSessionRecovery() && !emptyDataSuspected) return first
        if (emptyDataSuspected && emptyDataVerifiedAtGeneration == generation) return first
        val credentials = credentialStore.load() ?: return first

        return authenticationMutex.withLock {
            if (generation == authenticationGeneration) {
                when (val login = loginLocked(credentials, persistCredentials = true)) {
                    is JmxResult.Success -> Unit
                    is JmxResult.Failure -> return@withLock login
                }
            }
            block().also { retried ->
                if (emptyDataSuspected &&
                    retried is JmxResult.Failure &&
                    retried.error is JmxError.EmptyData
                ) {
                    emptyDataVerifiedAtGeneration = authenticationGeneration
                }
            }
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

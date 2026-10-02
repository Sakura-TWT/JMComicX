package app.prismia.plus

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import app.prismia.plus.core.cache.KeyValueStore
import app.prismia.plus.core.runtime.JmxCore
import app.prismia.plus.core.runtime.JmxCoreConfig
import app.prismia.plus.core.session.PersistentCookieStore

/**
 * 进程内唯一的 [JmxCore]。
 *
 * 以前每次调用都新建一个：反正 Cookie 与协议状态都落在 SharedPreferences 上，两份实例大体也能跑。
 * 但线路健康度、连接池、图片分发器都是**内存里**的状态——分成两份就等于两套各自试探的选路，
 * 谁也学不到对方的结论。而且 Coil 的 ImageLoader 在 [JmxApplication] 里构建，
 * 它必须拿到与业务请求同一个客户端，才能共用连接池并让图片走同一张线路表。
 */
private object AppJmxCoreHolder {
    @Volatile
    private var instance: JmxCore? = null

    fun get(context: Context): JmxCore {
        instance?.let { return it }
        return synchronized(this) {
            instance ?: createCore(context).also { instance = it }
        }
    }
}

internal fun createAppJmxCore(context: Context): JmxCore = AppJmxCoreHolder.get(context)

private fun createCore(context: Context): JmxCore {
    val appContext = context.applicationContext
    val stateStore = SharedPreferencesKeyValueStore(
        appContext.getSharedPreferences(JMX_CORE_PREFERENCES, Context.MODE_PRIVATE),
    )
    val settingsPreferences = appContext.getSharedPreferences(SETTINGS_PREFERENCES, Context.MODE_PRIVATE)
    return JmxCore.create(
        JmxCoreConfig(
            keyValueStore = stateStore,
            cookieStore = PersistentCookieStore(stateStore),
            // 官方客户端对每个 GET 都无条件附加 lang（`searchParams.set("lang", localStorage.lang || "TW")`），
            // 取值只有 TW / CN，缺省是 TW。此前我们仅在选择繁體时才附加 lang，
            // 选择简体时省略参数并期望服务端默认简体——实际服务端默认是繁體，
            // 于是两种选择都返回繁體，表现为"内容语言切换不生效"。这里改为始终显式发送。
            contentLanguageProvider = {
                if (settingsPreferences.getString(CONTENT_LANGUAGE_KEY, null) == CONTENT_LANGUAGE_TRADITIONAL) {
                    CONTENT_LANGUAGE_TRADITIONAL
                } else {
                    CONTENT_LANGUAGE_SIMPLIFIED
                }
            },
            // 放在 cacheDir 下：系统在磁盘吃紧时可自行回收，用户"清除缓存"也能一并清掉，
            // 不像 filesDir 那样被当成用户数据长期占着。
            responseCacheDirectory = appContext.cacheDir.resolve(API_JSON_CACHE_DIRECTORY).toPath(),
        ),
    )
}

private class SharedPreferencesKeyValueStore(
    private val preferences: SharedPreferences,
) : KeyValueStore {
    override fun getString(key: String): String? = preferences.getString(key, null)

    override fun putString(key: String, value: String?) {
        preferences.edit {
            if (value == null) remove(key) else putString(key, value)
        }
    }
}

private const val JMX_CORE_PREFERENCES = "jmx_core_state"

/** 接口 JSON 缓存子目录，与 Coil 的 image_cache 平级。 */
private const val API_JSON_CACHE_DIRECTORY = "api_json_cache"

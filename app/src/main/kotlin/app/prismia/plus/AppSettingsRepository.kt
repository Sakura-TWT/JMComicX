package app.prismia.plus

import android.content.Context
import androidx.core.content.edit
import coil3.imageLoader
import app.prismia.plus.core.network.ApiEndpointSelection
import app.prismia.plus.core.network.defaultOkHttpClient
import app.prismia.plus.core.network.normalizedBaseUrlOrNull
import app.prismia.plus.core.result.JmxResult
import app.prismia.plus.core.runtime.JmxCore
import app.prismia.plus.effect.FloatingNavBarStyle
import app.prismia.plus.effect.TopBarBlurStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.util.concurrent.TimeUnit

internal data class EndpointProbeUi(
    val url: String,
    val latencyMillis: Long? = null,
    val success: Boolean? = null,
)

internal data class EndpointSettingsState(
    val automatic: Boolean,
    val selectedApiUrl: String?,
    val activeApiUrl: String?,
    val selectedImageUrl: String?,
    val apiEndpoints: List<EndpointProbeUi>,
    val imageEndpoints: List<EndpointProbeUi>,
)

internal class AppSettingsRepository(
    context: Context,
    private val core: JmxCore,
    private val homeRepository: HomeRepository,
) {
    private val applicationContext = context.applicationContext
    private val preferences = applicationContext.getSharedPreferences(
        SETTINGS_PREFERENCES,
        Context.MODE_PRIVATE,
    )
    private val probeClient = defaultOkHttpClient().newBuilder()
        .callTimeout(IMAGE_PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .connectTimeout(IMAGE_PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    init {
        if (!preferences.getBoolean(API_SELECTION_CONFIGURED_KEY, false)) {
            core.endpointManager.useAutoSelection()
        }
    }

    fun autoCheckInEnabled(): Boolean = preferences.getBoolean(AUTO_CHECK_IN_KEY, true)

    /** 内容语言："CN"=简体（默认，不附加 lang 参数），"TW"=繁体（服务端返回繁体文本） */
    fun contentLanguage(): String {
        val value = preferences.getString(CONTENT_LANGUAGE_KEY, null)
        return if (value == CONTENT_LANGUAGE_TRADITIONAL) CONTENT_LANGUAGE_TRADITIONAL else CONTENT_LANGUAGE_SIMPLIFIED
    }

    fun setContentLanguage(language: String) {
        val normalized = if (language == CONTENT_LANGUAGE_TRADITIONAL) {
            CONTENT_LANGUAGE_TRADITIONAL
        } else {
            CONTENT_LANGUAGE_SIMPLIFIED
        }
        preferences.edit { putString(CONTENT_LANGUAGE_KEY, normalized) }
    }

    /** 顶栏模糊样式：GAUSSIAN（默认）或 PROGRESSIVE */
    fun topBarBlurStyle(): TopBarBlurStyle = TopBarBlurStyle.fromName(
        preferences.getString(TOP_BAR_BLUR_STYLE_KEY, null)
    )

    fun setTopBarBlurStyle(style: TopBarBlurStyle) {
        preferences.edit { putString(TOP_BAR_BLUR_STYLE_KEY, style.name) }
    }

    /** 底栏悬浮样式开关，默认关闭（关闭时使用普通贴底导航栏） */
    fun liquidGlassNavBarEnabled(): Boolean = preferences.getBoolean(LIQUID_GLASS_NAV_BAR_KEY, false)

    fun setLiquidGlassNavBarEnabled(enabled: Boolean) {
        preferences.edit { putBoolean(LIQUID_GLASS_NAV_BAR_KEY, enabled) }
    }

    /** 悬浮底栏样式：DEFAULT（官方磨砂，默认）或 IOS_LIKE（iOS 液态玻璃） */
    fun floatingNavBarStyle(): FloatingNavBarStyle = FloatingNavBarStyle.fromName(
        preferences.getString(FLOATING_NAV_BAR_STYLE_KEY, null)
    )

    fun setFloatingNavBarStyle(style: FloatingNavBarStyle) {
        preferences.edit { putString(FLOATING_NAV_BAR_STYLE_KEY, style.name) }
    }

    /** 收藏页排序方式，默认按收藏时间（与服务端默认一致） */
    fun favoriteSortOrder(): FavoriteSortOrder = FavoriteSortOrder.fromName(
        preferences.getString(FAVORITE_SORT_ORDER_KEY, null)
    )

    fun setFavoriteSortOrder(order: FavoriteSortOrder) {
        preferences.edit { putString(FAVORITE_SORT_ORDER_KEY, order.name) }
    }

    /** 收藏页排序方向，默认倒序（最新在前，与服务端原始顺序一致） */
    fun favoriteSortDirection(): FavoriteSortDirection = FavoriteSortDirection.fromName(
        preferences.getString(FAVORITE_SORT_DIRECTION_KEY, null)
    )

    fun setFavoriteSortDirection(direction: FavoriteSortDirection) {
        preferences.edit { putString(FAVORITE_SORT_DIRECTION_KEY, direction.name) }
    }

    fun setAutoCheckInEnabled(enabled: Boolean) {
        preferences.edit { putBoolean(AUTO_CHECK_IN_KEY, enabled) }
    }

    fun autoCheckInCompletedToday(): Boolean {
        return preferences.getString(AUTO_CHECK_IN_DATE_KEY, null) == todayDate()
    }

    fun markAutoCheckInCompleted() {
        preferences.edit { putString(AUTO_CHECK_IN_DATE_KEY, todayDate()) }
    }

    suspend fun clearImageCache() = withContext(Dispatchers.IO) {
        applicationContext.imageLoader.memoryCache?.clear()
        applicationContext.imageLoader.diskCache?.clear()
    }

    suspend fun imageCacheSizeBytes(): Long = withContext(Dispatchers.IO) {
        applicationContext.imageLoader.diskCache?.size ?: 0L
    }

    fun endpointSnapshot(): EndpointSettingsState {
        val selection = core.endpointManager.selection()
        val activeApiUrl = (core.endpointManager.current() as? JmxResult.Success)
            ?.value
            ?.toString()
            ?.trimEnd('/')
        return EndpointSettingsState(
            automatic = selection is ApiEndpointSelection.Auto,
            selectedApiUrl = (selection as? ApiEndpointSelection.Manual)
                ?.url
                ?.toString()
                ?.trimEnd('/'),
            activeApiUrl = activeApiUrl,
            selectedImageUrl = homeRepository.preferredImageHost(),
            apiEndpoints = core.endpointManager.all().map {
                EndpointProbeUi(url = it.url.toString().trimEnd('/'))
            },
            imageEndpoints = homeRepository.availableImageHosts().map(::EndpointProbeUi),
        )
    }

    suspend fun probeApiEndpoint(url: String): EndpointProbeUi {
        val normalized = url.normalizedBaseUrlOrNull()
            ?: return EndpointProbeUi(url = url.trimEnd('/'), success = false)
        val result = core.endpointProber.probe(normalized)
        return EndpointProbeUi(
            url = result.url.trimEnd('/'),
            latencyMillis = result.latencyMillis,
            success = result.success,
        )
    }

    suspend fun probeImageEndpoint(url: String): EndpointProbeUi = withContext(Dispatchers.IO) {
        probeImageHost(url)
    }

    fun useAutomaticApi() {
        core.endpointController.useAutoSelection()
        preferences.edit { putBoolean(API_SELECTION_CONFIGURED_KEY, true) }
    }

    fun useApiEndpoint(url: String): JmxResult<*> {
        val result = core.endpointController.useManualEndpoint(url)
        if (result is JmxResult.Success) {
            preferences.edit { putBoolean(API_SELECTION_CONFIGURED_KEY, true) }
        }
        return result
    }

    fun useAutomaticImageHost() {
        homeRepository.useImageHost(null)
    }

    fun useImageHost(url: String) {
        homeRepository.useImageHost(url)
    }

    private fun probeImageHost(host: String): EndpointProbeUi {
        val normalized = host.trimEnd('/')
        val started = System.nanoTime()
        val success = runCatching {
            probeClient.newCall(
                Request.Builder()
                    .url(normalized)
                    .head()
                    .build(),
            ).execute().use { true }
        }.getOrDefault(false)
        val latency = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started).coerceAtLeast(0L)
        return EndpointProbeUi(normalized, latency, success)
    }
}

internal const val SETTINGS_PREFERENCES = "jmx_settings"
internal const val CONTENT_LANGUAGE_KEY = "content_language"
internal const val CONTENT_LANGUAGE_SIMPLIFIED = "CN"
internal const val CONTENT_LANGUAGE_TRADITIONAL = "TW"
private const val AUTO_CHECK_IN_KEY = "auto_check_in"
private const val AUTO_CHECK_IN_DATE_KEY = "auto_check_in_date"
private const val API_SELECTION_CONFIGURED_KEY = "api_selection_configured"
private const val TOP_BAR_BLUR_STYLE_KEY = "top_bar_blur_style"
private const val LIQUID_GLASS_NAV_BAR_KEY = "liquid_glass_nav_bar"
private const val FLOATING_NAV_BAR_STYLE_KEY = "floating_nav_bar_style"
private const val FAVORITE_SORT_ORDER_KEY = "favorite_sort_order"
private const val FAVORITE_SORT_DIRECTION_KEY = "favorite_sort_direction"
private const val IMAGE_PROBE_TIMEOUT_SECONDS = 5L

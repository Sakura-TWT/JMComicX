package dev.jmx.client

import android.content.Context
import androidx.core.content.edit
import coil.annotation.ExperimentalCoilApi
import coil.imageLoader
import dev.jmx.client.core.network.ApiEndpointSelection
import dev.jmx.client.core.network.defaultOkHttpClient
import dev.jmx.client.core.network.normalizedBaseUrlOrNull
import dev.jmx.client.core.result.JmxResult
import dev.jmx.client.core.runtime.JmxCore
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

    fun setAutoCheckInEnabled(enabled: Boolean) {
        preferences.edit { putBoolean(AUTO_CHECK_IN_KEY, enabled) }
    }

    fun autoCheckInCompletedToday(): Boolean {
        return preferences.getString(AUTO_CHECK_IN_DATE_KEY, null) == todayDate()
    }

    fun markAutoCheckInCompleted() {
        preferences.edit { putString(AUTO_CHECK_IN_DATE_KEY, todayDate()) }
    }

    @OptIn(ExperimentalCoilApi::class)
    suspend fun clearImageCache() = withContext(Dispatchers.IO) {
        applicationContext.imageLoader.memoryCache?.clear()
        applicationContext.imageLoader.diskCache?.clear()
    }

    @OptIn(ExperimentalCoilApi::class)
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

private const val SETTINGS_PREFERENCES = "jmx_settings"
private const val AUTO_CHECK_IN_KEY = "auto_check_in"
private const val AUTO_CHECK_IN_DATE_KEY = "auto_check_in_date"
private const val API_SELECTION_CONFIGURED_KEY = "api_selection_configured"
private const val IMAGE_PROBE_TIMEOUT_SECONDS = 5L

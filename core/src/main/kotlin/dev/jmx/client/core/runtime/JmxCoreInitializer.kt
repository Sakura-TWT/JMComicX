package dev.jmx.client.core.runtime

import dev.jmx.client.core.api.RemoteSetting
import dev.jmx.client.core.network.ApiEndpoint
import dev.jmx.client.core.network.DomainRefresher
import dev.jmx.client.core.result.JmxError
import dev.jmx.client.core.result.JmxResult
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class JmxCoreInitResult(
    val domainRefresh: InitStepResult<List<ApiEndpoint>>,
    val settingFetch: InitStepResult<RemoteSetting>
) {
    val isFullySuccessful: Boolean =
        domainRefresh is InitStepResult.Success && settingFetch is InitStepResult.Success
}

sealed interface InitStepResult<out T> {
    data class Success<T>(
        val value: T
    ) : InitStepResult<T>

    data class Failure(
        val error: JmxError
    ) : InitStepResult<Nothing>
}

class JmxCoreInitializer(
    private val domainRefresher: DomainRefresher,
    private val settingApi: dev.jmx.client.core.api.SettingApi,
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
) {
    private val mutex = Mutex()
    @Volatile
    private var cachedResult: JmxCoreInitResult? = null
    @Volatile
    private var cachedAtMillis: Long = 0L

    suspend fun initialize(forceRefresh: Boolean = false): JmxCoreInitResult = mutex.withLock {
        val now = nowMillis()
        val cached = cachedResult
        val cacheTtl = if (cached?.isFullySuccessful == true) SUCCESS_CACHE_TTL_MILLIS else FAILURE_CACHE_TTL_MILLIS
        if (!forceRefresh && cached != null && now - cachedAtMillis in 0 until cacheTtl) {
            return@withLock cached
        }

        val domain = when (val result = domainRefresher.refresh()) {
            is JmxResult.Success -> InitStepResult.Success(result.value)
            is JmxResult.Failure -> InitStepResult.Failure(result.error)
        }
        val setting = when (val result = settingApi.fetchSetting()) {
            is JmxResult.Success -> InitStepResult.Success(result.value)
            is JmxResult.Failure -> InitStepResult.Failure(result.error)
        }
        JmxCoreInitResult(
            domainRefresh = domain,
            settingFetch = setting
        ).also {
            cachedResult = it
            cachedAtMillis = nowMillis()
        }
    }

    private companion object {
        const val SUCCESS_CACHE_TTL_MILLIS = 15 * 60 * 1000L
        const val FAILURE_CACHE_TTL_MILLIS = 30 * 1000L
    }
}

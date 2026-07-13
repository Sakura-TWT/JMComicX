package dev.jmx.client.core.runtime

import dev.jmx.client.core.api.RemoteSetting
import dev.jmx.client.core.network.ApiEndpoint
import dev.jmx.client.core.network.DomainRefresher
import dev.jmx.client.core.result.JmxError
import dev.jmx.client.core.result.JmxResult

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
    private val settingApi: dev.jmx.client.core.api.SettingApi
) {
    suspend fun initialize(): JmxCoreInitResult {
        val domain = when (val result = domainRefresher.refresh()) {
            is JmxResult.Success -> InitStepResult.Success(result.value)
            is JmxResult.Failure -> InitStepResult.Failure(result.error)
        }
        val setting = when (val result = settingApi.fetchSetting()) {
            is JmxResult.Success -> InitStepResult.Success(result.value)
            is JmxResult.Failure -> InitStepResult.Failure(result.error)
        }
        return JmxCoreInitResult(
            domainRefresh = domain,
            settingFetch = setting
        )
    }
}

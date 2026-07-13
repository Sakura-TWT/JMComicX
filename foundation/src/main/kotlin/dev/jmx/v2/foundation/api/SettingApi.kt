package dev.jmx.v2.foundation.api

import dev.jmx.v2.foundation.network.ApiRequest
import dev.jmx.v2.foundation.network.JmxApiClient
import dev.jmx.v2.foundation.protocol.ApiRoute
import dev.jmx.v2.foundation.protocol.ApiVersionProvider
import dev.jmx.v2.foundation.result.JmxError
import dev.jmx.v2.foundation.result.JmxResult

class SettingApi(
    private val apiClient: JmxApiClient,
    private val apiVersionProvider: ApiVersionProvider
) {
    suspend fun fetchSetting(): JmxResult<RemoteSetting> {
        val data = when (val result = apiClient.requestJson(ApiRequest(ApiRoute.Setting))) {
            is JmxResult.Success -> result.value
            is JmxResult.Failure -> return result
        }
        val root = data.asObjectOrNull()
            ?: return JmxResult.Failure(JmxError.Schema("setting data 不是对象"))
        val version = root.stringOrNull("jm3_version", "version", "api_version")
        if (version != null) apiVersionProvider.update(version)
        val shunts = root["app_shunts"].asObjectListOrEmpty().mapIndexed { index, item ->
            ApiShunt(
                id = item.stringOrNull("id", "value") ?: "${index + 1}",
                name = item.stringOrNull("title", "name") ?: "线路 ${index + 1}"
            )
        }
        return JmxResult.Success(
            RemoteSetting(
                apiVersion = version,
                imageHost = root.stringOrNull("img_host", "imgHost"),
                shunts = shunts
            )
        )
    }
}

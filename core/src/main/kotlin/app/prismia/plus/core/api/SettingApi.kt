package app.prismia.plus.core.api

import app.prismia.plus.core.network.JmxApiClient
import app.prismia.plus.core.network.apiRequest
import app.prismia.plus.core.protocol.ApiRoute
import app.prismia.plus.core.protocol.ApiVersionProvider
import app.prismia.plus.core.result.JmxError
import app.prismia.plus.core.result.JmxResult

/**
 * @param onApiVersionChanged 服务端下发的 API 版本与本地不同、且已写入时回调新版本号。
 *   本地响应缓存以该版本号为"代号"，版本一变旧内容整体作废；这里给出回收磁盘的时机
 *   （官方客户端用 setting 里的 ad_cache_version 达到同一目的：不发版就能让缓存失效）。
 */
class SettingApi(
    private val apiClient: JmxApiClient,
    private val apiVersionProvider: ApiVersionProvider,
    private val onApiVersionChanged: (String) -> Unit = {}
) {
    suspend fun fetchSetting(): JmxResult<RemoteSetting> {
        val data = when (val result = apiClient.requestJson(apiRequest(ApiRoute.Setting))) {
            is JmxResult.Success -> result.value
            is JmxResult.Failure -> return result
        }
        val root = data.asObjectOrNull()
            ?: return JmxResult.Failure(JmxError.Schema("setting data 不是对象"))
        val version = root.stringOrNull("jm3_version", "version", "api_version")
        if (version != null) {
            val previous = apiVersionProvider.current()
            if (apiVersionProvider.update(version) && apiVersionProvider.current() != previous) {
                onApiVersionChanged(apiVersionProvider.current())
            }
        }
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

package app.prismia.plus.core.network

import app.prismia.network.BufferedHttpExecutor
import app.prismia.network.ResponseSizeLimitException
import com.google.gson.JsonParser
import app.prismia.plus.core.crypto.AesEcbPkcs7
import app.prismia.plus.core.crypto.JmxHash
import app.prismia.plus.core.protocol.JmxProtocolConstants
import app.prismia.plus.core.result.JmxError
import app.prismia.plus.core.result.JmxResult
import app.prismia.plus.core.session.SessionManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

data class DomainServerPayload(
    val apiHosts: List<String>
)

data class DomainRefreshAttempt(
    val url: String,
    val success: Boolean,
    val message: String
)

class DomainServerDecoder {
    fun decode(text: String): JmxResult<DomainServerPayload> {
        val cleanText = text.trimLeadingNonAscii()
        if (cleanText.isBlank()) {
            return JmxResult.Failure(JmxError.Domain("域名服务器响应为空"))
        }
        val key = JmxHash.md5Hex(JmxProtocolConstants.DomainServerSecret)
        val json = AesEcbPkcs7.decryptBase64ToString(cleanText, key).let {
            when (it) {
                is JmxResult.Success -> it.value
                is JmxResult.Failure -> return it
            }
        }
        val root = runCatching { JsonParser.parseString(json).asJsonObject }.getOrElse {
            return JmxResult.Failure(JmxError.Schema("域名服务器 JSON 解析失败", cause = it))
        }
        val hosts = root["Server"]
            ?.takeIf { it.isJsonArray }
            ?.asJsonArray
            ?.mapNotNull { item -> item.takeIf { it.isJsonPrimitive }?.asString?.trim() }
            ?.filter { it.isNotEmpty() }
            ?.distinct()
            ?: emptyList()
        if (hosts.isEmpty()) {
            return JmxResult.Failure(JmxError.Domain("域名服务器未返回可用 API 域名"))
        }
        return JmxResult.Success(DomainServerPayload(hosts))
    }

    private fun String.trimLeadingNonAscii(): String {
        var index = 0
        while (index < length && !this[index].isAscii()) index++
        return substring(index).trim()
    }

    private fun Char.isAscii(): Boolean = code in 0..127
}

class DomainRefresher(
    private val endpointManager: ApiEndpointManager,
    private val okHttpClient: OkHttpClient = defaultOkHttpClient(),
    private val decoder: DomainServerDecoder = DomainServerDecoder(),
    private val serverUrls: List<String> = JmxProtocolConstants.DomainServerUrls,
    private val sessionManager: SessionManager? = null,
    maxResponseBytes: Long = 1024 * 1024,
) {
    private val executor = BufferedHttpExecutor(okHttpClient, maxResponseBytes)
    /**
     * 刷新 API 域名列表。
     *
     * 所有域名服务器并发竞速，先返回可解密内容的那个胜出，其余立即取消。
     * 原实现是顺序 for 循环：第一个地址不可达时要等它超时（连接 8s + 读 15s）才轮到第二个，
     * 而这一步挡在冷启动的最前面。两个地址托管在不同 CDN 上、下发的是同一份内容，
     * 没有先后之分（官方客户端本身也是从 Server 列表里随机取一个），因此谁先到就用谁。
     */
    suspend fun refresh(): JmxResult<List<ApiEndpoint>> = withContext(Dispatchers.IO) {
        if (serverUrls.isEmpty()) {
            return@withContext JmxResult.Failure(JmxError.Domain("未配置域名服务器"))
        }
        val attempts = mutableListOf<DomainRefreshAttempt>()
        val payload = when (val raced = raceServers(attempts)) {
            is JmxResult.Success -> raced.value
            is JmxResult.Failure -> return@withContext JmxResult.Failure(
                JmxError.Domain(
                    message = buildFailureMessage(attempts, raced.error),
                    cause = raced.error.cause
                )
            )
        }
        val endpoints = when (val replaced = endpointManager.replaceAll(payload.apiHosts)) {
            is JmxResult.Success -> replaced.value
            is JmxResult.Failure -> return@withContext replaced
        }
        when (val synced = sessionManager?.syncAvsCookieToHostsIfPresent(endpoints.map { it.url.toString() })) {
            is JmxResult.Failure -> return@withContext synced
            is JmxResult.Success,
            null -> Unit
        }
        JmxResult.Success(endpoints)
    }

    /** @param attempts 逐个地址的结果，仅在本协程内追加，用于失败时拼出可诊断的信息。 */
    private suspend fun raceServers(
        attempts: MutableList<DomainRefreshAttempt>
    ): JmxResult<DomainServerPayload> = coroutineScope {
        // 容量取地址数：每个分支都能无阻塞地投递结果，即使已经有赢家、没人再来接收。
        val results = Channel<Pair<String, JmxResult<DomainServerPayload>>>(capacity = serverUrls.size)
        val racers = serverUrls.map { url ->
            launch { results.send(url to requestAndDecode(url)) }
        }
        try {
            var lastError: JmxError? = null
            repeat(serverUrls.size) {
                val (url, result) = results.receive()
                when (result) {
                    is JmxResult.Success -> {
                        attempts += DomainRefreshAttempt(url, success = true, message = "${result.value.apiHosts.size} hosts")
                        return@coroutineScope result
                    }
                    is JmxResult.Failure -> {
                        lastError = result.error
                        attempts += DomainRefreshAttempt(url, success = false, message = result.error.message)
                    }
                }
            }
            JmxResult.Failure(lastError ?: JmxError.Domain("全部域名服务器刷新失败"))
        } finally {
            // Cancellation owns the losing call through body reads as well as headers.
            racers.forEach { it.cancel() }
        }
    }

    fun serverUrls(): List<String> = serverUrls

    private suspend fun requestAndDecode(url: String): JmxResult<DomainServerPayload> {
        val request = Request.Builder()
            .url(url)
            .header("user-agent", JmxProtocolConstants.MobileUserAgent)
            .get()
            .build()
        return try {
            executor.execute(request).let { response ->
                val body = response.body
                if (response.status !in 200..299) {
                    return JmxResult.Failure(JmxError.Http(response.status, "域名服务器请求失败：${response.status}"))
                }
                decoder.decode(body)
            }
        } catch (cancelled: CancellationException) {
            // 竞速失败方会被取消，这不是"域名服务器坏了"，不能降级成普通失败往上报。
            throw cancelled
        } catch (failure: Exception) {
            val error = when (failure) {
                is ResponseSizeLimitException -> JmxError.Schema("域名服务器响应超过大小限制", field = "responseBody", cause = failure)
                is IOException -> JmxError.Network("域名服务器网络请求失败", failure)
                else -> JmxError.Unknown(failure.message ?: "域名服务器未知错误", failure)
            }
            JmxResult.Failure(error)
        }
    }

    private fun buildFailureMessage(attempts: List<DomainRefreshAttempt>, lastError: JmxError?): String {
        if (attempts.isEmpty()) return lastError?.message ?: "全部域名服务器刷新失败"
        return attempts.joinToString(
            prefix = "全部域名服务器刷新失败：",
            separator = "；"
        ) { attempt ->
            val status = if (attempt.success) "success" else "failed"
            "$status ${attempt.url} ${attempt.message}"
        }
    }
}

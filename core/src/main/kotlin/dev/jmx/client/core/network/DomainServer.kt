package dev.jmx.client.core.network

import com.google.gson.JsonParser
import dev.jmx.client.core.crypto.AesEcbPkcs7
import dev.jmx.client.core.crypto.JmxHash
import dev.jmx.client.core.protocol.JmxProtocolConstants
import dev.jmx.client.core.result.JmxError
import dev.jmx.client.core.result.JmxResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

data class DomainServerPayload(
    val apiHosts: List<String>
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
    private val serverUrls: List<String> = JmxProtocolConstants.DomainServerUrls
) {
    suspend fun refresh(): JmxResult<List<ApiEndpoint>> = withContext(Dispatchers.IO) {
        var lastError: JmxError? = null
        for (url in serverUrls) {
            val result = requestAndDecode(url)
            when (result) {
                is JmxResult.Success -> return@withContext endpointManager.replaceAll(result.value.apiHosts)
                is JmxResult.Failure -> lastError = result.error
            }
        }
        JmxResult.Failure(lastError ?: JmxError.Domain("全部域名服务器刷新失败"))
    }

    private fun requestAndDecode(url: String): JmxResult<DomainServerPayload> {
        return runCatching {
            val request = Request.Builder()
                .url(url)
                .header("user-agent", JmxProtocolConstants.MobileUserAgent)
                .get()
                .build()
            okHttpClient.newCall(request).execute().use { response ->
                val body = response.body.string()
                if (!response.isSuccessful) {
                    return JmxResult.Failure(JmxError.Http(response.code, "域名服务器请求失败：${response.code}"))
                }
                decoder.decode(body)
            }
        }.getOrElse {
            val error = if (it is IOException) {
                JmxError.Network("域名服务器网络请求失败", it)
            } else {
                JmxError.Unknown(it.message ?: "域名服务器未知错误", it)
            }
            JmxResult.Failure(error)
        }
    }
}

package dev.jmx.client.core.download

import dev.jmx.client.core.network.defaultOkHttpClient
import dev.jmx.client.core.result.JmxError
import dev.jmx.client.core.result.JmxResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

interface Downloader {
    suspend fun download(request: DownloadRequest, sink: ByteSink): JmxResult<DownloadResult>
}

class BinaryDownloader(
    private val okHttpClient: OkHttpClient = defaultOkHttpClient(),
    private val bufferSize: Int = DEFAULT_BUFFER_SIZE
) : Downloader {
    override suspend fun download(request: DownloadRequest, sink: ByteSink): JmxResult<DownloadResult> =
        withContext(Dispatchers.IO) {
            request.observer.onEvent(DownloadEvent.Started(request.url))
            runCatching {
                val httpRequest = Request.Builder()
                    .url(request.url)
                    .apply {
                        request.headers.forEach { (key, value) -> header(key, value) }
                    }
                    .get()
                    .build()
                okHttpClient.newCall(httpRequest).execute().use { response ->
                    if (!response.isSuccessful) {
                        val error = JmxError.Http(
                            code = response.code,
                            message = "下载请求失败：${response.code}"
                        )
                        request.observer.onEvent(DownloadEvent.Failed(request.url, error))
                        return@withContext JmxResult.Failure(
                            error
                        )
                    }
                    val body = response.body
                    val contentType = body.contentType()?.toString()
                    if (!request.acceptedContentTypes.matches(contentType)) {
                        val error = JmxError.Schema(
                            "下载响应类型不匹配：${contentType ?: "unknown"}",
                            field = "content-type"
                        )
                        request.observer.onEvent(DownloadEvent.Failed(request.url, error))
                        return@withContext JmxResult.Failure(error)
                    }
                    val contentLength = body.contentLength()
                    if (request.maxBytes != null && contentLength > request.maxBytes) {
                        val error = JmxError.Schema(
                            "下载响应超过大小限制：$contentLength > ${request.maxBytes}",
                            field = "content-length"
                        )
                        request.observer.onEvent(DownloadEvent.Failed(request.url, error))
                        return@withContext JmxResult.Failure(error)
                    }
                    val buffer = ByteArray(bufferSize.coerceAtLeast(1))
                    var written = 0L
                    body.byteStream().use { stream ->
                        while (true) {
                            val read = stream.read(buffer)
                            if (read == -1) break
                            if (request.maxBytes != null && written + read > request.maxBytes) {
                                val error = JmxError.Schema(
                                    "下载数据超过大小限制：${written + read} > ${request.maxBytes}",
                                    field = "body"
                                )
                                request.observer.onEvent(DownloadEvent.Failed(request.url, error))
                                return@withContext JmxResult.Failure(error)
                            }
                            sink.write(buffer.copyOf(read))
                            written += read
                            request.observer.onEvent(
                                DownloadEvent.Progress(
                                    url = request.url,
                                    bytesRead = written,
                                    contentLength = contentLength
                                )
                            )
                        }
                    }
                    val result = DownloadResult(
                        url = response.request.url.toString(),
                        statusCode = response.code,
                        contentType = contentType,
                        contentLength = contentLength,
                        bytesWritten = written
                    )
                    request.observer.onEvent(DownloadEvent.Completed(request.url, result))
                    JmxResult.Success(result)
                }
            }.getOrElse {
                val error = if (it is IOException) {
                    JmxError.Network("下载网络请求失败", it)
                } else {
                    JmxError.Unknown(it.message ?: "下载未知错误", it)
                }
                request.observer.onEvent(DownloadEvent.Failed(request.url, error))
                JmxResult.Failure(error)
            }
        }

    private companion object {
        const val DEFAULT_BUFFER_SIZE = 8 * 1024
    }
}

private fun Set<String>.matches(contentType: String?): Boolean {
    if (isEmpty()) return true
    val actual = contentType?.substringBefore(';')?.trim()?.lowercase() ?: return false
    return any { expected ->
        val value = expected.substringBefore(';').trim().lowercase()
        value == actual || value.endsWith("/*") && actual.startsWith("${value.substringBefore('/')}/")
    }
}

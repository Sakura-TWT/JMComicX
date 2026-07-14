package dev.jmx.client.core.download

import dev.jmx.client.core.network.defaultOkHttpClient
import dev.jmx.client.core.result.JmxError
import dev.jmx.client.core.result.JmxResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException

interface Downloader {
    suspend fun download(request: DownloadRequest, sink: ByteSink): JmxResult<DownloadResult>
}

interface TruncatingSink {
    fun truncate()
}

class BinaryDownloader(
    private val okHttpClient: OkHttpClient = defaultOkHttpClient(),
    private val bufferSize: Int = DEFAULT_BUFFER_SIZE
) : Downloader {
    override suspend fun download(request: DownloadRequest, sink: ByteSink): JmxResult<DownloadResult> =
        withContext(Dispatchers.IO) {
            request.observer.onEvent(DownloadEvent.Started(request.url))
            try {
                val offset = request.rangeStartInclusive?.takeIf { it > 0L }
                val wantRange = request.preferRangeResume && offset != null
                if (wantRange) {
                    open(request, offset).use { response ->
                        when (response.code) {
                            206 -> return@withContext writeBody(
                                request, response, sink,
                                resumedFromOffset = offset,
                                usedRange = true
                            )
                            200 -> {
                                (sink as? TruncatingSink)?.truncate()
                                return@withContext writeBody(
                                    request, response, sink,
                                    resumedFromOffset = 0L,
                                    usedRange = false
                                )
                            }
                            416 -> Unit
                            else -> {
                                if (!response.isSuccessful) {
                                    return@withContext failHttp(request, response)
                                }
                            }
                        }
                    }
                    open(request, rangeStart = null).use { response ->
                        (sink as? TruncatingSink)?.truncate()
                        return@withContext writeBody(
                            request, response, sink,
                            resumedFromOffset = 0L,
                            usedRange = false
                        )
                    }
                } else {
                    open(request, rangeStart = null).use { response ->
                        return@withContext writeBody(
                            request, response, sink,
                            resumedFromOffset = 0L,
                            usedRange = false
                        )
                    }
                }
            } catch (it: Throwable) {
                val error = if (it is IOException) {
                    JmxError.Network(
                        "下载网络请求失败；" + DownloadFailureContext(url = request.url).describe(),
                        it
                    )
                } else {
                    JmxError.Unknown(
                        (it.message ?: "下载未知错误") + "；" +
                            DownloadFailureContext(url = request.url).describe(),
                        it
                    )
                }
                request.observer.onEvent(DownloadEvent.Failed(request.url, error))
                JmxResult.Failure(error)
            }
        }

    private fun open(request: DownloadRequest, rangeStart: Long?): Response {
        val builder = Request.Builder().url(request.url).get()
        request.headers.forEach { (key, value) ->
            if (!key.equals("Range", ignoreCase = true)) {
                builder.header(key, value)
            }
        }
        if (rangeStart != null && rangeStart > 0L) {
            builder.header("Range", "bytes=$rangeStart-")
        }
        return okHttpClient.newCall(builder.build()).execute()
    }

    private fun failHttp(request: DownloadRequest, response: Response): JmxResult<DownloadResult> {
        val contentType = response.body.contentType()?.toString()
        val contentLength = response.body.contentLength()
        val error = JmxError.Http(
            code = response.code,
            message = "下载请求失败：${response.code}；" + DownloadFailureContext(
                url = response.request.url.toString(),
                statusCode = response.code,
                contentType = contentType,
                contentLength = contentLength
            ).describe()
        )
        request.observer.onEvent(DownloadEvent.Failed(request.url, error))
        return JmxResult.Failure(error)
    }

    private fun writeBody(
        request: DownloadRequest,
        response: Response,
        sink: ByteSink,
        resumedFromOffset: Long,
        usedRange: Boolean
    ): JmxResult<DownloadResult> {
        val body = response.body
        val contentType = body.contentType()?.toString()
        val contentLength = body.contentLength()
        val status = response.code
        if (status != 200 && status != 206) {
            return failHttp(request, response)
        }
        if (!request.acceptedContentTypes.matches(contentType)) {
            val error = JmxError.Schema(
                "下载响应类型不匹配：${contentType ?: "unknown"}；" + DownloadFailureContext(
                    url = response.request.url.toString(),
                    statusCode = status,
                    contentType = contentType,
                    contentLength = contentLength
                ).describe(),
                field = "content-type"
            )
            request.observer.onEvent(DownloadEvent.Failed(request.url, error))
            return JmxResult.Failure(error)
        }
        val effectiveLength = if (status == 206 && contentLength >= 0 && resumedFromOffset > 0) {
            contentLength + resumedFromOffset
        } else {
            contentLength
        }
        if (request.maxBytes != null && effectiveLength > request.maxBytes) {
            val error = JmxError.Schema(
                "下载响应超过大小限制：$effectiveLength > ${request.maxBytes}；" + DownloadFailureContext(
                    url = response.request.url.toString(),
                    statusCode = status,
                    contentType = contentType,
                    contentLength = contentLength,
                    maxBytes = request.maxBytes
                ).describe(),
                field = "content-length"
            )
            request.observer.onEvent(DownloadEvent.Failed(request.url, error))
            return JmxResult.Failure(error)
        }
        val buffer = ByteArray(bufferSize.coerceAtLeast(1))
        var written = 0L
        body.byteStream().use { stream ->
            while (true) {
                val read = stream.read(buffer)
                if (read == -1) break
                val totalAfter = resumedFromOffset + written + read
                if (request.maxBytes != null && totalAfter > request.maxBytes) {
                    val error = JmxError.Schema(
                        "下载数据超过大小限制：$totalAfter > ${request.maxBytes}；" + DownloadFailureContext(
                            url = response.request.url.toString(),
                            statusCode = status,
                            contentType = contentType,
                            contentLength = contentLength,
                            bytesRead = totalAfter,
                            maxBytes = request.maxBytes
                        ).describe(),
                        field = "body"
                    )
                    request.observer.onEvent(DownloadEvent.Failed(request.url, error))
                    return JmxResult.Failure(error)
                }
                sink.write(buffer.copyOf(read))
                written += read
                request.observer.onEvent(
                    DownloadEvent.Progress(
                        url = request.url,
                        bytesRead = resumedFromOffset + written,
                        contentLength = effectiveLength
                    )
                )
            }
        }
        val result = DownloadResult(
            url = response.request.url.toString(),
            statusCode = status,
            contentType = contentType,
            contentLength = effectiveLength,
            bytesWritten = written,
            resumedFromOffset = resumedFromOffset,
            usedRange = usedRange && status == 206
        )
        request.observer.onEvent(DownloadEvent.Completed(request.url, result))
        return JmxResult.Success(result)
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

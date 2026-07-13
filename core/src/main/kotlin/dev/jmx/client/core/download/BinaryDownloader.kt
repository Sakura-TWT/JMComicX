package dev.jmx.client.core.download

import dev.jmx.client.core.network.defaultOkHttpClient
import dev.jmx.client.core.result.JmxError
import dev.jmx.client.core.result.JmxResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

class BinaryDownloader(
    private val okHttpClient: OkHttpClient = defaultOkHttpClient(),
    private val bufferSize: Int = DEFAULT_BUFFER_SIZE
) {
    suspend fun download(request: DownloadRequest, sink: ByteSink): JmxResult<DownloadResult> =
        withContext(Dispatchers.IO) {
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
                        return@withContext JmxResult.Failure(
                            JmxError.Http(
                                code = response.code,
                                message = "下载请求失败：${response.code}"
                            )
                        )
                    }
                    val body = response.body
                    val buffer = ByteArray(bufferSize.coerceAtLeast(1))
                    var written = 0L
                    body.byteStream().use { stream ->
                        while (true) {
                            val read = stream.read(buffer)
                            if (read == -1) break
                            sink.write(buffer.copyOf(read))
                            written += read
                        }
                    }
                    JmxResult.Success(
                        DownloadResult(
                            url = response.request.url.toString(),
                            statusCode = response.code,
                            contentType = body.contentType()?.toString(),
                            contentLength = body.contentLength(),
                            bytesWritten = written
                        )
                    )
                }
            }.getOrElse {
                val error = if (it is IOException) {
                    JmxError.Network("下载网络请求失败", it)
                } else {
                    JmxError.Unknown(it.message ?: "下载未知错误", it)
                }
                JmxResult.Failure(error)
            }
        }

    private companion object {
        const val DEFAULT_BUFFER_SIZE = 8 * 1024
    }
}

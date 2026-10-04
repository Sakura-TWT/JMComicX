package app.prismia.network

import kotlinx.coroutines.runBlocking
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

class BufferedHttpExecutorTest {
    @Test fun preservesCallerOwnedCookiesAndInterceptors() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody("ok"))
            val client = OkHttpClient.Builder().cookieJar(object : CookieJar {
                override fun loadForRequest(url: HttpUrl) = listOf(Cookie.Builder().name("avs").value("test-session").hostOnlyDomain(url.host).build())
                override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) = Unit
            }).addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().header("X-Protocol", "JM").build())
            }.build()
            BufferedHttpExecutor(client).execute(Request.Builder().url(server.url("/")).build())
            val request = server.takeRequest()
            assertEquals("avs=test-session", request.getHeader("Cookie"))
            assertEquals("JM", request.getHeader("X-Protocol"))
        }
    }

    @Test fun bomOverridesDeclaredCharsetLikeOkHttpString() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val text = "协议"
            val bytes = byteArrayOf(0xff.toByte(), 0xfe.toByte()) + text.toByteArray(Charsets.UTF_16LE)
            server.enqueue(MockResponse().setHeader("Content-Type", "text/plain; charset=utf-8").setBody(Buffer().write(bytes)))
            val response = BufferedHttpExecutor(OkHttpClient()).execute(Request.Builder().url(server.url("/")).build())
            assertEquals(text, response.body)
        }
    }

    @Test fun decompressedBytesCannotExceedLimit() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val bytes = ByteArrayOutputStream().apply {
                GZIPOutputStream(this).use { it.write(ByteArray(1024) { 65 }) }
            }.toByteArray()
            assertTrue(bytes.size < 64)
            server.enqueue(MockResponse().setHeader("Content-Encoding", "gzip").setBody(Buffer().write(bytes)))
            val failure = runCatching {
                BufferedHttpExecutor(OkHttpClient(), 64).execute(Request.Builder().url(server.url("/")).build())
            }.exceptionOrNull()
            assertTrue(failure is ResponseSizeLimitException)
        }
    }
}

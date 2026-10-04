package app.prismia.network

import app.prismia.foundation.ContentSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.EventListener
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.TimeUnit

class SourceHttpClientTest {
    @Test fun cancellationStillOwnsTheCallWhileReadingTheResponseBody() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody("a".repeat(64)).throttleBody(1, 1, TimeUnit.SECONDS))
            val reading = CompletableDeferred<Unit>()
            val failed = CompletableDeferred<Unit>()
            val client = OkHttpClient.Builder().eventListener(object : EventListener() {
                override fun responseBodyStart(call: Call) { reading.complete(Unit) }
                override fun callFailed(call: Call, ioe: IOException) { failed.complete(Unit) }
            }).build()
            val pending = async(Dispatchers.IO) {
                SourceHttpClient(client, ContentSource.IWARA).execute(Request.Builder().url(server.url("/")).build())
            }
            withTimeout(5_000) { reading.await() }
            withTimeout(2_000) { pending.cancelAndJoin(); failed.await() }
        }
    }

    @Test fun inheritedCookieJarAndAuthenticatorCannotInjectCredentials() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setResponseCode(401))
            val client = OkHttpClient.Builder()
                .cookieJar(object : CookieJar {
                    override fun loadForRequest(url: HttpUrl): List<Cookie> = error("inherited cookie jar must not be read")
                    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) = Unit
                })
                .authenticator { _, _ -> error("source adapter owns authentication") }
                .build()
            val response = SourceHttpClient(client, ContentSource.IWARA)
                .execute(Request.Builder().url(server.url("/")).build())
            assertEquals(401, response.status)
            assertEquals(1, server.requestCount)
            assertNull(server.takeRequest().getHeader("Cookie"))
        }
    }

    @Test fun interceptorsCannotBypassCredentialScoping() {
        val client = OkHttpClient.Builder().addInterceptor { it.proceed(it.request()) }.build()
        assertThrows(IllegalArgumentException::class.java) { SourceHttpClient(client, ContentSource.IWARA) }
    }

    @Test fun scopeMatchesOriginAndPathBoundaries() {
        val base = "https://api.example.test/api/".toHttpUrl()
        assertTrue("https://api.example.test/api/videos".toHttpUrl().isWithin(base))
        for (url in listOf("https://api.example.test.evil/api/videos", "https://api.example.test:8443/api/videos",
            "http://api.example.test/api/videos", "https://api.example.test/api-other/videos")) {
            assertFalse(url.toHttpUrl().isWithin(base))
        }
    }

    @Test fun crossOriginRedirectCannotCarryApiCredentials() = runBlocking {
        MockWebServer().use { api -> MockWebServer().use { cdn ->
            api.start(); cdn.start()
            api.enqueue(MockResponse().setResponseCode(302).addHeader("Location", cdn.url("/file")))
            cdn.enqueue(MockResponse().setBody("content"))
            val response = SourceHttpClient(OkHttpClient(), ContentSource.IWARA).execute(
                Request.Builder().url(api.url("/video"))
                    .header("Authorization", "Bearer test-token").header("Cookie", "test-cookie")
                    .header("X-Site", "www.iwara.tv").build(), api.url("/"),
            )
            assertEquals("content", response.body)
            assertEquals("Bearer test-token", api.takeRequest().getHeader("Authorization"))
            val redirected = cdn.takeRequest()
            for (header in listOf("Authorization", "Cookie", "X-Site")) assertNull(redirected.getHeader(header))
        } }
    }

    @Test fun chunkedResponseCannotExceedMemoryBudget() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setChunkedBody("a".repeat(64), 8))
            val failure = runCatching {
                SourceHttpClient(OkHttpClient(), ContentSource.IWARA, maxResponseBytes = 16).execute(Request.Builder().url(server.url("/")).build())
            }.exceptionOrNull()
            assertTrue(failure is SourceBodyLimitException)
        }
    }

    @Test fun cancellationClosesAnInFlightSocketWithoutWaitingForReadTimeout() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val failed = CompletableDeferred<Unit>()
            val client = OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS)
                .eventListener(object : EventListener() {
                    override fun callFailed(call: Call, ioe: IOException) { failed.complete(Unit) }
                }).build()
            val pending = async(Dispatchers.IO) {
                SourceHttpClient(client, ContentSource.IWARA).execute(Request.Builder().url(server.url("/slow")).build())
            }
            assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            withTimeout(2_000) { pending.cancelAndJoin(); failed.await() }
        }
    }

    @Test fun redirectLoopStopsAtConfiguredBound() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            repeat(3) { server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "/loop")) }
            val failure = runCatching {
                SourceHttpClient(OkHttpClient(), ContentSource.IWARA, maxRedirects = 2).execute(Request.Builder().url(server.url("/loop")).build())
            }.exceptionOrNull()
            assertTrue(failure is SourceRedirectException)
            assertEquals(3, server.requestCount)
        }
    }
}

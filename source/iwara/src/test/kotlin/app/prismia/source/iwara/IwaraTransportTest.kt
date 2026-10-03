package app.prismia.source.iwara

import app.prismia.foundation.SourceRetryPolicy
import app.prismia.foundation.SourceErrorCategory
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class IwaraTransportTest {
    @Test
    fun retriesTransientHttpFailureWithBoundedPolicy() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(503).setBody("busy"))
        server.enqueue(MockResponse().setBody("{\"ok\":true}"))
        server.start()
        try {
            val transport = OkHttpIwaraTransport(
                client = OkHttpClient(),
                baseUrl = server.url("/").toString(),
                retryPolicy = SourceRetryPolicy(maxAttempts = 2, initialDelayMillis = 0, maxDelayMillis = 0),
            )

            assertEquals("{\"ok\":true}", transport.get("video/v1"))
            assertEquals(2, server.requestCount)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun classifiesCloudflareChallengeSeparatelyFromAuthentication() {
        val failure = IwaraHttpException(403, "<title>Just a moment...</title><script>challenge-platform</script>")

        assertEquals(SourceErrorCategory.CLOUDFLARE, failure.sourceFailure.category)
        assertEquals(403, failure.sourceFailure.httpStatus)
    }

    @Test
    fun doesNotForwardBearerTokenToAbsoluteCdnUrl() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("{}"))
        server.start()
        try {
            val transport = OkHttpIwaraTransport(
                client = OkHttpClient(),
                baseUrl = server.url("/api/").toString(),
                accessTokenProvider = suspend { "secret-token" },
            )

            transport.get(server.url("/cdn/file").toString())
            val request = server.takeRequest()
            assertEquals(null, request.getHeader("Authorization"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun postSendsJsonAndRefreshBearerToApi() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("{\"accessToken\":\"ok\"}"))
        server.start()
        try {
            val transport = OkHttpIwaraTransport(
                client = OkHttpClient(),
                baseUrl = server.url("/api/").toString(),
            )

            assertEquals(
                "{\"accessToken\":\"ok\"}",
                transport.post("user/token", "{}", bearerToken = "refresh-value"),
            )
            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("application/json; charset=utf-8", request.getHeader("Content-Type"))
            assertEquals("Bearer refresh-value", request.getHeader("Authorization"))
            assertEquals("{}", request.body.readUtf8())
            assertEquals("www.iwara.tv", request.getHeader("X-Site"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun postDoesNotRetryUnauthorizedResponse() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(401).setBody("invalid refresh"))
        server.start()
        try {
            val transport = OkHttpIwaraTransport(
                client = OkHttpClient(),
                baseUrl = server.url("/").toString(),
                retryPolicy = SourceRetryPolicy(maxAttempts = 3, initialDelayMillis = 0, maxDelayMillis = 0),
            )
            val failure = assertThrows(IwaraHttpException::class.java) {
                runBlocking { transport.post("user/token", "{}", bearerToken = "refresh") }
            }
            assertEquals(401, failure.statusCode)
            assertEquals(SourceErrorCategory.AUTHENTICATION, failure.sourceFailure.category)
            assertEquals(1, server.requestCount)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun postDoesNotForwardBearerToAbsoluteCdnUrl() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("{}"))
        server.start()
        try {
            val transport = OkHttpIwaraTransport(
                client = OkHttpClient(),
                baseUrl = server.url("/api/").toString(),
            )
            transport.post(server.url("/cdn/file").toString(), "{}", bearerToken = "refresh")
            assertEquals(null, server.takeRequest().getHeader("Authorization"))
        } finally {
            server.shutdown()
        }
    }
}

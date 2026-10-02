package app.prismia.source.iwara

import app.prismia.foundation.SourceRetryPolicy
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
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
}

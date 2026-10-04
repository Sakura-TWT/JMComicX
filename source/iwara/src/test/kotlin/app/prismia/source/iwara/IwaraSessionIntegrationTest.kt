package app.prismia.source.iwara

import app.prismia.data.InMemoryVideoSessionStore
import app.prismia.data.VideoSession
import app.prismia.data.VideoSessionManager
import app.prismia.data.VideoSessionRefresher
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class IwaraSessionIntegrationTest {
    @Test fun applicationStyleWiringAllowsAnonymousBrowsing() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody("{\"results\":[]}"))
            val session = VideoSessionManager(InMemoryVideoSessionStore(), VideoSessionRefresher { error("no refresh token") })
            val transport = transport(server, session)
            assertTrue(IwaraClient(transport).browse(0, 1).items.isEmpty())
            assertNull(server.takeRequest().getHeader("Authorization"))
        }
    }

    @Test fun unauthorizedApiRequestRefreshesAndReplaysExactlyOnce() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setResponseCode(401).setBody("unauthorized"))
            server.enqueue(MockResponse().setBody("{\"results\":[]}"))
            var refreshes = 0
            val session = VideoSessionManager(
                InMemoryVideoSessionStore(VideoSession("old", "refresh", 2_000)),
                VideoSessionRefresher { refreshes++; VideoSession("renewed", "refresh", 3_000) },
                nowEpochSeconds = { 1_000 },
            )
            IwaraClient(transport(server, session)).browse(0, 1)
            assertEquals(1, refreshes)
            assertEquals("Bearer old", server.takeRequest().getHeader("Authorization"))
            assertEquals("Bearer renewed", server.takeRequest().getHeader("Authorization"))
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun aSecondUnauthorizedResponseDoesNotCauseARefreshLoop() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            repeat(2) { server.enqueue(MockResponse().setResponseCode(401)) }
            var refreshes = 0
            val session = VideoSessionManager(
                InMemoryVideoSessionStore(VideoSession("old", "refresh", 2_000)),
                VideoSessionRefresher { refreshes++; VideoSession("renewed", "refresh", 3_000) },
                nowEpochSeconds = { 1_000 },
            )
            assertTrue(runCatching { transport(server, session).get("videos") }.exceptionOrNull() is IwaraHttpException)
            assertEquals(1, refreshes)
            assertEquals(2, server.requestCount)
        }
    }

    private fun transport(server: MockWebServer, session: VideoSessionManager) = OkHttpIwaraTransport(
        client = OkHttpClient(),
        baseUrl = server.url("/").toString(),
        accessTokenProvider = { session.accessTokenOrRefresh() },
        rejectedTokenRefresher = { session.accessTokenAfterRejection(it) },
    )
}

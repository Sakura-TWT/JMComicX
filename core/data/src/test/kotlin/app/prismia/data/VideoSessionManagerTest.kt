package app.prismia.data

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import app.prismia.foundation.ContentSource
import app.prismia.foundation.SourceErrorCategory
import app.prismia.foundation.SourceFailure
import app.prismia.foundation.SourceFailureException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class VideoSessionManagerTest {
    @Test
    fun expirySafetyWindowDoesNotOverflowAtLongBoundary() {
        val session = VideoSession("access", accessTokenExpiresAtEpochSeconds = Long.MAX_VALUE)
        assertTrue(session.isUsable(Long.MAX_VALUE - 31))
        assertEquals(false, session.isUsable(Long.MAX_VALUE - 30))
        assertEquals(false, session.isUsable(Long.MAX_VALUE))
    }

    @Test
    fun anonymousSessionNeverInvokesRefresher() = runBlocking {
        val manager = VideoSessionManager(InMemoryVideoSessionStore(), VideoSessionRefresher { error("must not refresh") })
        assertNull(manager.accessTokenOrRefresh())
        assertNull(manager.accessTokenAfterRejection("obsolete"))
    }

    @Test
    fun concurrentUnauthorizedResponsesForOneTokenOnlyRefreshOnce() = runBlocking {
        val started = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val manager = VideoSessionManager(
            InMemoryVideoSessionStore(VideoSession("old", "refresh", 1_000)),
            VideoSessionRefresher {
                calls.incrementAndGet()
                started.complete(Unit)
                release.await()
                VideoSession("renewed", "refresh", 2_000)
            },
            nowEpochSeconds = { 200 },
        )
        val first = async { manager.accessTokenAfterRejection("old") }
        started.await()
        val others = List(7) { async { manager.accessTokenAfterRejection("old") } }
        release.complete(Unit)
        assertEquals(List(8) { "renewed" }, (listOf(first) + others).awaitAll())
        assertEquals(1, calls.get())
    }

    @Test
    fun cancellationDoesNotEraseTheSessionAndAllowsALaterRefresh() = runBlocking {
        val original = VideoSession("old", "refresh", 100)
        val store = InMemoryVideoSessionStore(original)
        val manager = VideoSessionManager(store, VideoSessionRefresher { throw kotlinx.coroutines.CancellationException("cancel") }, { 200 })
        val failure = runCatching { manager.accessTokenOrRefresh() }.exceptionOrNull()
        assertTrue(failure is kotlinx.coroutines.CancellationException)
        assertEquals(original, store.load())
    }

    @Test
    fun sessionStringDoesNotExposeEitherCredential() {
        val text = VideoSession("test-access-secret", "test-refresh-secret").toString()
        assertTrue("test-access-secret" !in text && "test-refresh-secret" !in text)
    }

    @Test
    fun expiredSessionRefreshesAndPersistsTheRenewedSession() = runBlocking {
        val store = InMemoryVideoSessionStore(VideoSession("old", "refresh", 100))
        val refreshed = VideoSession("new", "refresh-2", 1_000)
        val manager = VideoSessionManager(
            store = store,
            refresher = VideoSessionRefresher { refreshed },
            nowEpochSeconds = { 200 },
        )

        assertEquals("new", manager.accessTokenOrRefresh())
        assertEquals(refreshed, store.load())
    }

    @Test
    fun concurrentExpiredRequestsShareOneRefreshOperation() = runBlocking {
        val calls = AtomicInteger()
        val manager = VideoSessionManager(
            store = InMemoryVideoSessionStore(VideoSession("old", "refresh", 100)),
            refresher = VideoSessionRefresher {
                calls.incrementAndGet()
                VideoSession("new", "refresh", 1_000)
            },
            nowEpochSeconds = { 200 },
        )

        val tokens = List(8) { async { manager.accessTokenOrRefresh() } }.awaitAll()

        assertEquals(List(8) { "new" }, tokens)
        assertEquals(1, calls.get())
    }

    @Test
    fun expiredSessionWithoutRefresherDoesNotReturnAnExpiredToken() = runBlocking {
        val manager = VideoSessionManager(
            store = InMemoryVideoSessionStore(VideoSession("old", accessTokenExpiresAtEpochSeconds = 100)),
            nowEpochSeconds = { 200 },
        )

        assertNull(manager.accessTokenOrRefresh())
    }

    @Test
    fun rejectedRefreshClearsPersistedSessionBeforeFailureEscapes() = runBlocking {
        val store = InMemoryVideoSessionStore(VideoSession("old", "refresh", 100))
        val manager = VideoSessionManager(
            store = store,
            refresher = VideoSessionRefresher {
                throw SourceFailureException(
                    SourceFailure(
                        source = ContentSource.IWARA,
                        operation = "auth.refresh",
                        category = SourceErrorCategory.AUTHENTICATION,
                        httpStatus = 401,
                        message = "refresh rejected",
                    ),
                )
            },
            nowEpochSeconds = { 200 },
        )

        val failure = runCatching { manager.accessTokenOrRefresh() }.exceptionOrNull()

        assertTrue(failure is SourceFailureException)
        assertNull(store.load())
        assertNull(manager.currentAccessToken(200))
    }

    @Test
    fun cloudflareChallengeDoesNotEraseAReusableSession() = runBlocking {
        val session = VideoSession("old", "refresh", 100)
        val store = InMemoryVideoSessionStore(session)
        val manager = VideoSessionManager(
            store = store,
            refresher = VideoSessionRefresher {
                throw SourceFailureException(
                    SourceFailure(
                        source = ContentSource.IWARA,
                        operation = "auth.refresh",
                        category = SourceErrorCategory.CLOUDFLARE,
                        httpStatus = 401,
                        message = "challenge",
                    ),
                )
            },
            nowEpochSeconds = { 200 },
        )

        runCatching { manager.accessTokenOrRefresh() }

        assertEquals(session, store.load())
    }
}

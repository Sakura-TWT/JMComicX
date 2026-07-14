package dev.jmx.client.core.session

import dev.jmx.client.core.result.JmxResult
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionManagerTest {
    @Test
    fun installsAvsCookieForApiHostAndFiltersByDomain() {
        val store = InMemoryCookieStore()
        val session = SessionManager(store)

        val result = session.installAvsCookie("https://api.test", "secret")

        assertTrue(result is JmxResult.Success)
        assertEquals(1, store.load("https://api.test/album".toHttpUrl()).size)
        assertEquals(0, store.load("https://other.test/album".toHttpUrl()).size)

        assertEquals(1, store.load("https://sub.api.test/album".toHttpUrl()).size)
        assertEquals("AVS", session.cookies().single().name)
        assertTrue(!session.cookies().single().hostOnly)
    }

    @Test
    fun cookieJarAlwaysAttachesAvsEvenWhenHostDiffers() {
        val store = InMemoryCookieStore()
        val session = SessionManager(store)
        session.installAvsCookie("https://login.test", "secret-avs")
        val jar = StoreBackedCookieJar(store)

        val cookies = jar.loadForRequest("https://other-api.test/favorite".toHttpUrl())

        assertEquals(1, cookies.size)
        assertEquals("AVS", cookies.single().name)
        assertEquals("secret-avs", cookies.single().value)
    }

    @Test
    fun syncsAvsCookieToExplicitApiHosts() {
        val store = InMemoryCookieStore()
        val session = SessionManager(store)
        session.installAvsCookie("https://api.test", "secret")

        val result = session.syncAvsCookieToHosts(listOf("https://first.test", "second.test/path"))

        assertTrue(result is JmxResult.Success)
        assertEquals(3, session.cookies().size)
        assertEquals(1, store.load("https://api.test/album".toHttpUrl()).size)
        assertEquals(1, store.load("https://first.test/album".toHttpUrl()).size)
        assertEquals(1, store.load("https://second.test/album".toHttpUrl()).size)
    }

    @Test
    fun syncsAvsCookieOnlyWhenPresent() {
        val store = InMemoryCookieStore()
        val session = SessionManager(store)

        val empty = session.syncAvsCookieToHostsIfPresent(listOf("https://first.test"))

        assertTrue(empty is JmxResult.Success)
        assertEquals(0, (empty as JmxResult.Success).value.size)
        assertEquals(0, session.cookies().size)

        session.installAvsCookie("https://api.test", "secret")
        val synced = session.syncAvsCookieToHostsIfPresent(listOf("https://first.test"))

        assertTrue(synced is JmxResult.Success)
        assertEquals(1, (synced as JmxResult.Success).value.size)
        assertEquals(1, store.load("https://first.test/album".toHttpUrl()).size)
    }

    @Test
    fun syncUsesMostRecentlyInstalledAvsCookie() {
        val store = InMemoryCookieStore()
        val session = SessionManager(store)
        session.installAvsCookie("https://old.test", "old-secret")
        session.installAvsCookie("https://new.test", "new-secret")

        val result = session.syncAvsCookieToHosts(listOf("https://third.test"))

        assertTrue(result is JmxResult.Success)
        assertEquals(
            "new-secret",
            store.load("https://third.test/album".toHttpUrl()).single().value
        )
    }

    @Test
    fun removesExpiredCookiesWhenLoading() {
        var now = 1_000L
        val store = InMemoryCookieStore(nowMillis = { now })
        val url = "https://api.test/".toHttpUrl()
        val cookie = Cookie.Builder()
            .name("AVS")
            .value("secret")
            .domain("api.test")
            .path("/")
            .expiresAt(2_000L)
            .build()
        store.save(url, listOf(cookie))

        assertEquals(1, store.load("https://api.test/album".toHttpUrl()).size)
        now = 3_000L
        assertEquals(0, store.load("https://api.test/album".toHttpUrl()).size)
        assertEquals(0, store.snapshot().size)
    }

    @Test
    fun expiredReplacementRemovesExistingCookie() {
        val now = 1_000L
        val store = InMemoryCookieStore(nowMillis = { now })
        val url = "https://api.test/".toHttpUrl()
        val cookie = Cookie.Builder()
            .name("AVS")
            .value("secret")
            .hostOnlyDomain("api.test")
            .path("/")
            .expiresAt(5_000L)
            .build()
        val deletion = Cookie.Builder()
            .name("AVS")
            .value("")
            .hostOnlyDomain("api.test")
            .path("/")
            .expiresAt(500L)
            .build()

        store.save(url, listOf(cookie))
        assertEquals(1, store.load("https://api.test/album".toHttpUrl()).size)
        store.save(url, listOf(deletion))

        assertEquals(0, store.load("https://api.test/album".toHttpUrl()).size)
        assertEquals(0, store.snapshot().size)
    }

    @Test
    fun replicatesAllSessionCookiesAcrossHosts() {
        val store = InMemoryCookieStore()
        val session = SessionManager(store)
        session.installAvsCookie("https://login.test", "avs-token")
        val extraUrl = "https://login.test/".toHttpUrl()
        store.save(
            extraUrl,
            listOf(
                Cookie.Builder()
                    .name("SESSION")
                    .value("sess")
                    .hostOnlyDomain("login.test")
                    .path("/")
                    .build()
            )
        )

        val result = session.replicateSessionCookiesToHosts(
            listOf("https://mirror-a.test", "https://mirror-b.test")
        )

        assertTrue(result is JmxResult.Success)
        assertEquals("avs-token", store.load("https://mirror-a.test/favorite".toHttpUrl()).single { it.name == "AVS" }.value)
        assertEquals("sess", store.load("https://mirror-a.test/favorite".toHttpUrl()).single { it.name == "SESSION" }.value)
        assertEquals("avs-token", store.load("https://mirror-b.test/watch_list".toHttpUrl()).single { it.name == "AVS" }.value)
    }

    @Test
    fun loadUsesOkHttpCookieMatchingRules() {
        val store = InMemoryCookieStore()
        val url = "https://api.test/".toHttpUrl()
        val hostOnlyCookie = Cookie.Builder()
            .name("host")
            .value("only")
            .hostOnlyDomain("api.test")
            .path("/")
            .build()
        val domainCookie = Cookie.Builder()
            .name("domain")
            .value("wide")
            .domain("api.test")
            .path("/")
            .build()

        store.save(url, listOf(hostOnlyCookie, domainCookie))

        assertEquals(2, store.load("https://api.test/album".toHttpUrl()).size)
        assertEquals(
            listOf("domain"),
            store.load("https://sub.api.test/album".toHttpUrl()).map { it.name }
        )
    }
}

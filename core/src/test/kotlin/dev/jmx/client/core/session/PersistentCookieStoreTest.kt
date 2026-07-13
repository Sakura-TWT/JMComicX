package dev.jmx.client.core.session

import dev.jmx.client.core.cache.FileKeyValueStore
import dev.jmx.client.core.cache.InMemoryKeyValueStore
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Files

class PersistentCookieStoreTest {
    @Test
    fun persistsCookiesAcrossInstances() {
        val file = Files.createTempDirectory("jmx-cookies").resolve("cookies.properties")
        val url = "https://api.test/".toHttpUrl()
        val cookie = Cookie.Builder()
            .name("AVS")
            .value("secret")
            .domain("api.test")
            .path("/")
            .httpOnly()
            .build()

        PersistentCookieStore(FileKeyValueStore(file)).save(url, listOf(cookie))

        val reloaded = PersistentCookieStore(FileKeyValueStore(file))
        assertEquals("secret", reloaded.load("https://api.test/album".toHttpUrl()).single().value)
        assertEquals(0, reloaded.load("https://other.test/album".toHttpUrl()).size)
    }

    @Test
    fun preservesHostOnlyCookieMatching() {
        val store = PersistentCookieStore(InMemoryKeyValueStore())
        val url = "https://api.test/".toHttpUrl()
        val cookie = Cookie.Builder()
            .name("host")
            .value("only")
            .hostOnlyDomain("api.test")
            .path("/")
            .build()

        store.save(url, listOf(cookie))

        assertEquals(1, store.load("https://api.test/album".toHttpUrl()).size)
        assertEquals(0, store.load("https://sub.api.test/album".toHttpUrl()).size)
    }

    @Test
    fun removesExpiredCookiesFromPersistentState() {
        var now = 1_000L
        val keyValueStore = InMemoryKeyValueStore()
        val store = PersistentCookieStore(keyValueStore, nowMillis = { now })
        val url = "https://api.test/".toHttpUrl()
        val cookie = Cookie.Builder()
            .name("AVS")
            .value("secret")
            .domain("api.test")
            .path("/")
            .expiresAt(2_000L)
            .build()

        store.save(url, listOf(cookie))
        assertEquals(1, store.snapshot().size)
        now = 3_000L

        assertEquals(0, store.snapshot().size)
        assertEquals(null, keyValueStore.getString("session.cookies"))
    }
}

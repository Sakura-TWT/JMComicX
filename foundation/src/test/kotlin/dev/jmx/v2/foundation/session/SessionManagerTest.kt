package dev.jmx.v2.foundation.session

import dev.jmx.v2.foundation.result.JmxResult
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
        assertEquals("AVS", session.cookies().single().name)
    }
}

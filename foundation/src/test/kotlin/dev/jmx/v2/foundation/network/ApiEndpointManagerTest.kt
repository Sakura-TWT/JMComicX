package dev.jmx.v2.foundation.network

import dev.jmx.v2.foundation.result.JmxResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiEndpointManagerTest {
    @Test
    fun normalizesHostsAndKeepsHttpsDefault() {
        val manager = ApiEndpointManager(listOf("example.com/path?x=1", "https://second.test/abc"))

        val endpoints = manager.all()

        assertEquals("https://example.com/", endpoints[0].url.toString())
        assertEquals("https://second.test/", endpoints[1].url.toString())
    }

    @Test
    fun replacesDomainListWithValidHostsOnly() {
        val manager = ApiEndpointManager(listOf("old.test"))

        val result = manager.replaceAll(listOf("bad url", "https://new.test"))

        assertTrue(result is JmxResult.Success)
        assertEquals("https://new.test/", manager.all().single().url.toString())
    }
}

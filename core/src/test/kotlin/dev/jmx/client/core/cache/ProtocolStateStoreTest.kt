package dev.jmx.client.core.cache

import dev.jmx.client.core.protocol.JmxProtocolConstants
import org.junit.Assert.assertEquals
import org.junit.Test

class ProtocolStateStoreTest {
    @Test
    fun returnsFallbacksWhenStoreIsEmpty() {
        val store = ProtocolStateStore(InMemoryKeyValueStore())

        assertEquals(JmxProtocolConstants.DefaultApiVersion, store.apiVersion())
        assertEquals(JmxProtocolConstants.DefaultApiHosts, store.apiHosts())
    }

    @Test
    fun persistsVersionAndDistinctHosts() {
        val store = ProtocolStateStore(InMemoryKeyValueStore())

        store.updateApiVersion("2.1.0")
        store.updateApiHosts(listOf(" https://a.test ", "https://a.test", "b.test"))

        assertEquals("2.1.0", store.apiVersion())
        assertEquals(listOf("https://a.test", "b.test"), store.apiHosts())
    }
}

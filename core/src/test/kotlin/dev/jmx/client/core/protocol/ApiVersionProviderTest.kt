package dev.jmx.client.core.protocol

import dev.jmx.client.core.cache.InMemoryKeyValueStore
import dev.jmx.client.core.cache.ProtocolStateStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiVersionProviderTest {
    @Test
    fun updatesOnlyValidVersion() {
        val provider = MutableApiVersionProvider("1.0.0")

        assertTrue(provider.update("2.0.26"))
        assertEquals("2.0.26", provider.current())
        assertFalse(provider.update("bad"))
        assertEquals("2.0.26", provider.current())
    }

    @Test
    fun storedProviderPersistsOnlyValidVersions() {
        val stateStore = ProtocolStateStore(InMemoryKeyValueStore())
        val provider = StoredApiVersionProvider(stateStore)

        assertTrue(provider.update("2.1.0"))
        assertEquals("2.1.0", provider.current())
        assertEquals("2.1.0", stateStore.apiVersion())
        assertFalse(provider.update("bad"))
        assertEquals("2.1.0", provider.current())
        assertEquals("2.1.0", stateStore.apiVersion())
    }
}

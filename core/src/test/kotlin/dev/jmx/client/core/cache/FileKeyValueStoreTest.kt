package dev.jmx.client.core.cache

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.file.Files

class FileKeyValueStoreTest {
    @Test
    fun persistsValuesAcrossInstances() {
        val file = Files.createTempDirectory("jmx-store").resolve("state.properties")

        FileKeyValueStore(file).putString("token", "value")

        assertEquals("value", FileKeyValueStore(file).getString("token"))
    }

    @Test
    fun removesValueWhenNullIsStored() {
        val file = Files.createTempDirectory("jmx-store").resolve("state.properties")
        val store = FileKeyValueStore(file)

        store.putString("key", "value")
        store.putString("key", null)

        assertNull(FileKeyValueStore(file).getString("key"))
    }

    @Test
    fun supportsProtocolStatePersistence() {
        val file = Files.createTempDirectory("jmx-store").resolve("protocol.properties")
        val state = ProtocolStateStore(FileKeyValueStore(file))

        state.updateApiVersion("2.4.0")
        state.updateApiHosts(listOf("https://a.test", "https://b.test"))

        val reloaded = ProtocolStateStore(FileKeyValueStore(file))
        assertEquals("2.4.0", reloaded.apiVersion())
        assertEquals(listOf("https://a.test", "https://b.test"), reloaded.apiHosts())
    }
}

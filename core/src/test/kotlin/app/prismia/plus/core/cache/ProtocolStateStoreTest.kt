package app.prismia.plus.core.cache

import app.prismia.plus.core.protocol.JmxProtocolConstants
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

    @Test
    fun upgradesStaleVersionBelowBuiltInDefault() {
        val store = ProtocolStateStore(InMemoryKeyValueStore())

        store.updateApiVersion("2.0.27")

        assertEquals(JmxProtocolConstants.DefaultApiVersion, store.apiVersion())
    }

    @Test
    fun keepsServerAdvertisedVersionNewerThanBuiltInDefault() {
        val store = ProtocolStateStore(InMemoryKeyValueStore())

        store.updateApiVersion("2.0.31")

        assertEquals("2.0.31", store.apiVersion())
    }

    @Test
    fun migratesLegacyHostsWithoutGenerationMarkerIntoDefaults() {
        val backing = InMemoryKeyValueStore()
        // 模拟旧版本写入的持久化域名（没有代际标记）
        backing.putString("protocol.api.hosts", "https://retired.test")
        val store = ProtocolStateStore(backing)

        val hosts = store.apiHosts()

        assertEquals(
            listOf("https://retired.test") + JmxProtocolConstants.DefaultApiHosts,
            hosts,
        )
        // 迁移结果已写入代际标记，再次读取保持稳定且不再追加
        assertEquals(hosts, store.apiHosts())
    }

    @Test
    fun keepsCurrentGenerationHostsUntouched() {
        val store = ProtocolStateStore(InMemoryKeyValueStore())

        store.updateApiHosts(listOf("https://remote.test"))

        assertEquals(listOf("https://remote.test"), store.apiHosts())
    }
}

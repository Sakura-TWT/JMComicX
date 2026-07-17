package dev.jmx.client.core.network

import dev.jmx.client.core.cache.InMemoryKeyValueStore
import dev.jmx.client.core.cache.ProtocolStateStore
import dev.jmx.client.core.result.JmxResult
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

    @Test
    fun persistsReplacedHostsWhenProtocolStateStoreIsProvided() {
        val stateStore = ProtocolStateStore(InMemoryKeyValueStore())
        val manager = ApiEndpointManager(
            initialHosts = listOf("old.test"),
            protocolStateStore = stateStore
        )

        manager.replaceAll(listOf("https://new.test/path", "second.test"))

        assertEquals(listOf("https://new.test/", "https://second.test/"), stateStore.apiHosts())
    }

    @Test
    fun currentSkipsEndpointDuringBackoffWindow() {
        var now = 1_000L
        val manager = ApiEndpointManager(
            initialHosts = listOf("https://first.test", "https://second.test"),
            maxFailuresBeforeDemote = 1,
            nowMillis = { now }
        )
        val first = manager.all()[0].url

        manager.markFailure(first, "timeout")

        val currentDuringBackoff = manager.current()
        assertTrue(currentDuringBackoff is JmxResult.Success)
        assertEquals("https://second.test/", (currentDuringBackoff as JmxResult.Success).value.toString())
        val demoted = manager.all()[0]
        assertEquals(1, demoted.failureCount)
        assertEquals(1, demoted.consecutiveFailureCount)
        assertEquals(2_000L, demoted.unavailableUntilMillis)
        assertTrue(demoted.healthScore(now) < manager.all()[1].healthScore(now))

        now = 2_000L
        val currentAfterBackoff = manager.current()
        assertTrue(currentAfterBackoff is JmxResult.Success)
        assertEquals("https://second.test/", (currentAfterBackoff as JmxResult.Success).value.toString())
    }

    @Test
    fun successRestoresEndpointHealth() {
        var now = 1_000L
        val manager = ApiEndpointManager(
            initialHosts = listOf("https://first.test", "https://second.test"),
            maxFailuresBeforeDemote = 1,
            nowMillis = { now }
        )
        val first = manager.all()[0].url

        manager.markFailure(first, "timeout")
        now = 3_000L
        manager.markSuccess(first)

        val restored = manager.all()[0]
        assertEquals(1, restored.successCount)
        assertEquals(0, restored.failureCount)
        assertEquals(0, restored.consecutiveFailureCount)
        assertEquals(3_000L, restored.lastSuccessAtMillis)
        assertEquals(null, restored.unavailableUntilMillis)
        assertEquals(null, restored.lastFailureMessage)
        assertEquals("https://first.test/", (manager.current() as JmxResult.Success).value.toString())
    }

    @Test
    fun successTracksLatencyWithWeightedAverage() {
        var now = 1_000L
        val manager = ApiEndpointManager(
            initialHosts = listOf("https://first.test", "https://second.test"),
            nowMillis = { now }
        )
        val first = manager.all()[0].url

        manager.markSuccess(first, latencyMillis = 800)
        now = 2_000L
        manager.markSuccess(first, latencyMillis = 1600)

        val endpoint = manager.all()[0]
        assertEquals(2, endpoint.successCount)
        assertEquals(1600L, endpoint.lastLatencyMillis)
        assertEquals(900L, endpoint.averageLatencyMillis)
        assertEquals(2_000L, endpoint.lastSuccessAtMillis)
        assertTrue(endpoint.healthScore(now) < manager.all()[1].healthScore(now) + 10)
    }

    @Test
    fun successfulAutomaticEndpointBecomesColdStartPreferred() {
        val stateStore = ProtocolStateStore(InMemoryKeyValueStore())
        stateStore.updateApiHosts(listOf("https://first.test", "https://second.test"))
        val manager = ApiEndpointManager(
            initialHosts = listOf("https://first.test", "https://second.test"),
            protocolStateStore = stateStore,
        )
        val second = manager.all()[1].url

        manager.markSuccess(second, latencyMillis = 120)

        val restored = ApiEndpointManager(protocolStateStore = stateStore)
        assertEquals("https://second.test/", (restored.current() as JmxResult.Success).value.toString())
    }

    @Test
    fun remoteRefreshPreservesHealthForUnchangedEndpoints() {
        val manager = ApiEndpointManager(listOf("https://first.test", "https://second.test"))
        val second = manager.all()[1].url
        manager.markSuccess(second, latencyMillis = 140)

        manager.replaceAll(listOf("https://first.test", "https://second.test", "https://third.test"))

        val preserved = manager.all().single { it.url.toString() == "https://second.test/" }
        assertEquals(1, preserved.successCount)
        assertEquals(140L, preserved.averageLatencyMillis)
        assertEquals("https://second.test/", (manager.current() as JmxResult.Success).value.toString())
    }

    @Test
    fun manualEndpointOverridesAutomaticHealthSelectionAndPersists() {
        val stateStore = ProtocolStateStore(InMemoryKeyValueStore())
        val manager = ApiEndpointManager(
            initialHosts = listOf("https://first.test", "https://second.test"),
            protocolStateStore = stateStore
        )

        val selected = manager.useManualEndpoint("manual.test/path")

        assertTrue(selected is JmxResult.Success)
        assertEquals("https://manual.test/", (selected as JmxResult.Success).value.toString())
        assertEquals("https://manual.test/", (manager.current() as JmxResult.Success).value.toString())
        assertEquals("https://manual.test/", manager.all().last().url.toString())
        assertEquals("https://manual.test/", stateStore.manualApiHost())
        val reloaded = ApiEndpointManager(
            initialHosts = listOf("https://first.test"),
            protocolStateStore = stateStore
        )
        assertEquals("https://manual.test/", (reloaded.current() as JmxResult.Success).value.toString())
        assertEquals("https://manual.test/", reloaded.all().last().url.toString())
        assertTrue(reloaded.selection() is ApiEndpointSelection.Manual)
    }

    @Test
    fun autoSelectionCanBeRestoredAfterManualEndpoint() {
        val stateStore = ProtocolStateStore(InMemoryKeyValueStore())
        stateStore.updateApiHosts(listOf("https://first.test", "https://second.test"))
        val manager = ApiEndpointManager(
            initialHosts = listOf("https://first.test", "https://second.test"),
            protocolStateStore = stateStore
        )

        manager.useManualEndpoint("manual.test")
        manager.useAutoSelection()

        assertEquals(null, stateStore.manualApiHost())
        assertTrue(manager.selection() is ApiEndpointSelection.Auto)
        assertEquals("https://first.test/", (manager.current() as JmxResult.Success).value.toString())
        assertTrue(manager.all().none { it.url.toString() == "https://manual.test/" })
    }

    @Test
    fun remoteRefreshDoesNotClearManualEndpoint() {
        val stateStore = ProtocolStateStore(InMemoryKeyValueStore())
        val manager = ApiEndpointManager(
            initialHosts = listOf("https://first.test"),
            protocolStateStore = stateStore
        )
        manager.useManualEndpoint("manual.test")

        manager.replaceAll(listOf("https://fresh.test"))

        assertEquals(listOf("https://fresh.test/"), stateStore.apiHosts())
        assertEquals("https://manual.test/", stateStore.manualApiHost())
        assertEquals("https://manual.test/", (manager.current() as JmxResult.Success).value.toString())
        assertEquals(
            listOf("https://fresh.test/", "https://manual.test/"),
            manager.all().map { it.url.toString() }
        )
        manager.useAutoSelection()
        assertEquals("https://fresh.test/", (manager.current() as JmxResult.Success).value.toString())
        assertEquals(listOf("https://fresh.test/"), manager.all().map { it.url.toString() })
    }

    @Test
    fun manualEndpointHealthIsTrackedWithoutPersistingToAutoHosts() {
        var now = 1_000L
        val stateStore = ProtocolStateStore(InMemoryKeyValueStore())
        stateStore.updateApiHosts(listOf("https://auto.test"))
        val manager = ApiEndpointManager(
            initialHosts = listOf("https://auto.test"),
            protocolStateStore = stateStore,
            nowMillis = { now }
        )
        val manual = (manager.useManualEndpoint("manual.test") as JmxResult.Success).value

        manager.markSuccess(manual, latencyMillis = 123)
        now = 2_000L
        manager.markFailure(manual, "manual failed")

        val endpoint = manager.all().single { it.url.toString() == "https://manual.test/" }
        assertEquals(1, endpoint.successCount)
        assertEquals(1, endpoint.failureCount)
        assertEquals(1, endpoint.consecutiveFailureCount)
        assertEquals(123L, endpoint.lastLatencyMillis)
        assertEquals("manual failed", endpoint.lastFailureMessage)
        assertEquals(listOf("https://auto.test"), stateStore.apiHosts())
    }
}

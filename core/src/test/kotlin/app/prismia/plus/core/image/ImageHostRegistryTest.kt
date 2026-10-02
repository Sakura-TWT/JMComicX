package app.prismia.plus.core.image

import app.prismia.plus.core.cache.InMemoryKeyValueStore
import app.prismia.plus.core.cache.ProtocolStateStore
import app.prismia.plus.core.network.HostHealth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageHostRegistryTest {
    @Test
    fun keepsGivenOrderAndStripsScheme() {
        val registry = ImageHostRegistry(
            initialHosts = listOf("https://first.test", "second.test/", "  "),
        )

        assertEquals(listOf("first.test", "second.test"), registry.all().map { it.host })
        assertEquals("https://first.test", registry.current())
    }

    /** 失败的那台要在退避窗口内被跳过，否则每张图都要先撞一次同样的错误。 */
    @Test
    fun demotesFailedHostDuringBackoffWindow() {
        var now = 10_000L
        val registry = ImageHostRegistry(
            initialHosts = listOf("first.test", "second.test"),
            maxFailuresBeforeDemote = 1,
            nowMillis = { now },
        )

        registry.markFailure("first.test", "HTTP 503")

        assertEquals("second.test", registry.currentHost())
        assertEquals(listOf("second.test"), registry.candidates(setOf("first.test")))
        val demoted = registry.all().single { it.host == "first.test" }
        assertEquals(1, demoted.failureCount)
        assertTrue(demoted.unavailableUntilMillis!! > now)
    }

    /**
     * 退避到期后那台仍要能被选回来。
     *
     * 这是 [HostHealth] 存在的理由：罚分只在成功时清零，而一台被降级的机器不会再被选中，
     * 于是永远没有成功的机会——没有衰减就等于永久拉黑，最后所有机器都会被逐个拉黑。
     */
    @Test
    fun recoversAfterPenaltyDecays() {
        var now = 10_000L
        val registry = ImageHostRegistry(
            initialHosts = listOf("first.test", "second.test"),
            maxFailuresBeforeDemote = 1,
            nowMillis = { now },
        )
        // 让 first 连续失败，罚分足够压过 second。
        repeat(3) { registry.markFailure("first.test", "HTTP 503") }
        registry.markSuccess("second.test", latencyMillis = 400L)
        assertEquals("second.test", registry.currentHost())

        val firstBeforeDecay = registry.all().single { it.host == "first.test" }
        val scoreBeforeDecay = firstBeforeDecay.healthScore(firstBeforeDecay.unavailableUntilMillis!! + 1)

        // 宽限期加上五个半衰期之后，旧账应当基本清空。
        now += HostHealth.PENALTY_GRACE_MILLIS + HostHealth.PENALTY_HALF_LIFE_MILLIS * 5
        val firstAfterDecay = registry.all().single { it.host == "first.test" }
        val scoreAfterDecay = firstAfterDecay.healthScore(now)

        assertTrue("$scoreAfterDecay 应当高于 $scoreBeforeDecay", scoreAfterDecay > scoreBeforeDecay)
        // 满额衰减（宽限期 + 5 个半衰期）后旧账只剩个零头，足以重新参与选路。
        assertTrue("衰减后仍有 ${100 - scoreAfterDecay} 分罚分", scoreAfterDecay >= 95)
    }

    @Test
    fun averagesLatencyWithExponentialWeighting() {
        val registry = ImageHostRegistry(initialHosts = listOf("first.test"))

        registry.markSuccess("first.test", latencyMillis = 800L)
        registry.markSuccess("first.test", latencyMillis = 800L)

        val host = registry.all().single()
        assertEquals(800L, host.averageLatencyMillis)
        assertEquals(800L, host.lastLatencyMillis)
        assertEquals(2, host.successCount)
    }

    /** 手动钉住的线路不参与自动选路，也不允许换机——否则"手动选线路"这个设置毫无意义。 */
    @Test
    fun manualHostShortCircuitsSelectionAndFailover() {
        val registry = ImageHostRegistry(initialHosts = listOf("first.test", "second.test"))

        registry.useManualHost("https://pinned.test")

        assertEquals("https://pinned.test", registry.current())
        assertEquals("https://pinned.test", registry.manualHost())
        assertEquals(listOf("pinned.test"), registry.candidates())
        assertEquals(emptyList<String>(), registry.candidates(setOf("pinned.test")))
        assertTrue(registry.knows("pinned.test"))

        registry.markFailure("pinned.test", "HTTP 503")
        assertEquals("https://pinned.test", registry.current())
    }

    @Test
    fun clearingManualHostReturnsToAutomaticSelection() {
        val stateStore = ProtocolStateStore(InMemoryKeyValueStore())
        val registry = ImageHostRegistry(
            initialHosts = listOf("first.test", "second.test"),
            protocolStateStore = stateStore,
        )
        registry.useManualHost("second.test")

        registry.useManualHost(null)

        assertNull(registry.manualHost())
        assertNull(stateStore.manualImageHost())
        assertEquals("first.test", registry.currentHost())
    }

    /** 冷启动的第一张封面就该打在上次最优的那台上，而不是内置表的第一台。 */
    @Test
    fun persistsPreferredHostAndHoistsItOnNextStart() {
        val keyValueStore = InMemoryKeyValueStore()
        val stateStore = ProtocolStateStore(keyValueStore)
        val first = ImageHostRegistry(
            initialHosts = listOf("first.test", "second.test"),
            protocolStateStore = stateStore,
            maxFailuresBeforeDemote = 1,
        )
        first.markFailure("first.test", "HTTP 503")
        first.markSuccess("second.test", latencyMillis = 120L)
        assertEquals("second.test", stateStore.preferredAutoImageHost())

        val restarted = ImageHostRegistry(
            initialHosts = listOf("first.test", "second.test"),
            protocolStateStore = ProtocolStateStore(keyValueStore),
        )

        assertEquals("second.test", restarted.currentHost())
        assertEquals(listOf("second.test", "first.test"), restarted.all().map { it.host })
    }

    /** /setting 下发的 img_host 是服务端认为当前该用的那台，要并入线路表并排在内置表前面。 */
    @Test
    fun remembersRemoteHostAheadOfBuiltInTable() {
        val keyValueStore = InMemoryKeyValueStore()
        val registry = ImageHostRegistry(
            initialHosts = listOf("first.test"),
            protocolStateStore = ProtocolStateStore(keyValueStore),
        )

        registry.rememberRemoteHost("https://remote.test/")

        assertTrue(registry.knows("remote.test"))
        val restarted = ImageHostRegistry(
            initialHosts = listOf("first.test"),
            protocolStateStore = ProtocolStateStore(keyValueStore),
        )
        assertEquals(listOf("remote.test", "first.test"), restarted.all().map { it.host })
    }

    /**
     * /chapter 给的 data_original_domain 要能并进线路表（选路拦截器只接管认识的主机），
     * 但不该落盘：那是某一章的临时地址，把它当成"上次最优的那台"会带到下次冷启动。
     */
    @Test
    fun remembersChapterHostWithoutPersistingIt() {
        val keyValueStore = InMemoryKeyValueStore()
        val registry = ImageHostRegistry(
            initialHosts = listOf("first.test"),
            protocolStateStore = ProtocolStateStore(keyValueStore),
        )

        registry.rememberHost("https://chapter.test/")
        registry.rememberHost("chapter.test")

        assertTrue(registry.knows("chapter.test"))
        assertEquals(listOf("first.test", "chapter.test"), registry.all().map { it.host })
        assertNull(ProtocolStateStore(keyValueStore).remoteImageHost())
        val restarted = ImageHostRegistry(
            initialHosts = listOf("first.test"),
            protocolStateStore = ProtocolStateStore(keyValueStore),
        )
        assertEquals(listOf("first.test"), restarted.all().map { it.host })
    }

    @Test
    fun ignoresBlankHostUpdates() {
        val registry = ImageHostRegistry(initialHosts = listOf("first.test"))

        registry.rememberRemoteHost("   ")
        registry.markSuccess("")
        registry.markFailure("  ", "boom")

        assertEquals(listOf("first.test"), registry.all().map { it.host })
        assertEquals(0, registry.all().single().successCount)
    }

    @Test
    fun successClearsBackoffAndFailureCounters() {
        var now = 5_000L
        val registry = ImageHostRegistry(
            initialHosts = listOf("first.test"),
            maxFailuresBeforeDemote = 1,
            nowMillis = { now },
        )
        registry.markFailure("first.test", "HTTP 500")

        now += 10_000L
        registry.markSuccess("first.test", latencyMillis = 90L)

        val host = registry.all().single()
        assertEquals(0, host.failureCount)
        assertEquals(0, host.consecutiveFailureCount)
        assertNull(host.unavailableUntilMillis)
        assertNull(host.lastFailureMessage)
        assertTrue(host.isAvailableAt(now))
    }
}

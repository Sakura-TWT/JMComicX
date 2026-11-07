package dev.jmx.client

import org.junit.Assert.*
import org.junit.Test

class EndpointPresentationTest {
    private val api = "https://a-very-long-route-host-name-for-layout-reproduction.example.invalid"
    private val image = "https://a-very-long-image-source-host-name-for-layout-reproduction.example.invalid"
    private fun state() = EndpointSettingsState(
        automatic = false,
        selectedApiUrl = api,
        activeApiUrl = api,
        selectedImageUrl = image,
        apiEndpoints = listOf(EndpointProbeUi(api, 123L, true)),
        imageEndpoints = listOf(EndpointProbeUi(image, 4200L, true)),
    )

    @Test fun displayNamePreservesCompleteLongHostAndPortPath() {
        val value = "http://very-long-name.example.invalid:8443/api/v2/"
        assertEquals("very-long-name.example.invalid:8443/api/v2", value.endpointDisplayName())
        assertEquals(api.removePrefix("https://"), api.endpointDisplayName())
        assertEquals(image.removePrefix("https://"), image.endpointDisplayName())
        assertEquals("[2001:db8::1]:443", "https://[2001:db8::1]:443/".endpointDisplayName())
        assertEquals("例子.example.invalid", "https://例子.example.invalid/".endpointDisplayName())
        assertEquals("", "".endpointDisplayName())
    }

    @Test fun statusSeparatesPendingFailureAndMissingLatency() {
        assertEquals(EndpointProbeStatus.Pending, EndpointProbeUi(api).presentation().status)
        assertEquals("探测中", EndpointProbeUi(api).presentation().statusLabel)
        assertEquals("探测失败", EndpointProbeUi(api, 123L, false).presentation().statusLabel)
        assertEquals("—", EndpointProbeUi(api, 123L, false).presentation().latencyLabel)
        assertEquals("已连通", EndpointProbeUi(api, success = true).presentation().statusLabel)
        assertEquals("耗时未知", EndpointProbeUi(api, success = true).presentation().latencyLabel)
    }

    @Test fun measuredStatusUsesExistingNormalSlowBoundaryAndExactLatency() {
        listOf(0L, 300L, 800L).forEach { latency ->
            assertEquals(EndpointProbeStatus.Normal, EndpointProbeUi(api, latency, true).presentation().status)
            assertEquals("$latency ms", EndpointProbeUi(api, latency, true).presentation().latencyLabel)
        }
        listOf(801L, 1500L, 4200L, Long.MAX_VALUE).forEach { latency ->
            assertEquals(EndpointProbeStatus.Slow, EndpointProbeUi(api, latency, true).presentation().status)
            assertEquals("$latency ms", EndpointProbeUi(api, latency, true).presentation().latencyLabel)
        }
    }

    @Test fun latencySignalGradesUseMeasuredThresholdsWithoutInventingUnknownSpeed() {
        listOf(0L to 4, 300L to 4, 301L to 3, 800L to 3, 801L to 2, 1500L to 2, 1501L to 1, Long.MAX_VALUE to 1).forEach { (latency, bars) ->
            val result = EndpointProbeUi(api, latency, true).presentation()
            assertEquals(bars, result.signalBars)
            assertEquals("探测延迟等级 $bars/4", result.signalDescription)
            assertEquals(when (bars) { 4, 3 -> EndpointSignalTone.Good; 2 -> EndpointSignalTone.Caution; else -> EndpointSignalTone.Poor }, result.signalTone)
        }
        listOf(EndpointProbeUi(api), EndpointProbeUi(api, success = true), EndpointProbeUi(api, -1L, true), EndpointProbeUi(api, 123L, false)).forEach { probe ->
            assertEquals(0, probe.presentation().signalBars)
            assertNull(probe.presentation().signalDescription)
        }
        assertEquals("耗时未知", EndpointProbeUi(api, -1L, true).presentation().latencyLabel)
        assertEquals(EndpointSignalTone.Poor, EndpointProbeUi(api, success = false).presentation().signalTone)
    }

    @Test fun entrySummaryDistinguishesBothIndependentSelectionModes() {
        assertEquals("API 手动选择 · 图片 手动选择", state().entrySummary())
        assertEquals("API 自动选择 · 图片 手动选择", state().copy(automatic = true).entrySummary())
        assertEquals("API 手动选择 · 图片 跟随服务", state().copy(selectedImageUrl = null).entrySummary())
        assertEquals("API 自动选择 · 图片 跟随服务", state().copy(automatic = true, selectedImageUrl = null).entrySummary())
    }

    @Test fun automaticSummaryNeverLabelsManualRouteAsAutomaticCurrent() {
        assertEquals("根据线路健康状态自动切换", state().automaticApiSummary())
        assertEquals("当前使用\n" + api.endpointDisplayName(), state().copy(automatic = true).automaticApiSummary())
        assertEquals("根据线路健康状态自动切换", state().copy(automatic = true, activeApiUrl = null).automaticApiSummary())
    }

    @Test fun selectionSnapshotRefreshKeepsProbeResultsInsteadOfReturningToPending() {
        val before = state()
        val snapshot = before.copy(
            automatic = true,
            selectedApiUrl = null,
            selectedImageUrl = null,
            apiEndpoints = listOf(EndpointProbeUi(api)),
            imageEndpoints = listOf(EndpointProbeUi(image)),
        )
        val after = before.withSelectionSnapshot(snapshot)
        assertTrue(after.automatic)
        assertNull(after.selectedApiUrl)
        assertNull(after.selectedImageUrl)
        assertSame(before.apiEndpoints, after.apiEndpoints)
        assertSame(before.imageEndpoints, after.imageEndpoints)
    }

    @Test fun reprobingApiNeverChangesManualOrImageSelection() {
        val before = state()
        val pending = before.withApiProbe(EndpointProbeUi(api), api)
        val failed = pending.withApiProbe(EndpointProbeUi(api, success = false), api)
        listOf(pending, failed).forEach { after ->
            assertFalse(after.automatic)
            assertEquals(api, after.selectedApiUrl)
            assertEquals(image, after.selectedImageUrl)
            assertTrue(after.isApiSelected(api))
            assertSame(before.imageEndpoints, after.imageEndpoints)
        }
    }

    @Test fun reprobingImageNeverChangesApiModeOrActiveRoute() {
        val before = state().copy(automatic = true)
        val after = before.withImageProbe(EndpointProbeUi(image, success = false))
        assertTrue(after.automatic)
        assertEquals(before.selectedApiUrl, after.selectedApiUrl)
        assertEquals(before.activeApiUrl, after.activeApiUrl)
        assertEquals(image, after.selectedImageUrl)
        assertTrue(after.isImageSelected(image))
        assertFalse(after.isApiSelected(api))
        assertSame(before.apiEndpoints, after.apiEndpoints)
    }

    @Test fun unknownProbeUrlCannotReplaceAnExistingChoice() {
        val before = state()
        assertEquals(before, before.withApiProbe(EndpointProbeUi("https://unknown.invalid", success = false), api))
        assertEquals(before, before.withImageProbe(EndpointProbeUi("https://unknown.invalid", success = false)))
    }
}

package dev.jmx.client

internal enum class EndpointProbeStatus { Pending, Normal, Slow, Failed }
internal enum class EndpointSignalTone { Neutral, Good, Caution, Poor }

internal data class EndpointProbePresentation(
    val status: EndpointProbeStatus,
    val statusLabel: String,
    val latencyLabel: String,
    val signalBars: Int = 0,
    val signalTone: EndpointSignalTone = EndpointSignalTone.Neutral,
) {
    // A visual latency grade, not radio strength or measured transfer throughput.
    val signalDescription: String? get() = if (signalBars > 0) "探测延迟等级 $signalBars/4" else null
}

internal fun EndpointProbeUi.presentation(): EndpointProbePresentation = when {
    success == null -> EndpointProbePresentation(EndpointProbeStatus.Pending, "探测中", "—")
    !success -> EndpointProbePresentation(EndpointProbeStatus.Failed, "探测失败", "—", signalTone = EndpointSignalTone.Poor)
    latencyMillis == null || latencyMillis < 0L -> EndpointProbePresentation(EndpointProbeStatus.Normal, "已连通", "耗时未知")
    latencyMillis <= 300L -> EndpointProbePresentation(EndpointProbeStatus.Normal, "正常", "$latencyMillis ms", 4, EndpointSignalTone.Good)
    latencyMillis <= 800L -> EndpointProbePresentation(EndpointProbeStatus.Normal, "正常", "$latencyMillis ms", 3, EndpointSignalTone.Good)
    latencyMillis <= 1_500L -> EndpointProbePresentation(EndpointProbeStatus.Slow, "较慢", "$latencyMillis ms", 2, EndpointSignalTone.Caution)
    else -> EndpointProbePresentation(EndpointProbeStatus.Slow, "较慢", "$latencyMillis ms", 1, EndpointSignalTone.Poor)
}

internal fun String.endpointDisplayName(): String = removePrefix("https://")
    .removePrefix("http://")
    .trimEnd('/')

internal fun EndpointSettingsState.entrySummary(): String =
    "API " + (if (automatic) "自动选择" else "手动选择") +
        " · 图片 " + (if (selectedImageUrl == null) "跟随服务" else "手动选择")

internal fun EndpointSettingsState.automaticApiSummary(): String =
    if (automatic && activeApiUrl != null) "当前使用\n" + activeApiUrl.endpointDisplayName()
    else "根据线路健康状态自动切换"

internal fun EndpointSettingsState.isApiSelected(url: String): Boolean =
    !automatic && selectedApiUrl == url

internal fun EndpointSettingsState.isImageSelected(url: String): Boolean = selectedImageUrl == url

internal fun EndpointSettingsState.withSelectionSnapshot(snapshot: EndpointSettingsState): EndpointSettingsState =
    snapshot.copy(apiEndpoints = apiEndpoints, imageEndpoints = imageEndpoints)

internal fun EndpointSettingsState.withApiProbe(probe: EndpointProbeUi, activeUrl: String?): EndpointSettingsState =
    copy(
        activeApiUrl = activeUrl,
        apiEndpoints = apiEndpoints.map { if (it.url == probe.url) probe else it },
    )

internal fun EndpointSettingsState.withImageProbe(probe: EndpointProbeUi): EndpointSettingsState =
    copy(imageEndpoints = imageEndpoints.map { if (it.url == probe.url) probe else it })

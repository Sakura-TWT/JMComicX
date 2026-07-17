package dev.jmx.client

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.jmx.client.core.result.JmxResult
import java.util.Locale
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.Check
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

@Composable
internal fun SettingsScreen(
    innerPadding: PaddingValues,
    repository: AppSettingsRepository,
    autoCheckIn: Boolean,
    onAutoCheckInChanged: (Boolean) -> Unit,
    autoCheckUpdates: Boolean,
    checkingForUpdates: Boolean,
    currentVersion: String,
    onAutoCheckUpdatesChanged: (Boolean) -> Unit,
    onCheckForUpdates: () -> Unit,
    onImageHostChanged: () -> Unit,
) {
    var showClearConfirmation by remember { mutableStateOf(false) }
    var clearing by remember { mutableStateOf(false) }
    var clearMessage by remember { mutableStateOf<String?>(null) }
    var showEndpoints by remember { mutableStateOf(false) }
    var cacheSize by remember { mutableStateOf<Long?>(null) }
    var cacheRefreshKey by remember { mutableIntStateOf(0) }
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(repository, cacheRefreshKey) {
        cacheSize = runCatching { repository.imageCacheSizeBytes() }.getOrNull()
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MiuixTheme.colorScheme.surface),
        contentPadding = PaddingValues(
            top = innerPadding.calculateTopPadding() + 8.dp,
            bottom = innerPadding.calculateBottomPadding() + 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "settings-service") {
            Column {
                SmallTitle(text = "账户与服务")
                Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                    SwitchPreference(
                        title = "自动签到",
                        summary = "登录状态下启动应用时自动完成当日签到",
                        checked = autoCheckIn,
                        onCheckedChange = onAutoCheckInChanged,
                    )
                    ArrowPreference(
                        title = "线路与图源",
                        summary = "查看测速结果并选择 API 线路和图片源",
                        onClick = { showEndpoints = true },
                    )
                }
            }
        }
        item(key = "settings-storage") {
            Column {
                SmallTitle(text = "存储")
                Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                    ArrowPreference(
                        title = "清理图片缓存",
                        summary = "清除封面和漫画图片的内存及磁盘缓存",
                        endActions = {
                            Text(
                                text = cacheSize?.let(::formatByteCount) ?: "计算中",
                                style = MiuixTheme.textStyles.body2,
                                color = MiuixTheme.colorScheme.onSurfaceVariantActions,
                            )
                        },
                        onClick = { showClearConfirmation = true },
                    )
                }
            }
        }
        item(key = "settings-update") {
            Column {
                SmallTitle(text = "更新")
                Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                    SwitchPreference(
                        title = "检测更新",
                        summary = "启动应用后自动检查是否有新版本",
                        checked = autoCheckUpdates,
                        onCheckedChange = onAutoCheckUpdatesChanged,
                    )
                    ArrowPreference(
                        title = "获取更新",
                        summary = "当前版本 v$currentVersion",
                        endActions = {
                            if (checkingForUpdates) {
                                CircularProgressIndicator(
                                    size = 20.dp,
                                    strokeWidth = 3.dp,
                                    modifier = Modifier.padding(horizontal = 12.dp),
                                )
                            }
                        },
                        onClick = { if (!checkingForUpdates) onCheckForUpdates() },
                    )
                }
            }
        }
    }

    WindowDialog(
        show = showClearConfirmation,
        title = "清理图片缓存",
        summary = "当前占用 ${cacheSize?.let(::formatByteCount) ?: "正在计算"}，确认清除所有已缓存的封面和漫画图片？",
        onDismissRequest = { if (!clearing) showClearConfirmation = false },
    ) {
        TextButton(
            text = if (clearing) "清理中" else "确认清理",
            enabled = !clearing,
            onClick = {
                clearing = true
                coroutineScope.launch {
                    runCatching { repository.clearImageCache() }
                        .onSuccess {
                            cacheRefreshKey++
                            clearMessage = "图片缓存已清理"
                        }
                        .onFailure { clearMessage = it.message ?: "缓存清理失败" }
                    clearing = false
                    showClearConfirmation = false
                }
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.textButtonColorsPrimary(),
        )
    }
    WindowDialog(
        show = clearMessage != null,
        title = "缓存管理",
        summary = clearMessage,
        onDismissRequest = { clearMessage = null },
    ) {
        TextButton(
            text = "知道了",
            onClick = { clearMessage = null },
            modifier = Modifier.fillMaxWidth(),
        )
    }
    EndpointSelectionDialog(
        show = showEndpoints,
        repository = repository,
        onDismiss = { showEndpoints = false },
        onImageHostChanged = onImageHostChanged,
    )
}

@Composable
private fun EndpointSelectionDialog(
    show: Boolean,
    repository: AppSettingsRepository,
    onDismiss: () -> Unit,
    onImageHostChanged: () -> Unit,
) {
    var state by remember(repository) {
        mutableStateOf(
            EndpointDialogState(
                endpoints = repository.endpointSnapshot(),
            ),
        )
    }
    var refreshKey by remember { mutableIntStateOf(0) }
    val coroutineScope = rememberCoroutineScope()

    fun refreshSelection() {
        val snapshot = repository.endpointSnapshot()
        state = state.copy(
            endpoints = snapshot.copy(
                apiEndpoints = state.endpoints.apiEndpoints,
                imageEndpoints = state.endpoints.imageEndpoints,
            ),
        )
    }

    fun updateApiProbe(probe: EndpointProbeUi) {
        val activeApiUrl = repository.endpointSnapshot().activeApiUrl
        state = state.copy(
            endpoints = state.endpoints.copy(
                activeApiUrl = activeApiUrl,
                apiEndpoints = state.endpoints.apiEndpoints.map {
                    if (it.url == probe.url) probe else it
                },
            ),
        )
    }

    fun updateImageProbe(probe: EndpointProbeUi) {
        state = state.copy(
            endpoints = state.endpoints.copy(
                imageEndpoints = state.endpoints.imageEndpoints.map {
                    if (it.url == probe.url) probe else it
                },
            ),
        )
    }

    fun probeApi(url: String) {
        updateApiProbe(EndpointProbeUi(url = url))
        coroutineScope.launch {
            val probe = runCatching { repository.probeApiEndpoint(url) }
                .getOrElse { EndpointProbeUi(url = url, success = false) }
            updateApiProbe(probe)
        }
    }

    fun probeImage(url: String) {
        updateImageProbe(EndpointProbeUi(url = url))
        coroutineScope.launch {
            val probe = runCatching { repository.probeImageEndpoint(url) }
                .getOrElse { EndpointProbeUi(url = url, success = false) }
            updateImageProbe(probe)
        }
    }

    LaunchedEffect(show, refreshKey, repository) {
        if (!show) return@LaunchedEffect
        val snapshot = repository.endpointSnapshot()
        state = EndpointDialogState(endpoints = snapshot)
        snapshot.apiEndpoints.forEach { endpoint ->
            launch {
                val probe = runCatching { repository.probeApiEndpoint(endpoint.url) }
                    .getOrElse { EndpointProbeUi(url = endpoint.url, success = false) }
                updateApiProbe(probe)
            }
        }
        snapshot.imageEndpoints.forEach { endpoint ->
            launch {
                val probe = runCatching { repository.probeImageEndpoint(endpoint.url) }
                    .getOrElse { EndpointProbeUi(url = endpoint.url, success = false) }
                updateImageProbe(probe)
            }
        }
    }

    WindowDialog(
        show = show,
        title = "线路与图源",
        summary = "选择线路后立即生效。",
        onDismissRequest = onDismiss,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 260.dp, max = 600.dp),
        ) {
            EndpointOptions(
                state = state,
                repository = repository,
                onRefresh = { refreshKey++ },
                onSelectionChanged = ::refreshSelection,
                onImageHostChanged = onImageHostChanged,
                onProbeApi = ::probeApi,
                onProbeImage = ::probeImage,
            )
        }
    }
}

@Composable
private fun EndpointOptions(
    state: EndpointDialogState,
    repository: AppSettingsRepository,
    onRefresh: () -> Unit,
    onSelectionChanged: () -> Unit,
    onImageHostChanged: () -> Unit,
    onProbeApi: (String) -> Unit,
    onProbeImage: (String) -> Unit,
) {
    val endpoints = state.endpoints
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        Text(
            text = "API 线路",
            style = MiuixTheme.textStyles.title3,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
        )
        EndpointOption(
            title = "自动选择",
            summary = endpoints.activeApiUrl?.let { "当前使用 ${it.endpointDisplayName()}" }
                ?: "根据线路健康状态自动切换",
            selected = endpoints.automatic,
            probe = null,
            onProbe = null,
            onClick = {
                repository.useAutomaticApi()
                onSelectionChanged()
            },
        )
        endpoints.apiEndpoints.forEach { endpoint ->
            EndpointOption(
                title = endpoint.url.endpointDisplayName(),
                summary = null,
                selected = !endpoints.automatic && endpoints.selectedApiUrl == endpoint.url,
                probe = endpoint,
                onProbe = { onProbeApi(endpoint.url) },
                onClick = {
                    if (repository.useApiEndpoint(endpoint.url) is JmxResult.Success) {
                        onSelectionChanged()
                    }
                },
            )
        }
        Text(
            text = "图片源",
            style = MiuixTheme.textStyles.title3,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
        )
        EndpointOption(
            title = "跟随服务配置",
            summary = "自动使用平台当前推荐图片源",
            selected = endpoints.selectedImageUrl == null,
            probe = null,
            onProbe = null,
            onClick = {
                repository.useAutomaticImageHost()
                onImageHostChanged()
                onSelectionChanged()
            },
        )
        endpoints.imageEndpoints.forEach { endpoint ->
            EndpointOption(
                title = endpoint.url.endpointDisplayName(),
                summary = null,
                selected = endpoints.selectedImageUrl == endpoint.url,
                probe = endpoint,
                onProbe = { onProbeImage(endpoint.url) },
                onClick = {
                    repository.useImageHost(endpoint.url)
                    onImageHostChanged()
                    onSelectionChanged()
                },
            )
        }
        TextButton(
            text = "全部重新测速",
            onClick = onRefresh,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun EndpointOption(
    title: String,
    summary: String?,
    selected: Boolean,
    probe: EndpointProbeUi?,
    onProbe: (() -> Unit)?,
    onClick: () -> Unit,
) {
    BasicComponent(
        title = title,
        summary = summary,
        onClick = onClick,
        endActions = {
            if (probe != null) {
                if (probe.success == null) {
                    CircularProgressIndicator(
                        size = 20.dp,
                        strokeWidth = 3.dp,
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                } else {
                    Text(
                        text = probe.latencyLabel().orEmpty(),
                        style = MiuixTheme.textStyles.footnote1,
                        color = probe.signalColor(),
                    )
                    IconButton(
                        onClick = { onProbe?.invoke() },
                        enabled = onProbe != null,
                        modifier = Modifier.semantics {
                            contentDescription = "重新测速 $title"
                        },
                    ) {
                        EndpointSignal(probe)
                    }
                }
            }
            if (selected) {
                Icon(
                    imageVector = MiuixIcons.Basic.Check,
                    contentDescription = "已选择",
                    tint = MiuixTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 10.dp),
                )
            }
        },
    )
}

@Composable
private fun EndpointSignal(probe: EndpointProbeUi) {
    val activeBars = probe.signalBars()
    val color = probe.signalColor()
    Row(
        modifier = Modifier
            .width(24.dp)
            .height(18.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        repeat(4) { index ->
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height((5 + index * 4).dp)
                    .background(
                        if (index < activeBars) color
                        else MiuixTheme.colorScheme.onSurface.copy(alpha = 0.14f),
                    ),
            )
        }
    }
}

private fun EndpointProbeUi.latencyLabel(): String? = when (success) {
    true -> latencyMillis?.let { "$it ms" }
    false -> "不可用"
    null -> null
}

private fun EndpointProbeUi.signalBars(): Int = when {
    success != true -> 0
    latencyMillis == null -> 0
    latencyMillis <= 300L -> 4
    latencyMillis <= 800L -> 3
    latencyMillis <= 1_500L -> 2
    else -> 1
}

private fun EndpointProbeUi.signalColor(): Color = when {
    success != true -> UnavailableSignalColor
    signalBars() >= 3 -> AvailableSignalColor
    else -> SlowSignalColor
}

private fun String.endpointDisplayName(): String = removePrefix("https://")
    .removePrefix("http://")
    .trimEnd('/')

internal fun formatByteCount(bytes: Long): String {
    if (bytes < 1_024L) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unit = -1
    while (value >= 1_024.0 && unit < units.lastIndex) {
        value /= 1_024.0
        unit++
    }
    return if (value >= 100 || value % 1.0 == 0.0) {
        String.format(Locale.ROOT, "%.0f %s", value, units[unit])
    } else {
        String.format(Locale.ROOT, "%.1f %s", value, units[unit])
    }
}

private data class EndpointDialogState(
    val endpoints: EndpointSettingsState,
)

private val AvailableSignalColor = Color(0xFF168A50)
private val SlowSignalColor = Color(0xFFB06B00)
private val UnavailableSignalColor = Color(0xFFCB3A31)

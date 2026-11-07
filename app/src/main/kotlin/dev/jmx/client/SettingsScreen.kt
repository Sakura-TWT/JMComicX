package dev.jmx.client

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.jmx.client.effect.FloatingNavBarStyle
import dev.jmx.client.effect.TopBarBlurStyle
import dev.jmx.client.core.result.JmxResult
import java.util.Locale
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.DropdownDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.Check
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.preference.WindowDropdownPreference
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

@Composable
internal fun SettingsScreen(
    innerPadding: PaddingValues,
    repository: AppSettingsRepository,
    themeMode: AppThemeMode,
    onThemeModeChanged: (AppThemeMode) -> Unit,
    autoCheckIn: Boolean,
    onAutoCheckInChanged: (Boolean) -> Unit,
    autoCheckUpdates: Boolean,
    checkingForUpdates: Boolean,
    currentVersion: String,
    onAutoCheckUpdatesChanged: (Boolean) -> Unit,
    onCheckForUpdates: () -> Unit,
    onImageHostChanged: () -> Unit,
    onContentLanguageChanged: () -> Unit,
    onTopBarBlurStyleChanged: (TopBarBlurStyle) -> Unit,
    onLiquidGlassNavBarChanged: (Boolean) -> Unit,
    onFloatingNavBarStyleChanged: (FloatingNavBarStyle) -> Unit,
    comicUpdateSummary: String = "",
    scanningComicUpdates: Boolean = false,
    onScanComicUpdates: () -> Unit = {},
    onSimulateComicUpdates: () -> Unit = {},
    onClearComicUpdates: () -> Unit = {},
    backdrop: LayerBackdrop? = null,
) {
    var showClearConfirmation by remember { mutableStateOf(false) }
    var clearing by remember { mutableStateOf(false) }
    var clearMessage by remember { mutableStateOf<String?>(null) }
    var showEndpoints by remember { mutableStateOf(false) }
    var endpointSettings by remember(repository) { mutableStateOf(repository.endpointSnapshot()) }
    var cacheSize by remember { mutableStateOf<Long?>(null) }
    var cacheRefreshKey by remember { mutableIntStateOf(0) }
    var contentLanguage by remember(repository) { mutableStateOf(repository.contentLanguage()) }
    var topBarBlurStyle by remember(repository) { mutableStateOf(repository.topBarBlurStyle()) }
    var liquidGlassNavBar by remember(repository) { mutableStateOf(repository.liquidGlassNavBarEnabled()) }
    var floatingNavBarStyle by remember(repository) { mutableStateOf(repository.floatingNavBarStyle()) }
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(repository, cacheRefreshKey) {
        cacheSize = runCatching { repository.imageCacheSizeBytes() }.getOrNull()
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier)
            .background(MiuixTheme.colorScheme.surface),
        contentPadding = PaddingValues(
            top = innerPadding.calculateTopPadding() + 8.dp,
            bottom = innerPadding.calculateBottomPadding() + 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "settings-display") {
            Column {
                SmallTitle(text = "显示")
                Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                    WindowDropdownPreference(
                        title = "主题模式",
                        summary = themeMode.summary,
                        items = AppThemeMode.entries.map(AppThemeMode::label),
                        selectedIndex = AppThemeMode.entries.indexOf(themeMode),
                        onSelectedIndexChange = { index ->
                            AppThemeMode.entries.getOrNull(index)?.let(onThemeModeChanged)
                        },
                    )
                    WindowDropdownPreference(
                        title = "内容语言",
                        summary = if (contentLanguage == CONTENT_LANGUAGE_TRADITIONAL) {
                            "繁體中文 · 标题、简介与标签显示为繁体"
                        } else {
                            "简体中文 · 标题、简介与标签显示为简体"
                        },
                        items = listOf("简体中文", "繁體中文"),
                        selectedIndex = if (contentLanguage == CONTENT_LANGUAGE_TRADITIONAL) 1 else 0,
                        onSelectedIndexChange = { index ->
                            val language = if (index == 1) CONTENT_LANGUAGE_TRADITIONAL else CONTENT_LANGUAGE_SIMPLIFIED
                            if (language != contentLanguage) {
                                repository.setContentLanguage(language)
                                contentLanguage = language
                                // 语言只影响服务端返回的文案，已加载的内容不会自行变化，
                                // 必须像切换图源那样主动丢弃并重新请求。
                                onContentLanguageChanged()
                            }
                        },
                    )
                    WindowDropdownPreference(
                        title = "顶栏模糊样式",
                        summary = "滚动内容在顶栏后的显示效果：${topBarBlurStyle.label()}",
                        items = TopBarBlurStyle.entries.map(TopBarBlurStyle::label),
                        selectedIndex = TopBarBlurStyle.entries.indexOf(topBarBlurStyle),
                        onSelectedIndexChange = { index ->
                            TopBarBlurStyle.entries.getOrNull(index)?.let { style ->
                                repository.setTopBarBlurStyle(style)
                                topBarBlurStyle = style
                                onTopBarBlurStyleChanged(style)
                            }
                        },
                    )
                    SwitchPreference(
                        title = "悬浮底栏",
                        summary = "底部导航切换为悬浮样式，可选择默认磨砂或 iOS 液态玻璃，需要设备支持",
                        checked = liquidGlassNavBar,
                        onCheckedChange = { enabled ->
                            repository.setLiquidGlassNavBarEnabled(enabled)
                            liquidGlassNavBar = enabled
                            onLiquidGlassNavBarChanged(enabled)
                        },
                    )
                    AnimatedVisibility(visible = liquidGlassNavBar) {
                        WindowDropdownPreference(
                            title = "悬浮底栏样式",
                            summary = "当前：${floatingNavBarStyle.label()}",
                            items = FloatingNavBarStyle.entries.map(FloatingNavBarStyle::label),
                            selectedIndex = FloatingNavBarStyle.entries.indexOf(floatingNavBarStyle),
                            onSelectedIndexChange = { index ->
                                FloatingNavBarStyle.entries.getOrNull(index)?.let { style ->
                                    repository.setFloatingNavBarStyle(style)
                                    floatingNavBarStyle = style
                                    onFloatingNavBarStyleChanged(style)
                                }
                            },
                        )
                    }
                }
            }
        }
        item(key = "settings-service") {
            Column {
                SmallTitle(text = "账户与网络")
                Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                    SwitchPreference(
                        title = "自动签到",
                        summary = "登录状态下启动应用时自动完成当日签到",
                        checked = autoCheckIn,
                        onCheckedChange = onAutoCheckInChanged,
                    )
                    ArrowPreference(
                        title = "线路与图源",
                        summary = endpointSettings.entrySummary(),
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
        // 自测入口：release 下这里是编译期常量 false，整块（含其中的字串）会被折叠掉。
        if (SHOW_COMIC_UPDATE_TEST_ENTRIES) {
            item(key = "settings-comic-updates") {
                Column {
                    SmallTitle(text = "漫画更新提示")
                    Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                        ArrowPreference(
                            title = "立即检查漫画更新",
                            summary = comicUpdateSummary,
                            endActions = {
                                if (scanningComicUpdates) {
                                    CircularProgressIndicator(
                                        size = 20.dp,
                                        strokeWidth = 3.dp,
                                        modifier = Modifier.padding(horizontal = 12.dp),
                                    )
                                }
                            },
                            onClick = { if (!scanningComicUpdates) onScanComicUpdates() },
                        )
                        ArrowPreference(
                            title = "模拟一次漫画更新",
                            // 真实更新几天才来一次，没有这个入口就没法验证红点、角标和消失时机。
                            summary = "把书架前 $SIMULATED_UPDATE_ALBUMS 部漫画的基线回退 " +
                                "$SIMULATED_UPDATE_CHAPTERS 话，用于自测提示链路",
                            enabled = !scanningComicUpdates,
                            onClick = onSimulateComicUpdates,
                        )
                        ArrowPreference(
                            title = "清除所有更新提示",
                            summary = "把当前所有更新标记按已看过处理",
                            enabled = !scanningComicUpdates,
                            onClick = onClearComicUpdates,
                        )
                    }
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
        onEndpointSelectionChanged = { endpointSettings = repository.endpointSnapshot() },
    )
}

@Composable
private fun EndpointSelectionDialog(
    show: Boolean,
    repository: AppSettingsRepository,
    onDismiss: () -> Unit,
    onImageHostChanged: () -> Unit,
    onEndpointSelectionChanged: () -> Unit,
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
            endpoints = state.endpoints.withSelectionSnapshot(snapshot),
        )
        onEndpointSelectionChanged()
    }

    fun updateApiProbe(probe: EndpointProbeUi) {
        val activeApiUrl = repository.endpointSnapshot().activeApiUrl
        state = state.copy(
            endpoints = state.endpoints.withApiProbe(probe, activeApiUrl),
        )
    }

    fun updateImageProbe(probe: EndpointProbeUi) {
        state = state.copy(
            endpoints = state.endpoints.withImageProbe(probe),
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
internal fun EndpointOptions(
    state: EndpointDialogState,
    repository: AppSettingsRepository,
    onRefresh: () -> Unit,
    onSelectionChanged: () -> Unit,
    onImageHostChanged: () -> Unit,
    onProbeApi: (String) -> Unit,
    onProbeImage: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val endpoints = state.endpoints
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        EndpointSectionTitle("API 线路", "${endpoints.apiEndpoints.size} 条")
        Text(
            text = "API 测速是独立 /setting 探测，不能代表漫画接口、业务排队、图片下载及还原速度。",
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        EndpointOption(
            title = "自动选择",
            summary = endpoints.automaticApiSummary(),
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
                selected = endpoints.isApiSelected(endpoint.url),
                probe = endpoint,
                onProbe = { onProbeApi(endpoint.url) },
                onClick = {
                    if (repository.useApiEndpoint(endpoint.url) is JmxResult.Success) {
                        onSelectionChanged()
                    }
                },
            )
        }
        EndpointSectionTitle("图片源", "${endpoints.imageEndpoints.size} 个")
        Text(
            text = "图片源独立于 API 线路，探测仅反映连接耗时。",
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
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
                selected = endpoints.isImageSelected(endpoint.url),
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
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        )
    }
}

@Composable
private fun EndpointSectionTitle(title: String, count: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = title, style = MiuixTheme.textStyles.title3)
        Text(
            text = count,
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
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
    val presentation = probe?.presentation()
    val selectionColors = DropdownDefaults.dialogDropdownColors()
    val titleColor = if (selected) selectionColors.selectedContentColor else selectionColors.contentColor
    val summaryColor = if (selected) selectionColors.selectedSummaryColor else selectionColors.summaryColor
    Card(
        modifier = Modifier.fillMaxWidth(),
        // Clip the selection fill to the same native rounded surface as the option card.
        cornerRadius = CardDefaults.CornerRadius,
        colors = CardDefaults.defaultColors(
            color = if (selected) selectionColors.selectedContainerColor else selectionColors.containerColor,
        ),
    ) {
        BasicComponent(
            onClick = onClick,
            modifier = Modifier.semantics {
                this.selected = selected
                contentDescription = probe?.url ?: title
                stateDescription = listOfNotNull(
                    if (selected) "已选择" else "未选择",
                    presentation?.statusLabel,
                    presentation?.latencyLabel,
                    presentation?.signalDescription,
                ).joinToString(" · ")
            },
            insideMargin = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
        ) {
            if (probe == null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = title,
                        style = MiuixTheme.textStyles.headline1,
                        fontWeight = FontWeight.Medium,
                        color = titleColor,
                        modifier = Modifier.weight(1f),
                    )
                    EndpointSelectionMark(selected)
                }
            } else {
                Text(
                    text = title,
                    style = MiuixTheme.textStyles.body2,
                    fontWeight = FontWeight.Medium,
                    color = titleColor,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (summary != null) {
                Text(
                    text = summary,
                    style = MiuixTheme.textStyles.body2,
                    color = summaryColor,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            if (presentation != null) {
                Row(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    EndpointLatencyIndicator(presentation, Modifier.weight(1f))
                    EndpointSelectionMark(selected)
                    IconButton(
                        onClick = { onProbe?.invoke() },
                        enabled = onProbe != null && presentation.status != EndpointProbeStatus.Pending,
                        modifier = Modifier.size(44.dp).semantics {
                            contentDescription = "重新测速 $title"
                        },
                    ) {
                        Text(
                            text = "重测",
                            style = MiuixTheme.textStyles.footnote1,
                            color = if (presentation.status == EndpointProbeStatus.Pending) {
                                MiuixTheme.colorScheme.onSurfaceVariantActions
                            } else MiuixTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EndpointSelectionMark(selected: Boolean) {
    Box(modifier = Modifier.size(24.dp), contentAlignment = Alignment.Center) {
        if (selected) {
            Icon(
                imageVector = MiuixIcons.Basic.Check,
                contentDescription = "已选择",
                tint = DropdownDefaults.dialogDropdownColors().selectedIndicatorColor,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun EndpointLatencyIndicator(
    presentation: EndpointProbePresentation,
    modifier: Modifier = Modifier,
) {
    val color = endpointSignalColor(presentation.signalTone)
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(18.dp), contentAlignment = Alignment.Center) {
            if (presentation.status == EndpointProbeStatus.Pending) {
                CircularProgressIndicator(size = 14.dp, strokeWidth = 2.dp)
            } else {
                EndpointSignalBars(presentation, color)
            }
        }
        Text(text = presentation.statusLabel, style = MiuixTheme.textStyles.footnote1, color = color)
        Text(
            text = presentation.latencyLabel,
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}

@Composable
private fun endpointSignalColor(tone: EndpointSignalTone): Color {
    val dark = MiuixTheme.colorScheme.surface.luminance() < 0.5f
    return when (tone) {
        EndpointSignalTone.Good -> if (dark) Color(0xFF6ECF91) else Color(0xFF237A42)
        EndpointSignalTone.Caution -> if (dark) Color(0xFFFFC46B) else Color(0xFF996500)
        EndpointSignalTone.Poor -> MiuixTheme.colorScheme.error
        EndpointSignalTone.Neutral -> MiuixTheme.colorScheme.onSurfaceVariantSummary
    }
}

@Composable
private fun EndpointSignalBars(presentation: EndpointProbePresentation, color: Color) {
    // Decorative only: the selectable row exposes the latency grade in its stateDescription.
    Canvas(Modifier.size(width = 18.dp, height = 16.dp)) {
        val gap = 1.5.dp.toPx()
        val width = (size.width - gap * 3) / 4
        repeat(4) { index ->
            val height = size.height * (index + 1) / 4
            drawRoundRect(
                color = if (index < presentation.signalBars) color else color.copy(alpha = 0.22f),
                topLeft = Offset(index * (width + gap), size.height - height),
                size = Size(width, height),
                cornerRadius = CornerRadius(0.7.dp.toPx()),
            )
        }
        if (presentation.status == EndpointProbeStatus.Failed) {
            drawLine(color, Offset(0f, size.height), Offset(size.width, 0f), strokeWidth = 1.5.dp.toPx())
        }
    }
}

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

internal data class EndpointDialogState(
    val endpoints: EndpointSettingsState,
)

/** 模拟更新时改动的漫画部数：够验证"我的"页的计数角标，又不至于把整屏都标红。 */
internal const val SIMULATED_UPDATE_ALBUMS = 3

/** 模拟更新时伪造的新增话数。 */
internal const val SIMULATED_UPDATE_CHAPTERS = 2

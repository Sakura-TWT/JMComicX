package dev.jmx.client

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.jmx.client.effect.BlurredBar
import dev.jmx.client.effect.TopBarBlurStyle
import dev.jmx.client.effect.rememberBarBackdrop
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.ProgressIndicatorDefaults
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBarDefaults
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.Download
import top.yukonga.miuix.kmp.icon.extended.Share
import top.yukonga.miuix.kmp.icon.extended.SelectAll
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog
import kotlin.math.roundToInt

/** The route supplies only explicit/manual downloads, never the reader's temporary cache. */
internal data class OfflineDownloadsUiState(
    val items: List<OfflineDownloadUiModel> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val busy: Boolean = false,
)

internal data class OfflineDownloadUiModel(
    val album: HomeAlbum,
    val downloadedChapters: Int,
    val totalChapters: Int,
    val progress: Float? = null,
    val statusLabel: String = "等待下载",
    val canPause: Boolean = false,
    val canResume: Boolean = false,
    val canRetry: Boolean = false,
    val error: String? = null,
    val chapterLabel: String? = null,
    val pageLabel: String? = null,
) {
    val id: String get() = album.id
}

/** Owns its bars and selection state, so the route can mount it without a second Scaffold. */
@Composable
internal fun OfflineDownloadsScreen(
    state: OfflineDownloadsUiState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onAlbumClick: (HomeAlbum, Rect?) -> Unit,
    onDelete: (Set<String>) -> Unit,
    onExport: (Set<String>) -> Unit,
    onPause: (String) -> Unit,
    onResume: (String) -> Unit,
    onRetryDownload: (String) -> Unit,
    onRetryDownloads: (Set<String>) -> Unit,
    modifier: Modifier = Modifier,
    topBarBlurStyle: TopBarBlurStyle = TopBarBlurStyle.GAUSSIAN,
    liftedAlbumId: String? = null,
) {
    val backdrop = rememberBarBackdrop()
    val gridState = rememberLazyGridState()
    val haptics = LocalHapticFeedback.current
    val items = remember(state.items) { state.items.distinctBy(OfflineDownloadUiModel::id) }
    val failedIds = remember(items) { offlineRetryIds(items) }
    var failuresOnly by rememberSaveable { mutableStateOf(false) }
    val visibleItems = if (failuresOnly) items.filter { it.id in failedIds } else items
    val availableIds = visibleItems.mapTo(linkedSetOf(), OfflineDownloadUiModel::id)
    var selectionMode by rememberSaveable { mutableStateOf(false) }
    // ArrayList is Bundle-saveable, unlike an arbitrary Set implementation.
    var savedSelection by rememberSaveable { mutableStateOf<List<String>>(arrayListOf()) }
    val selectedIds = savedSelection.toSet().intersect(availableIds)
    val canExportSelection = canExportOfflineSelection(items, selectedIds)
    var failureDetailsId by rememberSaveable { mutableStateOf<String?>(null) }
    val failureDetails = items.firstOrNull { it.id == failureDetailsId && it.canRetry }
    var pendingDeletion by remember { mutableStateOf<Set<String>>(emptySet()) }

    fun cancelSelection() {
        selectionMode = false
        savedSelection = arrayListOf()
    }

    fun toggle(id: String) {
        if (!state.busy) {
            savedSelection = ArrayList(toggleDownloadSelection(selectedIds, id, availableIds))
        }
    }

    LaunchedEffect(availableIds, state.loading) {
        // A transient loading snapshot must not erase a restored selection.
        if (!state.loading) {
            savedSelection = ArrayList(savedSelection.filter { it in availableIds })
            pendingDeletion = pendingDeletion.intersect(availableIds)
            if (availableIds.isEmpty()) cancelSelection()
        }
    }
    LaunchedEffect(failedIds, state.loading) {
        if (!state.loading && failedIds.isEmpty()) failuresOnly = false
        if (failureDetailsId !in failedIds) failureDetailsId = null
    }
    BackHandler(enabled = selectionMode && pendingDeletion.isEmpty()) { if (!state.busy) cancelSelection() }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = Color.Transparent,
        topBar = {
            BlurredBar(backdrop = backdrop, style = topBarBlurStyle) {
                AnimatedContent(
                    targetState = selectionMode,
                    transitionSpec = {
                        (fadeIn(tween(220)) + slideInVertically { it / 3 }) togetherWith
                            (fadeOut(tween(140)) + slideOutVertically { -it / 4 })
                    },
                    label = "OfflineDownloadsSelectionBar",
                ) { selecting ->
                    SmallTopAppBar(
                        title = if (selecting) "已选择 ${selectedIds.size} 部" else "离线下载",
                        titlePadding = if (selecting) 8.dp else TopAppBarDefaults.TitlePadding,
                        color = if (backdrop != null) Color.Transparent else MiuixTheme.colorScheme.surface,
                        navigationIcon = {
                            if (!selecting) {
                                IconButton(onClick = onBack) {
                                    Icon(MiuixIcons.Back, contentDescription = "返回", tint = MiuixTheme.colorScheme.onBackground)
                                }
                            }
                        },
                        actions = {
                            if (selecting) {
                                IconButton(
                                    onClick = { savedSelection = ArrayList(toggleAllDownloadSelection(selectedIds, availableIds)) },
                                    enabled = availableIds.isNotEmpty() && !state.busy,
                                    minWidth = 42.dp,
                                    minHeight = 42.dp,
                                ) {
                                    Icon(
                                        imageVector = MiuixIcons.SelectAll,
                                        contentDescription = if (selectedIds.containsAll(availableIds)) "取消全选" else "全选当前列表",
                                        tint = MiuixTheme.colorScheme.onBackground,
                                    )
                                }
                                IconButton(
                                    onClick = { onExport(selectedIds.toSet()) },
                                    enabled = canExportSelection && !state.busy,
                                    minWidth = 42.dp,
                                    minHeight = 42.dp,
                                ) {
                                    Icon(MiuixIcons.Share, contentDescription = "导出已选漫画", tint = MiuixTheme.colorScheme.primary)
                                }
                                IconButton(
                                    onClick = { pendingDeletion = selectedIds.toSet() },
                                    enabled = selectedIds.isNotEmpty() && !state.busy,
                                    holdDownState = pendingDeletion.isNotEmpty(),
                                    minWidth = 42.dp,
                                    minHeight = 42.dp,
                                ) {
                                    Icon(MiuixIcons.Delete, contentDescription = "删除已选漫画", tint = MiuixTheme.colorScheme.error)
                                }
                                IconButton(
                                    onClick = ::cancelSelection,
                                    enabled = !state.busy,
                                    minWidth = 42.dp,
                                    minHeight = 42.dp,
                                ) {
                                    Icon(MiuixIcons.Close, contentDescription = "退出多选", tint = MiuixTheme.colorScheme.onBackground)
                                }
                            } else {
                                IconButton(
                                    onClick = { selectionMode = true },
                                    enabled = visibleItems.isNotEmpty() && !state.busy,
                                    minWidth = 42.dp,
                                    minHeight = 42.dp,
                                ) {
                                    Icon(MiuixIcons.SelectAll, contentDescription = "选择漫画", tint = MiuixTheme.colorScheme.onBackground)
                                }
                            }
                        },
                    )
                }
            }
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier.fillMaxSize()
                .then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier)
                // Keep this page's opaque pixels inside the backdrop capture during route transitions.
                .background(MiuixTheme.colorScheme.surface),
        ) {
            when {
                items.isEmpty() && state.loading -> AccountPageLoading(Modifier.padding(innerPadding))
                items.isEmpty() && state.error != null -> OfflineDownloadsPlaceholder(
                    title = "下载记录加载失败",
                    message = state.error,
                    onRetry = onRetry,
                    modifier = Modifier.padding(innerPadding),
                )
                items.isEmpty() -> OfflineDownloadsPlaceholder(
                    title = "还没有离线下载",
                    message = "在漫画详情页选择章节并下载后，即可在这里管理和离线阅读。临时阅读缓存不会显示在这里。",
                    modifier = Modifier.padding(innerPadding),
                )
                else -> LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    state = gridState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 12.dp,
                        end = 12.dp,
                        top = innerPadding.calculateTopPadding() + 12.dp,
                        bottom = innerPadding.calculateBottomPadding() + 20.dp,
                    ),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    item(key = "download-summary", span = { GridItemSpan(maxLineSpan) }) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(
                                text = when {
                                    selectionMode && selectedIds.isNotEmpty() && !canExportSelection -> "部分漫画没有完整章节，暂不可导出；可继续删除操作。"
                                    selectionMode -> "点选漫画后，可导出或删除。返回可退出多选。"
                                    failuresOnly -> "仅显示失败任务 · 长按可多选"
                                    else -> "共 ${items.size} 部漫画 · 长按可多选"
                                },
                                modifier = Modifier.padding(horizontal = 4.dp),
                                style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                            if (state.loading || state.busy) {
                                LinearProgressIndicator()
                                Text(
                                    text = if (state.busy) "正在处理，请稍候…" else "正在更新下载记录…",
                                    style = MiuixTheme.textStyles.footnote2,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                )
                            }
                            if (failedIds.isNotEmpty() && !selectionMode) {
                                Card(insideMargin = PaddingValues(16.dp)) {
                                    Text(
                                        text = "${failedIds.size} 部漫画下载失败",
                                        style = MiuixTheme.textStyles.subtitle,
                                        color = MiuixTheme.colorScheme.error,
                                    )
                                    Text(
                                        text = "有效页面会保留，重试时将校验并续传。完整章节仍可阅读或导出。",
                                        modifier = Modifier.padding(top = 6.dp, bottom = 12.dp),
                                        style = MiuixTheme.textStyles.footnote1,
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    )
                                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        TextButton(
                                            text = "重试失败项",
                                            onClick = { onRetryDownloads(failedIds.toSet()) },
                                            enabled = !state.busy && !state.loading,
                                            colors = ButtonDefaults.textButtonColorsPrimary(),
                                        )
                                        TextButton(
                                            text = if (failuresOnly) "显示全部" else "只看失败",
                                            onClick = { failuresOnly = !failuresOnly },
                                            enabled = !state.busy,
                                        )
                                    }
                                }
                            }
                            state.error?.let { error ->
                                Card(insideMargin = PaddingValues(12.dp)) {
                                    Text(text = error, color = MiuixTheme.colorScheme.error, style = MiuixTheme.textStyles.footnote1)
                                    Spacer(Modifier.height(8.dp))
                                    TextButton(text = "重试", onClick = onRetry, enabled = !state.loading && !state.busy)
                                }
                            }
                        }
                    }
                    items(visibleItems, key = OfflineDownloadUiModel::id) { item ->
                        OfflineDownloadCard(
                            item = item,
                            selecting = selectionMode,
                            selected = item.id in selectedIds,
                            busy = state.busy,
                            coverLifted = item.id == liftedAlbumId,
                            onAlbumClick = { album, bounds ->
                                if (!state.busy) {
                                    when {
                                        selectionMode -> toggle(album.id)
                                        item.canRetry && item.downloadedChapters == 0 -> failureDetailsId = item.id
                                        else -> onAlbumClick(album, bounds)
                                    }
                                }
                            },
                            onLongClick = {
                                if (!state.busy) {
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    selectionMode = true
                                    savedSelection = ArrayList(selectedIds + item.id)
                                }
                            },
                            onFailureDetails = { failureDetailsId = item.id },
                            onPause = { onPause(item.id) },
                            onResume = { onResume(item.id) },
                            onRetry = { onRetryDownload(item.id) },
                        )
                    }
                }
            }
        }
    }

    WindowDialog(
        show = failureDetails != null,
        title = "下载失败",
        summary = failureDetails?.album?.name,
        maxWidth = 480.dp,
        onDismissRequest = { failureDetailsId = null },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(
                modifier = Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(text = failureDetails?.error ?: "下载已中断，请检查网络或存储空间后重试。", color = MiuixTheme.colorScheme.error)
                Text(
                    text = "重试时会校验本地文件，复用有效进度后继续下载。完整章节仍可阅读或导出。",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(text = "关闭", onClick = { failureDetailsId = null }, modifier = Modifier.weight(1f))
                TextButton(
                    text = "重试下载",
                    enabled = !state.busy && failureDetails != null,
                    onClick = {
                        failureDetails?.let { onRetryDownload(it.id) }
                        failureDetailsId = null
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            }
        }
    }

    WindowDialog(
        show = pendingDeletion.isNotEmpty(),
        title = "删除 ${pendingDeletion.size} 部离线漫画？",
        summary = "离线文件删除后需重新下载。书架记录会保留。",
        maxWidth = 480.dp,
        onDismissRequest = { pendingDeletion = emptySet() },
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(
                text = "取消",
                onClick = { pendingDeletion = emptySet() },
                modifier = Modifier.weight(1f),
            )
            TextButton(
                text = "删除",
                enabled = !state.busy && pendingDeletion.any { it in availableIds },
                onClick = {
                    val deleting = pendingDeletion.intersect(availableIds)
                    pendingDeletion = emptySet()
                    if (deleting.isNotEmpty()) {
                        onDelete(deleting)
                        cancelSelection()
                    }
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.textButtonColorsPrimary(color = MiuixTheme.colorScheme.error, textColor = MiuixTheme.colorScheme.onError),
            )
        }
    }
}

@Composable
private fun OfflineDownloadCard(
    item: OfflineDownloadUiModel,
    selecting: Boolean,
    selected: Boolean,
    busy: Boolean,
    coverLifted: Boolean,
    onAlbumClick: (HomeAlbum, Rect?) -> Unit,
    onLongClick: () -> Unit,
    onFailureDetails: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onRetry: () -> Unit,
) {
    Column(modifier = Modifier.padding(4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        AlbumItem(
            album = item.album,
            coverLifted = coverLifted,
            onSelected = { album, bounds -> onAlbumClick(album, bounds) },
            onLongSelected = onLongClick,
            selected = if (selecting) selected else null,
            enabled = !busy,
            selectionOverlay = {
                if (selecting) AlbumSelectionOverlay(selected, Modifier.matchParentSize())
            },
        )
        val progress = item.progress?.takeIf(Float::isFinite)?.coerceIn(0f, 1f)
        val completed = item.downloadedChapters.coerceAtLeast(0)
        val total = maxOf(item.totalChapters, completed)
        val fallbackProgress = if (total > 0) completed.toFloat() / total else 0f
        LinearProgressIndicator(
            progress = progress ?: if (item.canPause) null else fallbackProgress,
            colors = ProgressIndicatorDefaults.progressIndicatorColors(
                foregroundColor = when {
                    item.canRetry -> MiuixTheme.colorScheme.error
                    item.canResume -> MiuixTheme.colorScheme.onSurfaceVariantSummary
                    else -> MiuixTheme.colorScheme.primary
                },
            ),
        )
        Text(
            text = item.statusLabel + (progress?.takeIf { item.canPause || item.canResume || item.canRetry }
                ?.let { " · ${(it * 100).roundToInt()}%" } ?: ""),
            style = MiuixTheme.textStyles.footnote1,
            color = when {
                item.canRetry -> MiuixTheme.colorScheme.error
                item.canResume -> MiuixTheme.colorScheme.onSurfaceVariantSummary
                else -> MiuixTheme.colorScheme.primary
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = if (total > 0) "已完成 $completed / $total 章" else "正在准备章节…",
            style = MiuixTheme.textStyles.footnote2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        item.chapterLabel?.let { chapter ->
            Text(
                text = chapter,
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        item.pageLabel?.let { pages ->
            Text(text = pages, style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
        if (item.canRetry && !selecting) {
            Surface(
                onClick = onFailureDetails,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
                color = MiuixTheme.colorScheme.surfaceContainer,
                shape = RoundedCornerShape(8.dp),
            ) {
                Text(
                    text = "查看失败原因",
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                )
            }
        }
        if (!selecting && (item.canPause || item.canResume || item.canRetry)) {
            TextButton(
                text = when {
                    item.canPause -> "暂停"
                    item.canRetry -> "重试"
                    else -> "继续"
                },
                onClick = when {
                    item.canPause -> onPause
                    item.canRetry -> onRetry
                    else -> onResume
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
                insideMargin = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                minHeight = 40.dp,
            )
        }
    }
}

@Composable
private fun OfflineDownloadsPlaceholder(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = MiuixIcons.Download,
                contentDescription = null,
                modifier = Modifier.size(44.dp),
                tint = MiuixTheme.colorScheme.onSurfaceVariantActions,
            )
            Text(text = title, style = MiuixTheme.textStyles.title3, textAlign = TextAlign.Center)
            Text(
                text = message,
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                textAlign = TextAlign.Center,
            )
            if (onRetry != null) TextButton(text = "重试", onClick = onRetry, colors = ButtonDefaults.textButtonColorsPrimary())
        }
    }
}

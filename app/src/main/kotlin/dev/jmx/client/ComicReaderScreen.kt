package dev.jmx.client

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.BitmapDrawable
import android.os.BatteryManager
import android.os.Build
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.content.edit
import coil.ImageLoader
import coil.compose.AsyncImagePainter
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent
import coil.imageLoader
import coil.request.ImageRequest
import dev.jmx.client.core.api.AlbumChapter
import dev.jmx.client.core.api.AlbumDetail
import dev.jmx.client.core.chapter.ChapterTemplate
import dev.jmx.client.core.download.ImageHttpHeaders
import dev.jmx.client.core.image.ImagePipeline
import dev.jmx.client.core.image.ImagePlan
import dev.jmx.client.core.image.ImageSegmentMove
import dev.jmx.client.core.image.ImageUrl
import dev.jmx.client.core.protocol.JmxProtocolConstants
import dev.jmx.client.core.result.JmxResult
import dev.jmx.client.core.runtime.JmxCore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt
import okhttp3.Headers
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.ChevronForward
import top.yukonga.miuix.kmp.icon.extended.ListView
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.icon.extended.VolumeUp
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

internal data class ReaderLaunchRequest(
    val album: HomeAlbum,
    val detail: AlbumDetail,
    val initialChapterId: String,
)

internal data class ReaderPage(
    val index: Int,
    val url: String,
    val plan: ImagePlan,
    val headers: Headers,
)

internal sealed interface ReaderChapterState {
    data object Loading : ReaderChapterState
    data class Content(
        val template: ChapterTemplate,
        val pages: List<ReaderPage>,
    ) : ReaderChapterState
    data class Error(val message: String) : ReaderChapterState
}

@Composable
internal fun ComicReaderScreen(
    request: ReaderLaunchRequest,
    repository: ComicReaderRepository,
    onBack: () -> Unit,
) {
    val readerColors = remember { lightColorScheme() }
    MiuixTheme(colors = readerColors) {
        ComicReaderContent(request = request, repository = repository, onBack = onBack)
    }
}

@Composable
private fun ComicReaderContent(
    request: ReaderLaunchRequest,
    repository: ComicReaderRepository,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val chapters = remember(request.detail) { request.detail.readingChapters() }
    val initialChapterIndex = remember(request.initialChapterId, chapters) {
        chapters.indexOfFirst { it.id == request.initialChapterId }.takeIf { it >= 0 } ?: 0
    }
    var selectedChapterIndex by rememberSaveable(request.album.id) {
        mutableIntStateOf(initialChapterIndex)
    }
    selectedChapterIndex = selectedChapterIndex.coerceIn(chapters.indices)
    val selectedChapter = chapters[selectedChapterIndex]
    var chapterState by remember(request.album.id) {
        mutableStateOf<ReaderChapterState>(ReaderChapterState.Loading)
    }
    var chapterRetryKey by remember(request.album.id) { mutableIntStateOf(0) }
    var controlsVisible by rememberSaveable(request.album.id) { mutableStateOf(true) }
    var showCatalog by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var currentPageIndex by remember(request.album.id) { mutableIntStateOf(0) }
    var sliderDraft by remember { mutableStateOf<Float?>(null) }
    val failedPages = remember(selectedChapter.id) { mutableStateMapOf<Int, String>() }
    val loadedPages = remember(selectedChapter.id) { mutableStateMapOf<Int, Boolean>() }
    val pageRetryKeys = remember(selectedChapter.id) { mutableStateMapOf<Int, Int>() }
    val listState = key(selectedChapter.id) { rememberLazyListState() }
    val coroutineScope = rememberCoroutineScope()
    val settingsStore = remember(context) { ReaderSettingsStore(context) }
    var settings by remember { mutableStateOf(settingsStore.load()) }

    fun updateSettings(updated: ReaderSettings) {
        settings = updated
        settingsStore.save(updated)
    }

    fun selectChapter(index: Int) {
        val safeIndex = index.coerceIn(chapters.indices)
        if (safeIndex == selectedChapterIndex) return
        selectedChapterIndex = safeIndex
        currentPageIndex = 0
        sliderDraft = null
        showCatalog = false
    }

    LaunchedEffect(selectedChapter.id, chapterRetryKey, repository) {
        chapterState = ReaderChapterState.Loading
        currentPageIndex = 0
        val loadedState = repository.loadChapter(
            chapterId = selectedChapter.id,
            imageHostHint = request.album.imageHost,
        )
        chapterState = loadedState
    }

    val pages = (chapterState as? ReaderChapterState.Content)?.pages.orEmpty()
    LaunchedEffect(listState, pages) {
        if (pages.isEmpty()) return@LaunchedEffect
        snapshotFlow {
            selectCurrentReaderPage(
                visiblePages = listState.layoutInfo.visibleItemsInfo.map {
                    ReaderVisiblePage(index = it.index, offset = it.offset, size = it.size)
                },
                viewportStart = listState.layoutInfo.viewportStartOffset,
                viewportEnd = listState.layoutInfo.viewportEndOffset,
            )
        }.distinctUntilChanged().collect { index ->
            currentPageIndex = index.coerceIn(pages.indices)
        }
    }

    LaunchedEffect(currentPageIndex, pages, loadedPages[currentPageIndex], repository) {
        if (pages.isNotEmpty() && loadedPages[currentPageIndex] == true) {
            repository.prefetchNext(pages, currentPageIndex)
        }
    }

    fun scrollToPage(index: Int) {
        if (pages.isEmpty()) return
        coroutineScope.launch {
            listState.scrollToItem(index.coerceIn(pages.indices))
        }
    }

    val latestPreviousPage by rememberUpdatedState(newValue = { scrollToPage(currentPageIndex - 1) })
    val latestNextPage by rememberUpdatedState(newValue = { scrollToPage(currentPageIndex + 1) })
    DisposableEffect(settings.volumeKeyPaging, pages) {
        ReaderVolumeKeyDispatcher.handler = if (settings.volumeKeyPaging && pages.isNotEmpty()) {
            { keyCode ->
                when (keyCode) {
                    KeyEvent.KEYCODE_VOLUME_UP -> {
                        latestPreviousPage()
                        true
                    }
                    KeyEvent.KEYCODE_VOLUME_DOWN -> {
                        latestNextPage()
                        true
                    }
                    else -> false
                }
            }
        } else {
            null
        }
        onDispose { ReaderVolumeKeyDispatcher.handler = null }
    }

    ReaderSystemBarsEffect(immersive = !controlsVisible && !showCatalog && !showSettings)
    BackHandler(onBack = onBack)

    Scaffold(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MiuixTheme.colorScheme.surface)
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        val activeTop = size.height * READER_TAP_REGION_TOP_FRACTION
                        val activeBottom = size.height * READER_TAP_REGION_BOTTOM_FRACTION
                        if (offset.y in activeTop..activeBottom) {
                            controlsVisible = !controlsVisible
                        }
                    }
                },
        ) {
            when (val state = chapterState) {
                ReaderChapterState.Loading -> ReaderLoading(chapterName = selectedChapter.displayName(selectedChapterIndex))
                is ReaderChapterState.Error -> ReaderError(
                    message = state.message,
                    onRetry = { chapterRetryKey++ },
                    onBack = onBack,
                )
                is ReaderChapterState.Content -> ReaderPages(
                    pages = state.pages,
                    listState = listState,
                    failedPages = failedPages,
                    retryKeys = pageRetryKeys,
                    onPageFailed = { index, message -> failedPages[index] = message },
                    onPageLoaded = { index ->
                        failedPages.remove(index)
                        loadedPages[index] = true
                    },
                    onRetryPage = { index ->
                        failedPages.remove(index)
                        pageRetryKeys[index] = (pageRetryKeys[index] ?: 0) + 1
                    },
                )
            }

            if (!controlsVisible) {
                ImmersiveStatus(
                    showBatteryTime = settings.showBatteryTime,
                    showPageNumber = settings.showPageNumber,
                    currentPage = currentPageIndex + 1,
                    totalPages = pages.size,
                )
            }

            AnimatedVisibility(
                visible = controlsVisible,
                enter = fadeIn() + slideInVertically { -it },
                exit = fadeOut() + slideOutVertically { -it },
                modifier = Modifier.align(Alignment.TopCenter),
            ) {
                ReaderTopBar(
                    chapter = selectedChapter,
                    chapterIndex = selectedChapterIndex,
                    chapterCount = chapters.size,
                    onBack = onBack,
                )
            }

            AnimatedVisibility(
                visible = controlsVisible,
                enter = fadeIn() + slideInVertically { it },
                exit = fadeOut() + slideOutVertically { it },
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                ReaderControlPanel(
                    currentPageIndex = currentPageIndex,
                    totalPages = pages.size,
                    sliderDraft = sliderDraft,
                    onSliderChange = { sliderDraft = it },
                    onSliderFinished = {
                        val target = readerPageFromSlider(sliderDraft ?: currentPageIndex.toFloat(), pages.size)
                        sliderDraft = null
                        scrollToPage(target)
                    },
                    canPreviousChapter = selectedChapterIndex > 0,
                    canNextChapter = selectedChapterIndex < chapters.lastIndex,
                    failedPage = failedPages.keys.minOrNull(),
                    onPreviousChapter = { selectChapter(selectedChapterIndex - 1) },
                    onNextChapter = { selectChapter(selectedChapterIndex + 1) },
                    onShowCatalog = { showCatalog = true },
                    onShowSettings = { showSettings = true },
                )
            }

            ReaderCatalogSheet(
                show = showCatalog,
                chapters = chapters,
                selectedChapterIndex = selectedChapterIndex,
                onSelect = ::selectChapter,
                onDismiss = { showCatalog = false },
            )
            ReaderSettingsSheet(
                show = showSettings,
                settings = settings,
                onSettingsChange = ::updateSettings,
                onDismiss = { showSettings = false },
            )
        }
    }
}

@Composable
private fun ReaderPages(
    pages: List<ReaderPage>,
    listState: androidx.compose.foundation.lazy.LazyListState,
    failedPages: Map<Int, String>,
    retryKeys: Map<Int, Int>,
    onPageFailed: (Int, String) -> Unit,
    onPageLoaded: (Int) -> Unit,
    onRetryPage: (Int) -> Unit,
) {
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
    ) {
        itemsIndexed(
            items = pages,
            key = { _, page -> page.plan.cacheKey },
            contentType = { _, _ -> "reader-page" },
        ) { index, page ->
            ReaderPageImage(
                page = page,
                retryKey = retryKeys[index] ?: 0,
                knownError = failedPages[index],
                onError = { message -> onPageFailed(index, message) },
                onLoaded = { onPageLoaded(index) },
                onRetry = { onRetryPage(index) },
            )
        }
    }
}

@Composable
private fun ReaderPageImage(
    page: ReaderPage,
    retryKey: Int,
    knownError: String?,
    onError: (String) -> Unit,
    onLoaded: () -> Unit,
    onRetry: () -> Unit,
) {
    val context = LocalContext.current
    val request = remember(page, retryKey) { buildReaderImageRequest(context, page, retryKey) }
    if (knownError != null) {
        ReaderPageError(pageNumber = page.index + 1, message = knownError, onRetry = onRetry)
        return
    }
    SubcomposeAsyncImage(
        model = request,
        contentDescription = "第 ${page.index + 1} 页",
        modifier = Modifier.fillMaxWidth(),
        contentScale = ContentScale.FillWidth,
        transform = { state ->
            if (state is AsyncImagePainter.State.Success && page.plan.requiresRestore) {
                val image = (state.result.drawable as? BitmapDrawable)?.bitmap?.asImageBitmap()
                state.copy(painter = JmxUnscramblePainter(state.painter, image, page.plan))
            } else {
                state
            }
        },
        loading = { ReaderPageLoading(page.index + 1) },
        error = { state ->
            LaunchedEffect(state.result.throwable) {
                onError(state.result.throwable.message ?: "图片请求失败")
            }
            ReaderPageError(
                pageNumber = page.index + 1,
                message = state.result.throwable.message ?: "图片请求失败",
                onRetry = onRetry,
            )
        },
        success = {
            LaunchedEffect(page.plan.cacheKey) { onLoaded() }
            SubcomposeAsyncImageContent(modifier = Modifier.fillMaxWidth())
        },
    )
}

@Composable
private fun ReaderPageLoading(pageNumber: Int) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(READER_PAGE_PLACEHOLDER_HEIGHT)
            .background(MiuixTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(size = 28.dp, strokeWidth = 3.dp)
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "正在加载第 $pageNumber 页",
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}

@Composable
private fun ReaderPageError(pageNumber: Int, message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(READER_PAGE_PLACEHOLDER_HEIGHT)
            .background(MiuixTheme.colorScheme.errorContainer)
            .clickable(onClick = onRetry)
            .padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = MiuixIcons.Refresh,
            contentDescription = null,
            modifier = Modifier.size(28.dp),
            tint = MiuixTheme.colorScheme.error,
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "第 $pageNumber 页加载失败，点击重试",
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onErrorContainer,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = message,
            style = MiuixTheme.textStyles.footnote2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            textAlign = TextAlign.Center,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ReaderTopBar(
    chapter: AlbumChapter,
    chapterIndex: Int,
    chapterCount: Int,
    onBack: () -> Unit,
) {
    Surface(color = MiuixTheme.colorScheme.background) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, top = 36.dp, end = 16.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = MiuixIcons.Back,
                    contentDescription = "返回详情",
                    tint = MiuixTheme.colorScheme.onBackground,
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "第 ${chapterIndex + 1} / $chapterCount 话",
                    style = MiuixTheme.textStyles.body2,
                    fontWeight = FontWeight.SemiBold,
                    color = MiuixTheme.colorScheme.onBackground,
                )
                Text(
                    text = chapter.displayName(chapterIndex),
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun ReaderControlPanel(
    currentPageIndex: Int,
    totalPages: Int,
    sliderDraft: Float?,
    onSliderChange: (Float) -> Unit,
    onSliderFinished: () -> Unit,
    canPreviousChapter: Boolean,
    canNextChapter: Boolean,
    failedPage: Int?,
    onPreviousChapter: () -> Unit,
    onNextChapter: () -> Unit,
    onShowCatalog: () -> Unit,
    onShowSettings: () -> Unit,
) {
    val safeTotal = totalPages.coerceAtLeast(1)
    val sliderEnd = (safeTotal - 1).toFloat().coerceAtLeast(1f)
    val displayedPage = readerPageFromSlider(sliderDraft ?: currentPageIndex.toFloat(), safeTotal) + 1
    Surface(color = MiuixTheme.colorScheme.surfaceContainer) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (totalPages > 0) "$displayedPage / $totalPages" else "正在加载章节",
                    style = MiuixTheme.textStyles.body2,
                    fontWeight = FontWeight.SemiBold,
                    color = MiuixTheme.colorScheme.onSurfaceContainer,
                )
                Text(
                    text = if (totalPages > 0) "${displayedPage * 100 / totalPages}%" else "",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            if (totalPages > 0) {
                Slider(
                    value = (sliderDraft ?: currentPageIndex.toFloat()).coerceIn(0f, sliderEnd),
                    onValueChange = onSliderChange,
                    onValueChangeFinished = onSliderFinished,
                    enabled = totalPages > 1,
                    valueRange = 0f..sliderEnd,
                    steps = (safeTotal - 2).coerceAtLeast(0),
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                Spacer(modifier = Modifier.height(28.dp))
            }
            if (failedPage != null) {
                Text(
                    text = "第 ${failedPage + 1} 页加载失败，可在原位置点击重试",
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.error,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceAround,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ReaderIconButton(
                    icon = MiuixIcons.ChevronForward,
                    description = "上一话",
                    enabled = canPreviousChapter,
                    mirrorHorizontally = true,
                    onClick = onPreviousChapter,
                )
                ReaderIconButton(
                    icon = MiuixIcons.ListView,
                    description = "选择章节",
                    enabled = true,
                    onClick = onShowCatalog,
                )
                ReaderIconButton(
                    icon = MiuixIcons.ChevronForward,
                    description = "下一话",
                    enabled = canNextChapter,
                    onClick = onNextChapter,
                )
                ReaderIconButton(
                    icon = MiuixIcons.Settings,
                    description = "阅读设置",
                    enabled = true,
                    onClick = onShowSettings,
                )
            }
        }
    }
}

@Composable
private fun ReaderIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    enabled: Boolean,
    mirrorHorizontally: Boolean = false,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, enabled = enabled) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            modifier = Modifier.graphicsLayer { scaleX = if (mirrorHorizontally) -1f else 1f },
            tint = if (enabled) {
                MiuixTheme.colorScheme.onSurface
            } else {
                MiuixTheme.colorScheme.disabledOnSurface
            },
        )
    }
}

@Composable
private fun ReaderCatalogSheet(
    show: Boolean,
    chapters: List<AlbumChapter>,
    selectedChapterIndex: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    OverlayBottomSheet(
        show = show,
        title = "选择章节",
        onDismissRequest = onDismiss,
    ) {
        LazyColumn(modifier = Modifier.heightIn(max = 560.dp)) {
            itemsIndexed(
                items = chapters,
                key = { _, chapter -> chapter.id },
            ) { index, chapter ->
                BasicComponent(
                    title = chapter.displayName(index),
                    summary = "JM${chapter.id}",
                    onClick = { onSelect(index) },
                    startAction = {
                        Icon(
                            imageVector = MiuixIcons.ListView,
                            contentDescription = null,
                            modifier = Modifier.padding(end = 14.dp),
                            tint = if (index == selectedChapterIndex) {
                                MiuixTheme.colorScheme.primary
                            } else {
                                MiuixTheme.colorScheme.onSurfaceVariantSummary
                            },
                        )
                    },
                    endActions = {
                        if (index == selectedChapterIndex) {
                            Text(
                                text = "当前",
                                style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.primary,
                            )
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun ReaderSettingsSheet(
    show: Boolean,
    settings: ReaderSettings,
    onSettingsChange: (ReaderSettings) -> Unit,
    onDismiss: () -> Unit,
) {
    var statusOptionsExpanded by rememberSaveable { mutableStateOf(false) }
    OverlayBottomSheet(
        show = show,
        title = "阅读设置",
        onDismissRequest = onDismiss,
    ) {
        Column {
            SwitchPreference(
                title = "音量键翻页",
                summary = "音量上键上一页，音量下键下一页",
                checked = settings.volumeKeyPaging,
                onCheckedChange = { onSettingsChange(settings.copy(volumeKeyPaging = it)) },
                startAction = {
                    Icon(
                        imageVector = MiuixIcons.VolumeUp,
                        contentDescription = null,
                        modifier = Modifier.padding(end = 14.dp),
                        tint = MiuixTheme.colorScheme.onSurface,
                    )
                },
            )
            BasicComponent(
                title = "沉浸阅读时状态显示",
                summary = "选择隐藏操作栏后仍保留的信息",
                onClick = { statusOptionsExpanded = !statusOptionsExpanded },
                endActions = {
                    Icon(
                        imageVector = MiuixIcons.ChevronForward,
                        contentDescription = if (statusOptionsExpanded) "收起" else "展开",
                        modifier = Modifier.graphicsLayer {
                            rotationZ = if (statusOptionsExpanded) 90f else 0f
                        },
                        tint = MiuixTheme.colorScheme.onSurfaceVariantActions,
                    )
                },
            )
            AnimatedVisibility(visible = statusOptionsExpanded) {
                Column(modifier = Modifier.padding(start = 24.dp)) {
                    SwitchPreference(
                        title = "电量、充放电状态与时间",
                        summary = "显示在右上角",
                        checked = settings.showBatteryTime,
                        onCheckedChange = { onSettingsChange(settings.copy(showBatteryTime = it)) },
                    )
                    SwitchPreference(
                        title = "当前话页码",
                        summary = "以 当前页/总页数 显示在右下角",
                        checked = settings.showPageNumber,
                        onCheckedChange = { onSettingsChange(settings.copy(showPageNumber = it)) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ImmersiveStatus(
    showBatteryTime: Boolean,
    showPageNumber: Boolean,
    currentPage: Int,
    totalPages: Int,
) {
    val battery = rememberBatteryStatus()
    val currentTime by produceState(initialValue = formatReaderTime()) {
        while (true) {
            value = formatReaderTime()
            delay(30_000L)
        }
    }
    Box(modifier = Modifier.fillMaxSize()) {
        if (showBatteryTime) {
            Text(
                text = "${if (battery.isCharging) "充电" else "放电"} ${battery.level}%  $currentTime",
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MiuixTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.94f))
                    .padding(horizontal = 9.dp, vertical = 5.dp),
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurface,
            )
        }
        if (showPageNumber && totalPages > 0) {
            Text(
                text = "$currentPage/$totalPages",
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(12.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MiuixTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.94f))
                    .padding(horizontal = 9.dp, vertical = 5.dp),
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun ReaderLoading(chapterName: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(modifier = Modifier.height(14.dp))
            Text(
                text = "正在准备 $chapterName",
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}

@Composable
private fun ReaderError(message: String, onRetry: () -> Unit, onBack: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "章节加载失败",
            style = MiuixTheme.textStyles.title3,
            color = MiuixTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = message,
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TextButton(text = "返回", onClick = onBack)
            TextButton(text = "重试", onClick = onRetry)
        }
    }
}

internal class ComicReaderRepository(
    context: Context,
    private val core: JmxCore,
) {
    private val applicationContext = context.applicationContext
    private val imageLoader: ImageLoader = applicationContext.imageLoader
    private val imagePipeline = ImagePipeline()

    suspend fun loadChapter(
        chapterId: String,
        imageHostHint: String?,
    ): ReaderChapterState {
        return try {
            loadChapterFromApi(chapterId, imageHostHint)?.let { return it }
            val templateResult = withTimeoutOrNull(READER_TEMPLATE_TIMEOUT_MILLIS) {
                core.chapterApi.template(chapterId, shunt = DEFAULT_IMAGE_SHUNT)
            } ?: return ReaderChapterState.Error("章节准备超时，请检查网络后重试。")
            when (val result = templateResult) {
                is JmxResult.Success -> result.value.toReaderState()
                is JmxResult.Failure -> ReaderChapterState.Error(result.error.toUiMessage())
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            ReaderChapterState.Error(error.message ?: "章节加载出现未知异常。")
        }
    }

    private suspend fun loadChapterFromApi(
        chapterId: String,
        imageHostHint: String?,
    ): ReaderChapterState? {
        val photo = when (val result = core.chapterApi.detail(chapterId)) {
            is JmxResult.Success -> result.value
            is JmxResult.Failure -> {
                return null
            }
        }
        if (photo.pageArr.isEmpty()) return null
        val numericId = photo.id.toIntOrNull() ?: chapterId.toIntOrNull() ?: return null
        val imageHost = photo.imageDomain
            ?: imageHostHint
            ?: ImageUrl.pickDefaultImageHost()
        val scrambleId = photo.scrambleId
            ?: core.chapterApi.cachedScrambleId(photo.id, photo.albumId)
            ?: JmxProtocolConstants.Scramble220980
        return ChapterTemplate(
            albumId = numericId,
            scrambleId = scrambleId,
            speed = "",
            imageHost = imageHost,
            chapterId = photo.id,
            cacheSuffix = "",
            imageFileNames = photo.pageArr,
        ).toReaderState()
    }

    private fun ChapterTemplate.toReaderState(): ReaderChapterState {
        val headers = ImageHttpHeaders.default(refererHost = imageHost).toCoilHeaders()
        val pages = imageUrls.mapIndexed { index, url ->
            ReaderPage(
                index = index,
                url = url,
                plan = imagePipeline.plan(url, albumId, scrambleId),
                headers = headers,
            )
        }
        return if (pages.isEmpty()) {
            ReaderChapterState.Error("章节没有返回可阅读的图片。")
        } else {
            ReaderChapterState.Content(template = this, pages = pages)
        }
    }

    suspend fun prefetchNext(pages: List<ReaderPage>, currentIndex: Int) {
        val nextIndex = currentIndex + 1
        if (nextIndex !in pages.indices) return
        imageLoader.execute(buildReaderImageRequest(applicationContext, pages[nextIndex], retryKey = 0))
    }
}

internal fun buildReaderImageRequest(context: Context, page: ReaderPage, retryKey: Int): ImageRequest {
    val builder = ImageRequest.Builder(context)
        .data(page.url)
        .headers(page.headers)
        .allowHardware(true)
        .crossfade(false)
        .memoryCacheKey("${page.plan.cacheKey}:reader:$retryKey")
        .diskCacheKey(page.plan.cacheKey)
    return builder.build()
}

private class JmxUnscramblePainter(
    private val delegate: Painter,
    private val image: ImageBitmap?,
    private val plan: ImagePlan,
    private val pipeline: ImagePipeline = ImagePipeline(),
) : Painter() {
    override val intrinsicSize: androidx.compose.ui.geometry.Size
        get() = image?.let {
            androidx.compose.ui.geometry.Size(it.width.toFloat(), it.height.toFloat())
        } ?: delegate.intrinsicSize

    override fun DrawScope.onDraw() {
        val sourceHeight = intrinsicSize.height.roundToInt()
        val moves = pipeline.restoreMoves(sourceHeight, plan.segmentCount)
        if (moves.isEmpty() || sourceHeight <= 0) {
            with(delegate) { draw(size = this@onDraw.size) }
            return
        }
        val sourceImage = image
        if (sourceImage != null) {
            drawRestoredImage(sourceImage, sourceHeight, moves)
            return
        }

        val scaleY = size.height / sourceHeight
        val drawSize = size
        moves.forEach { move ->
            val targetTop = move.targetY * scaleY
            val targetBottom = (move.targetY + move.height) * scaleY
            val translationY = (move.targetY - move.sourceY) * scaleY
            val clipTop = (targetTop - READER_SEGMENT_OVERLAP_PX).coerceAtLeast(0f)
            val clipBottom = (targetBottom + READER_SEGMENT_OVERLAP_PX).coerceAtMost(size.height)
            clipRect(top = clipTop, bottom = clipBottom) {
                translate(top = translationY) {
                    with(delegate) { draw(size = drawSize) }
                }
            }
        }
    }

    private fun DrawScope.drawRestoredImage(
        sourceImage: ImageBitmap,
        sourceHeight: Int,
        moves: List<ImageSegmentMove>,
    ) {
        val targetWidth = size.width.roundToInt().coerceAtLeast(1)
        val targetHeight = size.height.roundToInt().coerceAtLeast(1)
        val targetSegments = scaleReaderTargetSegments(moves, sourceHeight, targetHeight)
        moves.zip(targetSegments).forEach { (move, target) ->
            if (target.bottom <= target.top) return@forEach
            drawImage(
                image = sourceImage,
                srcOffset = IntOffset(0, move.sourceY),
                srcSize = IntSize(sourceImage.width, move.height),
                dstOffset = IntOffset(0, target.top),
                dstSize = IntSize(targetWidth, target.bottom - target.top),
                filterQuality = FilterQuality.Low,
            )
        }
    }
}

internal data class ReaderTargetSegment(val top: Int, val bottom: Int)

internal fun scaleReaderTargetSegments(
    moves: List<ImageSegmentMove>,
    sourceHeight: Int,
    targetHeight: Int,
): List<ReaderTargetSegment> {
    if (sourceHeight <= 0 || targetHeight <= 0) return emptyList()
    return moves.map { move ->
        ReaderTargetSegment(
            top = (move.targetY.toFloat() * targetHeight / sourceHeight).roundToInt(),
            bottom = ((move.targetY + move.height).toFloat() * targetHeight / sourceHeight).roundToInt(),
        )
    }
}

internal data class ReaderVisiblePage(
    val index: Int,
    val offset: Int,
    val size: Int,
)

internal fun selectCurrentReaderPage(
    visiblePages: List<ReaderVisiblePage>,
    viewportStart: Int,
    viewportEnd: Int,
): Int {
    if (visiblePages.isEmpty()) return 0
    return visiblePages.maxByOrNull { page ->
        val visibleStart = maxOf(page.offset, viewportStart)
        val visibleEnd = minOf(page.offset + page.size, viewportEnd)
        (visibleEnd - visibleStart).coerceAtLeast(0)
    }?.index ?: visiblePages.first().index
}

internal fun readerPageFromSlider(value: Float, totalPages: Int): Int {
    if (totalPages <= 1) return 0
    return value.roundToInt().coerceIn(0, totalPages - 1)
}

internal object ReaderVolumeKeyDispatcher {
    var handler: ((Int) -> Boolean)? = null

    fun shouldConsume(keyCode: Int): Boolean {
        return handler != null && keyCode in READER_VOLUME_KEY_CODES
    }

    fun dispatch(keyCode: Int): Boolean = handler?.invoke(keyCode) == true
}

private data class ReaderSettings(
    val volumeKeyPaging: Boolean = false,
    val showBatteryTime: Boolean = true,
    val showPageNumber: Boolean = true,
)

private class ReaderSettingsStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        READER_PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    fun load(): ReaderSettings {
        return ReaderSettings(
            volumeKeyPaging = preferences.getBoolean(READER_VOLUME_KEYS, false),
            showBatteryTime = preferences.getBoolean(READER_BATTERY_TIME, true),
            showPageNumber = preferences.getBoolean(READER_PAGE_NUMBER, true),
        )
    }

    fun save(settings: ReaderSettings) {
        preferences.edit {
            putBoolean(READER_VOLUME_KEYS, settings.volumeKeyPaging)
            putBoolean(READER_BATTERY_TIME, settings.showBatteryTime)
            putBoolean(READER_PAGE_NUMBER, settings.showPageNumber)
        }
    }
}

private data class ReaderBatteryStatus(val level: Int, val isCharging: Boolean)

@Composable
private fun rememberBatteryStatus(): ReaderBatteryStatus {
    val context = LocalContext.current
    var status by remember { mutableStateOf(context.readBatteryStatus(null)) }
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                status = context.readBatteryStatus(intent)
            }
        }
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val sticky = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            context.registerReceiver(receiver, filter)
        }
        if (sticky != null) status = context.readBatteryStatus(sticky)
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }
    return status
}

private fun Context.readBatteryStatus(intent: Intent?): ReaderBatteryStatus {
    val source = intent ?: registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    val level = source?.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) ?: 0
    val scale = source?.getIntExtra(BatteryManager.EXTRA_SCALE, 100)?.coerceAtLeast(1) ?: 100
    val state = source?.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
    return ReaderBatteryStatus(
        level = (level * 100 / scale).coerceIn(0, 100),
        isCharging = state == BatteryManager.BATTERY_STATUS_CHARGING ||
            state == BatteryManager.BATTERY_STATUS_FULL,
    )
}

@Composable
private fun ReaderSystemBarsEffect(immersive: Boolean) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    DisposableEffect(activity, immersive) {
        val window = activity?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        val previousLightStatusBars = controller?.isAppearanceLightStatusBars
        val previousLightNavigationBars = controller?.isAppearanceLightNavigationBars
        controller?.isAppearanceLightStatusBars = true
        controller?.isAppearanceLightNavigationBars = true
        if (immersive) {
            controller?.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller?.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller?.show(WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            controller?.show(WindowInsetsCompat.Type.systemBars())
            if (previousLightStatusBars != null) {
                controller.isAppearanceLightStatusBars = previousLightStatusBars
            }
            if (previousLightNavigationBars != null) {
                controller.isAppearanceLightNavigationBars = previousLightNavigationBars
            }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? {
    return when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
}

private fun Map<String, String>.toCoilHeaders(): Headers {
    return Headers.Builder().apply {
        forEach { (name, value) -> add(name, value) }
    }.build()
}

private fun formatReaderTime(): String {
    return SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
}

private val READER_PAGE_PLACEHOLDER_HEIGHT = 720.dp
private const val READER_PREFERENCES_NAME = "reader_settings"
private const val READER_VOLUME_KEYS = "volume_key_paging"
private const val READER_BATTERY_TIME = "show_battery_time"
private const val READER_PAGE_NUMBER = "show_page_number"
private const val READER_TAP_REGION_TOP_FRACTION = 0.2f
private const val READER_TAP_REGION_BOTTOM_FRACTION = 0.8f
private const val READER_TEMPLATE_TIMEOUT_MILLIS = 15_000L
private val READER_VOLUME_KEY_CODES = setOf(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN)
private const val READER_SEGMENT_OVERLAP_PX = 1.5f

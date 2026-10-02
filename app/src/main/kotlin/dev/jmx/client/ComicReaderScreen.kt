package dev.jmx.client

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Rect
import android.os.BatteryManager
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntSize
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.content.edit
import androidx.core.graphics.createBitmap
import coil3.ImageLoader
import coil3.asImage
import coil3.compose.AsyncImage
import coil3.decode.DecodeResult
import coil3.decode.Decoder
import coil3.decode.ImageSource
import coil3.fetch.SourceFetchResult
import coil3.imageLoader
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import coil3.request.Options
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.request.crossfade
import coil3.size.Dimension
import coil3.size.Size
import coil3.size.pxOrElse
import dev.jmx.client.core.api.AlbumChapter
import dev.jmx.client.core.api.AlbumDetail
import dev.jmx.client.core.chapter.ChapterTemplate
import dev.jmx.client.core.download.ImageHttpHeaders
import dev.jmx.client.core.image.ImagePipeline
import dev.jmx.client.core.image.ImagePlan
import dev.jmx.client.core.protocol.JmxProtocolConstants
import dev.jmx.client.core.result.JmxResult
import dev.jmx.client.core.runtime.JmxCore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.ProgressIndicatorDefaults
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.ChevronForward
import top.yukonga.miuix.kmp.icon.extended.ExpandLess
import top.yukonga.miuix.kmp.icon.extended.ExpandMore
import top.yukonga.miuix.kmp.icon.extended.ListView
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.icon.extended.VolumeUp
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal data class ReaderLaunchRequest(
    val album: HomeAlbum,
    val detail: AlbumDetail,
    val initialChapterId: String,
    val initialPageIndex: Int = 0,
    val source: ReaderResourceSource = ReaderResourceSource.OnlineJm,
)

internal data class ReaderProgressUpdate(
    val album: HomeAlbum,
    val chapterId: String,
    val chapterName: String,
    val pageIndex: Int,
    val pageCount: Int,
)

internal data class ReaderPage(
    val index: Int,
    val resource: ReaderPageResource,
) {
    val cacheKey: String get() = resource.cacheKey
}

internal sealed interface ReaderChapterState {
    data object Loading : ReaderChapterState
    data class Content(
        val pages: List<ReaderPage>,
    ) : ReaderChapterState
    data class Error(val message: String) : ReaderChapterState
}

@Composable
internal fun ComicReaderScreen(
    request: ReaderLaunchRequest,
    repository: ComicReaderRepository,
    onProgress: (ReaderProgressUpdate) -> Unit,
    onBack: () -> Unit,
) {
    key(request.album.id, request.source) {
        val offline = request.source as? ReaderResourceSource.Offline
        val catalogError = offline?.manifest?.catalogError(request.album.id)
            ?: if (offline != null && offline.manifest.chapters.none { it.id == request.initialChapterId }) {
                "离线目录中没有请求的章节，不会请求网络。"
            } else {
                null
            }
        if (catalogError != null) {
            BackHandler(onBack = onBack)
            ReaderError(message = catalogError, onRetry = null, onBack = onBack)
        } else {
            ComicReaderContent(
                request = request,
                repository = repository,
                onProgress = onProgress,
                onBack = onBack,
            )
        }
    }
}

@Composable
private fun ComicReaderContent(
    request: ReaderLaunchRequest,
    repository: ComicReaderRepository,
    onProgress: (ReaderProgressUpdate) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val source = request.source
    val offline = source as? ReaderResourceSource.Offline
    val chapters = remember(request.detail, source) {
        when (source) {
            ReaderResourceSource.OnlineJm -> request.detail.readingChapters()
            is ReaderResourceSource.Offline -> source.manifest.chapters.map { it.toReaderChapter() }
        }
    }
    var offlineInspection by remember(source) { mutableStateOf<ReaderOfflineInspection?>(null) }
    val availableChapterIds = offlineInspection?.availableChapterIds.orEmpty()
    val initialChapterIndex = remember(request.initialChapterId, chapters) {
        chapters.indexOfFirst { it.id == request.initialChapterId }.takeIf { it >= 0 } ?: 0
    }
    var selectedChapterIndex by rememberSaveable(request.album.id) {
        mutableIntStateOf(initialChapterIndex)
    }
    var initialProgressConsumed by rememberSaveable(request.album.id) { mutableStateOf(false) }
    selectedChapterIndex = selectedChapterIndex.coerceIn(chapters.indices)
    val selectedChapter = chapters[selectedChapterIndex]
    val previousChapterIndex = when (source) {
        ReaderResourceSource.OnlineJm -> (selectedChapterIndex - 1).takeIf { it in chapters.indices }
        is ReaderResourceSource.Offline -> readerOfflineAdjacentChapterIndex(
            source.manifest.chapters, availableChapterIds, selectedChapterIndex, -1,
        )
    }
    val nextChapterIndex = when (source) {
        ReaderResourceSource.OnlineJm -> (selectedChapterIndex + 1).takeIf { it in chapters.indices }
        is ReaderResourceSource.Offline -> readerOfflineAdjacentChapterIndex(
            source.manifest.chapters, availableChapterIds, selectedChapterIndex, 1,
        )
    }
    val initialPageForChapter = if (
        !initialProgressConsumed && selectedChapter.id == request.initialChapterId
    ) {
        request.initialPageIndex.coerceAtLeast(0)
    } else {
        0
    }
    var chapterState by remember(request.album.id, selectedChapter.id) {
        mutableStateOf<ReaderChapterState>(ReaderChapterState.Loading)
    }
    var chapterRetryKey by remember(request.album.id) { mutableIntStateOf(0) }
    var controlsVisible by rememberSaveable(request.album.id) { mutableStateOf(true) }
    var showCatalog by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var currentPageIndex by remember(request.album.id) {
        mutableIntStateOf(request.initialPageIndex.coerceAtLeast(0))
    }
    var sliderDraft by remember { mutableStateOf<Float?>(null) }
    val failedPages = remember(selectedChapter.id) { mutableStateMapOf<Int, String>() }
    val pageRetryKeys = remember(selectedChapter.id) { mutableStateMapOf<Int, Int>() }
    val listState = key(selectedChapter.id) {
        rememberLazyListState(initialFirstVisibleItemIndex = initialPageForChapter)
    }
    val coroutineScope = rememberCoroutineScope()
    val settingsStore = remember(context) { ReaderSettingsStore(context) }
    var settings by remember { mutableStateOf(settingsStore.load()) }

    // 解码与占位都按视口宽算。取屏幕宽而不是量出来的列表宽：阅览页的列表本就铺满宽度，
    // 而量出来的值第一帧是 0，会让首屏那几页用 0 宽去建请求键，白白多一次解码。
    val viewportWidthPx = remember(context) { readerViewportWidthPx(context) }
    val geometryStore = remember(context) { ReaderPageGeometryStore(context) }
    val geometry = remember(selectedChapter.id) {
        ReaderPageGeometryState(geometryStore.load(selectedChapter.id))
    }
    val prefetch = remember(context, coroutineScope) {
        ReaderPrefetchController(context, context.imageLoader, coroutineScope)
    }
    // 翻页速度（页/秒）的指数滑动平均，决定预取窗口开多大。
    var pageVelocity by remember(selectedChapter.id) { mutableFloatStateOf(0f) }
    var scrollingForward by remember(selectedChapter.id) { mutableStateOf(true) }
    var chapterLanding by remember(request.album.id) { mutableStateOf(ReaderChapterLanding.INITIAL) }
    // 相邻话的页数（章末预热时顺手记下）。换章提示卡要写"全 N 页"，没预热到就只写话名。
    var neighborPageCounts by remember(request.album.id) { mutableStateOf(emptyMap<String, Int>()) }

    DisposableEffect(selectedChapter.id, geometry) {
        onDispose {
            if (geometry.dirty) {
                geometryStore.save(selectedChapter.id, geometry.snapshot())
                geometry.markSaved()
            }
        }
    }
    // 边读边落盘：只靠 onDispose 的话，被系统杀掉的那次测量结果就白测了，
    // 下次进同一话又要从灰块跳一遍。
    LaunchedEffect(selectedChapter.id, geometry) {
        while (true) {
            delay(READER_GEOMETRY_FLUSH_INTERVAL_MILLIS)
            if (!geometry.dirty) continue
            geometryStore.save(selectedChapter.id, geometry.snapshot())
            geometry.markSaved()
        }
    }
    DisposableEffect(prefetch) {
        onDispose { prefetch.clear() }
    }

    fun updateSettings(updated: ReaderSettings) {
        settings = updated
        settingsStore.save(updated)
    }

    fun selectChapter(index: Int, landing: ReaderChapterLanding = ReaderChapterLanding.INITIAL) {
        val safeIndex = index.coerceIn(chapters.indices)
        if (safeIndex == selectedChapterIndex) return
        if (offline != null && chapters[safeIndex].id !in availableChapterIds) return
        (chapterState as? ReaderChapterState.Content)?.pages?.takeIf { it.isNotEmpty() }?.let { loadedPages ->
            onProgress(
                ReaderProgressUpdate(
                    album = request.album,
                    chapterId = selectedChapter.id,
                    chapterName = selectedChapter.displayName(selectedChapterIndex),
                    pageIndex = currentPageIndex.coerceIn(loadedPages.indices),
                    pageCount = loadedPages.size,
                ),
            )
        }
        chapterLanding = landing
        selectedChapterIndex = safeIndex
        initialProgressConsumed = true
        currentPageIndex = 0
        sliderDraft = null
        showCatalog = false
    }

    LaunchedEffect(selectedChapter.id, chapterRetryKey, repository, source) {
        chapterState = ReaderChapterState.Loading
        currentPageIndex = initialPageForChapter
        val loadedState = when (source) {
            ReaderResourceSource.OnlineJm -> repository.loadChapter(
                chapterId = selectedChapter.id,
                imageHostHint = request.album.imageHost,
            )
            is ReaderResourceSource.Offline -> {
                val inspection = withContext(Dispatchers.IO) {
                    inspectReaderOfflineManifest(source.manifest, request.album.id)
                }
                offlineInspection = inspection
                neighborPageCounts = inspection.chapters
                    .filter { it.error == null }
                    .associate { it.chapter.id to it.files.size }
                inspection.readerChapterState(selectedChapter.id)
            }
        }
        chapterState = loadedState
        if (loadedState is ReaderChapterState.Content) {
            // 上拉换到上一话时落在最后一页：回看的自然预期是"接着刚才那一页往前"。
            val landingPage = when (chapterLanding) {
                ReaderChapterLanding.LAST -> loadedState.pages.lastIndex
                ReaderChapterLanding.INITIAL -> initialPageForChapter
            }.coerceIn(loadedState.pages.indices)
            chapterLanding = ReaderChapterLanding.INITIAL
            currentPageIndex = landingPage
            listState.scrollToReaderPage(landingPage)
            initialProgressConsumed = true
        }
    }

    val pages = (chapterState as? ReaderChapterState.Content)?.pages.orEmpty()
    val latestOnProgress by rememberUpdatedState(onProgress)

    fun reportProgress() {
        if (pages.isEmpty()) return
        latestOnProgress(
            ReaderProgressUpdate(
                album = request.album,
                chapterId = selectedChapter.id,
                chapterName = selectedChapter.displayName(selectedChapterIndex),
                pageIndex = currentPageIndex.coerceIn(pages.indices),
                pageCount = pages.size,
            ),
        )
    }

    fun closeReader() {
        reportProgress()
        onBack()
    }

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

    // 翻页速度与方向。用页码差而不是滚动像素：预取的单位本来就是页，
    // 而同一段像素在长图页和短页上跨过的页数差很多。
    LaunchedEffect(selectedChapter.id, pages.size) {
        if (pages.isEmpty()) return@LaunchedEffect
        var previousPage = currentPageIndex
        var previousAt = System.nanoTime()
        snapshotFlow { currentPageIndex }.distinctUntilChanged().collect { index ->
            val now = System.nanoTime()
            pageVelocity = readerPageVelocity(
                previousVelocity = pageVelocity,
                pageDelta = index - previousPage,
                elapsedSeconds = (now - previousAt) / 1_000_000_000f,
            )
            if (index != previousPage) scrollingForward = index > previousPage
            previousPage = index
            previousAt = now
        }
    }

    // 预取。刻意不再等"当前页已加载"——跳到第 200 页时当前页本身还在下载，
    // 那道门槛会让后面几页一直排不上，正是"跳页后上下翻都很慢"的直接原因。
    val estimatedPageWidthPx = geometry.estimatedWidthPx(viewportWidthPx)
    LaunchedEffect(pages, currentPageIndex, pageVelocity, scrollingForward, viewportWidthPx, estimatedPageWidthPx) {
        if (pages.isEmpty()) return@LaunchedEffect
        prefetch.update(
            chapterId = selectedChapter.id,
            pages = pages,
            center = currentPageIndex,
            forward = scrollingForward,
            velocity = pageVelocity,
            viewportWidthPx = viewportWidthPx,
            decodedWidthPx = estimatedPageWidthPx,
            pageAspect = geometry.estimatedAspect(),
        )
    }

    // 快读到章末（或回到章首）时把相邻那一话的模板与头几页备好，
    // 让"拉到底换章"落地即有画面，而不是先看一屏转圈；顺手把页数记下来给提示卡用。
    LaunchedEffect(selectedChapter.id, pages.size, currentPageIndex, viewportWidthPx, source) {
        if (pages.isEmpty() || source != ReaderResourceSource.OnlineJm) return@LaunchedEffect
        val nearEnd = pages.lastIndex - currentPageIndex <= READER_CHAPTER_WARMUP_DISTANCE
        val nearStart = currentPageIndex <= READER_CHAPTER_WARMUP_DISTANCE
        suspend fun warmUp(chapter: AlbumChapter) {
            repository.prepareChapter(chapter.id, request.album.imageHost)?.let { prepared ->
                neighborPageCounts = neighborPageCounts + (chapter.id to prepared.pages.size)
            }
            repository.warmUpChapter(chapter.id, request.album.imageHost, viewportWidthPx)
        }
        if (nearEnd) chapters.getOrNull(selectedChapterIndex + 1)?.let { warmUp(it) }
        if (nearStart) chapters.getOrNull(selectedChapterIndex - 1)?.let { warmUp(it) }
    }

    LaunchedEffect(request.album.id, selectedChapter.id, currentPageIndex, pages.size) {
        if (pages.isEmpty()) return@LaunchedEffect
        delay(READER_PROGRESS_SAVE_DELAY_MILLIS)
        reportProgress()
    }

    fun scrollToPage(index: Int) {
        if (pages.isEmpty()) return
        val target = index.coerceIn(pages.indices)
        // 先把页码推到目标值，再去滚动。
        // scrollToItem 是挂起函数，而进度条显示的是 currentPageIndex——后者要等列表真正落位、
        // snapshotFlow 再算一轮才会更新。若在这之前就交还控制权（松手时 sliderDraft 已被清空），
        // 进度条会先读到旧页码，然后被 MIUIX Slider 的非拖拽动画（stiffness=322，约半秒）
        // 慢慢地"退回"原处——表现就是"拖到 30 松手，进度条自己滑回 2"。
        // 万一列表到不了目标（章节末尾余量不足），后续 snapshotFlow 会把它纠正回真实页。
        currentPageIndex = target
        coroutineScope.launch {
            listState.scrollToReaderPage(target)
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

    // 换章手势跑在 [ReaderPages] 里（listState 与缩放状态都在那儿），提示卡却必须画在
    // 顶栏/底栏之上——所以状态提到这一层，卡片作为最外层 Box 的最后一个孩子渲染。
    // 之前卡片挂在 ReaderPages 内部：顶栏、底栏、沉浸态角标都是它的后继兄弟，会盖在
    // 卡片上面，章首下拉的提示正好被顶栏吃掉，看起来就是"顶部下拉没有提示"。
    val flipThresholdPx = with(LocalDensity.current) { READER_CHAPTER_FLIP_THRESHOLD.toPx() }
    val flipHint = remember(pages, flipThresholdPx) { ReaderFlipHintState(flipThresholdPx) }
    // 目标话必须 remember：这一层现在每一帧拖动都会重组，而 displayName() 里有一次
    // Html.fromHtml——按帧解 HTML 会实打实地拖慢手势。
    val flipForwardTarget = remember(chapters, nextChapterIndex, neighborPageCounts) {
        readerFlipTarget(
            chapters = chapters,
            index = nextChapterIndex ?: -1,
            pageCounts = neighborPageCounts,
        )
    }
    val flipBackwardTarget = remember(chapters, previousChapterIndex, neighborPageCounts) {
        readerFlipTarget(
            chapters = chapters,
            index = previousChapterIndex ?: -1,
            pageCounts = neighborPageCounts,
        )
    }
    val flipTarget = when {
        flipHint.direction > 0 -> flipForwardTarget
        flipHint.direction < 0 -> flipBackwardTarget
        else -> null
    }
    val flip = continuousFlipState(flipHint.overscroll, flipThresholdPx, flipTarget != null)
    val flipHaptic = LocalHapticFeedback.current
    LaunchedEffect(flip.armed) {
        // 只在跨过阈值那一刻震一次；回落到阈值下 armed 变回 false，再次越过才重新震。
        if (flip.armed) flipHaptic.performHapticFeedback(HapticFeedbackType.Confirm)
    }
    val flipCard = flipHint.direction.takeIf { it != 0 }?.let { direction ->
        ReaderFlipCardModel(
            forward = direction > 0,
            target = flipTarget,
            progress = flip.progress,
            armed = flip.armed,
        )
    }
    // 松手后 direction/overscroll 立刻归零，退场动画却还在放——直接读实时状态的话，
    // 卡片会在淡出的半秒里翻成反方向的文案，所以渲染模型单独留一份快照。
    var lastFlipCard by remember(pages) { mutableStateOf<ReaderFlipCardModel?>(null) }
    if (flipCard != null && flipCard != lastFlipCard) lastFlipCard = flipCard

    val systemBarColor = MiuixTheme.colorScheme.background
    ReaderSystemBarsEffect(
        immersive = !controlsVisible && !showCatalog && !showSettings,
        barColor = systemBarColor.toArgb(),
        useDarkIcons = systemBarColor.luminance() > 0.5f,
    )
    BackHandler(onBack = ::closeReader)

    Scaffold(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MiuixTheme.colorScheme.surface),
        ) {
            when (val state = chapterState) {
                ReaderChapterState.Loading -> ReaderLoading(chapterName = selectedChapter.displayName(selectedChapterIndex))
                is ReaderChapterState.Error -> ReaderError(
                    message = state.message,
                    onRetry = { chapterRetryKey++ },
                    onBack = ::closeReader,
                )
                is ReaderChapterState.Content -> ReaderPages(
                    pages = state.pages,
                    geometry = geometry,
                    viewportWidthPx = viewportWidthPx,
                    listState = listState,
                    failedPages = failedPages,
                    retryKeys = pageRetryKeys,
                    continuousFlipEnabled = settings.continuousChapterFlip,
                    flipHint = flipHint,
                    canFlipForward = flipForwardTarget != null,
                    canFlipBackward = flipBackwardTarget != null,
                    onFlipForward = {
                        nextChapterIndex?.let { selectChapter(it, ReaderChapterLanding.INITIAL) }
                    },
                    onFlipBackward = {
                        previousChapterIndex?.let { selectChapter(it, ReaderChapterLanding.LAST) }
                    },
                    onPageFailed = { index, message -> failedPages[index] = message },
                    onPageLoaded = { index, width, height ->
                        failedPages.remove(index)
                        geometry.record(index, width, height)
                        prefetch.markSucceeded(index)
                    },
                    onRetryPage = { index ->
                        failedPages.remove(index)
                        pageRetryKeys[index] = (pageRetryKeys[index] ?: 0) + 1
                    },
                    onToggleControls = { controlsVisible = !controlsVisible },
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
                    source = source,
                    onBack = ::closeReader,
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
                    onSliderChange = {
                        // 进度条属于独立操作：一旦开始拖动，就清空章末换章草稿，
                        // 避免之前在章尾累计的状态在松开进度条时误提交换章。
                        flipHint.resetAfterRelease()
                        sliderDraft = it
                    },
                    onSliderFinished = {
                        flipHint.resetAfterRelease()
                        // 顺序要紧：scrollToPage 会同步把 currentPageIndex 推到目标页，
                        // 之后清空草稿才不会让进度条露出一帧旧页码。
                        scrollToPage(readerPageFromSlider(sliderDraft ?: currentPageIndex.toFloat(), pages.size))
                        sliderDraft = null
                    },
                    canPreviousChapter = previousChapterIndex != null,
                    canNextChapter = nextChapterIndex != null,
                    failedPage = failedPages.keys.minOrNull(),
                    onPreviousChapter = { previousChapterIndex?.let { selectChapter(it) } },
                    onNextChapter = { nextChapterIndex?.let { selectChapter(it) } },
                    onShowCatalog = { showCatalog = true },
                    onShowSettings = { showSettings = true },
                )
            }

            // 顶栏/底栏之后才画：换章提示是当下这一下手势的反馈，被任何常驻控件盖住都是错的。
            ReaderChapterFlipCard(
                visible = flipCard != null,
                model = lastFlipCard,
                source = source,
                modifier = Modifier.align(
                    // 退场动画期间读的是快照，方向也得跟着快照，否则卡片会在淡出时换边。
                    if (lastFlipCard?.forward != false) Alignment.BottomCenter else Alignment.TopCenter,
                ),
            )

            ReaderCatalogSheet(
                show = showCatalog,
                chapters = chapters,
                selectedChapterIndex = selectedChapterIndex,
                availableChapterIds = if (offline != null) availableChapterIds else null,
                onSelect = { selectChapter(it) },
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
    geometry: ReaderPageGeometryState,
    viewportWidthPx: Int,
    listState: LazyListState,
    failedPages: Map<Int, String>,
    retryKeys: Map<Int, Int>,
    continuousFlipEnabled: Boolean,
    flipHint: ReaderFlipHintState,
    canFlipForward: Boolean,
    canFlipBackward: Boolean,
    onFlipForward: () -> Unit,
    onFlipBackward: () -> Unit,
    onPageFailed: (Int, String) -> Unit,
    onPageLoaded: (Int, Int, Int) -> Unit,
    onRetryPage: (Int) -> Unit,
    onToggleControls: () -> Unit,
) {
    val coroutineScope = rememberCoroutineScope()
    var scale by remember(pages) { mutableFloatStateOf(READER_MIN_ZOOM) }
    var offset by remember(pages) { mutableStateOf(Offset.Zero) }
    var viewportSize by remember(pages) { mutableStateOf(IntSize.Zero) }
    var zoomAnimation by remember(pages) { mutableStateOf<Job?>(null) }
    DisposableEffect(pages) {
        onDispose { zoomAnimation?.cancel() }
    }

    fun animateZoom(targetScale: Float, focus: Offset) {
        zoomAnimation?.cancel()
        val startScale = scale
        val startOffset = offset
        val targetOffset = if (!isReaderZoomed(targetScale)) {
            Offset.Zero
        } else {
            readerZoomOffsetAfterGesture(
                currentOffset = ReaderZoomOffset(startOffset.x, startOffset.y),
                currentScale = startScale,
                requestedScale = targetScale,
                focusX = focus.x,
                focusY = focus.y,
                viewportWidth = viewportSize.width.toFloat(),
                viewportHeight = viewportSize.height.toFloat(),
            ).offset.toOffset()
        }
        zoomAnimation = coroutineScope.launch {
            animate(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = tween(READER_DOUBLE_TAP_ANIMATION_MILLIS),
            ) { progress, _ ->
                scale = startScale + (targetScale - startScale) * progress
                offset = Offset(
                    x = startOffset.x + (targetOffset.x - startOffset.x) * progress,
                    y = startOffset.y + (targetOffset.y - startOffset.y) * progress,
                )
            }
        }
    }

    // 缩放时整体停用：那时的单指位移已经被平移手势占用，两边抢同一段位移只会互相干扰。
    val flipEnabledState = rememberUpdatedState(continuousFlipEnabled && !isReaderZoomed(scale))
    val canForwardState = rememberUpdatedState(canFlipForward)
    val canBackwardState = rememberUpdatedState(canFlipBackward)
    val onForwardState = rememberUpdatedState(onFlipForward)
    val onBackwardState = rememberUpdatedState(onFlipBackward)
    val flipConnection = remember(listState, flipHint) {
        object : NestedScrollConnection {
            /**
             * 反向拖动时先把已累计的位移退回去。
             *
             * 这一步不做的话：上拉过阈值、手指不抬再往下拉，列表自己就把这段位移消化了
             * （它还能往回滚），[onPostScroll] 拿到的 `available` 是 0，累计值一直卡在阈值以上，
             * 松手必然换章——"只要拉过阈值就一定进下一话"。所以按下拉刷新的老规矩来：
             * 提示卡还立着的时候，反向的位移先归提示卡，退完了再还给列表。
             */
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (!flipEnabledState.value || source != NestedScrollSource.UserInput) {
                    return Offset.Zero
                }
                val direction = flipHint.direction
                if (direction == 0) return Offset.Zero
                val delta = available.y
                // 只管反向位移：同向的继续交给 onPostScroll 累加。
                val rewinding = if (direction > 0) delta > 0f else delta < 0f
                if (!rewinding) return Offset.Zero
                if (flipHint.wasArmed) {
                    flipHint.reverseTravelPx += abs(delta)
                    if (readerFlipShouldCancelAfterReverse(flipHint.reverseTravelPx, flipHint.thresholdPx)) {
                        // 越过阈值后的任何有效反向动作都代表用户改变了意图。
                        // 反向后手指可能已经贴近屏幕边缘，不能要求用户再反向移动
                        // 足够距离才能撤销；撤销动作本身就应当立即切断旧确认。
                        flipHint.cancelArmedPull()
                        return Offset.Zero
                    }
                }
                val rewind = readerFlipRewind(flipHint.overscroll, delta, READER_FLIP_DRAG_RESISTANCE)
                flipHint.overscroll = rewind.overscroll
                // 退到 0 就是彻底放弃这一次换章：方向也清掉，提示卡跟着退场。
                if (rewind.overscroll <= 0f) flipHint.resetAfterRelease()
                return Offset(0f, rewind.consumed)
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                // 只认真正的手指拖动：惯性滑动撞到底部也会剩下位移，那不是"用户想换章"。
                if (!flipEnabledState.value || source != NestedScrollSource.UserInput) {
                    return Offset.Zero
                }
                val delta = available.y
                val direction = when {
                    delta < 0f && !listState.canScrollForward -> 1
                    delta > 0f && !listState.canScrollBackward -> -1
                    else -> 0
                }
                if (direction == 0) {
                    // 反向拖动的退回由 onPreScroll 负责——它可能刚吃掉了整段位移，
                    // 到这里 available 就是 0，此时绝不能把进度清零，否则一反向卡片就没了。
                    // 这里只做收尾：退干净了才把方向也放掉。
                    if (flipHint.overscroll <= 0f) flipHint.resetAfterRelease()
                    return Offset.Zero
                }
                flipHint.direction = direction
                val allowed = if (direction > 0) canForwardState.value else canBackwardState.value
                // 首/末章只出提示卡：不累加、不震动，也不吃掉这段位移。
                flipHint.overscroll = if (allowed) {
                    readerFlipOverscroll(flipHint.overscroll, delta, READER_FLIP_DRAG_RESISTANCE)
                } else {
                    0f
                }
                if (allowed && flipHint.overscroll >= flipHint.thresholdPx) {
                    flipHint.wasArmed = true
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                val direction = flipHint.direction
                val allowed = when {
                    direction > 0 -> canForwardState.value
                    direction < 0 -> canBackwardState.value
                    else -> false
                }
                // armed 就地算，不读组合期的快照：抬手与上一次重组之间未必隔着一帧。
                val armed = flipHint.wasArmed &&
                    continuousFlipState(flipHint.overscroll, flipHint.thresholdPx, allowed).armed
                flipHint.resetAfterRelease()
                if (!flipEnabledState.value || !armed) return Velocity.Zero
                if (direction > 0) onForwardState.value() else onBackwardState.value()
                // 换章后整份列表都会换掉，这一下惯性没有意义，顺手吃掉。
                return available
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clipToBounds()
            .onSizeChanged { size ->
                viewportSize = size
                offset = constrainReaderZoomOffset(
                    offset = ReaderZoomOffset(offset.x, offset.y),
                    scale = scale,
                    viewportWidth = size.width.toFloat(),
                    viewportHeight = size.height.toFloat(),
                ).toOffset()
            }
            .pointerInput(pages) {
                detectTapGestures(
                    onTap = { onToggleControls() },
                    onDoubleTap = { position ->
                        animateZoom(
                            targetScale = if (isReaderZoomed(scale)) {
                                READER_MIN_ZOOM
                            } else {
                                READER_DOUBLE_TAP_ZOOM
                            },
                            focus = position,
                        )
                    },
                )
            }
            .pointerInput(pages) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var transforming = false
                    do {
                        val event = awaitPointerEvent()
                        val pressedPointers = event.changes.count { it.pressed }
                        if (pressedPointers > 0 && (pressedPointers >= 2 || transforming)) {
                            transforming = true
                            zoomAnimation?.cancel()
                            val centroid = event.calculateCentroid()
                            val pan = event.calculatePan()
                            val next = readerZoomOffsetAfterGesture(
                                currentOffset = ReaderZoomOffset(offset.x, offset.y),
                                currentScale = scale,
                                requestedScale = scale * event.calculateZoom(),
                                focusX = centroid.x,
                                focusY = centroid.y,
                                viewportWidth = viewportSize.width.toFloat(),
                                viewportHeight = viewportSize.height.toFloat(),
                                panX = pan.x,
                                panY = pan.y,
                            )
                            scale = next.scale
                            offset = next.offset.toOffset()
                            event.changes.forEach { it.consume() }
                        } else if (pressedPointers == 1 && isReaderZoomed(scale)) {
                            zoomAnimation?.cancel()
                            val pan = event.calculatePan()
                            val nextOffset = constrainReaderZoomOffset(
                                offset = ReaderZoomOffset(offset.x + pan.x, offset.y),
                                scale = scale,
                                viewportWidth = viewportSize.width.toFloat(),
                                viewportHeight = viewportSize.height.toFloat(),
                            )
                            offset = nextOffset.toOffset()
                        }
                    } while (event.changes.any { it.pressed })
                }
            },
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(flipConnection)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                },
            userScrollEnabled = true,
        ) {
            itemsIndexed(
                items = pages,
                key = { _, page -> page.cacheKey },
                contentType = { _, _ -> "reader-page" },
            ) { index, page ->
                ReaderPageImage(
                    page = page,
                    aspect = geometry.aspectOf(index),
                    viewportWidthPx = viewportWidthPx,
                    retryKey = retryKeys[index] ?: 0,
                    knownError = failedPages[index],
                    onError = { message -> onPageFailed(index, message) },
                    onLoaded = { width, height -> onPageLoaded(index, width, height) },
                    onRetry = { onRetryPage(index) },
                )
            }
        }
    }
}

/**
 * 换章手势的状态：方向（1 下一话 / -1 上一话）与越过边界后的累计位移。
 *
 * 单独拎成一个持有者，是为了让手势和提示卡分居两处：手势必须挂在 [ReaderPages] 的
 * `LazyColumn` 上（`listState` 和缩放状态都在那儿），提示卡却必须画在顶栏/底栏之上，
 * 也就是外层 Box 的最后一个孩子。以前卡片挂在 [ReaderPages] 里面，顶栏就把章首那张
 * 卡片整个盖住了——看起来正是"顶部下拉没有提示"。
 */
@Stable
private class ReaderFlipHintState(val thresholdPx: Float) {
    var direction by mutableIntStateOf(0)
    var overscroll by mutableFloatStateOf(0f)
    var wasArmed by mutableStateOf(false)
    var reverseTravelPx = 0f

    fun cancelArmedPull() {
        direction = 0
        overscroll = 0f
        wasArmed = false
        reverseTravelPx = 0f
    }

    fun resetAfterRelease() {
        direction = 0
        overscroll = 0f
        wasArmed = false
        reverseTravelPx = 0f
    }
}

/** 换章提示卡要显示的东西。方向、目标话、进度、是否已越过阈值。 */
private data class ReaderFlipCardModel(
    val forward: Boolean,
    val target: ReaderFlipTarget?,
    val progress: Float,
    val armed: Boolean,
)

/** 换章的目标话：卡片上写的"第 3 话 · 全 24 页"就来自这里。页数没预热到时为 null。 */
internal data class ReaderFlipTarget(val label: String, val pageCount: Int?)

/** 取相邻话作为换章目标；越界（首章上拉、末章下拉）返回 null。 */
internal fun readerFlipTarget(
    chapters: List<AlbumChapter>,
    index: Int,
    pageCounts: Map<String, Int>,
): ReaderFlipTarget? {
    val chapter = chapters.getOrNull(index) ?: return null
    return ReaderFlipTarget(
        label = chapter.displayName(index),
        pageCount = pageCounts[chapter.id]?.takeIf { it > 0 },
    )
}

/** 提示卡的副标题："第 3 话 · 全 24 页"；页数还没备好就只写话名。 */
internal fun readerFlipCardSummary(target: ReaderFlipTarget?): String? {
    val label = target?.label?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val count = target.pageCount ?: return label
    return "$label · 全 $count 页"
}

/**
 * 换章手势的位移累加。
 *
 * 乘一个小于 1 的阻尼系数：列表到底后剩下的每一段手指位移都 1:1 记进来的话，
 * 阈值一瞬间就满了，提示卡的两段文案根本来不及看。
 */
internal fun readerFlipOverscroll(current: Float, delta: Float, resistance: Float): Float {
    val base = if (current.isFinite() && current > 0f) current else 0f
    if (!delta.isFinite() || !resistance.isFinite() || resistance <= 0f) return base
    return base + abs(delta) * resistance
}

/** 反向拖动退回一段累计位移的结果：剩下多少、这一段里吃掉了多少（带符号，回给嵌套滚动）。 */
internal data class ReaderFlipRewind(val overscroll: Float, val consumed: Float)

/**
 * 换章手势的位移回退。
 *
 * 上拉过阈值、手指不抬、再往下拉——这一段位移必须先把提示卡的进度退回去，而不是交给列表。
 * 不这么做的话列表自己就能往回滚，[readerFlipOverscroll] 累计的值一直挂在阈值以上，
 * 松手就必然换章，用户的感受是"只要拉过一次阈值就再也退不出来了"。
 *
 * 阻尼与累加时用同一个系数，退回的手感才和拉出去时对称。位移比剩余进度还多时只吃掉
 * 需要的那部分，多出来的还给列表——否则退到 0 的那一帧列表会僵住一下。
 */
internal fun readerFlipRewind(current: Float, delta: Float, resistance: Float): ReaderFlipRewind {
    val base = if (current.isFinite() && current > 0f) current else 0f
    if (!delta.isFinite() || !resistance.isFinite() || resistance <= 0f) {
        return ReaderFlipRewind(base, 0f)
    }
    val available = abs(delta) * resistance
    val used = min(available, base)
    // 退回的位移量按同一阻尼折算回"手指走了多远"，只吃这么多。
    val consumedPx = used / resistance
    return ReaderFlipRewind(
        overscroll = (base - used).coerceAtLeast(0f),
        consumed = if (delta > 0f) consumedPx else -consumedPx,
    )
}

/**
 * 越过阈值后，只要出现一个有效的反向位移就立即撤销本次换章确认。
 * 用户可能已经把手指拉到屏幕边缘，不能再要求一段固定的回滚距离。
 */
internal fun readerFlipShouldCancelAfterReverse(reverseTravelPx: Float, thresholdPx: Float): Boolean {
    if (!reverseTravelPx.isFinite() || !thresholdPx.isFinite() || thresholdPx <= 0f) return false
    return reverseTravelPx >= READER_FLIP_REVERSE_MIN_DELTA_PX
}

/**
 * 拉到章首/章末时的换章提示卡。
 *
 * 走过两版：第一版是一条 3dp 高、96dp 宽的细胶囊，只写"继续上拉进入下一话"——既看不出
 * 还要拉多久，也不知道换过去是哪一话；第二版加了话名与页数，但卡片带半透明、进度轨是手搓的
 * 两层 Box，跟设置页那些 miuix 组件放在一起明显是两套东西。
 *
 * 这一版按 MIUIX 的路子来：不透明的 [Surface] + 阴影（阅读页背景是漫画，半透明会透出画面），
 * 左边一个圆形箭头徽标承担状态——未越阈值是浅色底 + 次级箭头，越过阈值整块染成主色、箭头翻面
 * 并转白，右边是文案、目标话与 miuix 自己的 [LinearProgressIndicator]。文案在两段之间切换时
 * 卡片宽度不变（进度条把宽度撑到上限），不会跟着字数抖一下。
 *
 * 末章下拉、首章上拉只出一句"已经是最后/第一话"，没有进度轨也不震——
 * 让手势看起来能用却什么都不会发生，比直接说不行更让人困惑。
 *
 * 插入的两道 inset 是必须的：卡片现在画在顶栏/底栏之上（见 [ReaderFlipHintState]），
 * 没有 inset 的话章首那张会压在状态栏上。
 */
@Composable
private fun ReaderChapterFlipCard(
    visible: Boolean,
    model: ReaderFlipCardModel?,
    source: ReaderResourceSource,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible && model != null,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier,
    ) {
        val card = model ?: return@AnimatedVisibility
        val canFlip = card.target != null
        val forward = card.forward
        val title = when {
            !canFlip && source is ReaderResourceSource.Offline ->
                if (forward) "后面没有已下载章节" else "前面没有已下载章节"
            !canFlip -> if (forward) "已经是最后一话" else "已经是第一话"
            card.armed -> "松手进入"
            else -> if (forward) "继续上拉进入下一话" else "继续下拉进入上一话"
        }
        val summary = readerFlipCardSummary(card.target)
        val arrowRotation by animateFloatAsState(
            targetValue = if (card.armed) 180f else 0f,
            animationSpec = tween(READER_FLIP_ARROW_ANIMATION_MILLIS),
            label = "flip-arrow",
        )
        // 徽标底色跟着 armed 渐变，而不是"到点了突然变色"：那一下比震动还突然。
        val badgeColor by animateColorAsState(
            targetValue = if (card.armed) {
                MiuixTheme.colorScheme.primary
            } else {
                MiuixTheme.colorScheme.surfaceContainerHighest
            },
            animationSpec = tween(READER_FLIP_ARROW_ANIMATION_MILLIS),
            label = "flip-badge",
        )
        val arrowTint by animateColorAsState(
            targetValue = when {
                card.armed -> MiuixTheme.colorScheme.onPrimary
                canFlip -> MiuixTheme.colorScheme.onSurfaceVariantActions
                else -> MiuixTheme.colorScheme.onSurfaceVariantSummary
            },
            animationSpec = tween(READER_FLIP_ARROW_ANIMATION_MILLIS),
            label = "flip-arrow-tint",
        )
        Surface(
            modifier = Modifier
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 20.dp)
                .widthIn(max = READER_FLIP_CARD_MAX_WIDTH),
            shape = RoundedCornerShape(READER_FLIP_CARD_CORNER),
            color = MiuixTheme.colorScheme.surfaceContainerHigh,
            shadowElevation = READER_FLIP_CARD_ELEVATION,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(READER_FLIP_BADGE_SIZE)
                        .clip(CircleShape)
                        .background(badgeColor),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = if (forward) MiuixIcons.ExpandLess else MiuixIcons.ExpandMore,
                        contentDescription = null,
                        tint = arrowTint,
                        modifier = Modifier
                            .size(18.dp)
                            .graphicsLayer { rotationZ = arrowRotation },
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MiuixTheme.textStyles.body2,
                        fontWeight = FontWeight.Medium,
                        color = if (canFlip) {
                            MiuixTheme.colorScheme.onSurface
                        } else {
                            MiuixTheme.colorScheme.onSurfaceVariantSummary
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (summary != null) {
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = summary,
                            style = MiuixTheme.textStyles.footnote2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (canFlip) {
                        Spacer(modifier = Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = card.progress.coerceIn(0f, 1f),
                            height = READER_FLIP_CARD_TRACK_HEIGHT,
                            colors = ProgressIndicatorDefaults.progressIndicatorColors(
                                backgroundColor = MiuixTheme.colorScheme.surfaceContainerHighest,
                            ),
                        )
                    }
                }
            }
        }
    }
}

/**
 * 一页漫画。
 *
 * 关键约束：**这个 item 从第一次测量起就必须有接近真实的高度。**
 *
 * `LazyListState.scrollToItem` 会 `forceRemeasure()`——恰好在目标页首次被组合的那一次测量里
 * 同步跑完，那时图片还没解出来。若此时高度是 0，LazyColumn 会一路往后组合（整话的请求被
 * 一次性发出，这就是"跳转后下面加载极慢"），仍填不满视口就往回补页，落点因此乱跳；
 * 若高度是个写死的偏大值（旧版 720dp，比 1080p 上真实的 540–580dp 高约 25%），
 * 则每出一张图内容就缩一次——中段是闪烁抖动，靠近末尾时总高收缩触发 LazyList 末端夹取，
 * 表现为"翻到最底、往上翻，上面加载完又被送回底部"。
 *
 * 所以高度不再是常量，而是 [ReaderPageGeometryState] 给出的估算：该页量过就用精确比例，
 * 没量过就用本章已测页的中位数（JM 同章页尺寸高度一致，测到两三页后估算基本无误差），
 * 整章都还没测过才退到 [READER_DEFAULT_PAGE_ASPECT]。比例落盘，二次进同一话第一帧就是准的。
 *
 * 用 [AsyncImage] + `aspectRatio` 而不是 `SubcomposeAsyncImage`：后者每页多一次
 * subcomposition，且在 `State.Empty` 那一帧既不给 loading 槽也没有固有尺寸，正是 0 高的来源。
 */
@Composable
private fun ReaderPageImage(
    page: ReaderPage,
    aspect: Float,
    viewportWidthPx: Int,
    retryKey: Int,
    knownError: String?,
    onError: (String) -> Unit,
    onLoaded: (Int, Int) -> Unit,
    onRetry: () -> Unit,
) {
    val context = LocalContext.current
    val request = remember(page, retryKey, viewportWidthPx) {
        buildReaderImageRequest(context, page, retryKey, viewportWidthPx)
    }
    if (knownError != null) {
        ReaderPageError(
            pageNumber = page.index + 1,
            aspect = aspect,
            message = knownError,
            onRetry = onRetry,
        )
        return
    }
    // 加载状态按 item 实例记：外层按页码记的话，页面被回收再复用时还带着上一次的 true。
    var loading by remember(page.cacheKey, retryKey) { mutableStateOf(true) }
    var spinnerReady by remember(page.cacheKey, retryKey) { mutableStateOf(false) }
    LaunchedEffect(page.cacheKey, retryKey) {
        // 命中内存/磁盘缓存时这一页几乎瞬间就出来，转圈立刻出现再消失就是一下灰闪。
        delay(READER_SPINNER_DELAY_MILLIS)
        spinnerReady = true
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f / aspect)
            .clipToBounds()
            // 占位色与页面背景一致：估算有偏差时露出来的那几像素不会是一条灰边。
            .background(MiuixTheme.colorScheme.surface),
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            model = request,
            contentDescription = "第 ${page.index + 1} 页",
            modifier = Modifier.fillMaxWidth(),
            contentScale = ContentScale.FillWidth,
            alignment = Alignment.TopCenter,
            onSuccess = { state ->
                loading = false
                onLoaded(state.result.image.width, state.result.image.height)
            },
            onError = { state ->
                loading = false
                onError(state.result.throwable.message ?: "图片请求失败")
            },
        )
        AnimatedVisibility(
            visible = loading && spinnerReady,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            ReaderPageSpinner(pageNumber = page.index + 1)
        }
    }
}

@Composable
private fun ReaderPageSpinner(pageNumber: Int) {
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

@Composable
private fun ReaderPageError(pageNumber: Int, aspect: Float, message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f / aspect)
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
    source: ReaderResourceSource,
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
                    text = when (source) {
                        ReaderResourceSource.OnlineJm -> "第 ${chapterIndex + 1} / $chapterCount 话"
                        is ReaderResourceSource.Offline -> "离线 · 第 ${chapterIndex + 1} / $chapterCount 话"
                    },
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
    availableChapterIds: Set<String>?,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    OverlayBottomSheet(
        show = show,
        title = if (availableChapterIds == null) "选择章节" else "离线目录 · 仅已下载章节可读",
        onDismissRequest = onDismiss,
    ) {
        LazyColumn(modifier = Modifier.heightIn(max = 560.dp)) {
            itemsIndexed(
                items = chapters,
                key = { _, chapter -> chapter.id },
            ) { index, chapter ->
                BasicComponent(
                    title = chapter.displayName(index),
                    summary = when {
                        availableChapterIds == null -> "JM${chapter.id}"
                        chapter.id in availableChapterIds -> "JM${chapter.id} · 已下载"
                        else -> "JM${chapter.id} · 未完整下载或文件缺失"
                    },
                    enabled = availableChapterIds == null || chapter.id in availableChapterIds,
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
            SwitchPreference(
                title = "持续阅读",
                summary = "在章首或章末继续拉动，松手切换上一话／下一话",
                checked = settings.continuousChapterFlip,
                onCheckedChange = { onSettingsChange(settings.copy(continuousChapterFlip = it)) },
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
private fun ReaderError(message: String, onRetry: (() -> Unit)?, onBack: () -> Unit) {
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
            if (onRetry != null) TextButton(text = "重试", onClick = onRetry)
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

    /**
     * 最近几话的模板缓存。
     *
     * 换章要重新出网拿模板、再按页算一遍 MD5，"拉到底进下一话"落地时就得先看一屏转圈。
     * 只留 [CHAPTER_CACHE_ENTRIES] 话：一话上百页的 [ReaderPage] 列表本身也占内存，
     * 留多了就和图片的内存缓存互相挤。
     */
    private val chapterCache = LinkedHashMap<String, ReaderChapterState.Content>()
    private val chapterCacheLock = Any()

    /**
     * 载入一话。
     *
     * 整段在 IO 线程上跑：除了出网与解密，[ImagePipeline.plan] 还要按页算一次 MD5，
     * 一话上百页就是上百次——留在调用方（Compose 的 LaunchedEffect，主线程）上，
     * 表现就是"进阅览页转圈快转完时卡一下"。
     */
    suspend fun loadChapter(
        chapterId: String,
        imageHostHint: String?,
    ): ReaderChapterState {
        cachedChapter(chapterId)?.let { return it }
        return withContext(Dispatchers.IO) {
            try {
                loadChapterFromApi(chapterId, imageHostHint)?.let { return@withContext it.cached(chapterId) }
                val templateResult = withTimeoutOrNull(READER_TEMPLATE_TIMEOUT_MILLIS) {
                    core.chapterApi.template(chapterId, shunt = DEFAULT_IMAGE_SHUNT)
                } ?: return@withContext ReaderChapterState.Error("章节准备超时，请检查网络后重试。")
                when (val result = templateResult) {
                    is JmxResult.Success -> result.value.toReaderState().cached(chapterId)
                    is JmxResult.Failure -> ReaderChapterState.Error(result.error.toUiMessage())
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                ReaderChapterState.Error(error.message ?: "章节加载出现未知异常。")
            }
        }
    }

    private fun cachedChapter(chapterId: String): ReaderChapterState.Content? =
        synchronized(chapterCacheLock) {
            // 命中的那一话顺手移到队尾，淘汰的永远是最久没碰过的。
            chapterCache.remove(chapterId)?.also { chapterCache[chapterId] = it }
        }

    private fun ReaderChapterState.cached(chapterId: String): ReaderChapterState {
        if (this !is ReaderChapterState.Content) return this
        synchronized(chapterCacheLock) {
            chapterCache.remove(chapterId)
            chapterCache[chapterId] = this
            while (chapterCache.size > CHAPTER_CACHE_ENTRIES) {
                val oldest = chapterCache.keys.firstOrNull() ?: break
                chapterCache.remove(oldest)
            }
        }
        return this
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
            // 兜底改为线路表当前最优的那台，而不是内置表的第一台：
            // 后者是写死的顺序，被墙或过载时整章白屏且不会自愈。
            ?: core.imageHostRegistry.current()
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
        // /chapter 给的 data_original_domain 未必在内置线路表里，先并进去，
        // 这一章的取图才会被选路拦截器接管（失败自动换机、成败计入健康度）。
        core.imageHostRegistry.rememberHost(imageHost)
        val headers = ImageHttpHeaders.default(refererHost = imageHost).toCoilHeaders()
        val pages = imageUrls.mapIndexed { index, url ->
            ReaderPage(
                index = index,
                resource = ReaderPageResource.OnlineJm(
                    url = url,
                    plan = imagePipeline.plan(url, albumId, scrambleId),
                    headers = headers,
                ),
            )
        }
        return if (pages.isEmpty()) {
            ReaderChapterState.Error("章节没有返回可阅读的图片。")
        } else {
            ReaderChapterState.Content(pages = pages)
        }
    }

    /**
     * 只把一话的模板备好并交出来，不取图。
     *
     * 换章提示卡要在用户拉动之前就知道"下一话全几页"，那时不该为了一个页数去下图。
     * 失败静默返回 null——提示卡少一行字而已。
     */
    suspend fun prepareChapter(chapterId: String, imageHostHint: String?): ReaderChapterState.Content? =
        cachedChapter(chapterId) ?: loadChapter(chapterId, imageHostHint) as? ReaderChapterState.Content

    /**
     * 预热相邻的一话：把模板与头几页备好。
     *
     * 只在快读到章首/章末时调用（见 ComicReaderContent 里的预热效果），"拉到底换章"
     * 落地时才不是先看一屏转圈。失败静默——这只是提速，不该把错误提示给用户。
     */
    suspend fun warmUpChapter(chapterId: String, imageHostHint: String?, viewportWidthPx: Int) {
        val content = prepareChapter(chapterId, imageHostHint) ?: return
        content.pages.take(CHAPTER_WARMUP_PAGES).forEach { page ->
            try {
                imageLoader.execute(
                    buildReaderImageRequest(
                        context = applicationContext,
                        page = page,
                        retryKey = 0,
                        viewportWidthPx = viewportWidthPx,
                    ),
                )
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                return
            }
        }
    }

    private companion object {
        const val CHAPTER_CACHE_ENTRIES = 3
        const val CHAPTER_WARMUP_PAGES = 2
    }
}

/**
 * 建一页的图片请求。
 *
 * 混淆页不再走 `Size.ORIGINAL`：原图按视口宽解出来就够看，而 `ORIGINAL` 会把一张
 * 1080×1500 的页整解成 6MB 再让变换复制一份，单页峰值约 14MB——48–64MB 的内存缓存
 * 只装得下七八页，回翻必然重解码。[viewportWidthPx] 一并进 memoryCacheKey：
 * 同一页在不同宽度下解出来的位图不是一份东西。
 */
internal fun buildReaderImageRequest(
    context: Context,
    page: ReaderPage,
    retryKey: Int,
    viewportWidthPx: Int,
): ImageRequest {
    val resource = when (val resource = page.resource) {
        is ReaderPageResource.RestoredLocal -> return buildReaderOfflineImageRequest(
            context, resource, retryKey, viewportWidthPx,
        )
        is ReaderPageResource.OnlineJm -> resource
    }
    val builder = ImageRequest.Builder(context)
        .data(resource.url)
        .httpHeaders(resource.headers)
        .allowHardware(!resource.plan.requiresRestore)
        .crossfade(false)
        .memoryCacheKey(
            "${page.cacheKey}:reader:${resource.plan.segmentCount}:" +
                "$READER_RESTORE_VERSION:$viewportWidthPx:$retryKey",
        )
        .diskCacheKey(page.cacheKey)
    if (resource.plan.requiresRestore) {
        builder
            .size(Size(viewportWidthPx, Dimension.Undefined))
            .decoderFactory(JmxScrambledDecoderFactory(resource.plan))
    }
    return builder.build()
}

/**
 * 混淆页解码器：整图连续解一次，再把段按原分辨率 1:1 搬回原位。
 *
 * 相比 2.5.0 的 Coil `Transformation`，这里把「解码 + 还原」合成一步，请求尺寸也从
 * `Size.ORIGINAL` 收到视口宽——不过降采样有前提，见下。
 *
 * **接缝上一个像素都不能重算。** 2.6.0 开发期出过两轮横线，两轮的根因不同：
 * 1. 第一版按段解出来后画进「缩放后的目标矩形」，每段各自重采样一次，
 *    相邻段在接缝处的插值相位对不上，于是有段数那么多条均匀分布的横线；
 * 2. 第二版改成 `BitmapRegionDecoder` 逐段解 + 原分辨率整段拷贝，横线仍在——
 *    JPEG 的区域解码是从区域上边界重新起算的，色度上采样拿不到上一行的上下文，
 *    每段首行与「整图连续解出来的同一行」总会差出一点，接缝照样看得见。
 *
 * 所以这里只留一条路：**整图连续解一次**（上采样上下文完整），段与段之间只做等宽
 * 等高、整数偏移、不带 paint 的拷贝，缩到视口宽交给上层对整张图做一次。降采样同理
 * 只在分段边界能被采样倍数整除时才敢用（见 [readerScrambledSampleSize]），
 * 否则边界那一行会被采样器和邻段的像素混在一起——又是一条线。
 *
 * 段的划分仍由 core 的 [ImagePipeline.restoreMoves] 决定（已有单测），本类只负责
 * 把段搬到正确的位置。
 */
private class JmxScrambledDecoder(
    private val source: ImageSource,
    private val options: Options,
    private val plan: ImagePlan,
    private val pipeline: ImagePipeline = ImagePipeline(),
) : Decoder {
    override suspend fun decode(): DecodeResult = withContext(Dispatchers.IO) {
        val bytes = source.source().use { it.readByteArray() }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val sourceWidth = bounds.outWidth
        val sourceHeight = bounds.outHeight
        val requested = options.size.width.pxOrElse { 0 }
        val targetWidth = if (sourceWidth > 0 && requested in 1 until sourceWidth) {
            requested
        } else {
            sourceWidth
        }
        val sampleSize = readerScrambledSampleSize(
            sourceWidth = sourceWidth,
            sourceHeight = sourceHeight,
            targetWidth = targetWidth,
            segmentCount = plan.segmentCount,
        )
        val bitmap = decodeAndRestore(bytes, sampleSize)
        DecodeResult(
            image = bitmap.asImage(),
            isSampled = sampleSize > 1,
        )
    }

    private fun decodeAndRestore(bytes: ByteArray, sampleSize: Int): Bitmap {
        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val input = requireNotNull(
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOptions),
        ) { "图片解码失败" }
        val rects = readerSegmentRects(input.height, plan.segmentCount, pipeline)
        // 只有一段（或拿不到分段信息）的页本来就不用还原，省掉一次整图拷贝。
        if (rects.isEmpty()) return input
        val output = createBitmap(
            input.width,
            input.height,
            input.config ?: Bitmap.Config.ARGB_8888,
        ).apply {
            density = input.density
            setHasAlpha(input.hasAlpha())
        }
        val canvas = Canvas(output)
        rects.forEach { rect ->
            // src 与 dst 等宽等高、偏移是整数、不给 paint：不缩放、不滤波、不抖动，
            // 按行严丝合缝地拼。接缝处的像素值与整图解出来的完全一致。
            canvas.drawBitmap(
                input,
                Rect(0, rect.sourceTop, input.width, rect.sourceBottom),
                Rect(0, rect.targetTop, input.width, rect.targetBottom),
                null,
            )
        }
        input.recycle()
        return output
    }
}

private class JmxScrambledDecoderFactory(private val plan: ImagePlan) : Decoder.Factory {
    override fun create(
        result: SourceFetchResult,
        options: Options,
        imageLoader: ImageLoader,
    ): Decoder? {
        if (!plan.requiresRestore) return null
        return JmxScrambledDecoder(result.source, options, plan)
    }

    override fun equals(other: Any?): Boolean =
        other is JmxScrambledDecoderFactory && other.plan == plan

    override fun hashCode(): Int = plan.hashCode()
}

/** 一段在原图与输出图上的位置。上下边界都是含头不含尾。 */
internal data class ReaderSegmentRect(
    val sourceTop: Int,
    val sourceBottom: Int,
    val targetTop: Int,
    val targetBottom: Int,
)

/**
 * [ImagePipeline.restoreMoves] 的分段映射，按输出图上的位置排好。
 *
 * `restoreMoves` 给出的 `targetY` 恰好是前面各段高度的累加值，各段又正好铺满
 * `[0, sourceHeight)`，所以这里排完序就是一张无缝、不重叠、总高守恒的拼图，
 * 调用方按整数偏移原样搬运即可。
 *
 * 这里**不做缩放**：2.6.0 的第一版把 target 坐标乘上 `targetWidth / sourceWidth`，
 * 于是每段都要独立重采样一次，接缝处的插值相位对不上，页面上出现段数那么多条横线。
 * 缩到视口宽由上层对整张图做一次。
 */
internal fun readerSegmentRects(
    sourceHeight: Int,
    segmentCount: Int,
    pipeline: ImagePipeline = ImagePipeline(),
): List<ReaderSegmentRect> {
    if (sourceHeight <= 0 || segmentCount <= 1) return emptyList()
    val moves = pipeline.restoreMoves(sourceHeight, segmentCount)
    if (moves.isEmpty()) return emptyList()
    return moves
        .sortedBy { it.targetY }
        .map { move ->
            ReaderSegmentRect(
                sourceTop = move.sourceY,
                sourceBottom = move.sourceY + move.height,
                targetTop = move.targetY,
                targetBottom = move.targetY + move.height,
            )
        }
}

/**
 * 解码时的降采样倍数：不低于目标宽度的最大 2 的幂。
 *
 * 宁可解得比目标宽一点再由画布缩下去，也不能解得比目标窄——那是真的糊。
 */
internal fun readerSampleSize(sourceWidth: Int, targetWidth: Int): Int {
    if (sourceWidth <= 0 || targetWidth <= 0) return 1
    var sample = 1
    while (sourceWidth / (sample * 2) >= targetWidth) {
        sample *= 2
    }
    return sample
}

/**
 * 混淆页能用的降采样倍数：在 [readerSampleSize] 的基础上，只保留**分段边界能被整除**的那一档。
 *
 * 降采样是把相邻的 `sampleSize` 行合成一行。分段边界如果落在这样一组行的中间，
 * 合出来的那一行就混进了邻段的像素——还原之后，段数那么多条横线又回来了。
 * 段高是 `sourceHeight / segmentCount`，余数全给第一段（见 `ImagePipeline.restoreMoves`），
 * 所以段高与余数都能被整除时，采样后的分段划分与原图严格成比例，接缝才是干净的。
 *
 * JM 的页宽大多在 1000–1300，视口 1080，本来就落在 1 倍这一档；这里主要是给畸形大图
 * 留一条既省内存又不出线的路，实在对不齐就老老实实按原分辨率解。
 */
internal fun readerScrambledSampleSize(
    sourceWidth: Int,
    sourceHeight: Int,
    targetWidth: Int,
    segmentCount: Int,
): Int {
    if (sourceHeight <= 0) return 1
    var sample = readerSampleSize(sourceWidth, targetWidth)
    if (segmentCount <= 1) return sample
    val baseHeight = sourceHeight / segmentCount
    val remainder = sourceHeight % segmentCount
    while (sample > 1) {
        if (baseHeight % sample == 0 && remainder % sample == 0) return sample
        sample /= 2
    }
    return 1
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

/**
 * 落位到目标页，并在随后一两帧里确认落点。
 *
 * 目标页首次被组合的那一次测量（`scrollToItem` 内部的 `forceRemeasure()` 就发生在那里）
 * 未必量得到真实高度，LazyColumn 会因为填不满视口而把落点往回拉，见 ReaderPageImage 的说明。
 * [ReaderPageImage] 已经用占位高度堵住了这个洞，这里只作为兜底再确认一次。
 *
 * 次数刻意压得很小：真到了章节末尾余量不足时，落点本就该被夹住，不该无限纠正。
 * 用户此时若已开始拖动列表，[LazyListState.scrollToItem] 会被优先级更高的手势抢掉滚动权
 * 并抛出取消，这个循环随之安静结束。
 */
private suspend fun LazyListState.scrollToReaderPage(target: Int) {
    scrollToItem(target)
    repeat(READER_JUMP_SETTLE_ATTEMPTS) {
        withFrameNanos { }
        if (firstVisibleItemIndex == target) return
        scrollToItem(target)
    }
}

internal fun isReaderZoomed(scale: Float): Boolean =
    scale > READER_MIN_ZOOM + READER_ZOOM_EPSILON

internal data class ReaderZoomOffset(
    val x: Float,
    val y: Float,
) {
    fun toOffset(): Offset = Offset(x, y)
}

internal data class ReaderZoomTransform(
    val scale: Float,
    val offset: ReaderZoomOffset,
)

internal fun constrainReaderZoomOffset(
    offset: ReaderZoomOffset,
    scale: Float,
    viewportWidth: Float,
    viewportHeight: Float,
): ReaderZoomOffset {
    val safeScale = scale.coerceIn(READER_MIN_ZOOM, READER_MAX_ZOOM)
    val maxX = (viewportWidth * (safeScale - 1f) / 2f).coerceAtLeast(0f)
    val maxY = (viewportHeight * (safeScale - 1f) / 2f).coerceAtLeast(0f)
    return ReaderZoomOffset(
        x = offset.x.coerceIn(-maxX, maxX),
        y = offset.y.coerceIn(-maxY, maxY),
    )
}

internal fun readerZoomOffsetAfterGesture(
    currentOffset: ReaderZoomOffset,
    currentScale: Float,
    requestedScale: Float,
    focusX: Float,
    focusY: Float,
    viewportWidth: Float,
    viewportHeight: Float,
    panX: Float = 0f,
    panY: Float = 0f,
): ReaderZoomTransform {
    val nextScale = requestedScale.coerceIn(READER_MIN_ZOOM, READER_MAX_ZOOM)
    if (!isReaderZoomed(nextScale) || viewportWidth <= 0f || viewportHeight <= 0f) {
        return ReaderZoomTransform(READER_MIN_ZOOM, ReaderZoomOffset(0f, 0f))
    }
    val safeCurrentScale = currentScale.coerceIn(READER_MIN_ZOOM, READER_MAX_ZOOM)
    val ratio = nextScale / safeCurrentScale
    val centerX = viewportWidth / 2f
    val centerY = viewportHeight / 2f
    val relativeFocusX = if (focusX.isFinite()) focusX - centerX else 0f
    val relativeFocusY = if (focusY.isFinite()) focusY - centerY else 0f
    val candidate = ReaderZoomOffset(
        x = currentOffset.x * ratio + relativeFocusX * (1f - ratio) + panX,
        y = currentOffset.y * ratio + relativeFocusY * (1f - ratio) + panY,
    )
    return ReaderZoomTransform(
        scale = nextScale,
        offset = constrainReaderZoomOffset(
            offset = candidate,
            scale = nextScale,
            viewportWidth = viewportWidth,
            viewportHeight = viewportHeight,
        ),
    )
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
    val continuousChapterFlip: Boolean = true,
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
            continuousChapterFlip = preferences.getBoolean(READER_CONTINUOUS_FLIP, true),
        )
    }

    fun save(settings: ReaderSettings) {
        preferences.edit {
            putBoolean(READER_VOLUME_KEYS, settings.volumeKeyPaging)
            putBoolean(READER_BATTERY_TIME, settings.showBatteryTime)
            putBoolean(READER_PAGE_NUMBER, settings.showPageNumber)
            putBoolean(READER_CONTINUOUS_FLIP, settings.continuousChapterFlip)
        }
    }
}

/** 换章后落在哪一页。 */
internal enum class ReaderChapterLanding {
    /** 按阅读进度或第一页。 */
    INITIAL,

    /** 最后一页——上拉回看上一话时的落点。 */
    LAST,
}

/** 视口宽度（像素）。解码与内存缓存键都按它算。 */
private fun readerViewportWidthPx(context: Context): Int =
    context.resources.displayMetrics.widthPixels.coerceAtLeast(READER_MIN_VIEWPORT_WIDTH_PX)

/** 高宽比转成千分数存：一页只占四五个字符，一话四百页也就两千字节。 */
internal fun readerHeightPermille(width: Int, height: Int): Int {
    if (width <= 0 || height <= 0) return 0
    return (height.toLong() * 1000L / width).toInt()
}

/** 已测页高宽比的中位数。取中位数而不是均值：偶尔一页跨页大图不该把整章的估算带偏。 */
internal fun readerMedianPermille(values: Collection<Int>): Int {
    val measured = values.filter { it > 0 }.sorted()
    if (measured.isEmpty()) return 0
    return measured[measured.size / 2]
}

/**
 * 这一页占位用多高。
 *
 * 优先用这一页量到的精确值，其次用本章已测页的中位数（JM 同章各页尺寸高度一致，
 * 测到两三页后误差就可以忽略），整章都没测过才退到 [fallback]。
 * 上下夹一刀是防畸形图：一张 1×20000 的图不该撑出一个几万 dp 高的 item。
 */
internal fun readerPageAspect(measuredPermille: Int, estimatePermille: Int, fallback: Float): Float {
    val chosen = if (measuredPermille > 0) measuredPermille else estimatePermille
    if (chosen <= 0) return fallback
    return (chosen / 1000f).coerceIn(READER_MIN_PAGE_ASPECT, READER_MAX_PAGE_ASPECT)
}

/** 逗号分隔的千分数；未测过的页留空位，末尾连续的空位截掉。 */
internal fun encodeReaderPageGeometry(permille: List<Int>): String {
    val lastMeasured = permille.indexOfLast { it > 0 }
    if (lastMeasured < 0) return ""
    return permille.take(lastMeasured + 1).joinToString(",") { if (it > 0) it.toString() else "" }
}

internal fun decodeReaderPageGeometry(raw: String?): List<Int> {
    if (raw.isNullOrEmpty()) return emptyList()
    return raw.split(',').map { entry -> entry.trim().toIntOrNull()?.takeIf { it > 0 } ?: 0 }
}

/** 保留哪些章节的几何记录。最近读的排在前面，超出上限的连数据一起清掉。 */
internal data class ReaderGeometryIndex(val kept: List<String>, val evicted: List<String>)

internal fun readerGeometryIndex(previous: String?, chapterId: String, limit: Int): ReaderGeometryIndex {
    val ordered = buildList {
        add(chapterId)
        previous?.split(',')?.forEach { raw ->
            val id = raw.trim()
            if (id.isNotEmpty() && id != chapterId && id !in this) add(id)
        }
    }
    return ReaderGeometryIndex(
        kept = ordered.take(limit.coerceAtLeast(1)),
        evicted = ordered.drop(limit.coerceAtLeast(1)),
    )
}

/**
 * 每页高宽比的本机记录。
 *
 * 落盘的意义在第二次进同一话：那时首帧的占位就已经是准的，从第一屏起就没有高度跳变。
 */
private class ReaderPageGeometryStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        READER_GEOMETRY_PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    fun load(chapterId: String): List<Int> =
        decodeReaderPageGeometry(preferences.getString(chapterKey(chapterId), null))

    fun save(chapterId: String, permille: List<Int>) {
        val encoded = encodeReaderPageGeometry(permille)
        val index = readerGeometryIndex(
            previous = preferences.getString(READER_GEOMETRY_INDEX_KEY, null),
            chapterId = chapterId,
            limit = READER_GEOMETRY_CHAPTER_LIMIT,
        )
        preferences.edit {
            if (encoded.isEmpty()) remove(chapterKey(chapterId)) else putString(chapterKey(chapterId), encoded)
            putString(READER_GEOMETRY_INDEX_KEY, index.kept.joinToString(","))
            index.evicted.forEach { remove(chapterKey(it)) }
        }
    }

    private fun chapterKey(chapterId: String): String = "$READER_GEOMETRY_KEY_PREFIX$chapterId"
}

/**
 * 一话里各页的高宽比，随读随测。
 *
 * 已测值按页码存在 [SnapshotStateList] 里，量到新的一页就地更新占位；中位数缓存在
 * [estimatedPermille]，不在 [aspectOf] 里重算——那是每页每帧都要走的路径。
 */
@Stable
private class ReaderPageGeometryState(initial: List<Int>) {
    private val permille = mutableStateListOf<Int>().apply { addAll(initial) }
    private val samples = ArrayDeque<Int>()
    private var estimatedPermille by mutableIntStateOf(0)

    /**
     * 实测的解码宽度（只在运行期用，不落盘）。
     *
     * 混淆页几乎总是按原图宽解出来（见 [JmxScrambledDecoder]：只有分段边界能被采样
     * 倍数整除时才敢降采样，否则接缝会出横线），原图比视口宽一截时按视口宽算单页字节数
     * 就偏小，预取窗口会开得过深，把已看过的页从内存缓存里挤出去。
     */
    private var decodedWidthPx by mutableIntStateOf(0)

    var dirty: Boolean = false
        private set

    init {
        initial.filter { it > 0 }.takeLast(READER_GEOMETRY_SAMPLE_LIMIT).forEach(samples::addLast)
        estimatedPermille = readerMedianPermille(samples)
    }

    fun aspectOf(index: Int): Float = readerPageAspect(
        measuredPermille = permille.getOrNull(index) ?: 0,
        estimatePermille = estimatedPermille,
        fallback = READER_DEFAULT_PAGE_ASPECT,
    )

    /** 本章当前的整体估算，供预取按单页字节数算窗口深度。 */
    fun estimatedAspect(): Float = readerPageAspect(0, estimatedPermille, READER_DEFAULT_PAGE_ASPECT)

    /** 算单页字节数时用的宽度：取视口宽与实测解码宽的较大者。 */
    fun estimatedWidthPx(viewportWidthPx: Int): Int = maxOf(viewportWidthPx, decodedWidthPx)

    fun record(index: Int, imageWidth: Int, imageHeight: Int) {
        if (index < 0) return
        // 宽度先记：下面按高宽比去重会提前返回，而同一章各页宽度相同，
        // 记在 return 之后就只有第一页能更新到。
        if (imageWidth > decodedWidthPx) decodedWidthPx = imageWidth
        val value = readerHeightPermille(imageWidth, imageHeight)
        if (value <= 0) return
        while (permille.size <= index) permille.add(0)
        if (permille[index] == value) return
        permille[index] = value
        if (samples.size >= READER_GEOMETRY_SAMPLE_LIMIT) samples.removeFirst()
        samples.addLast(value)
        estimatedPermille = readerMedianPermille(samples)
        dirty = true
    }

    fun snapshot(): List<Int> = permille.toList()

    fun markSaved() {
        dirty = false
    }
}

/** 预取窗口：往滚动方向开 [ahead] 页，反方向留 [behind] 页。 */
internal data class ReaderPrefetchPlan(val ahead: Int, val behind: Int)

/**
 * 一页解码后大约占多少字节。
 *
 * 按视口宽 × 估算高 × 4（ARGB_8888）。这是窗口深度的分母，宁可估大一点：
 * 估小了窗口就开得过深，把已看过的页从内存缓存里挤出去，回翻又要重解码。
 */
internal fun readerEstimatedPageBytes(viewportWidthPx: Int, aspect: Float): Long {
    if (viewportWidthPx <= 0 || !aspect.isFinite() || aspect <= 0f) return 0L
    val height = (viewportWidthPx * aspect).toLong().coerceAtLeast(1L)
    return viewportWidthPx.toLong() * height * 4L
}

/**
 * 预取深度上限：拿内存缓存的一半来装预取页。
 *
 * 旧代码把这个数写死成 2，顾虑是"再多就要和已看过的页互相挤"——顾虑本身是对的，
 * 但写死的常数在 400 页漫画上就成了没有缓冲。改成按预算算：装得下多少就开多深。
 */
internal fun readerPrefetchDepth(budgetBytes: Long, pageBytes: Long): Int {
    if (budgetBytes <= 0L || pageBytes <= 0L) return READER_PREFETCH_MIN_DEPTH
    return (budgetBytes / 2L / pageBytes)
        .coerceIn(READER_PREFETCH_MIN_DEPTH.toLong(), READER_PREFETCH_MAX_DEPTH.toLong())
        .toInt()
}

/**
 * 翻得越快，往前开得越深；反方向始终留一点，回翻头两页才不必重下。
 */
internal fun readerPrefetchPlan(
    budgetBytes: Long,
    pageBytes: Long,
    velocity: Float,
): ReaderPrefetchPlan {
    val depth = readerPrefetchDepth(budgetBytes, pageBytes)
    val safeVelocity = if (velocity.isFinite()) abs(velocity) else 0f
    val ahead = (READER_PREFETCH_MIN_DEPTH + READER_PREFETCH_VELOCITY_GAIN * safeVelocity)
        .roundToInt()
        .coerceIn(READER_PREFETCH_MIN_DEPTH, depth)
    val behind = (ahead / 3).coerceIn(1, READER_PREFETCH_MAX_BEHIND)
    return ReaderPrefetchPlan(ahead = ahead, behind = behind)
}

/**
 * 该预取哪些页，按发起顺序排好。
 *
 * 顺序是"离当前页近的先发"，同距时滚动方向那边优先——带宽有限，先到的那几页
 * 才是用户下一秒真会看到的。当前页不在窗口里：它由 UI 自己在加载。
 */
internal fun readerPrefetchWindow(
    center: Int,
    ahead: Int,
    behind: Int,
    forward: Boolean,
    pageCount: Int,
): List<Int> {
    if (pageCount <= 0) return emptyList()
    val safeCenter = center.coerceIn(0, pageCount - 1)
    val forwardSpan = (if (forward) ahead else behind).coerceAtLeast(0)
    val backwardSpan = (if (forward) behind else ahead).coerceAtLeast(0)
    return ((safeCenter - backwardSpan)..(safeCenter + forwardSpan))
        .filter { it in 0 until pageCount && it != safeCenter }
        .sortedWith(
            compareBy(
                { abs(it - safeCenter) },
                { if ((it > safeCenter) == forward) 0 else 1 },
            ),
        )
}

/** 翻页速度（页/秒）的指数滑动平均。单帧的瞬时值抖得厉害，直接用会让窗口忽大忽小。 */
internal fun readerPageVelocity(
    previousVelocity: Float,
    pageDelta: Int,
    elapsedSeconds: Float,
): Float {
    if (!elapsedSeconds.isFinite() || elapsedSeconds <= 0f) return previousVelocity
    val instant = abs(pageDelta) / elapsedSeconds
    val smoothed = previousVelocity * (1f - READER_VELOCITY_SMOOTHING) +
        instant * READER_VELOCITY_SMOOTHING
    return smoothed.coerceIn(0f, READER_MAX_TRACKED_VELOCITY)
}

/**
 * 阅读器预取控制器：窗口式、双向、按方向与速度调整深度、并发受限、出窗即取消。
 *
 * 旧实现是"当前页下完之后，串行往后取两页"。三个毛病叠在一起，跳到第 200 页后就崩：
 * 门槛让预取在最需要的时候（当前页还在下载）根本不启动；只朝前，往上翻等于没有缓冲；
 * 深度写死成 2，追不上翻页速度。这里三处都改掉，并且旧窗口的请求一出窗就取消——
 * 否则跳页后新窗口要排在一串没人要的请求后面，用户感觉"越跳越慢"。
 */
private class ReaderPrefetchController(
    context: Context,
    private val imageLoader: ImageLoader,
    private val scope: CoroutineScope,
) {
    private val applicationContext = context.applicationContext
    private val semaphore = Semaphore(READER_PREFETCH_CONCURRENCY)
    private val inFlight = mutableMapOf<Int, Job>()
    private val succeeded = mutableSetOf<Int>()
    private var chapterKey: String? = null

    fun update(
        chapterId: String,
        pages: List<ReaderPage>,
        center: Int,
        forward: Boolean,
        velocity: Float,
        viewportWidthPx: Int,
        decodedWidthPx: Int,
        pageAspect: Float,
    ) {
        if (pages.isEmpty()) return
        val plan = readerPrefetchPlan(
            budgetBytes = imageLoader.memoryCache?.maxSize ?: 0L,
            pageBytes = readerEstimatedPageBytes(decodedWidthPx, pageAspect),
            velocity = velocity,
        )
        val window = readerPrefetchWindow(
            center = center,
            ahead = plan.ahead,
            behind = plan.behind,
            forward = forward,
            pageCount = pages.size,
        )
        val launching = synchronized(this) {
            if (chapterKey != chapterId) {
                chapterKey = chapterId
                cancelAllLocked()
            }
            inFlight.entries.removeAll { (index, job) ->
                if (index in window) {
                    false
                } else {
                    job.cancel()
                    true
                }
            }
            window.filter { it !in succeeded && it !in inFlight.keys }
        }
        launching.forEach { index -> launch(pages[index], index, viewportWidthPx) }
    }

    fun markSucceeded(index: Int) {
        synchronized(this) { succeeded.add(index) }
    }

    fun clear() {
        synchronized(this) {
            cancelAllLocked()
            succeeded.clear()
            chapterKey = null
        }
    }

    private fun cancelAllLocked() {
        inFlight.values.forEach(Job::cancel)
        inFlight.clear()
        succeeded.clear()
    }

    private fun launch(page: ReaderPage, index: Int, viewportWidthPx: Int) {
        val job = scope.launch {
            semaphore.withPermit {
                val result = try {
                    imageLoader.execute(
                        buildReaderImageRequest(
                            context = applicationContext,
                            page = page,
                            retryKey = 0,
                            viewportWidthPx = viewportWidthPx,
                        ),
                    )
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    null
                }
                // 失败的页不记成功：留在窗口里，下一次 update 会再试一次。
                if (result is SuccessResult) markSucceeded(index)
            }
        }
        val replaced = synchronized(this) {
            val previous = inFlight.put(index, job)
            previous
        }
        replaced?.cancel()
        job.invokeOnCompletion {
            synchronized(this) { if (inFlight[index] === job) inFlight.remove(index) }
        }
    }
}

/** 换章手势的进度与"已越过阈值"。方向由调用方持有，这里只看位移大小。 */
internal data class ReaderFlipState(val progress: Float, val armed: Boolean)

internal fun continuousFlipState(
    overscrollPx: Float,
    thresholdPx: Float,
    canFlip: Boolean,
): ReaderFlipState {
    if (!canFlip || thresholdPx <= 0f || overscrollPx <= 0f || !overscrollPx.isFinite()) {
        return ReaderFlipState(progress = 0f, armed = false)
    }
    return ReaderFlipState(
        progress = (overscrollPx / thresholdPx).coerceIn(0f, 1f),
        armed = overscrollPx >= thresholdPx,
    )
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
        val sticky = context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
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
private fun ReaderSystemBarsEffect(
    immersive: Boolean,
    barColor: Int,
    useDarkIcons: Boolean,
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    DisposableEffect(activity, immersive, barColor, useDarkIcons) {
        val window = activity?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        val previousLightStatusBars = controller?.isAppearanceLightStatusBars
        val previousLightNavigationBars = controller?.isAppearanceLightNavigationBars
        @Suppress("DEPRECATION")
        val previousStatusBarColor = window?.statusBarColor
        @Suppress("DEPRECATION")
        val previousNavigationBarColor = window?.navigationBarColor
        @Suppress("DEPRECATION")
        if (window != null) {
            window.statusBarColor = barColor
            window.navigationBarColor = barColor
        }
        controller?.isAppearanceLightStatusBars = useDarkIcons
        controller?.isAppearanceLightNavigationBars = useDarkIcons
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
            @Suppress("DEPRECATION")
            if (window != null && previousStatusBarColor != null && previousNavigationBarColor != null) {
                window.statusBarColor = previousStatusBarColor
                window.navigationBarColor = previousNavigationBarColor
            }
        }
    }
}

internal tailrec fun Context.findActivity(): Activity? {
    return when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
}

private fun Map<String, String>.toCoilHeaders(): NetworkHeaders {
    return NetworkHeaders.Builder().apply {
        forEach { (name, value) -> add(name, value) }
    }.build()
}

private fun formatReaderTime(): String {
    return SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
}

private const val READER_PREFERENCES_NAME = "reader_settings"
private const val READER_VOLUME_KEYS = "volume_key_paging"
private const val READER_BATTERY_TIME = "show_battery_time"
private const val READER_PAGE_NUMBER = "show_page_number"
private const val READER_CONTINUOUS_FLIP = "continuous_chapter_flip"
private const val READER_PROGRESS_SAVE_DELAY_MILLIS = 350L

/** 页面几何记录。 */
private const val READER_GEOMETRY_PREFERENCES_NAME = "reader_page_geometry"
private const val READER_GEOMETRY_KEY_PREFIX = "chapter_"
private const val READER_GEOMETRY_INDEX_KEY = "recent_chapters"
private const val READER_GEOMETRY_CHAPTER_LIMIT = 40
private const val READER_GEOMETRY_FLUSH_INTERVAL_MILLIS = 5_000L

/** 估算高宽比时最多参考多少个已测页。JM 同章各页尺寸高度一致，取样再多也不会更准。 */
private const val READER_GEOMETRY_SAMPLE_LIMIT = 16

/** 整章都还没测出尺寸时的兜底高宽比：约等于 JM 页在 1080p 上按宽铺满的实际比例。 */
private const val READER_DEFAULT_PAGE_ASPECT = 1.42f
private const val READER_MIN_PAGE_ASPECT = 0.05f
private const val READER_MAX_PAGE_ASPECT = 20f

/** 转圈延迟出现的时间：命中缓存的页在这之前就画出来了，于是完全看不到灰块。 */
private const val READER_SPINNER_DELAY_MILLIS = 180L
private const val READER_MIN_VIEWPORT_WIDTH_PX = 360

/** 预取。深度按内存预算算，这里只给上下限与档位。 */
private const val READER_PREFETCH_CONCURRENCY = 3
private const val READER_PREFETCH_MIN_DEPTH = 2
private const val READER_PREFETCH_MAX_DEPTH = 12
private const val READER_PREFETCH_MAX_BEHIND = 3
private const val READER_PREFETCH_VELOCITY_GAIN = 0.8f
private const val READER_VELOCITY_SMOOTHING = 0.35f
private const val READER_MAX_TRACKED_VELOCITY = 20f

/** 距章首/章末几页开始预热相邻的一话。 */
private const val READER_CHAPTER_WARMUP_DISTANCE = 2

/**
 * 持续阅读：越过这段位移就算"要换章"。
 *
 * 96dp 时进度条几乎是一瞬间就满的，用户来不及看清提示卡在说什么就已经越过阈值，
 * 于是"松手换章"像是被误触的。阈值抬到 132dp 并配上 [READER_FLIP_DRAG_RESISTANCE]，
 * 手指要走约 240dp 才填满，两段提示（继续拉 → 松手进入）才都有存在的时间。
 */
private val READER_CHAPTER_FLIP_THRESHOLD = 132.dp

/** 提示卡的宽度上限：再宽在小屏上就贴边了。 */
private val READER_FLIP_CARD_MAX_WIDTH = 280.dp

/** 提示卡进度轨的高度。上一版是 3dp，太细，填充过程根本看不出来；6dp 正是 miuix 的默认值。 */
private val READER_FLIP_CARD_TRACK_HEIGHT = 6.dp

/** 提示卡圆角，取 miuix Card 那一档（16dp）稍大一点，卡片本身比 Card 矮。 */
private val READER_FLIP_CARD_CORNER = 20.dp

/** 提示卡阴影：背景是漫画本身，没有阴影会看不出卡片浮在画面之上。 */
private val READER_FLIP_CARD_ELEVATION = 8.dp

/** 提示卡左侧圆形箭头徽标的直径。 */
private val READER_FLIP_BADGE_SIZE = 30.dp

/** 越过阈值时箭头翻面的时长。 */
private const val READER_FLIP_ARROW_ANIMATION_MILLIS = 160

/**
 * 换章手势的阻尼。
 *
 * 列表滑到底后剩下的位移是"手指位移"，1:1 累加时几十毫秒就够越过阈值。乘一个小于 1
 * 的系数相当于让用户多拉一段，进度条的填充过程才看得出来——这是"拉动要有阻力感"的常见做法。
 */
private const val READER_FLIP_DRAG_RESISTANCE = 0.55f

/** 有效触摸事件的最小位移，过滤浮点噪声但不制造额外回滚距离。 */
private const val READER_FLIP_REVERSE_MIN_DELTA_PX = 0.5f

/** 跳页后重新落位的确认次数，见 scrollToPage。 */
private const val READER_JUMP_SETTLE_ATTEMPTS = 2
private const val READER_MIN_ZOOM = 1f
private const val READER_MAX_ZOOM = 4f
private const val READER_DOUBLE_TAP_ZOOM = 2.5f
private const val READER_ZOOM_EPSILON = 0.01f
private const val READER_DOUBLE_TAP_ANIMATION_MILLIS = 180
private const val READER_TEMPLATE_TIMEOUT_MILLIS = 15_000L
private val READER_VOLUME_KEY_CODES = setOf(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN)

/**
 * 解码路径变了，旧的内存键一并作废：v2 按段缩放会出横线，v3 改成按段区域解码 +
 * 原分辨率搬运、横线还在（区域解码的接缝首行对不上），v4 整图连续解一次再搬。
 */
private const val READER_RESTORE_VERSION = "decoded-v4"

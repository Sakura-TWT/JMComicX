package dev.jmx.client.ui.screens.readScreen

import android.app.Activity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import dev.jmx.client.store.LocalSettingManager
import dev.jmx.client.ui.glass.LocalJmxBackdrop
import dev.jmx.client.ui.glass.LocalJmxGlassPalette
import dev.jmx.client.ui.glass.JmxGlassStage
import dev.jmx.client.ui.glass.jmxContentBackdrop
import dev.jmx.client.ui.razor.RazorLoadingIndicator
import dev.jmx.client.ui.screens.LocalMainNavController
import dev.jmx.client.ui.viewModel.AlbumDetailViewModel
import dev.jmx.client.ui.viewModel.AlbumReadViewModel
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.getKoin
import org.koin.compose.viewmodel.koinActivityViewModel
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

@Composable
fun AlbumReadScreen(
    albumId: Int,
    albumReadViewModel: AlbumReadViewModel = koinViewModel(),
    albumDetailViewModel: AlbumDetailViewModel = koinActivityViewModel(),
    localSettingManager: LocalSettingManager = getKoin().get()
) {
    val context = LocalContext.current
    val view = LocalView.current
    val isShowToolbar by albumReadViewModel.isShowToolBar
    val albumDetailState by albumDetailViewModel.albumDetailState.collectAsState()
    val chapterList = albumDetailState.data?.albumChapterList.orEmpty()
    val currentChapterIndex = chapterList.indexOfFirst { it.id == albumId }
    val currentChapterLabel = when {
        currentChapterIndex >= 0 -> (currentChapterIndex + 1).toString()
        chapterList.isNotEmpty() -> "?"
        else -> ""
    }
    val size = albumReadViewModel.size
    var currentIndexState by albumReadViewModel.currentIndexState
    val localSetting by localSettingManager.localSettingState.collectAsState()
    val albumImageState by albumReadViewModel.albumImageState.collectAsState()
    val loading = albumImageState.isLoading
    val readMode = localSetting.readMode
    val lazyListState = rememberLazyListState()
    val pagerState = rememberPagerState(initialPage = 0) { size }
    val coroutineScope = rememberCoroutineScope()
    var sliderValue by remember(albumId, readMode) { mutableFloatStateOf(0f) }
    var isSliderDragging by remember(albumId, readMode) { mutableStateOf(false) }
    var showChapterPicker by remember(albumId) { mutableStateOf(false) }
    var scale by remember(albumId, readMode) { mutableFloatStateOf(1f) }
    var offset by remember(albumId, readMode) { mutableStateOf(Offset.Zero) }
    var viewportSize by remember(albumId, readMode) { mutableStateOf(IntSize.Zero) }
    val mainNavController = LocalMainNavController.current
    val palette = LocalJmxGlassPalette.current

    fun clampOffset(value: Offset, targetScale: Float): Offset {
        if (targetScale <= 1f || viewportSize == IntSize.Zero) {
            return Offset.Zero
        }
        val maxX = viewportSize.width * (targetScale - 1f) / 2f
        val maxY = if (readMode == "scroll") 0f else viewportSize.height * (targetScale - 1f) / 2f
        return Offset(
            x = value.x.coerceIn(-maxX, maxX),
            y = value.y.coerceIn(-maxY, maxY)
        )
    }

    suspend fun jumpToPage(index: Int) {
        val target = index.coerceIn(0, maxOf(0, size - 1))
        if (currentIndexState != target) {
            currentIndexState = target
        }
        if (readMode == "scroll") {
            lazyListState.scrollToItem(target)
        } else {
            pagerState.scrollToPage(target)
        }
        sliderValue = target.toFloat()
        albumReadViewModel.decodeIndex(target, context)
    }

    LaunchedEffect(currentIndexState, isSliderDragging) {
        if (!isSliderDragging) {
            sliderValue = currentIndexState.toFloat()
        }
    }

    LaunchedEffect(albumId, localSetting.shunt) {
        sliderValue = 0f
        isSliderDragging = false
        scale = 1f
        offset = Offset.Zero
        albumReadViewModel.getAlbumImageList(albumId, localSetting.shunt) {
            coroutineScope.launch {
                jumpToPage(0)
            }
        }
    }

    val controller = remember(view) {
        val window = (context as? Activity)?.window
        WindowInsetsControllerCompat(window!!, view).apply {
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
    LaunchedEffect(isShowToolbar) {
        if (isShowToolbar) {
            controller.show(WindowInsetsCompat.Type.statusBars())
        } else {
            controller.hide(WindowInsetsCompat.Type.statusBars())
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            controller.show(WindowInsetsCompat.Type.statusBars())
        }
    }

    JmxGlassStage {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(palette.page)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .jmxContentBackdrop(it)
                    .onSizeChanged {
                        viewportSize = it
                        offset = clampOffset(offset, scale)
                    }
                    .pointerInput(albumId, readMode) {
                        awaitEachGesture {
                            val down = awaitFirstDown(
                                requireUnconsumed = false,
                                pass = PointerEventPass.Initial
                            )
                            val startPosition = down.position
                            var lastPosition = startPosition
                            var maxDistance = 0f
                            var hadMultiTouch = false
                            var consumedTransform = false

                            while (true) {
                                val event = awaitPointerEvent(pass = PointerEventPass.Initial)
                                val trackedChange =
                                    event.changes.firstOrNull { it.id == down.id }
                                        ?: event.changes.firstOrNull()
                                if (trackedChange != null) {
                                    lastPosition = trackedChange.position
                                    maxDistance = max(
                                        maxDistance,
                                        (lastPosition - startPosition).getDistance()
                                    )
                                }

                                val pressedChanges = event.changes.filter { it.pressed }
                                if (pressedChanges.isEmpty()) {
                                    break
                                }

                                if (pressedChanges.size >= 2) {
                                    hadMultiTouch = true
                                    val zoom = event.calculateZoom()
                                    val pan = event.calculatePan()
                                    val nextScale = (scale * zoom).coerceIn(1f, 4f)
                                    scale = nextScale
                                    offset = if (nextScale <= 1f) {
                                        Offset.Zero
                                    } else {
                                        clampOffset(offset + pan * nextScale, nextScale)
                                    }
                                    event.changes.forEach { it.consume() }
                                    consumedTransform = true
                                } else if (scale > 1f) {
                                    val change = pressedChanges.first()
                                    val pan = change.positionChange()
                                    if (pan != Offset.Zero) {
                                        val horizontalDominant = abs(pan.x) >= abs(pan.y)
                                        if (readMode == "scroll") {
                                            offset = clampOffset(
                                                Offset(
                                                    x = offset.x + pan.x * scale,
                                                    y = 0f
                                                ),
                                                scale
                                            )
                                            if (horizontalDominant) {
                                                change.consume()
                                                consumedTransform = true
                                            }
                                        } else {
                                            val maxX = viewportSize.width * (scale - 1f) / 2f
                                            val atLeftEdge = offset.x <= -maxX + viewConfiguration.touchSlop
                                            val atRightEdge = offset.x >= maxX - viewConfiguration.touchSlop
                                            val wantsNextPage = horizontalDominant && pan.x < 0f && atLeftEdge
                                            val wantsPrevPage = horizontalDominant && pan.x > 0f && atRightEdge
                                            offset = clampOffset(offset + pan * scale, scale)
                                            if (!wantsNextPage && !wantsPrevPage) {
                                                change.consume()
                                                consumedTransform = true
                                            }
                                        }
                                    }
                                }
                            }

                            if (
                                readMode == "scroll" &&
                                !hadMultiTouch &&
                                !consumedTransform &&
                                maxDistance < viewConfiguration.touchSlop
                            ) {
                                val width = viewportSize.width
                                if (
                                    width == 0 ||
                                    lastPosition.x in width / 3f..width * 2f / 3f
                                ) {
                                    albumReadViewModel.triggerToolBar()
                                }
                            }
                        }
                    }
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = if (scale > 1f) offset.x else 0f
                        translationY = if (scale > 1f) offset.y else 0f
                    }
            ) {
                if (loading) {
                    RazorLoadingIndicator(modifier = Modifier.align(Alignment.Center))
                } else {
                    key(readMode) {
                        if (readMode == "scroll") {
                            AlbumScrollRead(lazyListState = lazyListState) {
                                if (!isSliderDragging) {
                                    sliderValue = it
                                }
                            }
                        } else {
                            AlbumPageRead(pagerState = pagerState) {
                                if (!isSliderDragging) {
                                    sliderValue = it
                                }
                            }
                        }
                    }
                }
            }
            CompositionLocalProvider(LocalJmxBackdrop provides it) {
                AnimatedVisibility(
                    modifier = Modifier.align(Alignment.BottomCenter),
                    visible = isShowToolbar && !loading,
                    enter = slideInVertically(
                        initialOffsetY = { fullHeight -> fullHeight },
                        animationSpec = tween(durationMillis = 300)
                    ) + fadeIn(),
                    exit = slideOutVertically(
                        targetOffsetY = { fullHeight -> fullHeight },
                        animationSpec = tween(durationMillis = 300)
                    ) + fadeOut()
                ) {
                    ToolsBar(
                        sliderValue = sliderValue,
                        albumReadViewModel = albumReadViewModel,
                        onSliderValueChange = {
                            isSliderDragging = true
                            sliderValue = it
                        },
                        onSliderValueChangeFinished = {
                            coroutineScope.launch {
                                isSliderDragging = false
                                jumpToPage(sliderValue.roundToInt())
                            }
                        },
                        showChapterButton = chapterList.isNotEmpty(),
                        chapterLabel = currentChapterLabel,
                        onChapterClick = {
                            showChapterPicker = true
                        }
                    )
                }
                if (showChapterPicker && chapterList.isNotEmpty()) {
                    ChapterPickerSheet(
                        currentAlbumId = albumId,
                        chapterList = chapterList,
                        onDismissRequest = {
                            showChapterPicker = false
                        },
                        onChapterClick = {
                            showChapterPicker = false
                            albumReadViewModel.hideToolBar()
                            mainNavController.navigate("albumRead/${it.id}") {
                                launchSingleTop = true
                            }
                        }
                    )
                }
                if (localSetting.showAlbumPageReadTip && readMode == "page" || localSetting.showAlbumScrollReadTip && readMode == "scroll") {
                    Tip(readMode = readMode)
                    TipCloseButton(
                        modifier = Modifier.align(
                            if (readMode == "scroll") Alignment.CenterEnd else Alignment.BottomCenter
                        ).let {
                            if (readMode == "scroll") {
                                it.padding(end = 40.dp)
                            } else {
                                it.padding(bottom = 40.dp)
                            }
                        },
                        onClick = {
                            if (readMode == "scroll") {
                                localSettingManager.closeShowAlbumScrollReadTip()
                            } else {
                                localSettingManager.closeShowAlbumPageReadTip()
                            }
                        }
                    )
                }
            }
        }
    }
}

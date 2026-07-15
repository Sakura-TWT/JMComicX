package dev.jmx.client

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import coil.compose.AsyncImage
import dev.jmx.client.core.api.AlbumDetail
import dev.jmx.client.core.api.AlbumChapter
import dev.jmx.client.core.api.CommentItem
import dev.jmx.client.core.api.CommentPage
import dev.jmx.client.core.result.JmxResult
import dev.jmx.client.core.runtime.JmxCore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.FabPosition
import top.yukonga.miuix.kmp.basic.FloatingActionButton
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.TabRowWithContour
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Download
import top.yukonga.miuix.kmp.icon.extended.Edit
import top.yukonga.miuix.kmp.icon.extended.Favorites
import top.yukonga.miuix.kmp.icon.extended.FavoritesFill
import top.yukonga.miuix.kmp.icon.extended.Play
import top.yukonga.miuix.kmp.icon.extended.Send
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal data class AlbumDetailTransitionRequest(
    val album: HomeAlbum,
    val sourceBounds: Rect,
)

@Composable
internal fun AlbumDetailTransitionHost(
    request: AlbumDetailTransitionRequest,
    repository: AlbumDetailRepository,
    readerActive: Boolean,
    onStartReading: (ReaderLaunchRequest) -> Unit,
    onDismiss: () -> Unit,
) {
    val transitionProgress = remember(request.album.id) { Animatable(0f) }
    val coroutineScope = rememberCoroutineScope()
    var targetBounds by remember(request.album.id) { mutableStateOf<Rect?>(null) }
    var hasEntered by remember(request.album.id) { mutableStateOf(false) }
    var isExiting by remember(request.album.id) { mutableStateOf(false) }
    var hasDismissed by remember(request.album.id) { mutableStateOf(false) }

    fun dismissOnce() {
        if (!hasDismissed) {
            hasDismissed = true
            onDismiss()
        }
    }

    fun exitDetail() {
        if (isExiting) return
        isExiting = true
        coroutineScope.launch {
            try {
                transitionProgress.animateTo(
                    targetValue = 0f,
                    animationSpec = tween(
                        durationMillis = DETAIL_EXIT_DURATION_MILLIS,
                        easing = DetailTransitionEasing,
                    ),
                )
            } finally {
                withContext(NonCancellable) {
                    transitionProgress.snapTo(0f)
                    dismissOnce()
                }
            }
        }
    }

    BackHandler(onBack = ::exitDetail)

    LaunchedEffect(targetBounds) {
        if (targetBounds != null && !hasEntered) {
            hasEntered = true
            transitionProgress.animateTo(
                targetValue = 1f,
                animationSpec = tween(
                    durationMillis = DETAIL_ENTER_DURATION_MILLIS,
                    easing = DetailTransitionEasing,
                ),
            )
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val pageWidthPx = constraints.maxWidth.toFloat()
        val progress = transitionProgress.value
        val blockerInteractionSource = remember { MutableInteractionSource() }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .zIndex(1f)
                .clickable(
                    interactionSource = blockerInteractionSource,
                    indication = null,
                    onClick = {},
                ),
        )

        AlbumDetailScreen(
            album = request.album,
            repository = repository,
            readerActive = readerActive,
            onStartReading = onStartReading,
            showCover = hasEntered && progress >= 0.999f && !isExiting,
            onBack = ::exitDetail,
            onCoverTargetChanged = { bounds -> targetBounds = bounds },
            modifier = Modifier
                .fillMaxSize()
                .zIndex(2f)
                .graphicsLayer {
                    translationX = pageWidthPx * (1f - progress)
                },
        )

        val destination = targetBounds
        if (destination == null || !hasEntered || progress < 0.999f || isExiting) {
            val animatedBounds = if (destination == null) {
                request.sourceBounds
            } else {
                curvedCoverBounds(
                    start = request.sourceBounds,
                    end = destination,
                    progress = progress,
                    maxBend = pageWidthPx * 0.26f,
                )
            }
            AsyncImage(
                model = buildCoverRequest(LocalContext.current, request.album.coverUrl),
                contentDescription = request.album.name,
                modifier = Modifier
                    .zIndex(4f)
                    .offset {
                        IntOffset(
                            x = animatedBounds.left.roundToInt(),
                            y = animatedBounds.top.roundToInt(),
                        )
                    }
                    .size(
                        width = with(density) { animatedBounds.width.toDp() },
                        height = with(density) { animatedBounds.height.toDp() },
                    )
                    .clip(RoundedCornerShape(8.dp)),
                contentScale = ContentScale.Crop,
            )
        }
    }
}

@Composable
private fun AlbumDetailScreen(
    album: HomeAlbum,
    repository: AlbumDetailRepository,
    readerActive: Boolean,
    onStartReading: (ReaderLaunchRequest) -> Unit,
    showCover: Boolean,
    onBack: () -> Unit,
    onCoverTargetChanged: (Rect) -> Unit,
    modifier: Modifier = Modifier,
) {
    var state by remember(album.id) { mutableStateOf<AlbumDetailUiState>(AlbumDetailUiState.Loading) }
    var selectedTab by rememberSaveable(album.id) { mutableIntStateOf(0) }
    var retryKey by remember(album.id) { mutableIntStateOf(0) }
    var pageSummary by remember(album.id) { mutableStateOf<AlbumPageSummary>(AlbumPageSummary.Loading) }
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(album.id, repository, retryKey) {
        state = repository.load(album.id)
    }

    val detail = (state as? AlbumDetailUiState.Content)?.detail
    LaunchedEffect(detail?.id, repository, readerActive) {
        pageSummary = if (detail == null) {
            AlbumPageSummary.Loading
        } else if (readerActive) {
            pageSummary
        } else {
            repository.loadPageSummary(detail) { progress -> pageSummary = progress }
        }
    }

    fun loadMoreComments() {
        val content = state as? AlbumDetailUiState.Content ?: return
        if (content.comments.isLoading || content.comments.endReached) return
        state = content.copy(comments = content.comments.copy(isLoading = true, error = null))
        coroutineScope.launch {
            val updated = repository.loadMoreComments(album.id, content.comments)
            val latest = state as? AlbumDetailUiState.Content
            if (latest?.detail?.id == content.detail.id) {
                state = latest.copy(comments = updated)
            }
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            SmallTopAppBar(
                title = when (val current = state) {
                    is AlbumDetailUiState.Content -> current.detail.name ?: album.name
                    else -> album.name
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = MiuixIcons.Back,
                            contentDescription = "返回",
                            tint = MiuixTheme.colorScheme.onBackground,
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    val content = state as? AlbumDetailUiState.Content
                    if (selectedTab != DETAIL_COMMENTS_TAB && content != null) {
                        val firstChapter = content.detail.readingChapters().firstOrNull()
                        if (firstChapter != null) {
                            onStartReading(
                                ReaderLaunchRequest(
                                    album = album,
                                    detail = content.detail,
                                    initialChapterId = firstChapter.id,
                                ),
                            )
                        }
                    }
                },
                minWidth = 132.dp,
                minHeight = 54.dp,
                shape = RoundedCornerShape(18.dp),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 18.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = if (selectedTab == DETAIL_COMMENTS_TAB) MiuixIcons.Send else MiuixIcons.Play,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MiuixTheme.colorScheme.onPrimary,
                    )
                    Text(
                        text = if (selectedTab == DETAIL_COMMENTS_TAB) "发表" else "开始观看",
                        style = MiuixTheme.textStyles.button,
                        color = MiuixTheme.colorScheme.onPrimary,
                    )
                }
            }
        },
        floatingActionButtonPosition = FabPosition.End,
    ) { innerPadding ->
        DetailBody(
            album = album,
            state = state,
            selectedTab = selectedTab,
            onTabSelected = { selectedTab = it },
            pageSummary = pageSummary,
            showCover = showCover,
            innerPadding = innerPadding,
            onCoverTargetChanged = onCoverTargetChanged,
            onChapterSelected = { chapter ->
                val content = state as? AlbumDetailUiState.Content
                if (content != null) {
                    onStartReading(
                        ReaderLaunchRequest(
                            album = album,
                            detail = content.detail,
                            initialChapterId = chapter.id,
                        ),
                    )
                }
            },
            onLoadMoreComments = ::loadMoreComments,
            onRetry = {
                state = AlbumDetailUiState.Loading
                retryKey++
            },
        )
    }
}

@Composable
private fun DetailBody(
    album: HomeAlbum,
    state: AlbumDetailUiState,
    selectedTab: Int,
    onTabSelected: (Int) -> Unit,
    pageSummary: AlbumPageSummary,
    showCover: Boolean,
    innerPadding: PaddingValues,
    onCoverTargetChanged: (Rect) -> Unit,
    onChapterSelected: (AlbumChapter) -> Unit,
    onLoadMoreComments: () -> Unit,
    onRetry: () -> Unit,
) {
    val listState = rememberLazyListState()
    val density = LocalDensity.current
    val coverTop = innerPadding.calculateTopPadding() + 12.dp
    val targetBounds = with(density) {
        Rect(
            left = DETAIL_HORIZONTAL_PADDING.toPx(),
            top = coverTop.toPx(),
            right = (DETAIL_HORIZONTAL_PADDING + DETAIL_COVER_WIDTH).toPx(),
            bottom = (coverTop + DETAIL_COVER_HEIGHT).toPx(),
        )
    }
    LaunchedEffect(targetBounds) { onCoverTargetChanged(targetBounds) }

    val detail = (state as? AlbumDetailUiState.Content)?.detail
    val comments = (state as? AlbumDetailUiState.Content)?.comments

    LaunchedEffect(listState, selectedTab, comments?.nextPage, comments?.isLoading, comments?.endReached) {
        if (selectedTab != DETAIL_COMMENTS_TAB || comments == null || comments.isLoading || comments.endReached) {
            return@LaunchedEffect
        }
        snapshotFlow {
            val layout = listState.layoutInfo
            val lastVisible = layout.visibleItemsInfo.lastOrNull()?.index ?: -1
            layout.totalItemsCount > 0 && lastVisible >= layout.totalItemsCount - 3
        }.filter { it }.first()
        onLoadMoreComments()
    }

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .background(MiuixTheme.colorScheme.surface),
        contentPadding = PaddingValues(
            start = DETAIL_HORIZONTAL_PADDING,
            top = coverTop,
            end = DETAIL_HORIZONTAL_PADDING,
            bottom = 108.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item(key = "summary") {
            DetailSummary(
                album = album,
                detail = detail,
                pageSummary = pageSummary,
                showCover = showCover,
            )
        }
        item(key = "actions") {
            DetailActions(
                detail = detail,
                onCommentsSelected = { onTabSelected(DETAIL_COMMENTS_TAB) },
            )
        }
        item(key = "tabs") {
            TabRowWithContour(
                tabs = listOf(
                    "介绍",
                    "目录 ${detail?.readingChapters()?.size ?: 0}",
                    "评论 ${detail?.commentTotal ?: comments?.total ?: 0}",
                ),
                selectedTabIndex = selectedTab,
                onTabSelected = onTabSelected,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        when {
            state is AlbumDetailUiState.Loading -> item(key = "loading") {
                DetailLoading()
            }
            state is AlbumDetailUiState.Error -> item(key = "error") {
                DetailError(message = state.message, onRetry = onRetry)
            }
            selectedTab == DETAIL_INFO_TAB && detail != null -> detailInfoItems(detail, pageSummary)
            selectedTab == DETAIL_CATALOG_TAB && detail != null -> catalogItems(detail, onChapterSelected)
            selectedTab == DETAIL_COMMENTS_TAB -> commentsItems(comments, onLoadMoreComments)
        }
    }
}

@Composable
private fun DetailSummary(
    album: HomeAlbum,
    detail: AlbumDetail?,
    pageSummary: AlbumPageSummary,
    showCover: Boolean,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(18.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .width(DETAIL_COVER_WIDTH)
                .aspectRatio(3f / 4f)
                .clip(RoundedCornerShape(8.dp))
                .background(MiuixTheme.colorScheme.surfaceContainerHigh),
        ) {
            if (showCover) {
                AsyncImage(
                    model = buildCoverRequest(LocalContext.current, album.coverUrl),
                    contentDescription = album.name,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            DetailMetaLine(label = "JM车号", value = "JM${album.id}")
            DetailMetaLine(
                label = "作者",
                value = detail?.authors?.joinToString(" / ")?.takeIf { it.isNotBlank() } ?: album.author,
            )
            DetailMetaLine(
                label = "页数",
                value = pageSummary.summaryText(detail),
            )
            if (!detail?.series.isNullOrEmpty()) {
                DetailMetaLine(label = "章节", value = "${detail.series.size} 话")
            }
        }
    }
}

@Composable
private fun DetailMetaLine(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = label,
            style = MiuixTheme.textStyles.footnote2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        Text(
            text = value,
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurface,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun DetailActions(
    detail: AlbumDetail?,
    onCommentsSelected: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        DetailAction(
            icon = if (detail?.liked == true) MiuixIcons.FavoritesFill else MiuixIcons.Favorites,
            value = compactCount(detail?.likes),
            label = "喜欢",
            enabled = false,
        )
        DetailAction(
            icon = MiuixIcons.Edit,
            value = compactCount(detail?.commentTotal),
            label = "评论",
            enabled = true,
            onClick = onCommentsSelected,
        )
        DetailAction(
            icon = MiuixIcons.Play,
            value = compactCount(detail?.totalViews),
            label = "观看",
            enabled = false,
        )
        DetailAction(
            icon = MiuixIcons.Favorites,
            value = if (detail?.isFavorite == true) "已收藏" else "收藏",
            label = "收藏",
            enabled = false,
        )
        DetailAction(
            icon = MiuixIcons.Download,
            value = "下载",
            label = "下载",
            enabled = false,
        )
    }
}

@Composable
private fun DetailAction(
    icon: ImageVector,
    value: String,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit = {},
) {
    val contentColor = if (enabled) {
        MiuixTheme.colorScheme.onSurface
    } else {
        MiuixTheme.colorScheme.onSurfaceVariantSummary
    }
    Column(
        modifier = Modifier.width(62.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        IconButton(
            onClick = onClick,
            enabled = enabled,
            backgroundColor = MiuixTheme.colorScheme.surfaceContainerHigh,
            minWidth = 42.dp,
            minHeight = 42.dp,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                modifier = Modifier.size(20.dp),
                tint = contentColor,
            )
        }
        Spacer(modifier = Modifier.height(5.dp))
        Text(
            text = value,
            style = MiuixTheme.textStyles.footnote1,
            color = contentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = label,
            style = MiuixTheme.textStyles.footnote2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.detailInfoItems(
    detail: AlbumDetail,
    pageSummary: AlbumPageSummary,
) {
    item(key = "description") {
        DetailSection(title = "漫画介绍") {
            Text(
                text = detail.description
                    ?.decodeHtml()
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?: "暂无介绍",
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurface,
            )
        }
    }
    item(key = "pages") {
        DetailSection(title = "章节与页数") {
            Text(
                text = pageSummary.sectionText(detail),
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurface,
            )
        }
    }
    if (detail.tags.isNotEmpty()) {
        item(key = "tags") {
            DetailLabels(title = "标签", values = detail.tags)
        }
    }
    if (detail.works.isNotEmpty()) {
        item(key = "works") {
            DetailLabels(title = "作品", values = detail.works)
        }
    }
    if (detail.actors.isNotEmpty()) {
        item(key = "actors") {
            DetailLabels(title = "角色", values = detail.actors)
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.catalogItems(
    detail: AlbumDetail,
    onChapterSelected: (AlbumChapter) -> Unit,
) {
    val chapters = detail.readingChapters()
    itemsIndexed(
        items = chapters,
        key = { _, chapter -> chapter.id },
    ) { index, chapter ->
        Surface(
            onClick = { onChapterSelected(chapter) },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
            color = MiuixTheme.colorScheme.surfaceContainerHigh,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Text(
                        text = chapter.displayName(index),
                        style = MiuixTheme.textStyles.body2,
                        fontWeight = FontWeight.SemiBold,
                        color = MiuixTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "JM${chapter.id}",
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
                Icon(
                    imageVector = MiuixIcons.Play,
                    contentDescription = "观看${chapter.displayName(index)}",
                    modifier = Modifier.size(20.dp),
                    tint = MiuixTheme.colorScheme.primary,
                )
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.commentsItems(
    comments: DetailCommentsState?,
    onRetry: () -> Unit,
) {
    when {
        comments?.error != null && comments.items.isEmpty() -> item(key = "comments-error") {
            DetailError(message = comments.error, onRetry = onRetry)
        }
        comments == null || comments.items.isEmpty() -> item(key = "comments-empty") {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(140.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "暂无评论",
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
        else -> itemsIndexed(
            items = comments.items,
            key = { index, comment -> "${comment.id.orEmpty()}:$index" },
        ) { _, comment ->
            CommentCard(comment = comment)
        }
    }
    if (comments != null && comments.items.isNotEmpty()) {
        item(key = "comments-footer") {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(72.dp),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    comments.isLoading -> CircularProgressIndicator(size = 24.dp, strokeWidth = 3.dp)
                    comments.error != null -> TextButton(text = "加载失败，重试", onClick = onRetry)
                    comments.endReached -> Text(
                        text = "已显示全部 ${comments.items.size} 条评论",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
        }
    }
}

@Composable
private fun DetailSection(
    title: String,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            style = MiuixTheme.textStyles.title3,
            fontWeight = FontWeight.SemiBold,
            color = MiuixTheme.colorScheme.onSurface,
        )
        content()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DetailLabels(title: String, values: List<String>) {
    DetailSection(title = title) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            values.distinct().forEach { value ->
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MiuixTheme.colorScheme.primaryContainer,
                ) {
                    Text(
                        text = value,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        style = MiuixTheme.textStyles.footnote1,
                        fontWeight = FontWeight.SemiBold,
                        color = MiuixTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
        }
    }
}

@Composable
private fun CommentCard(comment: CommentItem) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 8.dp,
        insideMargin = PaddingValues(14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = comment.username?.takeIf { it.isNotBlank() } ?: "匿名用户",
                style = MiuixTheme.textStyles.body2,
                fontWeight = FontWeight.SemiBold,
                color = MiuixTheme.colorScheme.onSurface,
            )
            if ((comment.likes ?: 0) > 0) {
                Text(
                    text = "${comment.likes} 赞",
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = comment.content?.decodeHtml()?.trim().orEmpty().ifBlank { "评论内容为空" },
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurface,
        )
        if (comment.replies.isNotEmpty()) {
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "${comment.replies.size} 条回复",
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun DetailLoading() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(180.dp),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator()
    }
}

@Composable
private fun DetailError(message: String, onRetry: (() -> Unit)?) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 30.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = message,
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            textAlign = TextAlign.Center,
        )
        if (onRetry != null) {
            Spacer(modifier = Modifier.height(12.dp))
            TextButton(text = "重试", onClick = onRetry)
        }
    }
}

internal class AlbumDetailRepository(
    private val core: JmxCore,
) {
    private val chapterPageCounts = ConcurrentHashMap<String, Int>()

    suspend fun load(albumId: String): AlbumDetailUiState = coroutineScope {
        try {
            val detailDeferred = async { core.albumApi.detailFull(albumId) }
            val commentsDeferred = async { core.interactionApi.albumComments(albumId, page = 1) }
            when (val detailResult = detailDeferred.await()) {
                is JmxResult.Success -> {
                    when (val commentsResult = commentsDeferred.await()) {
                        is JmxResult.Success -> AlbumDetailUiState.Content(
                            detail = detailResult.value,
                            comments = commentsResult.value.toDetailCommentsState(),
                        )
                        is JmxResult.Failure -> AlbumDetailUiState.Content(
                            detail = detailResult.value,
                            comments = DetailCommentsState(error = commentsResult.error.toUiMessage()),
                        )
                    }
                }
                is JmxResult.Failure -> AlbumDetailUiState.Error(detailResult.error.toUiMessage())
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            AlbumDetailUiState.Error(error.message ?: "详情加载出现未知异常。")
        }
    }

    suspend fun loadMoreComments(
        albumId: String,
        current: DetailCommentsState,
    ): DetailCommentsState {
        if (current.endReached) return current.copy(isLoading = false)
        return try {
            when (val result = core.interactionApi.albumComments(albumId, page = current.nextPage)) {
                is JmxResult.Success -> {
                    val merged = mergeCommentPages(current.items, result.value.comments)
                    val total = result.value.total ?: current.total
                    current.copy(
                        items = merged,
                        total = total,
                        nextPage = current.nextPage + 1,
                        isLoading = false,
                        endReached = result.value.comments.isEmpty() ||
                            (total != null && merged.size >= total),
                        error = null,
                    )
                }
                is JmxResult.Failure -> current.copy(
                    isLoading = false,
                    error = result.error.toUiMessage(),
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            current.copy(isLoading = false, error = error.message ?: "评论加载出现未知异常。")
        }
    }

    suspend fun loadPageSummary(
        detail: AlbumDetail,
        onProgress: (AlbumPageSummary.Ready) -> Unit = {},
    ): AlbumPageSummary = coroutineScope {
        val chapters = detail.readingChapters()
        val albumImageCount = detail.imageCount
        if (chapters.size == 1 && albumImageCount != null && albumImageCount > 0) {
            chapterPageCounts[chapters.first().id] = albumImageCount
            return@coroutineScope AlbumPageSummary.Ready(
                totalPages = albumImageCount,
                resolvedChapters = 1,
                totalChapters = 1,
            )
        }

        val semaphore = Semaphore(PAGE_COUNT_CONCURRENCY)
        val progressMutex = Mutex()
        var resolvedChapters = 0
        var resolvedPages = 0
        val counts = chapters.map { chapter ->
            async {
                val count = semaphore.withPermit {
                    chapterPageCounts[chapter.id] ?: loadChapterPageCount(chapter.id)?.also { count ->
                        chapterPageCounts[chapter.id] = count
                    }
                }
                if (count != null) {
                    progressMutex.withLock {
                        resolvedChapters++
                        resolvedPages += count
                        onProgress(
                            AlbumPageSummary.Ready(
                                totalPages = resolvedPages,
                                resolvedChapters = resolvedChapters,
                                totalChapters = chapters.size,
                            ),
                        )
                    }
                }
                count
            }
        }.map { it.await() }
        val resolved = counts.filterNotNull()
        if (resolved.isEmpty()) {
            AlbumPageSummary.Unavailable(totalChapters = chapters.size)
        } else {
            AlbumPageSummary.Ready(
                totalPages = resolved.sum(),
                resolvedChapters = resolved.size,
                totalChapters = chapters.size,
            )
        }
    }

    private suspend fun loadChapterPageCount(chapterId: String): Int? {
        when (val detailResult = core.chapterApi.detail(chapterId)) {
            is JmxResult.Success -> {
                val count = detailResult.value.imageCount ?: detailResult.value.pageArr.size
                if (count > 0) return count
            }
            is JmxResult.Failure -> Unit
        }
        return when (val templateResult = core.chapterApi.template(chapterId, shunt = DEFAULT_IMAGE_SHUNT)) {
            is JmxResult.Success -> templateResult.value.imageFileNames.size.takeIf { it > 0 }
            is JmxResult.Failure -> null
        }
    }
}

internal sealed interface AlbumDetailUiState {
    data object Loading : AlbumDetailUiState
    data class Content(
        val detail: AlbumDetail,
        val comments: DetailCommentsState,
    ) : AlbumDetailUiState
    data class Error(val message: String) : AlbumDetailUiState
}

internal data class DetailCommentsState(
    val items: List<CommentItem> = emptyList(),
    val total: Int? = null,
    val nextPage: Int = 1,
    val isLoading: Boolean = false,
    val endReached: Boolean = false,
    val error: String? = null,
)

internal sealed interface AlbumPageSummary {
    data object Loading : AlbumPageSummary

    data class Ready(
        val totalPages: Int,
        val resolvedChapters: Int,
        val totalChapters: Int,
    ) : AlbumPageSummary

    data class Unavailable(val totalChapters: Int) : AlbumPageSummary
}

internal fun AlbumDetail.readingChapters(): List<AlbumChapter> {
    return series
        .filter { it.id.isNotBlank() }
        .distinctBy { it.id }
        .ifEmpty {
            listOf(AlbumChapter(id = id, name = name, sort = null))
        }
}

internal fun AlbumChapter.displayName(index: Int): String {
    return name?.decodeHtml()?.trim()?.takeIf { it.isNotBlank() }
        ?: sort?.trim()?.takeIf { it.isNotBlank() }?.let { "第 $it 话" }
        ?: "第 ${index + 1} 话"
}

private fun CommentPage.toDetailCommentsState(): DetailCommentsState {
    val commentTotal = total
    return DetailCommentsState(
        items = comments,
        total = commentTotal,
        nextPage = 2,
        endReached = comments.isEmpty() || (commentTotal != null && comments.size >= commentTotal),
    )
}

private fun mergeCommentPages(
    existing: List<CommentItem>,
    incoming: List<CommentItem>,
): List<CommentItem> {
    val knownIds = existing.mapNotNullTo(mutableSetOf()) { it.id?.takeIf(String::isNotBlank) }
    return buildList(existing.size + incoming.size) {
        addAll(existing)
        incoming.forEach { comment ->
            val id = comment.id?.takeIf(String::isNotBlank)
            if (id == null || knownIds.add(id)) add(comment)
        }
    }
}

private fun AlbumPageSummary.summaryText(detail: AlbumDetail?): String {
    return when (this) {
        AlbumPageSummary.Loading -> detail?.readingChapters()?.size?.let { "$it 话 · 正在统计" } ?: "正在统计"
        is AlbumPageSummary.Ready -> if (resolvedChapters == totalChapters) {
            "$totalPages 页"
        } else {
            "$totalPages+ 页"
        }
        is AlbumPageSummary.Unavailable -> "$totalChapters 话"
    }
}

private fun AlbumPageSummary.sectionText(detail: AlbumDetail): String {
    val chapterCount = detail.readingChapters().size
    return when (this) {
        AlbumPageSummary.Loading -> "共 $chapterCount 话，正在汇总各话页数"
        is AlbumPageSummary.Ready -> if (resolvedChapters == totalChapters) {
            "全 $totalPages 页 · 共 $totalChapters 话"
        } else {
            "已读取 $resolvedChapters/$totalChapters 话，共 $totalPages 页"
        }
        is AlbumPageSummary.Unavailable -> "共 $totalChapters 话"
    }
}

internal fun curvedCoverBounds(
    start: Rect,
    end: Rect,
    progress: Float,
    maxBend: Float,
): Rect {
    val p = progress.coerceIn(0f, 1f)
    val startPoint = start.topLeft
    val endPoint = end.topLeft
    val dx = endPoint.x - startPoint.x
    val dy = endPoint.y - startPoint.y
    val distance = hypot(dx, dy).coerceAtLeast(1f)
    val normal = Offset(-dy / distance, dx / distance)
    val bend = min(distance * 0.24f, maxBend)
    val control1 = startPoint + Offset(dx * 0.24f, dy * 0.24f) + normal * bend
    val control2 = startPoint + Offset(dx * 0.72f, dy * 0.72f) + normal * (bend * 0.72f)
    val topLeft = cubicBezier(startPoint, control1, control2, endPoint, p)
    val width = start.width + (end.width - start.width) * p
    val height = start.height + (end.height - start.height) * p
    return Rect(topLeft, androidx.compose.ui.geometry.Size(width, height))
}

private fun cubicBezier(
    start: Offset,
    control1: Offset,
    control2: Offset,
    end: Offset,
    progress: Float,
): Offset {
    val inverse = 1f - progress
    val startWeight = inverse * inverse * inverse
    val control1Weight = 3f * inverse * inverse * progress
    val control2Weight = 3f * inverse * progress * progress
    val endWeight = progress * progress * progress
    return Offset(
        x = start.x * startWeight + control1.x * control1Weight +
            control2.x * control2Weight + end.x * endWeight,
        y = start.y * startWeight + control1.y * control1Weight +
            control2.y * control2Weight + end.y * endWeight,
    )
}

private fun compactCount(value: Int?): String {
    val number = value ?: 0
    return when {
        number >= 10_000 -> "${(number / 1_000) / 10f}万"
        number >= 1_000 -> "${(number / 100) / 10f}千"
        else -> number.toString()
    }
}

private val DetailTransitionEasing = CubicBezierEasing(0.18f, 0.82f, 0.16f, 1f)
private val DETAIL_HORIZONTAL_PADDING = 16.dp
private val DETAIL_COVER_WIDTH = 128.dp
private val DETAIL_COVER_HEIGHT = 170.6667.dp
private const val DETAIL_INFO_TAB = 0
private const val DETAIL_CATALOG_TAB = 1
private const val DETAIL_COMMENTS_TAB = 2
private const val PAGE_COUNT_CONCURRENCY = 4
internal const val DEFAULT_IMAGE_SHUNT = "1"
private const val DETAIL_ENTER_DURATION_MILLIS = 680
private const val DETAIL_EXIT_DURATION_MILLIS = 560

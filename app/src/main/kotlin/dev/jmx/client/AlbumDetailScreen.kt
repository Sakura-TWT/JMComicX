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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import dev.jmx.client.core.api.CommentItem
import dev.jmx.client.core.api.CommentPage
import dev.jmx.client.core.result.JmxResult
import dev.jmx.client.core.runtime.JmxCore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    showCover: Boolean,
    onBack: () -> Unit,
    onCoverTargetChanged: (Rect) -> Unit,
    modifier: Modifier = Modifier,
) {
    var state by remember(album.id) { mutableStateOf<AlbumDetailUiState>(AlbumDetailUiState.Loading) }
    var selectedTab by rememberSaveable(album.id) { mutableIntStateOf(0) }
    var retryKey by remember(album.id) { mutableIntStateOf(0) }

    LaunchedEffect(album.id, repository, retryKey) {
        state = repository.load(album.id)
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
                onClick = {},
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
                        imageVector = if (selectedTab == 0) MiuixIcons.Play else MiuixIcons.Send,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MiuixTheme.colorScheme.onPrimary,
                    )
                    Text(
                        text = if (selectedTab == 0) "开始观看" else "发表",
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
            showCover = showCover,
            innerPadding = innerPadding,
            onCoverTargetChanged = onCoverTargetChanged,
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
    showCover: Boolean,
    innerPadding: PaddingValues,
    onCoverTargetChanged: (Rect) -> Unit,
    onRetry: () -> Unit,
) {
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
    val commentsError = (state as? AlbumDetailUiState.Content)?.commentsError

    LazyColumn(
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
                showCover = showCover,
            )
        }
        item(key = "actions") {
            DetailActions(
                detail = detail,
                onCommentsSelected = { onTabSelected(1) },
            )
        }
        item(key = "tabs") {
            TabRowWithContour(
                tabs = listOf("介绍", "评论 ${detail?.commentTotal ?: comments?.total ?: 0}"),
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
            selectedTab == 0 && detail != null -> detailInfoItems(detail)
            selectedTab == 1 -> commentsItems(comments, commentsError, onRetry)
        }
    }
}

@Composable
private fun DetailSummary(
    album: HomeAlbum,
    detail: AlbumDetail?,
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
                value = detail?.imageCount?.let { "$it 页" } ?: "读取中",
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

private fun androidx.compose.foundation.lazy.LazyListScope.detailInfoItems(detail: AlbumDetail) {
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
                text = buildString {
                    append(detail.imageCount?.let { "全 $it 页" } ?: "页数未知")
                    if (detail.series.isNotEmpty()) append(" · 共 ${detail.series.size} 话")
                },
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

private fun androidx.compose.foundation.lazy.LazyListScope.commentsItems(
    comments: CommentPage?,
    commentsError: String?,
    onRetry: () -> Unit,
) {
    when {
        commentsError != null -> item(key = "comments-error") {
            DetailError(message = commentsError, onRetry = onRetry)
        }
        comments == null || comments.comments.isEmpty() -> item(key = "comments-empty") {
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
            items = comments.comments,
            key = { index, comment -> "${comment.id.orEmpty()}:$index" },
        ) { _, comment ->
            CommentCard(comment = comment)
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
                    color = MiuixTheme.colorScheme.secondaryContainer,
                ) {
                    Text(
                        text = value,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSecondaryContainer,
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
    suspend fun load(albumId: String): AlbumDetailUiState = coroutineScope {
        try {
            val detailDeferred = async { core.albumApi.detailFull(albumId) }
            val commentsDeferred = async { core.interactionApi.albumComments(albumId, page = 1) }
            when (val detailResult = detailDeferred.await()) {
                is JmxResult.Success -> {
                    when (val commentsResult = commentsDeferred.await()) {
                        is JmxResult.Success -> AlbumDetailUiState.Content(
                            detail = detailResult.value,
                            comments = commentsResult.value,
                            commentsError = null,
                        )
                        is JmxResult.Failure -> AlbumDetailUiState.Content(
                            detail = detailResult.value,
                            comments = null,
                            commentsError = commentsResult.error.toUiMessage(),
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
}

internal sealed interface AlbumDetailUiState {
    data object Loading : AlbumDetailUiState
    data class Content(
        val detail: AlbumDetail,
        val comments: CommentPage?,
        val commentsError: String?,
    ) : AlbumDetailUiState
    data class Error(val message: String) : AlbumDetailUiState
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
private const val DETAIL_ENTER_DURATION_MILLIS = 680
private const val DETAIL_EXIT_DURATION_MILLIS = 560

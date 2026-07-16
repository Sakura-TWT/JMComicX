package dev.jmx.client

import android.content.Context
import android.text.Html
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import coil.size.Precision
import dev.jmx.client.core.api.AlbumSummary
import dev.jmx.client.core.api.HomePromoteSection
import dev.jmx.client.core.image.ImageUrl
import dev.jmx.client.core.protocol.JmxProtocolConstants
import dev.jmx.client.core.result.JmxResult
import dev.jmx.client.core.runtime.InitStepResult
import dev.jmx.client.core.runtime.JmxCore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.Headers
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.PullToRefresh
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.TabRowWithContour
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.rememberPullToRefreshState
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Image
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun HomeScreen(
    innerPadding: PaddingValues,
    state: HomeUiState,
    isRefreshing: Boolean,
    selectedCategoryIndex: Int,
    onCategorySelected: (Int) -> Unit,
    liftedAlbumId: String?,
    onLoadMore: (String) -> Unit,
    onAlbumSelected: (HomeAlbum, Rect) -> Unit,
    onRefresh: () -> Unit,
    onRetry: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(innerPadding),
    ) {
        when (state) {
            HomeUiState.Loading -> LoadingHome()
            is HomeUiState.Content -> HomeContent(
                categories = state.categories,
                selectedCategoryIndex = selectedCategoryIndex,
                onCategorySelected = onCategorySelected,
                liftedAlbumId = liftedAlbumId,
                onLoadMore = onLoadMore,
                onAlbumSelected = onAlbumSelected,
                isRefreshing = isRefreshing,
                onRefresh = onRefresh,
            )
            is HomeUiState.Empty -> EmptyState(
                title = "暂无推荐",
                message = state.message,
                onRetry = onRetry,
            )
            is HomeUiState.Error -> EmptyState(
                title = "首页加载失败",
                message = state.message,
                onRetry = onRetry,
            )
        }
    }
}

@Composable
private fun HomeContent(
    categories: List<HomeCategory>,
    selectedCategoryIndex: Int,
    onCategorySelected: (Int) -> Unit,
    liftedAlbumId: String?,
    onLoadMore: (String) -> Unit,
    onAlbumSelected: (HomeAlbum, Rect) -> Unit,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
) {
    val safeSelectedIndex = selectedCategoryIndex.coerceIn(categories.indices)
    val pagerState = rememberPagerState(initialPage = safeSelectedIndex) { categories.size }
    val coroutineScope = rememberCoroutineScope()
    val categoryTabWidth = rememberCategoryTabWidth(categories)

    LaunchedEffect(pagerState.settledPage) {
        if (pagerState.settledPage != selectedCategoryIndex) {
            onCategorySelected(pagerState.settledPage)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TabRowWithContour(
            tabs = categories.map { it.title },
            selectedTabIndex = pagerState.currentPage,
            onTabSelected = { index ->
                coroutineScope.launch { pagerState.animateScrollToPage(index) }
            },
            minWidth = categoryTabWidth,
            maxWidth = categoryTabWidth,
            modifier = Modifier.padding(start = 12.dp, top = 10.dp, end = 12.dp, bottom = 8.dp),
        )
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            key = { categories[it].id },
        ) { page ->
            val pullToRefreshState = rememberPullToRefreshState()
            PullToRefresh(
                isRefreshing = isRefreshing && pagerState.currentPage == page,
                onRefresh = onRefresh,
                pullToRefreshState = pullToRefreshState,
                refreshTexts = listOf("下拉刷新", "松开刷新", "正在刷新", "刷新完成"),
                modifier = Modifier.fillMaxSize(),
            ) {
                HomeAlbumGrid(
                    category = categories[page],
                    onLoadMore = onLoadMore,
                    onAlbumSelected = onAlbumSelected,
                    liftedAlbumId = liftedAlbumId,
                )
            }
        }
    }
}

@Composable
private fun rememberCategoryTabWidth(categories: List<HomeCategory>): Dp {
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val textStyle = MiuixTheme.textStyles.body2.copy(fontWeight = FontWeight.Bold)
    val widestTextPx = categories.maxOf { category ->
        textMeasurer.measure(
            text = category.title,
            style = textStyle,
            maxLines = 1,
        ).size.width
    }
    return with(density) { widestTextPx.toDp() }
        .plus(24.dp)
        .coerceIn(84.dp, 160.dp)
}

@Composable
private fun HomeAlbumGrid(
    category: HomeCategory,
    onLoadMore: (String) -> Unit,
    onAlbumSelected: (HomeAlbum, Rect) -> Unit,
    liftedAlbumId: String?,
) {
    val gridState = rememberLazyGridState()
    val footerKey = remember(category.id) { "home-footer:${category.id}" }
    val footerVisible by remember(gridState, footerKey) {
        derivedStateOf {
            gridState.layoutInfo.visibleItemsInfo.any { item -> item.key == footerKey }
        }
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        state = gridState,
        modifier = Modifier
            .fillMaxSize()
            .semantics {
                contentDescription = "${category.title}漫画列表，共${category.albums.size}部"
            },
        contentPadding = PaddingValues(start = 12.dp, top = 12.dp, end = 12.dp, bottom = 28.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        items(
            items = category.albums,
            key = { it.id },
        ) { album ->
            AlbumItem(
                album = album,
                coverLifted = album.id == liftedAlbumId,
                onSelected = onAlbumSelected,
            )
        }
        item(key = footerKey, span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
            HomePaginationFooter(
                category = category,
                visible = footerVisible,
                onLoadMore = { onLoadMore(category.id) },
            )
        }
    }
}

@Composable
private fun HomePaginationFooter(
    category: HomeCategory,
    visible: Boolean,
    onLoadMore: () -> Unit,
) {
    var loadMoreArmed by remember(category.id) { mutableStateOf(true) }
    LaunchedEffect(visible) {
        if (!visible) loadMoreArmed = true
    }
    LaunchedEffect(
        category.id,
        category.nextPage,
        category.isLoadingMore,
        category.loadMoreError,
        category.endReached,
        visible,
    ) {
        if (
            visible &&
            loadMoreArmed &&
            !category.isLoadingMore &&
            category.loadMoreError == null &&
            !category.endReached
        ) {
            loadMoreArmed = false
            onLoadMore()
        }
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp),
        contentAlignment = Alignment.Center,
    ) {
        when {
            category.isLoadingMore -> CircularProgressIndicator(size = 24.dp, strokeWidth = 3.dp)
            category.loadMoreError != null -> TextButton(text = "加载失败，重试", onClick = onLoadMore)
            category.endReached -> Text(
                text = "已经到底了",
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}

@Composable
internal fun AlbumItem(
    album: HomeAlbum,
    coverLifted: Boolean,
    onSelected: (HomeAlbum, Rect) -> Unit,
) {
    var coverBounds by remember(album.id) { mutableStateOf(Rect.Zero) }
    Surface(
        onClick = {
            if (coverBounds.width > 0f && coverBounds.height > 0f) {
                onSelected(album, coverBounds)
            }
        },
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = Color.Transparent,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            AlbumCover(
                album = album,
                visible = !coverLifted,
                onBoundsChanged = { coverBounds = it },
            )
            Spacer(modifier = Modifier.height(9.dp))
            Text(
                text = album.name,
                style = MiuixTheme.textStyles.footnote1,
                fontWeight = FontWeight.SemiBold,
                color = MiuixTheme.colorScheme.onSurface,
                overflow = TextOverflow.Ellipsis,
                maxLines = 2,
                minLines = 2,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = album.author,
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                overflow = TextOverflow.Ellipsis,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun AlbumCover(
    album: HomeAlbum,
    visible: Boolean,
    onBoundsChanged: (Rect) -> Unit,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var retryAttempt by remember(album.id, album.coverUrl) { mutableIntStateOf(0) }
    var retryScheduled by remember(album.id, album.coverUrl) { mutableStateOf(false) }
    var loadFailed by remember(album.id, album.coverUrl) { mutableStateOf(false) }
    val coverRequest = remember(album.coverUrl, retryAttempt) {
        buildCoverRequest(context, album.coverUrl)
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(3f / 4f)
            .onGloballyPositioned { coordinates -> onBoundsChanged(coordinates.boundsInWindow()) }
            .clip(RoundedCornerShape(8.dp))
            .background(MiuixTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) {
        if (visible && loadFailed) {
            FailedCover(
                modifier = Modifier.clickable {
                    loadFailed = false
                    retryAttempt++
                },
            )
        } else if (visible) {
            AsyncImage(
                model = coverRequest,
                contentDescription = album.name,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                onSuccess = {
                    retryScheduled = false
                    loadFailed = false
                },
                onError = {
                    if (retryAttempt < COVER_UI_RETRY_COUNT && !retryScheduled) {
                        retryScheduled = true
                        coroutineScope.launch {
                            delay(COVER_UI_RETRY_BASE_DELAY_MILLIS * (retryAttempt + 1L))
                            retryAttempt++
                            retryScheduled = false
                        }
                    } else if (!retryScheduled) {
                        loadFailed = true
                    }
                },
            )
            if (retryScheduled) CircularProgressIndicator(size = 22.dp, strokeWidth = 3.dp)
        }
    }
}

@Composable
private fun FailedCover(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = MiuixIcons.Image,
            contentDescription = null,
            modifier = Modifier.size(30.dp),
            tint = MiuixTheme.colorScheme.onSurfaceContainerVariant,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "封面未加载成功",
            style = MiuixTheme.textStyles.footnote2,
            color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
            textAlign = TextAlign.Center,
            maxLines = 2,
        )
    }
}

@Composable
private fun LoadingHome() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(modifier = Modifier.height(14.dp))
            Text(
                text = "正在加载首页与封面",
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}

@Composable
private fun EmptyState(
    title: String,
    message: String,
    onRetry: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = title,
                style = MiuixTheme.textStyles.title3,
                color = MiuixTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = message,
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(18.dp))
            TextButton(text = "重试", onClick = onRetry)
        }
    }
}

internal class HomeRepository(
    context: Context,
    internal val core: JmxCore = createAppJmxCore(context),
) {
    private val applicationContext = context.applicationContext
    private val imageLoader: ImageLoader = applicationContext.imageLoader
    private val preferences = applicationContext.getSharedPreferences(
        HOME_PREFERENCES,
        Context.MODE_PRIVATE,
    )
    private var remoteImageHost: String? = null
    internal var currentImageHost: String = ImageUrl.pickDefaultImageHost()
        private set

    internal fun availableImageHosts(): List<String> = buildList {
        remoteImageHost?.let(::add)
        addAll(JmxProtocolConstants.DefaultImageHosts)
    }.map { it.trimEnd('/') }.distinct()

    internal fun preferredImageHost(): String? =
        preferences.getString(PREFERRED_IMAGE_HOST_KEY, null)?.trimEnd('/')

    internal fun useImageHost(host: String?) {
        preferences.edit {
            if (host.isNullOrBlank()) remove(PREFERRED_IMAGE_HOST_KEY)
            else putString(PREFERRED_IMAGE_HOST_KEY, host.trimEnd('/'))
        }
        currentImageHost = host?.trimEnd('/') ?: remoteImageHost ?: ImageUrl.pickDefaultImageHost()
    }

    suspend fun load(preloadCategoryId: String? = null): HomeUiState {
        return try {
            val init = core.initializer.initialize()
            remoteImageHost = (init.settingFetch as? InitStepResult.Success)
                ?.value
                ?.imageHost
                ?.trimEnd('/')
            val imageHost = preferredImageHost()
                ?: remoteImageHost
                ?: ImageUrl.pickDefaultImageHost()
            currentImageHost = imageHost
            when (val result = core.libraryApi.promotedSections()) {
                is JmxResult.Success -> result.value.toHomeState(imageHost, preloadCategoryId)
                is JmxResult.Failure -> HomeUiState.Error(result.error.toUiMessage())
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            HomeUiState.Error(error.message ?: "首页加载出现未知异常。")
        }
    }

    suspend fun loadMore(category: HomeCategory): HomeCategory {
        return try {
            when (val result = core.libraryApi.promotedSectionPage(category.source, category.nextPage)) {
                is JmxResult.Success -> {
                    val receivedAlbums = result.value.content
                        .filter { it.id.isNotBlank() }
                        .distinctBy { it.id }
                        .map { item -> item.toHomeAlbum(category.imageHost) }
                    preloadCovers(receivedAlbums)
                    val mergedAlbums = (category.albums + receivedAlbums).distinctBy { it.id }
                    val total = result.value.total ?: category.total
                    category.copy(
                        albums = mergedAlbums,
                        total = total,
                        nextPage = category.nextPage + 1,
                        isLoadingMore = false,
                        loadMoreError = null,
                        endReached = result.value.content.isEmpty() ||
                            (total != null && mergedAlbums.size >= total),
                    )
                }
                is JmxResult.Failure -> category.copy(
                    isLoadingMore = false,
                    loadMoreError = result.error.toUiMessage(),
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            category.copy(
                isLoadingMore = false,
                loadMoreError = error.message ?: "加载更多时出现未知异常。",
            )
        }
    }

    private suspend fun List<HomePromoteSection>.toHomeState(
        imageHost: String,
        preloadCategoryId: String?,
    ): HomeUiState {
        val mappedCategories = filter { section ->
            section.type in SUPPORTED_HOME_PAGINATION_TYPES
        }.mapIndexedNotNull { index, section ->
            val albums = section.content
                .filter { it.id.isNotBlank() }
                .distinctBy { it.id }
                .take(60)
                .map { item -> item.toHomeAlbum(imageHost) }
            if (albums.isEmpty()) return@mapIndexedNotNull null
            val title = section.title.cleanHomeSectionTitle(fallback = "分类 ${index + 1}")
            HomeCategory(
                id = section.stableCategoryId(index, title),
                title = title,
                albums = albums,
                source = section,
                imageHost = imageHost,
            )
        }.distinctBy { it.id }
        val (serialCategories, otherCategories) = mappedCategories.partition { it.title.isSerialUpdateTitle() }
        val categories = serialCategories + otherCategories
        if (categories.isEmpty()) {
            return HomeUiState.Empty("接口返回成功，但首页分组没有可展示的漫画。")
        }

        val preloadCategory = categories.firstOrNull { it.id == preloadCategoryId } ?: categories.first()
        preloadCovers(preloadCategory.albums)
        return HomeUiState.Content(
            categories = categories,
        )
    }

    private suspend fun preloadCovers(albums: List<HomeAlbum>) = coroutineScope {
        val semaphore = Semaphore(COVER_PRELOAD_CONCURRENCY)
        albums.map { album ->
            async {
                semaphore.withPermit {
                    val request = ImageRequest.Builder(applicationContext)
                        .data(album.coverUrl)
                        .headers(albumCoverHeaders)
                        .size(COVER_PRELOAD_WIDTH_PX, COVER_PRELOAD_HEIGHT_PX)
                        .precision(Precision.INEXACT)
                        .build()
                    imageLoader.execute(request)
                }
            }
        }.awaitAll()
    }
}

internal sealed interface HomeUiState {
    data object Loading : HomeUiState
    data class Content(val categories: List<HomeCategory>) : HomeUiState
    data class Empty(val message: String) : HomeUiState
    data class Error(val message: String) : HomeUiState
}

internal data class HomeCategory(
    val id: String,
    val title: String,
    val albums: List<HomeAlbum>,
    val source: HomePromoteSection,
    val imageHost: String,
    val total: Int? = null,
    val nextPage: Int = 1,
    val isLoadingMore: Boolean = false,
    val loadMoreError: String? = null,
    val endReached: Boolean = false,
)

internal data class HomeAlbum(
    val id: String,
    val name: String,
    val author: String,
    val coverUrl: String,
    val imageHost: String,
)

internal fun AlbumSummary.toHomeAlbum(imageHost: String): HomeAlbum {
    return HomeAlbum(
        id = id,
        name = name?.takeIf { it.isNotBlank() } ?: "未命名漫画",
        author = author?.takeIf { it.isNotBlank() } ?: "未知作者",
        imageHost = imageHost,
        coverUrl = ImageUrl.resolveAlbumCover(
            imageHost = imageHost,
            albumId = id,
            rawImage = image,
        ),
    )
}

internal fun buildCoverRequest(context: Context, url: String): ImageRequest {
    return ImageRequest.Builder(context)
        .data(url)
        .headers(albumCoverHeaders)
        .crossfade(false)
        .build()
}

private fun String?.isSerialUpdateTitle(): Boolean {
    val normalized = this?.trim().orEmpty()
    return normalized.contains("连载") || normalized.contains("連載")
}

private fun String?.cleanHomeSectionTitle(fallback: String): String {
    return this.orEmpty()
        .replace("→右滑看更多→", "")
        .replace("->右滑看更多->", "")
        .decodeHtml()
        .trim()
        .ifBlank { fallback }
}

internal fun String.decodeHtml(): String {
    return Html.fromHtml(this, Html.FROM_HTML_MODE_LEGACY).toString()
}

private fun HomePromoteSection.stableCategoryId(index: Int, resolvedTitle: String): String {
    return id.takeIf { it.isNotBlank() }
        ?: slug?.takeIf { it.isNotBlank() }
        ?: filterValue?.takeIf { it.isNotBlank() }
        ?: type?.takeIf { it.isNotBlank() }?.let { "$it:$resolvedTitle" }
        ?: "section:$index:$resolvedTitle"
}

private val albumCoverHeaders: Headers = Headers.Builder()
    .add("User-Agent", JmxProtocolConstants.MobileUserAgent)
    .add("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8")
    .add("Referer", "https://18comic.vip/")
    .build()

private const val COVER_PRELOAD_CONCURRENCY = 6
private const val COVER_PRELOAD_WIDTH_PX = 360
private const val COVER_PRELOAD_HEIGHT_PX = 480
private const val COVER_UI_RETRY_COUNT = 3
private const val COVER_UI_RETRY_BASE_DELAY_MILLIS = 450L
private val SUPPORTED_HOME_PAGINATION_TYPES = setOf("promote", "category_id", "not_in_category_id")
private const val HOME_PREFERENCES = "jmx_home"
private const val PREFERRED_IMAGE_HOST_KEY = "preferred_image_host"

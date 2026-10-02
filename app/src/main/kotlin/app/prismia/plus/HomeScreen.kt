package app.prismia.plus

import android.content.Context
import android.text.Html
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.foundation.pager.PagerState
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.imageLoader
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.size.Precision
import app.prismia.plus.core.api.AlbumSummary
import app.prismia.plus.core.api.HomePromoteSection
import app.prismia.plus.core.image.ImageUrl
import app.prismia.plus.core.protocol.JmxProtocolConstants
import app.prismia.plus.core.result.JmxResult
import app.prismia.plus.core.runtime.InitStepResult
import app.prismia.plus.core.runtime.JmxCore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.PullToRefresh
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.rememberPullToRefreshState
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Image
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PagerGestureNestedScrollConnection
import top.yukonga.miuix.kmp.utils.pagerGestureOverride

@Composable
internal fun HomeScreen(
    innerPadding: PaddingValues,
    backdrop: LayerBackdrop? = null,
    state: HomeUiState,
    isRefreshing: Boolean,
    pagerState: PagerState,
    liftedAlbumId: String?,
    onLoadMore: (String) -> Unit,
    onAlbumSelected: (HomeAlbum, Rect) -> Unit,
    onRefresh: () -> Unit,
    onRetry: () -> Unit,
    pagerEnabled: Boolean = true,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        when (state) {
            HomeUiState.Loading -> LoadingHome()
            is HomeUiState.Content -> HomeContent(
                categories = state.categories,
                pagerState = pagerState,
                pagerEnabled = pagerEnabled,
                liftedAlbumId = liftedAlbumId,
                onLoadMore = onLoadMore,
                onAlbumSelected = onAlbumSelected,
                isRefreshing = isRefreshing,
                onRefresh = onRefresh,
                backdrop = backdrop,
                topInset = innerPadding.calculateTopPadding(),
                bottomInset = innerPadding.calculateBottomPadding(),
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
    pagerState: PagerState,
    pagerEnabled: Boolean,
    liftedAlbumId: String?,
    onLoadMore: (String) -> Unit,
    onAlbumSelected: (HomeAlbum, Rect) -> Unit,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    backdrop: LayerBackdrop?,
    topInset: Dp,
    bottomInset: Dp,
) {
    // 分页状态由调用方（JmxApp）持有：顶栏标签行与这里的 HorizontalPager 共用同一个
    // PagerState，标签的选中态与指示器位置直接来自分页器的实时进度。此前是"顶栏索引"与
    // "分页状态"两份状态互相回写，且回写用的是 settledPage（惯性停稳后才更新），
    // 手动滑动时顶栏要等分页彻底停下才跟上，观感上就是切换慢、有间隔、割裂。
    Box(modifier = Modifier.fillMaxSize()) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxSize()
                .pagerGestureOverride(pagerState, enabled = pagerEnabled)
                .then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier),
            userScrollEnabled = false,
            pageNestedScrollConnection = PagerGestureNestedScrollConnection,
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
                    topInset = topInset,
                    bottomInset = bottomInset,
                )
            }
        }
    }
}

@Composable
private fun HomeAlbumGrid(
    category: HomeCategory,
    onLoadMore: (String) -> Unit,
    onAlbumSelected: (HomeAlbum, Rect) -> Unit,
    liftedAlbumId: String?,
    topInset: Dp,
    bottomInset: Dp,
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
        contentPadding = PaddingValues(start = 12.dp, top = topInset + 12.dp, end = 12.dp, bottom = 28.dp + bottomInset),
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
    onLongSelected: (() -> Unit)? = null,
    updateChapters: Int = 0,
    selected: Boolean? = null,
    enabled: Boolean = true,
    selectionOverlay: @Composable BoxScope.() -> Unit = {},
) {
    var coverBounds by remember(album.id) { mutableStateOf(Rect.Zero) }
    val select = {
        if (coverBounds.width > 0f && coverBounds.height > 0f) {
            onSelected(album, coverBounds)
        }
    }
    val content: @Composable () -> Unit = {
        Column(modifier = Modifier.fillMaxWidth()) {
            Box(modifier = Modifier.fillMaxWidth()) {
                AlbumCover(
                    album = album,
                    visible = !coverLifted,
                    retryEnabled = enabled && selected == null,
                    onBoundsChanged = { coverBounds = it },
                )
                // 角标画在封面外层：封面会被详情页转场"抬起"做共享元素动画，
                // 画进 AlbumCover 里就会跟着一起飞走。
                if (updateChapters > 0 && !coverLifted) {
                    AlbumUpdateChapterBadge(
                        chapters = updateChapters,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp),
                    )
                }
                if (!coverLifted) selectionOverlay()
            }
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
    val itemModifier = Modifier.fillMaxWidth().then(
        if (selected != null) Modifier.semantics(mergeDescendants = true) {
            role = Role.Checkbox
            toggleableState = ToggleableState(selected)
        } else Modifier,
    )
    if (onLongSelected == null) {
        Surface(
            onClick = select,
            enabled = enabled,
            modifier = itemModifier,
            shape = RoundedCornerShape(8.dp),
            color = Color.Transparent,
            content = content,
        )
    } else {
        Surface(
            modifier = itemModifier
                .combinedClickable(
                    enabled = enabled,
                    onClick = select,
                    onLongClick = onLongSelected,
                    onClickLabel = if (selected != null) { if (selected) "取消选择漫画" else "选择漫画" } else null,
                    onLongClickLabel = "选择漫画",
                ),
            shape = RoundedCornerShape(8.dp),
            color = Color.Transparent,
            content = content,
        )
    }
}

@Composable
private fun AlbumCover(
    album: HomeAlbum,
    visible: Boolean,
    retryEnabled: Boolean,
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
                modifier = if (retryEnabled) Modifier.clickable {
                    loadFailed = false
                    retryAttempt++
                } else Modifier,
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

/**
 * 封面右上角的"更新 N 章"提示。
 *
 * 用 error 配色而不是 primary：这是"有新东西"的通知，和"已选中/已启用"不是一类语义，
 * 混用会让书架里选中态和更新态看起来一样。
 */
@Composable
private fun AlbumUpdateChapterBadge(chapters: Int, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(6.dp),
        color = MiuixTheme.colorScheme.error,
    ) {
        Text(
            text = "更新${if (chapters > 99) "99+" else chapters.toString()}章",
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            style = MiuixTheme.textStyles.footnote2,
            fontWeight = FontWeight.SemiBold,
            color = MiuixTheme.colorScheme.onError,
            maxLines = 1,
        )
    }
}

@Composable
private fun FailedCover(modifier: Modifier = Modifier) {    Column(
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
    // 首屏内容不等待封面：预热在后台低优先级进行，可见封面请求优先获得带宽
    private val coverWarmUpScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val preferences = applicationContext.getSharedPreferences(
        HOME_PREFERENCES,
        Context.MODE_PRIVATE,
    )

    /**
     * 图片线路统一由 core 的线路表决定。
     *
     * 以前这里自己记一个 [currentImageHost]：一次 [load] 选定一台，之后无论那台是否变慢、
     * 被墙、还是直接 403，整个会话都不会换——图片请求占全部流量的绝大多数，却是唯一没有
     * 自动选路的一路。改为委托给 [app.prismia.plus.core.image.ImageHostRegistry] 后，
     * 选路依据变成实际的成败与延迟，且与阅读器、下载器共用同一份健康度。
     */
    private val imageHosts = core.imageHostRegistry

    init {
        migrateLegacyPreferredImageHost()
    }

    /** 当前该用的图片线路（带 scheme，末尾无斜杠）。 */
    internal val currentImageHost: String get() = imageHosts.current()

    internal fun availableImageHosts(): List<String> = buildList {
        addAll(imageHosts.all().map { "https://${it.host}" })
        addAll(JmxProtocolConstants.DefaultImageHosts)
    }.map { it.trimEnd('/') }.distinct()

    internal fun preferredImageHost(): String? = imageHosts.manualHost()

    internal fun useImageHost(host: String?) {
        imageHosts.useManualHost(host)
        // 旧键已迁走，别让它在下次冷启动时又把手动选择"复活"。
        preferences.edit { remove(PREFERRED_IMAGE_HOST_KEY) }
    }

    /**
     * 把 v1 存在 `jmx_home` 里的手动线路搬到线路表里，只做一次。
     * 不搬的话，升级后用户在设置里钉过的线路会静默失效（表现为"我选的线路没生效"）。
     */
    private fun migrateLegacyPreferredImageHost() {
        val legacy = preferences.getString(PREFERRED_IMAGE_HOST_KEY, null)?.trimEnd('/')
        if (legacy.isNullOrBlank()) return
        if (imageHosts.manualHost() == null) imageHosts.useManualHost(legacy)
        preferences.edit { remove(PREFERRED_IMAGE_HOST_KEY) }
    }

    /**
     * 拉首屏。
     *
     * 整段放在 IO 线程上。这条链里最重的几步——读响应体、解密、Gson 解析、写磁盘缓存、
     * 再把上百个条目映射成 [HomeAlbum]（含 `Html.fromHtml` 清洗栏目标题）——原先都跟着
     * 调用方（Compose 的 LaunchedEffect）跑在主线程上，于是每次"转圈快转完时"整个界面卡一下。
     */
    suspend fun load(preloadCategoryId: String? = null): HomeUiState = withContext(Dispatchers.IO) {
        try {
            val init = core.initializer.initialize()
            imageHosts.rememberRemoteHost(
                (init.settingFetch as? InitStepResult.Success)?.value?.imageHost,
            )
            val imageHost = currentImageHost
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

    /** 分类翻页，同 [load]：出网加映射整段留在 IO 线程，避免"刷新即将完成时卡一下"。 */
    suspend fun loadMore(category: HomeCategory): HomeCategory = withContext(Dispatchers.IO) {
        try {
            when (val result = core.libraryApi.promotedSectionPage(category.source, category.nextPage)) {
                is JmxResult.Success -> {
                    val receivedAlbums = result.value.content
                        .filter { it.id.isNotBlank() }
                        .distinctBy { it.id }
                        .map { item -> item.toHomeAlbum(category.imageHost) }
                    warmUpCovers(receivedAlbums.take(LOAD_MORE_COVER_PRELOAD_COUNT))
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
        warmUpCovers(preloadCategory.albums.take(INITIAL_COVER_PRELOAD_COUNT))
        return HomeUiState.Content(
            categories = categories,
        )
    }

    private fun warmUpCovers(albums: List<HomeAlbum>) {
        if (albums.isEmpty()) return
        coverWarmUpScope.launch {
            runCatching { preloadCovers(albums) }
        }
    }

    private suspend fun preloadCovers(albums: List<HomeAlbum>) {
        withTimeoutOrNull(COVER_PRELOAD_TIMEOUT_MILLIS) {
            coroutineScope {
                val semaphore = Semaphore(COVER_PRELOAD_CONCURRENCY)
                albums.map { album ->
                    async {
                        semaphore.withPermit {
                            val request = ImageRequest.Builder(applicationContext)
                                .data(album.coverUrl)
                                .httpHeaders(albumCoverHeaders)
                                .size(COVER_PRELOAD_WIDTH_PX, COVER_PRELOAD_HEIGHT_PX)
                                .precision(Precision.INEXACT)
                                .build()
                            imageLoader.execute(request)
                        }
                    }
                }.awaitAll()
            }
        }
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
        .httpHeaders(albumCoverHeaders)
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

private val albumCoverHeaders: NetworkHeaders = NetworkHeaders.Builder()
    .add("User-Agent", JmxProtocolConstants.MobileUserAgent)
    .add("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8")
    .add("Referer", "https://18comic.vip/")
    .build()

// 预热并发刻意低于可见封面请求可用的网络容量，让首屏可见项优先获得带宽
private const val COVER_PRELOAD_CONCURRENCY = 4
private const val INITIAL_COVER_PRELOAD_COUNT = 12
private const val LOAD_MORE_COVER_PRELOAD_COUNT = 6
private const val COVER_PRELOAD_TIMEOUT_MILLIS = 4_500L
private const val COVER_PRELOAD_WIDTH_PX = 360
private const val COVER_PRELOAD_HEIGHT_PX = 480
private const val COVER_UI_RETRY_COUNT = 3
private const val COVER_UI_RETRY_BASE_DELAY_MILLIS = 450L
private val SUPPORTED_HOME_PAGINATION_TYPES = setOf("promote", "category_id", "not_in_category_id")
private const val HOME_PREFERENCES = "jmx_home"
private const val PREFERRED_IMAGE_HOST_KEY = "preferred_image_host"

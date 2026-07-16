package dev.jmx.client

import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.github.houbb.opencc4j.util.ZhConverterUtil
import dev.jmx.client.core.api.AlbumDetail
import dev.jmx.client.core.api.SearchPage
import dev.jmx.client.core.result.JmxResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.InputField
import top.yukonga.miuix.kmp.basic.SearchBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.Check
import top.yukonga.miuix.kmp.icon.basic.Search
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun ComicSearchScreen(
    homeRepository: HomeRepository,
    liftedAlbumId: String?,
    onDismiss: () -> Unit,
    onAlbumSelected: (HomeAlbum, Rect?) -> Unit,
) {
    val context = LocalContext.current
    val darkMode = isSystemInDarkTheme()
    val density = LocalDensity.current
    val imeVisible = WindowInsets.ime.getBottom(density) > 0
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val repository = remember(homeRepository) { ComicSearchRepository(homeRepository) }
    val historyStore = remember(context) { SearchHistoryStore(context) }
    val statusBarPadding = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    var query by remember { mutableStateOf("") }
    var submittedQuery by remember { mutableStateOf<String?>(null) }
    var searchRequestId by remember { mutableIntStateOf(0) }
    var state by remember { mutableStateOf<ComicSearchUiState>(ComicSearchUiState.Idle) }
    var history by remember(historyStore) { mutableStateOf(historyStore.load()) }
    var deletingHistory by rememberSaveable { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    DisposableEffect(context, darkMode, imeVisible) {
        (context.findActivity() as? ComponentActivity)?.enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { darkMode },
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { darkMode },
        )
        onDispose {}
    }

    fun dismissSearch() {
        focusManager.clearFocus(force = true)
        keyboardController?.hide()
        onDismiss()
    }

    fun openAlbum(album: HomeAlbum, sourceBounds: Rect?) {
        focusManager.clearFocus(force = true)
        keyboardController?.hide()
        onAlbumSelected(album, sourceBounds)
    }

    fun submitSearch(value: String) {
        val normalizedQuery = value.trim()
        if (normalizedQuery.isEmpty()) return
        query = normalizedQuery
        history = historyStore.record(history, normalizedQuery)
        deletingHistory = false
        submittedQuery = normalizedQuery
        searchRequestId++
    }

    LaunchedEffect(submittedQuery, searchRequestId, repository) {
        val normalizedQuery = submittedQuery ?: return@LaunchedEffect
        state = ComicSearchUiState.Loading
        when (val result = repository.search(normalizedQuery, page = 1)) {
            is ComicSearchResult.Direct -> {
                state = ComicSearchUiState.Content(
                    query = normalizedQuery,
                    albums = listOf(result.album),
                    total = 1,
                    nextPage = 2,
                    endReached = true,
                )
                openAlbum(result.album, null)
            }
            is ComicSearchResult.Page -> {
                state = if (result.albums.isEmpty()) {
                    ComicSearchUiState.Empty(normalizedQuery)
                } else {
                    ComicSearchUiState.Content(
                        query = normalizedQuery,
                        albums = result.albums,
                        total = result.total,
                        nextPage = 2,
                        endReached = result.endReached,
                    )
                }
            }
            is ComicSearchResult.Error -> state = ComicSearchUiState.Error(result.message)
        }
    }

    fun loadMore() {
        val content = state as? ComicSearchUiState.Content ?: return
        if (content.isLoadingMore || content.endReached) return
        state = content.copy(isLoadingMore = true, loadMoreError = null)
        coroutineScope.launch {
            val result = repository.search(content.query, content.nextPage)
            val latest = state as? ComicSearchUiState.Content
            if (latest?.query != content.query) return@launch
            state = when (result) {
                is ComicSearchResult.Page -> {
                    val mergedPage = mergeSearchAlbums(
                        existing = latest.albums,
                        incoming = result.albums,
                        sourceEndReached = result.endReached,
                    )
                    latest.copy(
                        albums = mergedPage.albums,
                        total = result.total ?: latest.total,
                        nextPage = latest.nextPage + 1,
                        isLoadingMore = false,
                        endReached = mergedPage.endReached,
                        loadMoreError = null,
                    )
                }
                is ComicSearchResult.Error -> latest.copy(
                    isLoadingMore = false,
                    loadMoreError = result.message,
                )
                is ComicSearchResult.Direct -> latest.copy(isLoadingMore = false)
            }
        }
    }

    BackHandler {
        if (deletingHistory) deletingHistory = false else dismissSearch()
    }
    SearchBar(
        inputField = {
            InputField(
                query = query,
                onQueryChange = {
                    query = it
                    submittedQuery = null
                    state = ComicSearchUiState.Idle
                },
                onSearch = ::submitSearch,
                leadingIcon = {
                    Icon(
                        imageVector = MiuixIcons.Basic.Search,
                        contentDescription = "执行搜索",
                        tint = MiuixTheme.colorScheme.onSurfaceContainerHigh,
                        modifier = Modifier
                            .size(44.dp)
                            .clickable(onClick = { submitSearch(query) })
                            .padding(start = 16.dp, end = 8.dp),
                    )
                },
                expanded = true,
                onExpandedChange = { if (!it) dismissSearch() },
                label = "搜索标题、标签或JM车号",
                modifier = Modifier.fillMaxWidth(),
            )
        },
        expanded = true,
        onExpandedChange = { if (!it) dismissSearch() },
        outsideEndAction = {
            Text(
                text = "取消",
                color = MiuixTheme.colorScheme.primary,
                modifier = Modifier
                    .clickable(onClick = ::dismissSearch)
                    .padding(start = 4.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
            )
        },
        modifier = Modifier
            .fillMaxSize()
            .background(MiuixTheme.colorScheme.surface)
            .padding(top = statusBarPadding),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(MiuixTheme.colorScheme.surface),
        ) {
            when (val current = state) {
                ComicSearchUiState.Idle -> SearchHistory(
                    history = history,
                    deleting = deletingHistory,
                    onDeletingChange = { deletingHistory = it },
                    onSelected = ::submitSearch,
                    onDelete = { historyQuery ->
                        history = historyStore.remove(history, historyQuery)
                        if (history.isEmpty()) deletingHistory = false
                    },
                )
                ComicSearchUiState.Loading -> SearchLoading()
                is ComicSearchUiState.Empty -> SearchMessage("未找到“${current.query}”相关漫画")
                is ComicSearchUiState.Error -> SearchError(
                    message = current.message,
                    onRetry = { searchRequestId++ },
                )
                is ComicSearchUiState.Content -> SearchResultGrid(
                    content = current,
                    liftedAlbumId = liftedAlbumId,
                    onLoadMore = ::loadMore,
                    onAlbumSelected = { album, bounds -> openAlbum(album, bounds) },
                )
            }
        }
    }
}

@Composable
private fun SearchResultGrid(
    content: ComicSearchUiState.Content,
    liftedAlbumId: String?,
    onLoadMore: () -> Unit,
    onAlbumSelected: (HomeAlbum, Rect) -> Unit,
) {
    val gridState = rememberLazyGridState()
    val footerKey = "search-footer"
    val footerVisible by remember(gridState) {
        derivedStateOf {
            gridState.layoutInfo.visibleItemsInfo.any { item -> item.key == footerKey }
        }
    }
    var loadMoreArmed by remember(content.query) { mutableStateOf(true) }
    LaunchedEffect(footerVisible) {
        if (!footerVisible) loadMoreArmed = true
    }
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        state = gridState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 12.dp, top = 12.dp, end = 12.dp, bottom = 28.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        items(items = content.albums, key = { it.id }) { album ->
            AlbumItem(
                album = album,
                coverLifted = album.id == liftedAlbumId,
                onSelected = onAlbumSelected,
            )
        }
        item(key = "search-footer", span = { GridItemSpan(maxLineSpan) }) {
            LaunchedEffect(
                content.nextPage,
                content.isLoadingMore,
                content.loadMoreError,
                content.endReached,
                footerVisible,
            ) {
                if (
                    footerVisible &&
                    loadMoreArmed &&
                    !content.isLoadingMore &&
                    content.loadMoreError == null &&
                    !content.endReached
                ) {
                    loadMoreArmed = false
                    onLoadMore()
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(68.dp),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    content.isLoadingMore -> CircularProgressIndicator(size = 24.dp, strokeWidth = 3.dp)
                    content.loadMoreError != null -> TextButton(text = "加载失败，重试", onClick = onLoadMore)
                    content.endReached -> Text(
                        text = "已显示全部 ${content.albums.size} 部漫画",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchHistory(
    history: List<String>,
    deleting: Boolean,
    onDeletingChange: (Boolean) -> Unit,
    onSelected: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    if (history.isEmpty()) return
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 8.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .padding(start = 20.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "搜索历史",
                style = MiuixTheme.textStyles.title3,
            )
            Spacer(modifier = Modifier.weight(1f))
            IconButton(onClick = { onDeletingChange(!deleting) }) {
                Icon(
                    imageVector = if (deleting) MiuixIcons.Basic.Check else MiuixIcons.Delete,
                    contentDescription = if (deleting) "完成删除" else "删除搜索历史",
                    tint = MiuixTheme.colorScheme.onSurfaceVariantActions,
                )
            }
        }
        history.forEach { item ->
            BasicComponent(
                title = item,
                modifier = Modifier
                    .fillMaxWidth()
                    .combinedClickable(
                        onClick = { if (!deleting) onSelected(item) },
                        onLongClick = { onDeletingChange(true) },
                    ),
                endActions = {
                    AnimatedVisibility(
                        visible = deleting,
                        enter = fadeIn(),
                        exit = fadeOut(),
                    ) {
                        IconButton(onClick = { onDelete(item) }) {
                            Icon(
                                imageVector = MiuixIcons.Delete,
                                contentDescription = "删除 $item",
                                tint = MiuixTheme.colorScheme.primary,
                                modifier = Modifier.size(22.dp),
                            )
                        }
                    }
                },
            )
        }
    }
}

@Composable
private fun SearchLoading() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun SearchMessage(message: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 28.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = message,
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun SearchError(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = message,
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            textAlign = TextAlign.Center,
        )
        TextButton(text = "重试", onClick = onRetry)
    }
}

internal class ComicSearchRepository(
    private val homeRepository: HomeRepository,
) {
    suspend fun search(query: String, page: Int): ComicSearchResult {
        val normalizedQuery = query.trim()
        val directId = normalizedQuery.toJmSearchIdOrNull()
        if (directId != null) return loadDirectAlbum(directId)

        return withContext(Dispatchers.IO) {
            val variants = searchQueryVariants(normalizedQuery, ::toTraditionalChinese)
            try {
                val requests = variants.flatMap { variant ->
                    SEARCH_MAIN_TAGS.map { mainTag -> variant to mainTag }
                }
                val results = coroutineScope {
                    requests.map { (variant, mainTag) ->
                        async {
                            homeRepository.core.albumApi.search(
                                query = variant,
                                page = page,
                                mainTag = mainTag,
                            )
                        }
                    }.awaitAll()
                }
                val successes = results.mapNotNull { (it as? JmxResult.Success)?.value }
                if (successes.isEmpty()) {
                    val failure = results.firstOrNull() as? JmxResult.Failure
                    ComicSearchResult.Error(failure?.error?.toUiMessage() ?: "搜索请求失败。")
                } else {
                    val redirects = successes.mapNotNull(SearchPage::redirectAlbumId).distinct()
                    val redirectedAlbums = redirects.mapNotNull { id ->
                        (homeRepository.core.albumApi.detailFull(id) as? JmxResult.Success)
                            ?.value
                            ?.toHomeAlbum(homeRepository.currentImageHost)
                    }
                    val summaries = successes.flatMap(SearchPage::content)
                    val albums = (redirectedAlbums + summaries.map { summary ->
                        summary.toHomeAlbum(homeRepository.currentImageHost)
                    }).distinctBy { it.id }
                    ComicSearchResult.Page(
                        albums = albums,
                        total = successes.mapNotNull(SearchPage::total).maxOrNull(),
                        endReached = successes.all { it.content.isEmpty() && it.redirectAlbumId == null },
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                ComicSearchResult.Error(error.message ?: "搜索出现未知异常。")
            }
        }
    }

    private suspend fun loadDirectAlbum(albumId: String): ComicSearchResult {
        return when (val result = homeRepository.core.albumApi.detailFull(albumId)) {
            is JmxResult.Success -> ComicSearchResult.Direct(
                result.value.toHomeAlbum(homeRepository.currentImageHost),
            )
            is JmxResult.Failure -> ComicSearchResult.Error(result.error.toUiMessage())
        }
    }
}

internal sealed interface ComicSearchResult {
    data class Direct(val album: HomeAlbum) : ComicSearchResult
    data class Page(
        val albums: List<HomeAlbum>,
        val total: Int?,
        val endReached: Boolean,
    ) : ComicSearchResult
    data class Error(val message: String) : ComicSearchResult
}

internal data class SearchAlbumMerge(
    val albums: List<HomeAlbum>,
    val endReached: Boolean,
)

internal fun mergeSearchAlbums(
    existing: List<HomeAlbum>,
    incoming: List<HomeAlbum>,
    sourceEndReached: Boolean,
): SearchAlbumMerge {
    val existingIds = existing.mapTo(hashSetOf()) { it.id }
    val newAlbums = incoming.filter { it.id !in existingIds }.distinctBy { it.id }
    return SearchAlbumMerge(
        albums = existing + newAlbums,
        endReached = sourceEndReached || newAlbums.isEmpty(),
    )
}

private sealed interface ComicSearchUiState {
    data object Idle : ComicSearchUiState
    data object Loading : ComicSearchUiState
    data class Empty(val query: String) : ComicSearchUiState
    data class Error(val message: String) : ComicSearchUiState
    data class Content(
        val query: String,
        val albums: List<HomeAlbum>,
        val total: Int?,
        val nextPage: Int,
        val isLoadingMore: Boolean = false,
        val endReached: Boolean = false,
        val loadMoreError: String? = null,
    ) : ComicSearchUiState
}

internal fun searchQueryVariants(
    query: String,
    toTraditional: (String) -> String,
): List<String> {
    val normalized = query.trim()
    if (normalized.isEmpty()) return emptyList()
    return listOf(normalized, toTraditional(normalized).trim())
        .filter { it.isNotEmpty() }
        .distinct()
}

internal fun String.toJmSearchIdOrNull(): String? {
    return JM_SEARCH_ID_REGEX.matchEntire(trim())?.groupValues?.getOrNull(1)
}

internal fun AlbumDetail.toHomeAlbum(imageHost: String): HomeAlbum {
    return HomeAlbum(
        id = id,
        name = name?.takeIf { it.isNotBlank() } ?: "JM$id",
        author = authors.joinToString(" / ").ifBlank { "未知作者" },
        coverUrl = dev.jmx.client.core.image.ImageUrl.albumCover(imageHost, id),
        imageHost = imageHost,
    )
}

internal fun toTraditionalChinese(text: String): String =
    runCatching { ZhConverterUtil.toTraditional(text) }.getOrDefault(text)

private val JM_SEARCH_ID_REGEX = Regex("(?i)^(?:JM)?\\s*(\\d+)$")
private val SEARCH_MAIN_TAGS = listOf(0, 3)

package dev.jmx.client

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.jmx.client.core.api.AlbumDetail
import dev.jmx.client.core.api.SearchPage
import dev.jmx.client.core.result.JmxResult
import com.github.houbb.opencc4j.util.ZhConverterUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.InputField
import top.yukonga.miuix.kmp.basic.SearchBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun ComicSearchScreen(
    homeRepository: HomeRepository,
    onDismiss: () -> Unit,
    onAlbumSelected: (HomeAlbum) -> Unit,
) {
    val repository = remember(homeRepository) { ComicSearchRepository(homeRepository) }
    val statusBarPadding = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    var query by remember { mutableStateOf("") }
    var immediateQuery by remember { mutableStateOf<String?>(null) }
    var retryKey by remember { mutableIntStateOf(0) }
    var state by remember { mutableStateOf<ComicSearchUiState>(ComicSearchUiState.Idle) }
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(query, immediateQuery, retryKey, repository) {
        val normalizedQuery = query.trim()
        if (normalizedQuery.isEmpty()) {
            state = ComicSearchUiState.Idle
            return@LaunchedEffect
        }
        if (immediateQuery != normalizedQuery) delay(SEARCH_DEBOUNCE_MILLIS)
        state = ComicSearchUiState.Loading
        when (val result = repository.search(normalizedQuery, page = 1)) {
            is ComicSearchResult.Direct -> onAlbumSelected(result.album)
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
                    val merged = (latest.albums + result.albums).distinctBy { it.id }
                    latest.copy(
                        albums = merged,
                        total = result.total ?: latest.total,
                        nextPage = latest.nextPage + 1,
                        isLoadingMore = false,
                        endReached = result.endReached,
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

    BackHandler(onBack = onDismiss)
    SearchBar(
        inputField = {
            InputField(
                query = query,
                onQueryChange = {
                    query = it
                    if (immediateQuery != null) immediateQuery = null
                },
                onSearch = { immediateQuery = it.trim() },
                expanded = true,
                onExpandedChange = { if (!it) onDismiss() },
                label = "搜索标题、标签或JM车号",
                modifier = Modifier.fillMaxWidth(),
            )
        },
        expanded = true,
        onExpandedChange = { if (!it) onDismiss() },
        outsideEndAction = {
            Text(
                text = "取消",
                color = MiuixTheme.colorScheme.primary,
                modifier = Modifier
                    .clickable(onClick = onDismiss)
                    .padding(start = 4.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
            )
        },
        modifier = Modifier
            .fillMaxSize()
            .padding(top = statusBarPadding)
            .background(MiuixTheme.colorScheme.surface),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(MiuixTheme.colorScheme.surface),
        ) {
            when (val current = state) {
                ComicSearchUiState.Idle -> Unit
                ComicSearchUiState.Loading -> SearchLoading()
                is ComicSearchUiState.Empty -> SearchMessage("未找到“${current.query}”相关漫画")
                is ComicSearchUiState.Error -> SearchError(
                    message = current.message,
                    onRetry = { retryKey++ },
                )
                is ComicSearchUiState.Content -> SearchResultGrid(
                    content = current,
                    onLoadMore = ::loadMore,
                    onAlbumSelected = onAlbumSelected,
                )
            }
        }
    }
}

@Composable
private fun SearchResultGrid(
    content: ComicSearchUiState.Content,
    onLoadMore: () -> Unit,
    onAlbumSelected: (HomeAlbum) -> Unit,
) {
    val gridState = rememberLazyGridState()
    val footerKey = "search-footer"
    val footerVisible by remember(gridState) {
        derivedStateOf {
            gridState.layoutInfo.visibleItemsInfo.any { item -> item.key == footerKey }
        }
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
                coverLifted = false,
                onSelected = { selected, _ -> onAlbumSelected(selected) },
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
                    !content.isLoadingMore &&
                    content.loadMoreError == null &&
                    !content.endReached
                ) {
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

        val variants = searchQueryVariants(normalizedQuery, ::toTraditionalChinese)
        return try {
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
private const val SEARCH_DEBOUNCE_MILLIS = 420L

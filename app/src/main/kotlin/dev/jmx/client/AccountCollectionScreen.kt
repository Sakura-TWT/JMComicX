package dev.jmx.client

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.dp
import dev.jmx.client.core.result.JmxResult
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun AccountCollectionScreen(
    innerPadding: PaddingValues,
    kind: AccountCollectionKind,
    repository: AccountDataRepository,
    sessionRevision: Int,
    liftedAlbumId: String?,
    onAlbumSelected: (HomeAlbum, Rect) -> Unit,
    onRequireLogin: () -> Unit,
) {
    var state by remember(kind) { mutableStateOf<AccountCollectionState>(AccountCollectionState.Loading) }
    var retryKey by remember(kind) { mutableIntStateOf(0) }
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(kind, retryKey, sessionRevision, repository) {
        state = when (val result = repository.loadCollection(kind, page = 1)) {
            is JmxResult.Success -> {
                val total = result.value.total
                AccountCollectionState.Content(
                    albums = result.value.albums,
                    total = total,
                    nextPage = 2,
                    endReached = result.value.albums.isEmpty() ||
                        (total != null && result.value.albums.size >= total),
                )
            }
            is JmxResult.Failure -> {
                if (result.error.requiresSessionRecovery()) onRequireLogin()
                AccountCollectionState.Error(
                    if (result.error.requiresSessionRecovery()) {
                        "登录状态已失效，请重新登录"
                    } else {
                        result.error.toUiMessage()
                    },
                )
            }
        }
    }

    fun loadMore() {
        val content = state as? AccountCollectionState.Content ?: return
        if (content.loadingMore || content.endReached) return
        state = content.copy(loadingMore = true, loadMoreError = null)
        coroutineScope.launch {
            val result = repository.loadCollection(kind, content.nextPage)
            val latest = state as? AccountCollectionState.Content ?: return@launch
            state = when (result) {
                is JmxResult.Success -> {
                    val total = result.value.total
                    val existingIds = latest.albums.mapTo(hashSetOf()) { it.id }
                    val incoming = result.value.albums.filter { it.id !in existingIds }
                    val merged = latest.albums + incoming
                    latest.copy(
                        albums = merged,
                        total = total ?: latest.total,
                        nextPage = latest.nextPage + 1,
                        loadingMore = false,
                        endReached = incoming.isEmpty() ||
                            (total != null && merged.size >= total),
                    )
                }
                is JmxResult.Failure -> latest.copy(
                    loadingMore = false,
                    loadMoreError = if (result.error.requiresSessionRecovery()) {
                        "登录状态已失效，请重新登录"
                    } else {
                        result.error.toUiMessage()
                    },
                ).also {
                    if (result.error.requiresSessionRecovery()) onRequireLogin()
                }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MiuixTheme.colorScheme.surface),
    ) {
        when (val current = state) {
            AccountCollectionState.Loading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            is AccountCollectionState.Error -> CollectionMessage(
                message = current.message,
                action = "重试",
                onAction = { retryKey++ },
            )
            is AccountCollectionState.Content -> if (current.albums.isEmpty()) {
                CollectionMessage(
                    message = if (kind == AccountCollectionKind.FAVORITES) "暂无漫画收藏" else "暂无观看历史",
                )
            } else {
                AccountCollectionGrid(
                    innerPadding = innerPadding,
                    state = current,
                    liftedAlbumId = liftedAlbumId,
                    onAlbumSelected = onAlbumSelected,
                    onLoadMore = ::loadMore,
                )
            }
        }
    }
}

@Composable
private fun AccountCollectionGrid(
    innerPadding: PaddingValues,
    state: AccountCollectionState.Content,
    liftedAlbumId: String?,
    onAlbumSelected: (HomeAlbum, Rect) -> Unit,
    onLoadMore: () -> Unit,
) {
    val gridState = rememberLazyGridState()
    val footerVisible by remember(gridState) {
        derivedStateOf {
            gridState.layoutInfo.visibleItemsInfo.any { it.key == ACCOUNT_COLLECTION_FOOTER }
        }
    }
    var loadMoreArmed by remember { mutableStateOf(true) }
    LaunchedEffect(footerVisible) { if (!footerVisible) loadMoreArmed = true }

    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        state = gridState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 12.dp,
            top = innerPadding.calculateTopPadding() + 12.dp,
            end = 12.dp,
            bottom = innerPadding.calculateBottomPadding() + 24.dp,
        ),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        items(state.albums, key = { it.id }) { album ->
            AlbumItem(
                album = album,
                coverLifted = album.id == liftedAlbumId,
                onSelected = onAlbumSelected,
            )
        }
        item(key = ACCOUNT_COLLECTION_FOOTER, span = { GridItemSpan(maxLineSpan) }) {
            LaunchedEffect(
                state.nextPage,
                state.loadingMore,
                state.loadMoreError,
                state.endReached,
                footerVisible,
            ) {
                if (
                    footerVisible && loadMoreArmed && !state.loadingMore &&
                    state.loadMoreError == null && !state.endReached
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
                    state.loadingMore -> CircularProgressIndicator(size = 24.dp, strokeWidth = 3.dp)
                    state.loadMoreError != null -> TextButton(text = "加载失败，重试", onClick = onLoadMore)
                    state.endReached -> Text(
                        text = "已经到底了",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
        }
    }
}

@Composable
private fun CollectionMessage(
    message: String,
    action: String? = null,
    onAction: () -> Unit = {},
) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        androidx.compose.foundation.layout.Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = message,
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            if (action != null) TextButton(text = action, onClick = onAction)
        }
    }
}

private sealed interface AccountCollectionState {
    data object Loading : AccountCollectionState
    data class Error(val message: String) : AccountCollectionState
    data class Content(
        val albums: List<HomeAlbum>,
        val total: Int?,
        val nextPage: Int,
        val loadingMore: Boolean = false,
        val loadMoreError: String? = null,
        val endReached: Boolean = false,
    ) : AccountCollectionState
}

private const val ACCOUNT_COLLECTION_FOOTER = "account-collection-footer"

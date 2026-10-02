package app.prismia.plus

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.prismia.plus.core.api.FavoriteFolder
import app.prismia.plus.core.result.JmxResult
import app.prismia.plus.effect.BlurTabRow
import app.prismia.plus.effect.BlurredBar
import app.prismia.plus.effect.TopBarBlurStyle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBarDefaults
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.AddFolder
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.icon.extended.SelectAll
import top.yukonga.miuix.kmp.icon.extended.Sort
import top.yukonga.miuix.kmp.menu.WindowIconCascadingDropdownMenu
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PagerGestureNestedScrollConnection
import top.yukonga.miuix.kmp.utils.pagerGestureOverride
import top.yukonga.miuix.kmp.utils.springAnimateToPage

/** Selection is scoped to the active folder's loaded pages, never the whole server collection. */
@Stable
internal class FavoriteCollectionSelection {
    var selecting by mutableStateOf(false)
    var selectedIds by mutableStateOf<Set<String>>(emptySet())
    var availableIds by mutableStateOf<Set<String>>(emptySet())
    var busy by mutableStateOf(false)
    var showFolderPicker by mutableStateOf(false)
    var sortMenuExpanded by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var message by mutableStateOf<String?>(null)

    fun begin(id: String? = null) {
        if (busy) return
        selecting = true
        if (id != null) selectedIds = selectedIds + id
        message = null
    }

    fun toggle(id: String) {
        if (!busy) selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id
    }

    fun cancel() {
        if (busy) return
        selecting = false
        selectedIds = emptySet()
        showFolderPicker = false
        sortMenuExpanded = false
        error = null
    }
}

@Composable
internal fun FavoriteSelectionAction(selection: FavoriteCollectionSelection, selecting: Boolean = selection.selecting) {
    if (selecting) {
        IconButton(
            onClick = {
                selection.selectedIds = if (selection.selectedIds.containsAll(selection.availableIds)) {
                    selection.selectedIds - selection.availableIds
                } else selection.selectedIds + selection.availableIds
            },
            enabled = !selection.busy && !selection.showFolderPicker && selection.availableIds.isNotEmpty(),
            minWidth = 42.dp,
            minHeight = 42.dp,
        ) {
            Icon(
                imageVector = MiuixIcons.SelectAll,
                contentDescription = if (selection.availableIds.isNotEmpty() && selection.selectedIds.containsAll(selection.availableIds)) "取消选择已加载漫画" else "全选已加载漫画",
                tint = MiuixTheme.colorScheme.onBackground,
            )
        }
        IconButton(
            onClick = {
                selection.error = null
                selection.showFolderPicker = true
            },
            enabled = !selection.busy && !selection.showFolderPicker && selection.selectedIds.isNotEmpty(),
            minWidth = 42.dp,
            minHeight = 42.dp,
        ) {
            if (selection.busy) {
                CircularProgressIndicator(
                    size = 22.dp,
                    strokeWidth = 2.dp,
                    modifier = Modifier.semantics { contentDescription = "正在移动到资料夹" },
                )
            } else {
                Icon(
                    imageVector = MiuixIcons.AddFolder,
                    contentDescription = "移动到资料夹",
                    tint = MiuixTheme.colorScheme.primary,
                )
            }
        }
        IconButton(
            onClick = selection::cancel,
            enabled = !selection.busy && !selection.showFolderPicker,
            minWidth = 42.dp,
            minHeight = 42.dp,
        ) {
            Icon(imageVector = MiuixIcons.Close, contentDescription = "退出多选", tint = MiuixTheme.colorScheme.onBackground)
        }
    } else {
        IconButton(
            onClick = { selection.begin() },
            enabled = !selection.busy && selection.availableIds.isNotEmpty(),
            minWidth = 42.dp,
            minHeight = 42.dp,
        ) {
            Icon(imageVector = MiuixIcons.SelectAll, contentDescription = "选择漫画", tint = MiuixTheme.colorScheme.onBackground)
        }
    }
}


@Composable
internal fun AccountCollectionScreen(
    innerPadding: PaddingValues,
    kind: AccountCollectionKind,
    repository: AccountDataRepository,
    sessionRevision: Int,
    liftedAlbumId: String?,
    onAlbumSelected: (HomeAlbum, Rect) -> Unit,
    onRequireLogin: () -> Unit,
    backdrop: LayerBackdrop? = null,
    favoriteOrder: FavoriteSortOrder = FavoriteSortOrder.Default,
    favoriteDirection: FavoriteSortDirection = FavoriteSortDirection.Default,
    updateRecords: Map<String, AlbumUpdateRecord> = emptyMap(),
    selection: FavoriteCollectionSelection? = null,
    topBarBlurStyle: TopBarBlurStyle = TopBarBlurStyle.GAUSSIAN,
    // Favorites owns the complete top bar and takes its padding only from its own Scaffold.
    title: String? = null,
    onBack: (() -> Unit)? = null,
    topBarActions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {},
) {
    val ownSelection = remember(sessionRevision, repository) { FavoriteCollectionSelection() }
    val activeSelection = selection ?: ownSelection
    val favorites = kind == AccountCollectionKind.FAVORITES
    val scope = rememberCoroutineScope()
    var folders by remember(repository, sessionRevision) { mutableStateOf<List<FavoriteFolder>>(emptyList()) }
    var foldersLoading by remember(repository, sessionRevision) { mutableStateOf(true) }
    var folderError by remember(repository, sessionRevision) { mutableStateOf<String?>(null) }
    var foldersRetry by remember { mutableIntStateOf(0) }
    var contentRevision by remember(sessionRevision) { mutableIntStateOf(0) }
    var refreshAfterSelection by remember(repository, sessionRevision) { mutableStateOf(false) }
    // Keep the source FID, not its transient position in a refreshed folder array.
    var currentFolderId by remember(repository, sessionRevision) { mutableStateOf<String?>(null) }
    var restoringFolderPage by remember(repository, sessionRevision) { mutableStateOf(false) }
    val tabs = listOf("全部") + folders.map { it.displayName() }
    val pagerState = rememberPagerState { if (favorites) tabs.size else 1 }
    val hapticFeedback = LocalHapticFeedback.current

    LaunchedEffect(repository, sessionRevision, favorites, foldersRetry, contentRevision) {
        if (!favorites) return@LaunchedEffect
        foldersLoading = true
        folderError = null
        val requestedIdentity = repository.accountIdentity()
        val result = repository.loadFavoriteFolders()
        if (repository.accountIdentity() != requestedIdentity) {
            foldersLoading = false
            folderError = "账号已变化，请重新打开收藏页"
            return@LaunchedEffect
        }
        when (result) {
            is JmxResult.Success -> {
                val rememberedFolderId = currentFolderId
                val freshFolders = favoriteFolderChoices(result.value)
                val freshIndex = freshFolders.indexOfFirst { it.id == rememberedFolderId }
                restoringFolderPage = true
                try {
                    folders = freshFolders
                    val targetPage = if (rememberedFolderId == null || freshIndex < 0) 0 else freshIndex + 1
                    pagerState.scrollToPage(targetPage)
                    currentFolderId = freshFolders.getOrNull(targetPage - 1)?.id
                } finally {
                    restoringFolderPage = false
                }
            }
            is JmxResult.Failure -> {
                folderError = result.error.toUiMessage()
                if (result.error.requiresSessionRecovery()) onRequireLogin()
            }
        }
        foldersLoading = false
    }
    LaunchedEffect(pagerState.currentPage, folders, restoringFolderPage) {
        if (!restoringFolderPage) {
            val visibleFolderId = folders.getOrNull(pagerState.currentPage - 1)?.id
            if (currentFolderId != visibleFolderId) {
                currentFolderId = visibleFolderId
                activeSelection.cancel()
                activeSelection.availableIds = emptySet()
            }
        }
    }
    // Keep loaded pages stable during a partial move so failed IDs remain visible/retryable.
    // Refresh server content only after selection is completed or explicitly cancelled.
    LaunchedEffect(activeSelection.selecting, refreshAfterSelection) {
        if (!activeSelection.selecting && refreshAfterSelection) {
            refreshAfterSelection = false
            contentRevision++
        }
    }
    LaunchedEffect(kind, sessionRevision, favoriteOrder, favoriteDirection) {
        activeSelection.cancel()
        activeSelection.availableIds = emptySet()
    }
    DisposableEffect(activeSelection) {
        onDispose {
            activeSelection.busy = false
            activeSelection.cancel()
            activeSelection.availableIds = emptySet()
        }
    }
    BackHandler(enabled = favorites && activeSelection.selecting && !activeSelection.showFolderPicker) {
        activeSelection.cancel()
    }

    fun moveTo(folder: FavoriteFolder) {
        val target = folder.id.toIntOrNull()?.takeIf { it > 0 } ?: return
        if (activeSelection.busy || activeSelection.selectedIds.isEmpty()) return
        val movingIds = activeSelection.selectedIds.toSet()
        val identity = repository.accountIdentity()
        activeSelection.busy = true
        activeSelection.error = null
        scope.launch {
            try {
                val outcome = repository.moveFavoritesToFolder(movingIds, target)
                if (repository.accountIdentity() != identity) {
                    activeSelection.error = "账号已变化，请重新打开收藏页"
                    onRequireLogin()
                    return@launch
                }
                activeSelection.selectedIds = activeSelection.selectedIds - outcome.succeededIds
                if (outcome.succeededIds.isNotEmpty()) {
                    refreshAfterSelection = true
                    hapticFeedback.performHapticFeedback(HapticFeedbackType.Confirm)
                }
                if (outcome.failures.isEmpty()) {
                    activeSelection.busy = false
                    activeSelection.cancel()
                    activeSelection.message = "已移动 ${outcome.succeededIds.size} 部漫画到「${folder.displayName()}」"
                } else {
                    activeSelection.error = buildString {
                        if (outcome.succeededIds.isNotEmpty()) append("已移动 ${outcome.succeededIds.size} 部；")
                        append("${outcome.failures.size} 部移动失败，已保留选择。")
                        append(outcome.failures.values.first().toUiMessage())
                    }
                    if (outcome.failures.values.any { it.requiresSessionRecovery() }) onRequireLogin()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                activeSelection.error = failure.message ?: "移动失败，已保留选择，请重试"
            } finally {
                activeSelection.busy = false
            }
        }
    }

    val pagerEnabled = favorites && !activeSelection.selecting && !activeSelection.busy &&
        !activeSelection.showFolderPicker && !activeSelection.sortMenuExpanded && !restoringFolderPage
    val collectionContent: @Composable (PaddingValues) -> Unit = { pagePadding ->
        Box(
            modifier = Modifier.fillMaxSize()
                .then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier)
                .background(MiuixTheme.colorScheme.surface),
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize().pagerGestureOverride(pagerState, enabled = pagerEnabled),
                userScrollEnabled = false,
                pageNestedScrollConnection = PagerGestureNestedScrollConnection,
                key = { index -> if (index == 0) "all" else folders[index - 1].id },
            ) { page ->
                CollectionFolderPage(
                    innerPadding = pagePadding,
                    kind = kind,
                    folderId = if (favorites) folders.getOrNull(page - 1)?.id?.toIntOrNull() ?: 0 else 0,
                    repository = repository,
                    sessionRevision = sessionRevision,
                    revision = contentRevision,
                    favoriteOrder = favoriteOrder,
                    favoriteDirection = favoriteDirection,
                    liftedAlbumId = liftedAlbumId,
                    onAlbumSelected = onAlbumSelected,
                    onRequireLogin = onRequireLogin,
                    updateRecords = updateRecords,
                    selection = activeSelection.takeIf { favorites },
                    activePage = (if (page == 0) null else folders.getOrNull(page - 1)?.id) == currentFolderId && !restoringFolderPage,
                    metadataLoading = favorites && foldersLoading,
                )
            }
        }
        FavoriteFolderPicker(
            show = favorites && activeSelection.showFolderPicker,
            repository = repository,
            sessionRevision = sessionRevision,
            onDismiss = { if (!activeSelection.busy) activeSelection.showFolderPicker = false },
            onConfirm = ::moveTo,
            busy = activeSelection.busy,
            error = activeSelection.error,
        )
    }
    if (favorites) {
        // SmallTopAppBar owns the status-bar inset; Scaffold measures the complete header.
        // Neither the parent route padding nor an independently measured overlay is added.
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = Color.Transparent,
            topBar = {
                BlurredBar(backdrop = backdrop, style = topBarBlurStyle) {
                    Column(
                        modifier = Modifier.fillMaxWidth()
                            .then(if (backdrop == null) Modifier.background(MiuixTheme.colorScheme.surface) else Modifier),
                    ) {
                        AnimatedContent(
                            targetState = activeSelection.selecting,
                            transitionSpec = {
                                (fadeIn(tween(220)) + slideInVertically { it / 3 }) togetherWith
                                    (fadeOut(tween(140)) + slideOutVertically { -it / 4 })
                            },
                            label = "FavoriteTopBarMode",
                        ) { multiSelect ->
                            SmallTopAppBar(
                                title = if (multiSelect) "已选择 ${activeSelection.selectedIds.size} 部" else title ?: "我的收藏",
                                titlePadding = if (multiSelect) 8.dp else TopAppBarDefaults.TitlePadding,
                                color = Color.Transparent,
                                navigationIcon = {
                                    if (!multiSelect && onBack != null) {
                                        IconButton(onClick = onBack) {
                                            Icon(
                                                imageVector = MiuixIcons.Back,
                                                contentDescription = "返回",
                                                tint = MiuixTheme.colorScheme.onBackground,
                                            )
                                        }
                                    }
                                },
                                actions = {
                                    FavoriteSelectionAction(activeSelection, selecting = multiSelect)
                                    if (!multiSelect) topBarActions()
                                },
                                bottomContent = {
                                    if (!multiSelect) {
                                        CollectionFolderTabs(
                                            tabs = tabs,
                                            pagerState = pagerState,
                                            enabled = pagerEnabled,
                                            scope = scope,
                                        )
                                    }
                                },
                            )
                        }
                        if (folderError != null) {
                            Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(text = "资料夹读取失败", modifier = Modifier.weight(1f), style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.error)
                                TextButton(text = "重试", onClick = { foldersRetry++ }, enabled = !foldersLoading && !activeSelection.busy)
                            }
                        }
                        activeSelection.message?.let { message ->
                            Text(text = message, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.primary)
                        }
                    }
                }
            },
            content = collectionContent,
        )
    } else {
        collectionContent(innerPadding)
    }
}

@Composable
private fun CollectionFolderTabs(
    tabs: List<String>,
    pagerState: androidx.compose.foundation.pager.PagerState,
    enabled: Boolean,
    scope: kotlinx.coroutines.CoroutineScope,
) {
    BlurTabRow(
        tabs = tabs,
        selectedIndex = pagerState.currentPage.coerceIn(tabs.indices),
        onTabSelected = { index -> if (enabled) scope.launch { pagerState.springAnimateToPage(index) } },
        selectionProgress = { pagerState.currentPage + pagerState.currentPageOffsetFraction },
    )
}

@Composable
private fun CollectionFolderPage(
    innerPadding: PaddingValues,
    kind: AccountCollectionKind,
    folderId: Int,
    repository: AccountDataRepository,
    sessionRevision: Int,
    revision: Int,
    favoriteOrder: FavoriteSortOrder,
    favoriteDirection: FavoriteSortDirection,
    liftedAlbumId: String?,
    onAlbumSelected: (HomeAlbum, Rect) -> Unit,
    onRequireLogin: () -> Unit,
    updateRecords: Map<String, AlbumUpdateRecord>,
    selection: FavoriteCollectionSelection?,
    activePage: Boolean,
    metadataLoading: Boolean,
) {
    var state by remember(kind, folderId, favoriteOrder, favoriteDirection, sessionRevision, revision) {
        mutableStateOf<AccountCollectionState>(AccountCollectionState.Loading)
    }
    var retryKey by remember { mutableIntStateOf(0) }
    val coroutineScope = rememberCoroutineScope()
    var loadJob by remember { mutableStateOf<Job?>(null) }
    DisposableEffect(kind, folderId, favoriteOrder, favoriteDirection, sessionRevision, revision, retryKey) {
        onDispose { loadJob?.cancel() }
    }
    suspend fun loadPage(page: Int, pageCount: Int?): JmxResult<FavoriteLogicalPage> = loadFavoriteLogicalPage(
        logicalPage = page,
        direction = if (kind == AccountCollectionKind.FAVORITES) favoriteDirection else FavoriteSortDirection.ASCENDING,
        knownServerPageCount = pageCount,
        loadPage = { serverPage -> repository.loadCollection(kind, serverPage, favoriteOrder, folderId) },
    )
    LaunchedEffect(kind, folderId, favoriteOrder, favoriteDirection, retryKey, sessionRevision, repository, revision) {
        state = AccountCollectionState.Loading
        when (val result = loadPage(1, null)) {
            is JmxResult.Success -> state = AccountCollectionState.Content(
                albums = result.value.albums,
                total = result.value.total,
                nextPage = 2,
                serverPageCount = result.value.serverPageCount,
                endReached = result.value.albums.isEmpty() || (result.value.total?.let { result.value.albums.size >= it } == true),
            )
            is JmxResult.Failure -> {
                state = AccountCollectionState.Error(result.error.toUiMessage())
                if (result.error.requiresSessionRecovery()) onRequireLogin()
            }
        }
    }
    val validContent = state is AccountCollectionState.Content
    val availableIds = (state as? AccountCollectionState.Content)?.albums?.mapTo(linkedSetOf()) { it.id }.orEmpty()
    LaunchedEffect(activePage, validContent, availableIds, selection) {
        if (activePage && selection != null) {
            selection.availableIds = availableIds
            if (validContent) selection.selectedIds = selection.selectedIds.intersect(availableIds)
        }
    }
    fun loadMore() {
        val content = state as? AccountCollectionState.Content ?: return
        if (content.loadingMore || content.endReached) return
        state = content.copy(loadingMore = true, loadMoreError = null)
        loadJob = coroutineScope.launch {
            when (val result = loadPage(content.nextPage, content.serverPageCount)) {
                is JmxResult.Success -> {
                    val current = state as? AccountCollectionState.Content ?: return@launch
                    val existingIds = current.albums.mapTo(hashSetOf()) { it.id }
                    val incoming = result.value.albums.filter { it.id !in existingIds }
                    val merged = current.albums + incoming
                    state = current.copy(
                        albums = merged,
                        total = result.value.total ?: current.total,
                        nextPage = current.nextPage + 1,
                        serverPageCount = result.value.serverPageCount ?: current.serverPageCount,
                        loadingMore = false,
                        endReached = incoming.isEmpty() || (result.value.total?.let { merged.size >= it } == true),
                    )
                }
                is JmxResult.Failure -> {
                    state = (state as? AccountCollectionState.Content)?.copy(loadingMore = false, loadMoreError = result.error.toUiMessage()) ?: state
                    if (result.error.requiresSessionRecovery()) onRequireLogin()
                }
            }
        }
    }
    when (val current = if (metadataLoading) AccountCollectionState.Loading else state) {
        AccountCollectionState.Loading -> AccountPageLoading(Modifier.padding(innerPadding))
        is AccountCollectionState.Error -> CollectionMessage(current.message, Modifier.padding(innerPadding), "重试") { retryKey++ }
        is AccountCollectionState.Content -> if (current.albums.isEmpty()) {
            CollectionMessage(
                message = if (kind == AccountCollectionKind.FAVORITES) "此资料夹暂无漫画收藏" else "暂无观看历史",
                modifier = Modifier.padding(innerPadding),
            )
        } else AccountCollectionGrid(
            innerPadding = innerPadding,
            state = current,
            liftedAlbumId = liftedAlbumId,
            onAlbumSelected = onAlbumSelected,
            onLoadMore = ::loadMore,
            updateChaptersOf = { id -> if (kind == AccountCollectionKind.FAVORITES) updateRecords[id]?.pendingChapters ?: 0 else 0 },
            selection = selection,
        )
    }
}

@Composable
internal fun FavoriteSortAction(
    order: FavoriteSortOrder,
    direction: FavoriteSortDirection,
    onOrderSelected: (FavoriteSortOrder) -> Unit,
    onDirectionSelected: (FavoriteSortDirection) -> Unit,
    onExpandedChange: (Boolean) -> Unit = {},
) {
    val entry = DropdownEntry(
        items = buildList {
            FavoriteSortOrder.entries.forEach { option ->
                add(DropdownItem(text = option.label, selected = option == order, onClick = { onOrderSelected(option) }))
            }
            add(DropdownItem(
                text = "排序方向",
                summary = direction.label,
                children = FavoriteSortDirection.entries.map { option ->
                    DropdownItem(text = option.label, selected = option == direction, onClick = { onDirectionSelected(option) })
                },
            ))
        },
    )
    WindowIconCascadingDropdownMenu(entry = entry, onExpandedChange = onExpandedChange) {
        Icon(imageVector = MiuixIcons.Sort, contentDescription = "排序：${order.label} · ${direction.label}", tint = MiuixTheme.colorScheme.onBackground)
    }
}

@Composable
private fun AccountCollectionGrid(
    innerPadding: PaddingValues,
    state: AccountCollectionState.Content,
    liftedAlbumId: String?,
    onAlbumSelected: (HomeAlbum, Rect) -> Unit,
    onLoadMore: () -> Unit,
    updateChaptersOf: (String) -> Int,
    selection: FavoriteCollectionSelection?,
) {
    val gridState = rememberLazyGridState()
    val haptics = LocalHapticFeedback.current
    val footerVisible by remember(gridState) { derivedStateOf { gridState.layoutInfo.visibleItemsInfo.any { it.key == ACCOUNT_COLLECTION_FOOTER } } }
    // Load each logical page at most once automatically. A still-visible footer after append
    // may need another page on a tablet; an error always requires the explicit retry button.
    LaunchedEffect(footerVisible, state.nextPage, state.loadingMore, state.loadMoreError, state.endReached) {
        if (footerVisible && !state.loadingMore && state.loadMoreError == null && !state.endReached) onLoadMore()
    }
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        state = gridState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 12.dp, top = innerPadding.calculateTopPadding() + 12.dp, end = 12.dp, bottom = innerPadding.calculateBottomPadding() + 24.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        items(state.albums, key = { it.id }) { album ->
            val selected = selection?.selectedIds?.contains(album.id) == true
            val albumContent: @Composable () -> Unit = {
                AlbumItem(
                    album = album,
                    coverLifted = album.id == liftedAlbumId,
                    onSelected = { chosen, bounds ->
                        if (selection?.selecting == true) selection.toggle(chosen.id) else onAlbumSelected(chosen, bounds)
                    },
                    onLongSelected = selection?.let { holder -> {
                        if (!holder.busy) {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            holder.begin(album.id)
                        }
                    } },
                    updateChapters = if (selection?.selecting == true) 0 else updateChaptersOf(album.id),
                    selected = selected.takeIf { selection?.selecting == true },
                    enabled = selection?.busy != true && selection?.showFolderPicker != true,
                    selectionOverlay = {
                        if (selection?.selecting == true) AlbumSelectionOverlay(selected, Modifier.matchParentSize())
                    },
                )
            }
            if (selection == null) {
                // Keep the history card's existing normal-mode geometry unchanged.
                Surface(shape = RoundedCornerShape(10.dp), color = Color.Transparent) {
                    Box(modifier = Modifier.padding(3.dp)) { albumContent() }
                }
            } else {
                albumContent()
            }
        }
        item(key = ACCOUNT_COLLECTION_FOOTER, span = { GridItemSpan(maxLineSpan) }) {
            Box(modifier = Modifier.fillMaxWidth().height(68.dp), contentAlignment = Alignment.Center) {
                when {
                    state.loadingMore -> CircularProgressIndicator(size = 24.dp, strokeWidth = 3.dp)
                    state.loadMoreError != null -> TextButton(text = "加载失败，重试", onClick = onLoadMore)
                    state.endReached -> Text(text = "已经到底了", style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                }
            }
        }
    }
}

@Composable
private fun CollectionMessage(message: String, modifier: Modifier = Modifier, action: String? = null, onAction: () -> Unit = {}) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(modifier = Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(text = message, style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, textAlign = TextAlign.Center)
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
        val serverPageCount: Int? = null,
        val loadingMore: Boolean = false,
        val loadMoreError: String? = null,
        val endReached: Boolean = false,
    ) : AccountCollectionState
}

private const val ACCOUNT_COLLECTION_FOOTER = "account-collection-footer"

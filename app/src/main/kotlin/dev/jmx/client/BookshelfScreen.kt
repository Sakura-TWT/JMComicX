package dev.jmx.client

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import dev.jmx.client.core.result.JmxResult
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.TabRowWithContour
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.Check
import top.yukonga.miuix.kmp.icon.basic.Search
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.ListView
import top.yukonga.miuix.kmp.icon.extended.Notes
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.icon.extended.SelectAll
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.menu.WindowIconCascadingDropdownMenu
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

@Composable
internal fun BookshelfScreen(
    innerPadding: PaddingValues,
    repository: BookshelfRepository,
    detailRepository: AlbumDetailRepository,
    accountDataRepository: AccountDataRepository,
    authenticated: Boolean,
    onRequireLogin: () -> Unit,
    revision: Int,
    liftedAlbumId: String?,
    onAlbumSelected: (HomeAlbum, Rect) -> Unit,
) {
    var groups by remember(repository) { mutableStateOf(repository.groups()) }
    var selectedGroupId by rememberSaveable(repository) {
        mutableStateOf(ALL_BOOKSHELF_GROUP_ID)
    }
    var sortOrder by remember(repository) { mutableStateOf(repository.sortOrder()) }
    var entries by remember(repository, selectedGroupId, sortOrder) {
        mutableStateOf(repository.entries(selectedGroupId, sortOrder))
    }
    var showGroupEditor by remember { mutableStateOf(false) }
    var showGroupManager by remember { mutableStateOf(false) }
    var editingGroup by remember { mutableStateOf<BookshelfGroup?>(null) }
    var showManualPicker by remember { mutableStateOf(false) }
    var manualGroup by remember { mutableStateOf<BookshelfGroup?>(null) }
    var operationRunning by remember { mutableStateOf(false) }
    var operationMessage by remember { mutableStateOf<String?>(null) }
    var manualFavorites by remember { mutableStateOf<List<HomeAlbum>>(emptyList()) }
    var manualLoading by remember { mutableStateOf(false) }
    var manualError by remember { mutableStateOf<String?>(null) }
    var manualQuery by remember { mutableStateOf("") }
    var manualSearchExpanded by remember { mutableStateOf(false) }
    var selectedManualIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var selectionMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var pendingRemoval by remember { mutableStateOf<Set<String>>(emptySet()) }
    var pendingGroupDeletion by remember { mutableStateOf<BookshelfGroup?>(null) }
    var contentRevision by remember { mutableIntStateOf(0) }
    val coroutineScope = rememberCoroutineScope()
    val hapticFeedback = LocalHapticFeedback.current

    fun reload() {
        groups = repository.groups()
        if (selectedGroupId != ALL_BOOKSHELF_GROUP_ID && groups.none { it.id == selectedGroupId }) {
            selectedGroupId = ALL_BOOKSHELF_GROUP_ID
        }
        entries = repository.entries(selectedGroupId, sortOrder)
        contentRevision++
    }

    LaunchedEffect(repository, revision, selectedGroupId, sortOrder) {
        groups = repository.groups()
        entries = repository.entries(selectedGroupId, sortOrder)
    }

    fun requireAuthentication() {
        if (!authenticated) onRequireLogin()
    }

    fun runFavoriteSync(group: BookshelfGroup) {
        if (!authenticated) {
            operationMessage = "分组设置已保存。登录后可在分组管理中更新收藏匹配内容。"
            requireAuthentication()
            return
        }
        if (!group.matchFavoritesByTags || group.tagRules.isEmpty()) {
            operationMessage = "已保存分组设置。当前分组未启用标签匹配。"
            return
        }
        operationRunning = true
        coroutineScope.launch {
            val result = runCatching {
                syncFavoriteGroup(group, accountDataRepository, detailRepository, repository)
            }
            operationRunning = false
            result.onSuccess { outcome ->
                reload()
                operationMessage = when {
                    outcome.matched == 0 -> "收藏中没有符合全部标签规则的漫画。"
                    outcome.changed == 0 -> "已检查 ${outcome.matched} 部匹配漫画，当前分组已是最新。"
                    else -> "匹配 ${outcome.matched} 部漫画，本次新增或更新 ${outcome.changed} 部。"
                } + " 已有手动加入的内容不会被移除。"
            }.onFailure { error ->
                operationMessage = error.message ?: "收藏同步失败，请稍后重试。"
            }
        }
    }

    val sortChildren = BookshelfSortOrder.entries.map { order ->
        DropdownItem(
            text = order.label,
            selected = order == sortOrder,
            onClick = {
                sortOrder = order
                repository.setSortOrder(order)
            },
        )
    }
    val selectedGroup = groups.firstOrNull { it.id == selectedGroupId }
    val groupTabs = listOf("全部") + groups.map(BookshelfGroup::name)
    val selectedGroupIndex = (groups.indexOfFirst { it.id == selectedGroupId } + 1).coerceAtLeast(0)
    val pagerState = rememberPagerState(initialPage = selectedGroupIndex) { groupTabs.size }
    val groupTabWidth = rememberBookshelfTabWidth(groupTabs)

    LaunchedEffect(pagerState.settledPage, groups) {
        val settledGroupId = if (pagerState.settledPage == 0) {
            ALL_BOOKSHELF_GROUP_ID
        } else {
            groups.getOrNull(pagerState.settledPage - 1)?.id ?: ALL_BOOKSHELF_GROUP_ID
        }
        if (settledGroupId != selectedGroupId) {
            selectionMode = false
            selectedIds = emptySet()
            selectedGroupId = settledGroupId
        }
    }

    LaunchedEffect(selectedGroupId, groups) {
        val targetPage = (groups.indexOfFirst { it.id == selectedGroupId } + 1).coerceAtLeast(0)
        if (pagerState.currentPage != targetPage && !pagerState.isScrollInProgress) {
            pagerState.scrollToPage(targetPage)
        }
    }
    val menuEntry = DropdownEntry(
        items = buildList {
            add(
                DropdownItem(
                    text = "添加分组",
                    onClick = {
                        editingGroup = null
                        showGroupEditor = true
                    },
                ),
            )
            add(
                DropdownItem(
                    text = "分组管理",
                    enabled = selectedGroup != null,
                    summary = if (selectedGroup == null) "请先切换到要管理的分组" else "修改当前分组规则",
                    onClick = {
                        editingGroup = selectedGroup
                        showGroupManager = selectedGroup != null
                    },
                ),
            )
            add(
                DropdownItem(
                    text = "排序方式",
                    summary = sortOrder.label,
                    children = sortChildren,
                ),
            )
            selectedGroup?.let { group ->
                add(
                    DropdownItem(
                        text = "删除当前分组",
                        onClick = { pendingGroupDeletion = group },
                    ),
                )
            }
        },
    )

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            AnimatedContent(
                targetState = selectionMode,
                transitionSpec = {
                    (fadeIn(tween(220)) + slideInVertically { it / 3 }) togetherWith
                        (fadeOut(tween(140)) + slideOutVertically { -it / 4 })
                },
                label = "BookshelfTopBarMode",
            ) { multiSelect ->
                SmallTopAppBar(
                    title = if (multiSelect) "已选择 ${selectedIds.size} 部" else "书架",
                    actions = {
                        if (multiSelect) {
                            IconButton(
                                onClick = {
                                    val visibleIds = entries.mapTo(mutableSetOf(), BookshelfEntry::albumId)
                                    selectedIds = if (selectedIds.containsAll(visibleIds)) {
                                        emptySet()
                                    } else {
                                        visibleIds
                                    }
                                },
                                minWidth = 42.dp,
                                minHeight = 42.dp,
                            ) {
                                Icon(
                                    imageVector = MiuixIcons.SelectAll,
                                    contentDescription = "全选当前分组",
                                    tint = MiuixTheme.colorScheme.onBackground,
                                )
                            }
                            IconButton(
                                onClick = { pendingRemoval = selectedIds },
                                enabled = selectedIds.isNotEmpty(),
                                minWidth = 42.dp,
                                minHeight = 42.dp,
                            ) {
                                Icon(
                                    imageVector = MiuixIcons.Delete,
                                    contentDescription = "删除已选漫画",
                                    tint = MiuixTheme.colorScheme.error,
                                )
                            }
                            IconButton(
                                onClick = {
                                    selectionMode = false
                                    selectedIds = emptySet()
                                },
                                minWidth = 42.dp,
                                minHeight = 42.dp,
                            ) {
                                Icon(
                                    imageVector = MiuixIcons.Close,
                                    contentDescription = "退出多选",
                                    tint = MiuixTheme.colorScheme.onBackground,
                                )
                            }
                        } else {
                            WindowIconCascadingDropdownMenu(entry = menuEntry) {
                                Icon(
                                    imageVector = MiuixIcons.ListView,
                                    contentDescription = "书架功能菜单",
                                    tint = MiuixTheme.colorScheme.onBackground,
                                )
                            }
                        }
                    },
                )
            }
        },
    ) { pagePadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MiuixTheme.colorScheme.surface)
                .padding(
                    top = pagePadding.calculateTopPadding(),
                    bottom = innerPadding.calculateBottomPadding(),
                ),
        ) {
            TabRowWithContour(
                tabs = groupTabs,
                selectedTabIndex = pagerState.currentPage,
                onTabSelected = { index ->
                    selectionMode = false
                    selectedIds = emptySet()
                    coroutineScope.launch { pagerState.animateScrollToPage(index) }
                },
                minWidth = groupTabWidth,
                maxWidth = groupTabWidth,
                modifier = Modifier.padding(start = 12.dp, top = 10.dp, end = 12.dp, bottom = 8.dp),
            )
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxWidth().weight(1f),
                key = { page -> groups.getOrNull(page - 1)?.id ?: ALL_BOOKSHELF_GROUP_ID },
            ) { page ->
                val pageGroupId = groups.getOrNull(page - 1)?.id ?: ALL_BOOKSHELF_GROUP_ID
                val pageEntries = remember(pageGroupId, sortOrder, revision, contentRevision) {
                    repository.entries(pageGroupId, sortOrder)
                }
                if (pageEntries.isEmpty()) {
                    BookshelfEmptyState(customGroup = page > 0)
                } else {
                    val gridState = rememberLazyGridState()
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        state = gridState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 28.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        items(pageEntries, key = BookshelfEntry::albumId) { entry ->
                            val album = entry.toHomeAlbum()
                            Column {
                                Box {
                                    AlbumItem(
                                        album = album,
                                        coverLifted = album.id == liftedAlbumId,
                                        onSelected = { selected, bounds ->
                                            if (selectionMode) {
                                                selectedIds = if (selected.id in selectedIds) {
                                                    selectedIds - selected.id
                                                } else {
                                                    selectedIds + selected.id
                                                }
                                            } else {
                                                onAlbumSelected(selected, bounds)
                                            }
                                        },
                                        onLongSelected = {
                                            hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                                            selectionMode = true
                                            selectedIds = selectedIds + entry.albumId
                                        },
                                    )
                                    if (selectionMode) {
                                        val selected = entry.albumId in selectedIds
                                        val overlayColor by animateColorAsState(
                                            targetValue = if (selected) {
                                                MiuixTheme.colorScheme.primary.copy(alpha = 0.16f)
                                            } else {
                                                Color.Transparent
                                            },
                                            animationSpec = tween(220),
                                            label = "BookshelfSelectionOverlay",
                                        )
                                        val checkAlpha by animateFloatAsState(
                                            targetValue = if (selected) 1f else 0f,
                                            animationSpec = tween(160),
                                            label = "BookshelfSelectionCheck",
                                        )
                                        Box(
                                            modifier = Modifier
                                                .matchParentSize()
                                                .background(overlayColor),
                                        ) {
                                            Icon(
                                                imageVector = MiuixIcons.Basic.Check,
                                                contentDescription = "已选择",
                                                modifier = Modifier
                                                    .align(Alignment.TopEnd)
                                                    .padding(8.dp)
                                                    .graphicsLayer {
                                                        alpha = checkAlpha
                                                        scaleX = 0.82f + 0.18f * checkAlpha
                                                        scaleY = 0.82f + 0.18f * checkAlpha
                                                    },
                                                tint = MiuixTheme.colorScheme.primary,
                                            )
                                        }
                                    }
                                }
                                entry.lastReadAt?.let {
                                    Text(
                                        text = entry.progressSummary(),
                                        style = MiuixTheme.textStyles.footnote2,
                                        color = MiuixTheme.colorScheme.primary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(top = 5.dp, start = 2.dp, end = 2.dp),
                                    )
                                }
                            }
                        }
                        item(key = BOOKSHELF_FOOTER, span = { GridItemSpan(maxLineSpan) }) {
                            Text(
                                text = "共 ${pageEntries.size} 部漫画",
                                style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 18.dp),
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
            }
        }
    }

    BookshelfGroupEditorDialog(
        show = showGroupEditor,
        group = null,
        running = operationRunning,
        onDismiss = { if (!operationRunning) showGroupEditor = false },
        onSubmit = { name, match, rulesText ->
            val group = repository.createGroup(name, match, parseBookshelfTagRules(rulesText))
            if (group == null) {
                operationMessage = "分组名称不能为空，且不能与已有分组重复。"
            } else {
                showGroupEditor = false
                reload()
                runFavoriteSync(group)
            }
        },
    )

    BookshelfGroupManagerDialog(
        show = showGroupManager,
        selectedGroup = editingGroup,
        running = operationRunning,
        onDismiss = { if (!operationRunning) showGroupManager = false },
        onUpdate = { group, name, match, rulesText ->
            val updated = repository.updateGroup(group.id, name, match, parseBookshelfTagRules(rulesText))
            if (updated == null) {
                operationMessage = "分组名称不能为空，且不能与已有分组重复。"
            } else {
                reload()
                showGroupManager = false
                runFavoriteSync(updated)
            }
        },
        onSave = { group, name, match, rulesText ->
            val updated = repository.updateGroup(group.id, name, match, parseBookshelfTagRules(rulesText))
            if (updated == null) {
                operationMessage = "分组名称不能为空，且不能与已有分组重复。"
            } else {
                editingGroup = updated
                showGroupManager = false
                reload()
            }
        },
        onManualSelect = { group ->
            manualGroup = group
            showGroupManager = false
            selectedManualIds = emptySet()
            manualQuery = ""
            manualSearchExpanded = false
            showManualPicker = true
        },
    )

    ManualBookshelfPickerDialog(
        show = showManualPicker,
        group = manualGroup,
        favorites = manualFavorites,
        selectedIds = selectedManualIds,
        query = manualQuery,
        searchExpanded = manualSearchExpanded,
        loading = manualLoading,
        error = manualError,
        onQueryChange = { manualQuery = it },
        onSearchExpandedChange = { manualSearchExpanded = it },
        onToggle = { id ->
            selectedManualIds = if (id in selectedManualIds) selectedManualIds - id else selectedManualIds + id
        },
        onDismiss = { showManualPicker = false },
        onConfirm = {
            manualGroup?.let { group ->
                val albums = manualFavorites.filter { it.id in selectedManualIds }
                repository.addAllToGroup(albums, group.id)
            }
            showManualPicker = false
            reload()
        },
    )

    LaunchedEffect(showManualPicker, authenticated) {
        if (!showManualPicker) return@LaunchedEffect
        if (!authenticated) {
            onRequireLogin()
            showManualPicker = false
            return@LaunchedEffect
        }
        manualLoading = true
        manualError = null
        when (val result = runCatching { loadAllFavoriteAlbums(accountDataRepository) }.getOrNull()) {
            is JmxResult.Success -> manualFavorites = result.value
            is JmxResult.Failure -> manualError = result.error.toUiMessage()
            null -> manualError = "收藏加载失败，请检查登录状态和网络。"
        }
        manualLoading = false
    }

    WindowDialog(
        show = pendingRemoval.isNotEmpty(),
        title = "移出书架",
        summary = "确定移出已选择的 ${pendingRemoval.size} 部漫画吗？阅读进度也会一并删除。",
        onDismissRequest = { pendingRemoval = emptySet() },
    ) {
        TextButton(
            text = "确认移出",
            onClick = {
                pendingRemoval.forEach(repository::remove)
                pendingRemoval = emptySet()
                selectedIds = emptySet()
                selectionMode = false
                reload()
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }

    WindowDialog(
        show = pendingGroupDeletion != null,
        title = "删除分组",
        summary = pendingGroupDeletion?.let {
            "确定删除“${it.name}”吗？漫画仍会保留在“全部”中。"
        },
        onDismissRequest = { pendingGroupDeletion = null },
    ) {
        TextButton(
            text = "确认删除",
            onClick = {
                pendingGroupDeletion?.let { repository.deleteGroup(it.id) }
                pendingGroupDeletion = null
                editingGroup = null
                reload()
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }

    WindowDialog(
        show = operationRunning,
        title = "正在更新书架",
        summary = "正在读取收藏标签并匹配分组规则，请稍候。",
        onDismissRequest = {},
    ) {
        Box(
            modifier = Modifier.fillMaxWidth().height(64.dp),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(size = 30.dp, strokeWidth = 3.dp)
        }
    }

    WindowDialog(
        show = operationMessage != null,
        title = "书架分组",
        summary = operationMessage,
        onDismissRequest = { operationMessage = null },
    ) {
        TextButton(
            text = "知道了",
            onClick = { operationMessage = null },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun BookshelfGroupEditorDialog(
    show: Boolean,
    group: BookshelfGroup?,
    running: Boolean,
    onDismiss: () -> Unit,
    onSubmit: (String, Boolean, String) -> Unit,
) {
    var name by remember(show, group) { mutableStateOf(group?.name.orEmpty()) }
    var rules by remember(show, group) { mutableStateOf(group?.tagRules?.joinToString(" ").orEmpty()) }
    var matchFavorites by remember(show, group) { mutableStateOf(group?.matchFavoritesByTags ?: false) }
    WindowDialog(
        show = show,
        title = "添加书架分组",
        summary = "可按标签从收藏中自动纳入，多个标签需要同时命中。",
        onDismissRequest = onDismiss,
    ) {
        TextField(
            value = name,
            onValueChange = { name = it },
            label = "分组名称",
            enabled = !running,
            maxLines = 1,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("按收藏标签自动收录", style = MiuixTheme.textStyles.body2)
                Text(
                    "创建后从远程收藏匹配并加入本分组",
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            Switch(checked = matchFavorites, onCheckedChange = { matchFavorites = it }, enabled = !running)
        }
        if (matchFavorites) {
            Spacer(modifier = Modifier.height(10.dp))
            TextField(
                value = rules,
                onValueChange = { rules = it },
                label = "标签匹配规则",
                enabled = !running,
                maxLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = "例如：韩漫，或 韩漫 全彩",
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(start = 12.dp, top = 5.dp),
            )
        }
        Spacer(modifier = Modifier.height(14.dp))
        TextButton(
            text = if (running) "正在同步收藏" else "确定",
            enabled = name.isNotBlank() &&
                (!matchFavorites || parseBookshelfTagRules(rules).isNotEmpty()) &&
                !running,
            onClick = { onSubmit(name, matchFavorites, rules) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun BookshelfGroupManagerDialog(
    show: Boolean,
    selectedGroup: BookshelfGroup?,
    running: Boolean,
    onDismiss: () -> Unit,
    onUpdate: (BookshelfGroup, String, Boolean, String) -> Unit,
    onSave: (BookshelfGroup, String, Boolean, String) -> Unit,
    onManualSelect: (BookshelfGroup) -> Unit,
) {
    var name by remember(show, selectedGroup) { mutableStateOf(selectedGroup?.name.orEmpty()) }
    var rules by remember(show, selectedGroup) {
        mutableStateOf(selectedGroup?.tagRules?.joinToString(" ").orEmpty())
    }
    var matchFavorites by remember(show, selectedGroup) {
        mutableStateOf(selectedGroup?.matchFavoritesByTags ?: false)
    }
    WindowDialog(
        show = show,
        title = "分组管理",
        summary = "更新只会追加匹配内容，手动加入的漫画不会被清除。",
        onDismissRequest = onDismiss,
    ) {
        if (selectedGroup == null) {
            Text("暂无分组", color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        } else {
            TextField(
                value = name,
                onValueChange = { name = it },
                label = "分组名称",
                enabled = !running,
                maxLines = 1,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("按收藏标签自动收录", style = MiuixTheme.textStyles.body2)
                    Text(
                        "点击右侧刷新按钮更新内容",
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
                IconButton(
                    onClick = { onUpdate(selectedGroup, name, matchFavorites, rules) },
                    enabled = !running &&
                        name.isNotBlank() &&
                        (!matchFavorites || parseBookshelfTagRules(rules).isNotEmpty()),
                    minWidth = 42.dp,
                    minHeight = 42.dp,
                ) {
                    Icon(
                        imageVector = MiuixIcons.Refresh,
                        contentDescription = "立即更新分组",
                        tint = MiuixTheme.colorScheme.primary,
                    )
                }
                Switch(checked = matchFavorites, onCheckedChange = { matchFavorites = it }, enabled = !running)
            }
            if (matchFavorites) {
                Spacer(modifier = Modifier.height(10.dp))
                TextField(
                    value = rules,
                    onValueChange = { rules = it },
                    label = "标签匹配规则",
                    enabled = !running,
                    maxLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = "例如：韩漫 全彩",
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(start = 12.dp, top = 5.dp),
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            TextButton(
                text = "手动选择收藏漫画",
                enabled = !running,
                onClick = { onManualSelect(selectedGroup) },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TextButton(
                    text = "取消",
                    enabled = !running,
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    text = "保存",
                    enabled = name.isNotBlank() &&
                        (!matchFavorites || parseBookshelfTagRules(rules).isNotEmpty()) &&
                        !running,
                    onClick = { onSave(selectedGroup, name, matchFavorites, rules) },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            }
        }
    }
}

@Composable
private fun ManualBookshelfPickerDialog(
    show: Boolean,
    group: BookshelfGroup?,
    favorites: List<HomeAlbum>,
    selectedIds: Set<String>,
    query: String,
    searchExpanded: Boolean,
    loading: Boolean,
    error: String?,
    onQueryChange: (String) -> Unit,
    onSearchExpandedChange: (Boolean) -> Unit,
    onToggle: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val filtered = favorites.filter { album ->
        query.isBlank() || album.name.contains(query, ignoreCase = true) || album.id.contains(query)
    }
    WindowDialog(
        show = show,
        title = "手动加入 ${group?.name.orEmpty()}",
        summary = "已选择 ${selectedIds.size} 部，确认后追加到当前分组。",
        onDismissRequest = onDismiss,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            IconButton(
                onClick = { onSearchExpandedChange(!searchExpanded) },
                minWidth = 42.dp,
                minHeight = 42.dp,
            ) {
                Icon(
                    imageVector = MiuixIcons.Basic.Search,
                    contentDescription = "搜索收藏",
                    tint = MiuixTheme.colorScheme.primary,
                )
            }
        }
        if (searchExpanded) {
            TextField(
                value = query,
                onValueChange = onQueryChange,
                label = "搜索漫画名称或车号",
                maxLines = 1,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(8.dp))
        }
        Box(modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 420.dp)) {
            when {
                loading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                error != null -> Text(
                    text = error,
                    modifier = Modifier.align(Alignment.Center).padding(16.dp),
                    color = MiuixTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                )
                filtered.isEmpty() -> Text(
                    text = "没有匹配的收藏漫画",
                    modifier = Modifier.align(Alignment.Center),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(filtered, key = HomeAlbum::id) { album ->
                        val selected = album.id in selectedIds
                        Surface(
                            onClick = { onToggle(album.id) },
                            shape = RoundedCornerShape(8.dp),
                            color = if (selected) {
                                MiuixTheme.colorScheme.primaryContainer
                            } else {
                                MiuixTheme.colorScheme.surfaceContainerHigh
                            },
                            modifier = Modifier.padding(vertical = 3.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                AsyncImage(
                                    model = buildCoverRequest(LocalContext.current, album.coverUrl),
                                    contentDescription = album.name,
                                    modifier = Modifier.size(48.dp).clip(RoundedCornerShape(6.dp)),
                                    contentScale = ContentScale.Crop,
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(album.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        "JM${album.id} · ${album.author}",
                                        style = MiuixTheme.textStyles.footnote2,
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                if (selected) {
                                    Icon(
                                        imageVector = MiuixIcons.Basic.Check,
                                        contentDescription = "已选择",
                                        tint = MiuixTheme.colorScheme.primary,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(10.dp))
        TextButton(
            text = "确定添加",
            onClick = onConfirm,
            enabled = !loading && selectedIds.isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun BookshelfEmptyState(customGroup: Boolean) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = MiuixIcons.Notes,
                contentDescription = null,
                modifier = Modifier.size(42.dp),
                tint = MiuixTheme.colorScheme.onSurfaceVariantActions,
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = if (customGroup) "此分组还没有漫画" else "书架还是空的",
                style = MiuixTheme.textStyles.title3,
                color = MiuixTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(5.dp))
            Text(
                text = if (customGroup) "可以从右上角的分组管理中手动加入收藏" else "在漫画详情页加入书架，下次可直接继续阅读",
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(horizontal = 24.dp),
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun rememberBookshelfTabWidth(tabs: List<String>): Dp {
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val textStyle = MiuixTheme.textStyles.body2.copy(fontWeight = FontWeight.Bold)
    val widestTextPx = tabs.maxOfOrNull { tab ->
        textMeasurer.measure(
            text = tab,
            style = textStyle,
            maxLines = 1,
        ).size.width
    } ?: 0
    return with(density) { widestTextPx.toDp() }
        .plus(24.dp)
        .coerceIn(84.dp, 160.dp)
}

private suspend fun syncFavoriteGroup(
    group: BookshelfGroup,
    accountDataRepository: AccountDataRepository,
    detailRepository: AlbumDetailRepository,
    bookshelfRepository: BookshelfRepository,
): BookshelfGroupSyncOutcome {
    val albums = when (val result = loadAllFavoriteAlbums(accountDataRepository)) {
        is JmxResult.Success -> result.value
        is JmxResult.Failure -> error(result.error.toUiMessage())
    }
    val semaphore = Semaphore(6)
    val matched = coroutineScope {
        albums.map { album ->
            async {
                semaphore.withPermit {
                    when (val result = detailRepository.load(album.id)) {
                        is AlbumDetailUiState.Content -> album.takeIf {
                            matchesBookshelfTagRules(result.detail.tags, group.tagRules)
                        }
                        else -> null
                    }
                }
            }
        }.awaitAll().filterNotNull()
    }
    return BookshelfGroupSyncOutcome(
        matched = matched.size,
        changed = bookshelfRepository.addAllToGroup(matched, group.id),
    )
}

private suspend fun loadAllFavoriteAlbums(
    repository: AccountDataRepository,
): JmxResult<List<HomeAlbum>> {
    val all = mutableListOf<HomeAlbum>()
    val knownIds = mutableSetOf<String>()
    var page = 1
    var expectedTotal: Int? = null
    while (page <= MAX_FAVORITE_PAGES) {
        val result = repository.loadCollection(AccountCollectionKind.FAVORITES, page)
        when (result) {
            is JmxResult.Failure -> return result
            is JmxResult.Success -> {
                expectedTotal = result.value.total ?: expectedTotal
                val incoming = result.value.albums.filter { knownIds.add(it.id) }
                all += incoming
                if (
                    incoming.isEmpty() ||
                    result.value.albums.isEmpty() ||
                    (expectedTotal?.let { all.size >= it } == true)
                ) {
                    return JmxResult.Success(all)
                }
            }
        }
        page++
    }
    return JmxResult.Success(all)
}

private const val BOOKSHELF_FOOTER = "bookshelf-footer"
private const val MAX_FAVORITE_PAGES = 50

private data class BookshelfGroupSyncOutcome(
    val matched: Int,
    val changed: Int,
)

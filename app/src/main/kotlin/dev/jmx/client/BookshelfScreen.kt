package dev.jmx.client

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.jmx.client.core.protocol.JmxMagicConstants
import dev.jmx.client.core.result.JmxResult
import top.yukonga.miuix.kmp.blur.layerBackdrop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.PullToRefresh
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.TabRowWithContour
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TopAppBarDefaults
import top.yukonga.miuix.kmp.basic.rememberPullToRefreshState
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.Search
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.AddFolder
import top.yukonga.miuix.kmp.icon.extended.ExpandMore
import top.yukonga.miuix.kmp.icon.extended.Import
import top.yukonga.miuix.kmp.icon.extended.ListView
import top.yukonga.miuix.kmp.icon.extended.Notes
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.icon.extended.SelectAll
import top.yukonga.miuix.kmp.icon.extended.Share
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.menu.WindowIconCascadingDropdownMenu
import top.yukonga.miuix.kmp.menu.WindowIconDropdownMenu
import top.yukonga.miuix.kmp.preference.WindowDropdownPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PagerGestureNestedScrollConnection
import top.yukonga.miuix.kmp.utils.pagerGestureOverride
import top.yukonga.miuix.kmp.utils.springAnimateToPage
import top.yukonga.miuix.kmp.window.WindowDialog

@Composable
internal fun BookshelfScreen(
    innerPadding: PaddingValues,
    repository: BookshelfRepository,
    detailRepository: AlbumDetailRepository,
    accountDataRepository: AccountDataRepository,
    homeRepository: HomeRepository,
    authenticated: Boolean,
    onRequireLogin: () -> Unit,
    revision: Int,
    liftedAlbumId: String?,
    onAlbumSelected: (HomeAlbum, Rect) -> Unit,
    onOpenGroupOrder: () -> Unit = {},
    pendingGroupId: String? = null,
    onPendingGroupConsumed: () -> Unit = {},
    barBackdrop: top.yukonga.miuix.kmp.blur.LayerBackdrop? = null,
    topBarBlurStyle: dev.jmx.client.effect.TopBarBlurStyle = dev.jmx.client.effect.TopBarBlurStyle.GAUSSIAN,
    updateRecords: Map<String, AlbumUpdateRecord> = emptyMap(),
    onRefresh: suspend () -> Unit = {},
) {
    val activeBarBackdrop = barBackdrop ?: dev.jmx.client.effect.rememberBarBackdrop()
    var groups by remember(repository) { mutableStateOf(repository.groups()) }
    var selectedGroupId by rememberSaveable(repository) {
        mutableStateOf(ALL_BOOKSHELF_GROUP_ID)
    }
    var sortOrder by remember(repository) { mutableStateOf(repository.sortOrder()) }
    var sortDirection by remember(repository) { mutableStateOf(repository.sortDirection()) }
    var entries by remember(repository, selectedGroupId, sortOrder, sortDirection) {
        mutableStateOf(repository.entries(selectedGroupId, sortOrder, sortDirection))
    }
    var showGroupEditor by remember { mutableStateOf(false) }
    var menuExpanded by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    var showGroupManager by remember { mutableStateOf(false) }
    var editingGroup by remember { mutableStateOf<BookshelfGroup?>(null) }
    var showManualPicker by remember { mutableStateOf(false) }
    var manualGroup by remember { mutableStateOf<BookshelfGroup?>(null) }
    var operationRunning by remember { mutableStateOf(false) }
    var operationMessage by remember { mutableStateOf<String?>(null) }
    var manualFavorites by remember { mutableStateOf<List<HomeAlbum>>(emptyList()) }
    var manualLoading by remember { mutableStateOf(false) }
    var manualError by remember { mutableStateOf<String?>(null) }
    var manualSource by remember { mutableStateOf(ManualBookshelfSource.BOOKSHELF) }
    var manualQuery by remember { mutableStateOf("") }
    var manualSearchExpanded by remember { mutableStateOf(false) }
    var selectedManualIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var selectionMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var pendingRemoval by remember { mutableStateOf<Set<String>>(emptySet()) }
    var pendingGroupDeletion by remember { mutableStateOf<BookshelfGroup?>(null) }
    var showSelectionGroupPicker by remember { mutableStateOf(false) }
    var showExportDialog by remember { mutableStateOf(false) }
    var pendingImport by remember { mutableStateOf<BookshelfSnapshot?>(null) }
    var pendingImportReplace by remember { mutableStateOf<BookshelfSnapshot?>(null) }
    var transferRunning by remember { mutableStateOf(false) }
    var contentRevision by remember { mutableIntStateOf(0) }
    val coroutineScope = rememberCoroutineScope()
    val hapticFeedback = LocalHapticFeedback.current
    val context = LocalContext.current

    fun reload() {
        groups = repository.groups()
        if (selectedGroupId != ALL_BOOKSHELF_GROUP_ID && groups.none { it.id == selectedGroupId }) {
            selectedGroupId = ALL_BOOKSHELF_GROUP_ID
        }
        entries = repository.entries(selectedGroupId, sortOrder, sortDirection)
        contentRevision++
    }

    /**
     * 下拉刷新。
     *
     * 不是"只为更新检测"服务的：它同时重读本地书架（分组规则、阅读进度可能在别处改过）
     * 并让外部触发一次强制更新扫描。用户的极端场景——正好在下拉时某部漫画更新了——
     * 靠 [onRefresh] 里的强制扫描兜住，不受最小扫描间隔限制。
     */
    fun refreshBookshelf() {
        if (refreshing) return
        refreshing = true
        coroutineScope.launch {
            try {
                reload()
                onRefresh()
            } finally {
                refreshing = false
            }
        }
    }

    fun applyImport(snapshot: BookshelfSnapshot, replace: Boolean) {
        transferRunning = true
        coroutineScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                if (replace) repository.replaceWith(snapshot) else repository.mergeFrom(snapshot)
            }
            transferRunning = false
            pendingImport = null
            pendingImportReplace = null
            selectionMode = false
            selectedIds = emptySet()
            reload()
            hapticFeedback.performHapticFeedback(HapticFeedbackType.Confirm)
            operationMessage = buildBookshelfImportMessage(outcome, replace)
        }
    }

    // 文件选择器放开 MIME 过滤：各家文件管理器给 .json 报的类型五花八门（有的报
    // application/octet-stream），按 application/json 过滤会让用户在选择器里根本看不到
    // 自己刚导出的文件。文件内容本身有 format 字段兜底校验。
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        transferRunning = true
        coroutineScope.launch {
            when (val result = importBookshelfSnapshot(context, uri)) {
                is BookshelfImportResult.Success -> pendingImport = result.snapshot
                is BookshelfImportResult.Failure -> operationMessage = result.message
            }
            transferRunning = false
        }
    }

    LaunchedEffect(repository, revision, selectedGroupId, sortOrder, sortDirection) {
        reload()
    }
    // Repository reads are synchronous: prune only against the freshly read active group,
    // never against an adjacent pager page or an intermediate loading/empty placeholder.
    LaunchedEffect(entries, selectionMode) {
        if (selectionMode) selectedIds = selectedIds.intersect(entries.mapTo(hashSetOf(), BookshelfEntry::albumId))
    }
    BackHandler(enabled = selectionMode && !showSelectionGroupPicker && pendingRemoval.isEmpty() && operationMessage == null) {
        selectionMode = false
        selectedIds = emptySet()
    }

    fun requireAuthentication() {
        if (!authenticated) onRequireLogin()
    }

    fun runAutoCollect(group: BookshelfGroup) {
        val needsFavorites = group.matchFavoritesByTags ||
            (group.matchByAuthors && group.authorMatchSource == BookshelfAuthorMatchSource.FAVORITES)
        if (!group.matchFavoritesByTags && !group.matchByAuthors) {
            operationMessage = "已保存分组设置。当前没有启用自动收录规则。"
            return
        }
        if (needsFavorites && !authenticated) {
            operationMessage = "分组设置已保存。登录后可更新来自收藏的自动收录内容。"
            requireAuthentication()
            return
        }
        operationRunning = true
        coroutineScope.launch {
            val result = runCatching {
                syncBookshelfGroup(
                    group = group,
                    homeRepository = homeRepository,
                    accountDataRepository = accountDataRepository,
                    detailRepository = detailRepository,
                    bookshelfRepository = repository,
                )
            }
            operationRunning = false
            result.onSuccess { outcome ->
                reload()
                operationMessage = when {
                    outcome.matched == 0 -> "没有找到同时符合当前标签和作者规则的漫画。"
                    outcome.changed == 0 -> "已检查 ${outcome.candidates} 部候选漫画，当前分组已是最新。"
                    else -> "匹配 ${outcome.matched} 部漫画，本次新增或更新 ${outcome.changed} 部。"
                } + " 已有手动加入的内容不会被移除。"
            }.onFailure { error ->
                operationMessage = error.message ?: "收藏同步失败，请稍后重试。"
            }
        }
    }

    /**
     * 保存已有分组的规则并立即按新规则收录一次。
     *
     * 保存与"立即更新"走同一条路径：分组管理里改完规则只落盘、不收录的话，
     * 用户看到的就是"填了作者、点了保存、书架什么都没多"——规则得等下一次手动刷新才生效，
     * 而这个入口本来就是唯一能改规则的地方。没启用任何规则时 [runAutoCollect] 只会给一句提示，
     * 不会白跑网络请求。
     */
    fun applyGroupConfiguration(group: BookshelfGroup, config: BookshelfGroupConfiguration) {
        val updated = repository.updateGroup(
            groupId = group.id,
            name = config.name,
            matchFavoritesByTags = config.matchFavoritesByTags,
            tagRules = config.tagRules,
            matchByAuthors = config.matchByAuthors,
            authorRules = config.authorRules,
            authorMatchSource = config.authorMatchSource,
        )
        if (updated == null) {
            operationMessage = "分组名称不能为空，且不能与已有分组重复。"
            return
        }
        editingGroup = updated
        showGroupManager = false
        reload()
        runAutoCollect(updated)
    }

    // 名称 / 更新时间 / 最近阅读 放回"排序方式"的二级菜单；同一菜单底部再加一个"倒序"勾选项，
    // 勾上=倒序、不勾=正序。方向是跟着当前字段走的修饰项，与字段同列一眼就能看清
    // "按什么 + 哪个方向"，也省掉了原先第二个子菜单那次多余的展开。
    val sortChildren = BookshelfSortOrder.entries.map { order ->
        DropdownItem(
            text = order.label,
            selected = order == sortOrder,
            onClick = {
                sortOrder = order
                sortDirection = repository.sortDirection(order)
                repository.setSortOrder(order)
            },
        )
    } + DropdownItem(
        text = "倒序",
        selected = sortDirection == BookshelfSortDirection.DESCENDING,
        onClick = {
            val next = if (sortDirection == BookshelfSortDirection.DESCENDING) {
                BookshelfSortDirection.ASCENDING
            } else {
                BookshelfSortDirection.DESCENDING
            }
            sortDirection = next
            repository.setSortDirection(sortOrder, next)
        },
    )
    val selectedGroup = groups.firstOrNull { it.id == selectedGroupId }
    val groupTabs = listOf("全部") + groups.map(BookshelfGroup::name)
    // 红点按"这一组里有没有漫画在更新"算，而不是各标签各记一份已读。
    // 同一部漫画同时在"全部"和自建分组里，点开它之后两个标签的红点自然一起消失。
    val badgedTabs = remember(groups, updateRecords, revision, contentRevision, repository) {
        if (updateRecords.values.none(AlbumUpdateRecord::hasUpdate)) {
            emptySet()
        } else {
            buildSet {
                val pendingIds = updateRecords.values
                    .filter(AlbumUpdateRecord::hasUpdate)
                    .mapTo(mutableSetOf(), AlbumUpdateRecord::albumId)
                val all = repository.entries()
                if (all.any { it.albumId in pendingIds }) add(0)
                groups.forEachIndexed { index, group ->
                    if (all.any { group.id in it.groupIds && it.albumId in pendingIds }) add(index + 1)
                }
            }
        }
    }
    val selectedGroupIndex = (groups.indexOfFirst { it.id == selectedGroupId } + 1).coerceAtLeast(0)
    val pagerState = rememberPagerState(initialPage = selectedGroupIndex) { groupTabs.size }

    // 用 currentPage 而不是 settledPage：越过半页就认定切换，分组内容与顶栏标签同时跟上，
    // 不必等惯性彻底停稳（settledPage 的延迟就是"切换割裂"的来源）。
    LaunchedEffect(pagerState.currentPage, groups) {
        val settledGroupId = if (pagerState.currentPage == 0) {
            ALL_BOOKSHELF_GROUP_ID
        } else {
            groups.getOrNull(pagerState.currentPage - 1)?.id ?: ALL_BOOKSHELF_GROUP_ID
        }
        if (settledGroupId != selectedGroupId) {
            selectionMode = false
            selectedIds = emptySet()
            selectedGroupId = settledGroupId
            // 记一次访问喂给"自动排列"。放在这里而不是点击 tab 时：滑动切换同样算看过一次，
            // 而"全部"不参与排序，仓库层会自行忽略。
            repository.recordGroupVisit(settledGroupId)
        }
    }

    /**
     * 从分组序列页点某个分组回来后，直接落到它那一页。
     *
     * 用 [pagerState.scrollToPage] 而不是 animate：跨十几个分组的动画既慢又会把中间每一页都组合一遍。
     */
    LaunchedEffect(pendingGroupId, groups) {
        val target = pendingGroupId ?: return@LaunchedEffect
        val targetPage = if (target == ALL_BOOKSHELF_GROUP_ID) {
            0
        } else {
            groups.indexOfFirst { it.id == target }.takeIf { it >= 0 }?.plus(1)
        }
        onPendingGroupConsumed()
        if (targetPage == null) return@LaunchedEffect
        selectionMode = false
        selectedIds = emptySet()
        if (pagerState.currentPage != targetPage) pagerState.scrollToPage(targetPage)
    }

    LaunchedEffect(selectedGroupId, groups) {
        // 有待处理的定位请求时让上面那个效果先落位：这里读到的 selectedGroupId 还是上次的分组，
        // 会把刚跳过去的分页又拽回来。落位后分页 settle 会更新 selectedGroupId，本效果自然重跑。
        if (pendingGroupId != null) return@LaunchedEffect
        val targetPage = (groups.indexOfFirst { it.id == selectedGroupId } + 1).coerceAtLeast(0)
        if (pagerState.currentPage != targetPage && !pagerState.isScrollInProgress) {
            pagerState.scrollToPage(targetPage)
        }
    }
    val pagerEnabled = !selectionMode && !menuExpanded && !showGroupEditor && !showGroupManager && !showManualPicker &&
        !showSelectionGroupPicker && !showExportDialog && pendingRemoval.isEmpty() && pendingGroupDeletion == null &&
        pendingImport == null && pendingImportReplace == null && !operationRunning && !transferRunning && operationMessage == null
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
                    text = "分组序列",
                    enabled = groups.isNotEmpty(),
                    summary = if (groups.isEmpty()) "还没有分组" else "调整 tab 顺序 · 快速定位",
                    onClick = onOpenGroupOrder,
                ),
            )
            add(
                DropdownItem(
                    text = "排序方式",
                    summary = "${sortOrder.label} · ${sortDirection.label}",
                    children = sortChildren,
                ),
            )
            selectedGroup?.let { group ->
                add(
                    DropdownItem(
                        text = "删除当前分组",
                        icon = { modifier ->
                            Icon(
                                imageVector = MiuixIcons.Delete,
                                contentDescription = null,
                                modifier = modifier,
                                tint = MiuixTheme.colorScheme.error,
                            )
                        },
                        onClick = { pendingGroupDeletion = group },
                    ),
                )
            }
        },
    )

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        topBar = {
            dev.jmx.client.effect.BlurredBar(
                backdrop = activeBarBackdrop,
                style = topBarBlurStyle,
            ) {
            androidx.compose.foundation.layout.Column {
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
                    titlePadding = if (multiSelect) 8.dp else TopAppBarDefaults.TitlePadding,
                    color = if (activeBarBackdrop != null) androidx.compose.ui.graphics.Color.Transparent else MiuixTheme.colorScheme.surface,
                    navigationIcon = {
                        if (!multiSelect) {
                            IconButton(
                                onClick = { importLauncher.launch(arrayOf("*/*")) },
                                enabled = !transferRunning,
                                minWidth = 42.dp,
                                minHeight = 42.dp,
                            ) {
                                Icon(
                                    imageVector = MiuixIcons.Import,
                                    contentDescription = "导入书架文件",
                                    tint = MiuixTheme.colorScheme.onBackground,
                                )
                            }
                        }
                    },
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
                            if (selectedGroupId == ALL_BOOKSHELF_GROUP_ID && groups.isNotEmpty()) {
                                IconButton(
                                    onClick = { showSelectionGroupPicker = true },
                                    enabled = selectedIds.isNotEmpty(),
                                    minWidth = 42.dp,
                                    minHeight = 42.dp,
                                ) {
                                    Icon(
                                        imageVector = MiuixIcons.AddFolder,
                                        contentDescription = "将已选漫画加入分组",
                                        tint = MiuixTheme.colorScheme.primary,
                                    )
                                }
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
                            IconButton(
                                onClick = { showExportDialog = true },
                                enabled = !transferRunning,
                                minWidth = 42.dp,
                                minHeight = 42.dp,
                            ) {
                                Icon(
                                    imageVector = MiuixIcons.Share,
                                    contentDescription = "导出书架",
                                    tint = MiuixTheme.colorScheme.onBackground,
                                )
                            }
                            WindowIconCascadingDropdownMenu(entry = menuEntry, onExpandedChange = { menuExpanded = it }) {
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
            if (!selectionMode) {
                dev.jmx.client.effect.BlurTabRow(
                    tabs = groupTabs,
                    selectedIndex = pagerState.currentPage,
                    onTabSelected = { index ->
                        if (pagerEnabled) coroutineScope.launch { pagerState.springAnimateToPage(index) }
                    },
                    // 指示器跟随分页实时进度，手动滑动分组时标签不再等惯性停稳才切换。
                    selectionProgress = {
                        pagerState.currentPage + pagerState.currentPageOffsetFraction
                    },
                    badgedTabs = badgedTabs,
                )
            }
            }
            }
        },
    ) { pagePadding ->
        val topInset = pagePadding.calculateTopPadding()
        Box(modifier = Modifier.fillMaxSize()) {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier
                        .fillMaxSize()
                        .pagerGestureOverride(pagerState, enabled = pagerEnabled)
                        .then(if (activeBarBackdrop != null) Modifier.layerBackdrop(activeBarBackdrop) else Modifier),
                    userScrollEnabled = false,
                    pageNestedScrollConnection = PagerGestureNestedScrollConnection,
                    key = { page -> groups.getOrNull(page - 1)?.id ?: ALL_BOOKSHELF_GROUP_ID },
                ) { page ->
                val pageGroupId = groups.getOrNull(page - 1)?.id ?: ALL_BOOKSHELF_GROUP_ID
                val pageEntries = remember(pageGroupId, sortOrder, sortDirection, revision, contentRevision) {
                    repository.entries(pageGroupId, sortOrder, sortDirection)
                }
                val pullToRefreshState = rememberPullToRefreshState()
                PullToRefresh(
                    // 只让当前页显示刷新动效：分页器会同时组合相邻页，否则左右页也会跟着转圈。
                    isRefreshing = refreshing && pagerState.currentPage == page,
                    onRefresh = ::refreshBookshelf,
                    pullToRefreshState = pullToRefreshState,
                    refreshTexts = listOf("下拉刷新", "松开刷新", "正在刷新", "刷新完成"),
                    modifier = Modifier.fillMaxSize(),
                ) {
                if (pageEntries.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize().padding(top = topInset)) {
                        BookshelfEmptyState(customGroup = page > 0)
                    }
                } else {
                    val gridState = rememberLazyGridState()
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        state = gridState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 12.dp, top = topInset + 12.dp, end = 12.dp, bottom = 28.dp + innerPadding.calculateBottomPadding()),
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
                                        // 多选态下不画更新角标：那时封面上已经压了一层选中遮罩。
                                        updateChapters = if (selectionMode) {
                                            0
                                        } else {
                                            updateRecords[entry.albumId]?.pendingChapters ?: 0
                                        },
                                        selected = (entry.albumId in selectedIds).takeIf { selectionMode },
                                        selectionOverlay = {
                                            if (selectionMode) AlbumSelectionOverlay(
                                                selected = entry.albumId in selectedIds,
                                                modifier = Modifier.matchParentSize(),
                                            )
                                        },
                                    )
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
    }

    BookshelfGroupEditorDialog(
        show = showGroupEditor,
        group = null,
        running = operationRunning,
        onDismiss = { if (!operationRunning) showGroupEditor = false },
        onSubmit = { config ->
            val group = repository.createGroup(
                name = config.name,
                matchFavoritesByTags = config.matchFavoritesByTags,
                tagRules = config.tagRules,
                matchByAuthors = config.matchByAuthors,
                authorRules = config.authorRules,
                authorMatchSource = config.authorMatchSource,
            )
            if (group == null) {
                operationMessage = "分组名称不能为空，且不能与已有分组重复。"
            } else {
                showGroupEditor = false
                reload()
                runAutoCollect(group)
            }
        },
    )

    BookshelfGroupManagerDialog(
        show = showGroupManager,
        selectedGroup = editingGroup,
        running = operationRunning,
        onDismiss = { if (!operationRunning) showGroupManager = false },
        onUpdate = { group, config -> applyGroupConfiguration(group, config) },
        onSave = { group, config -> applyGroupConfiguration(group, config) },
        onManualSelect = { group ->
            manualGroup = group
            showGroupManager = false
            manualSource = ManualBookshelfSource.BOOKSHELF
            selectedManualIds = emptySet()
            manualQuery = ""
            manualSearchExpanded = false
            showManualPicker = true
        },
    )

    val manualAlbums = when (manualSource) {
        ManualBookshelfSource.BOOKSHELF -> remember(contentRevision, revision, repository) {
            repository.entries(ALL_BOOKSHELF_GROUP_ID).map(BookshelfEntry::toHomeAlbum)
        }
        ManualBookshelfSource.FAVORITES -> manualFavorites
    }
    ManualBookshelfPickerDialog(
        show = showManualPicker,
        group = manualGroup,
        source = manualSource,
        albums = manualAlbums,
        selectedIds = selectedManualIds,
        query = manualQuery,
        searchExpanded = manualSearchExpanded,
        loading = manualLoading,
        error = manualError,
        onSourceChange = { source ->
            manualSource = source
            selectedManualIds = emptySet()
            manualQuery = ""
            manualError = null
        },
        onQueryChange = { manualQuery = it },
        onSearchExpandedChange = { manualSearchExpanded = it },
        onToggle = { id ->
            selectedManualIds = if (id in selectedManualIds) selectedManualIds - id else selectedManualIds + id
        },
        onToggleAll = { visibleIds ->
            selectedManualIds = if (selectedManualIds.containsAll(visibleIds)) {
                selectedManualIds - visibleIds
            } else {
                selectedManualIds + visibleIds
            }
        },
        onDismiss = { showManualPicker = false },
        onConfirm = {
            manualGroup?.let { group ->
                val albums = manualAlbums.filter { it.id in selectedManualIds }
                repository.addAllToGroup(albums, group.id)
            }
            showManualPicker = false
            reload()
        },
    )

    BookshelfBatchGroupPickerDialog(
        show = showSelectionGroupPicker,
        groups = groups,
        selectedCount = selectedIds.size,
        onDismiss = { showSelectionGroupPicker = false },
        onConfirm = { groupIds ->
            val changed = repository.addEntriesToGroups(selectedIds, groupIds)
            showSelectionGroupPicker = false
            selectionMode = false
            selectedIds = emptySet()
            reload()
            hapticFeedback.performHapticFeedback(HapticFeedbackType.Confirm)
            operationMessage = if (changed > 0) {
                "已将 $changed 部漫画加入所选分组。"
            } else {
                "所选漫画已经在这些分组中。"
            }
        },
    )

    BookshelfExportDialog(
        show = showExportDialog,
        groups = groups,
        running = transferRunning,
        entryCounts = { bookshelfGroupCounts(repository, groups) },
        onDismiss = { if (!transferRunning) showExportDialog = false },
        onExport = { selection ->
            transferRunning = true
            coroutineScope.launch {
                val snapshot = withContext(Dispatchers.IO) {
                    repository.snapshot(
                        groupIds = selection.groupIds,
                        includeGroups = selection.includeGroups,
                    )
                }
                val result = exportBookshelfSnapshot(context, snapshot, System.currentTimeMillis())
                transferRunning = false
                showExportDialog = false
                operationMessage = when (result) {
                    is BookshelfExportResult.Success ->
                        "已导出 ${result.entryCount} 部漫画" +
                            (if (result.groupCount > 0) "、${result.groupCount} 个分组" else "") +
                            "到“${result.location}”目录：\n${result.fileName}"
                    is BookshelfExportResult.Failure -> result.message
                }
            }
        },
    )

    WindowDialog(
        show = pendingImport != null,
        title = "导入书架",
        summary = pendingImport?.let {
            "文件包含 ${it.entries.size} 部漫画、${it.groups.size} 个分组，选择导入方式。"
        },
        onDismissRequest = { if (!transferRunning) pendingImport = null },
    ) {
        Text(
            text = "合并只做加法：同名分组会自动归并，已在书架里的漫画只补分组归属，本机阅读进度不会被覆盖。",
            style = MiuixTheme.textStyles.footnote2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.padding(bottom = 12.dp),
        )
        TextButton(
            text = "合并到当前书架",
            enabled = !transferRunning,
            onClick = { pendingImport?.let { applyImport(it, replace = false) } },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.textButtonColorsPrimary(),
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TextButton(
                text = "取消",
                enabled = !transferRunning,
                onClick = { pendingImport = null },
                modifier = Modifier.weight(1f),
            )
            TextButton(
                text = "替换当前书架",
                enabled = !transferRunning,
                onClick = {
                    pendingImportReplace = pendingImport
                    pendingImport = null
                },
                modifier = Modifier.weight(1f),
            )
        }
    }

    // 替换是不可撤销的清空操作，单独再确认一次，并把会被清掉的数量说清楚。
    WindowDialog(
        show = pendingImportReplace != null,
        title = "替换当前书架",
        summary = pendingImportReplace?.let { snapshot ->
            val localCount = repository.entries(ALL_BOOKSHELF_GROUP_ID).size
            "当前书架的 $localCount 部漫画和 ${groups.size} 个分组会被清空，" +
                "换成文件里的 ${snapshot.entries.size} 部漫画和 ${snapshot.groups.size} 个分组。此操作无法撤销。"
        },
        onDismissRequest = { pendingImportReplace = null },
    ) {
        TextButton(
            text = "确认替换",
            onClick = { pendingImportReplace?.let { applyImport(it, replace = true) } },
            modifier = Modifier.fillMaxWidth(),
        )
    }

    LaunchedEffect(showManualPicker, manualSource, authenticated) {
        if (!showManualPicker) return@LaunchedEffect
        if (manualSource == ManualBookshelfSource.BOOKSHELF) {
            manualLoading = false
            manualError = null
            return@LaunchedEffect
        }
        if (!authenticated) {
            onRequireLogin()
            manualSource = ManualBookshelfSource.BOOKSHELF
            operationMessage = "登录后才能读取“我的收藏”，已切换回默认书架。"
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
                hapticFeedback.performHapticFeedback(HapticFeedbackType.Confirm)
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
            colors = ButtonDefaults.textButtonColors(
                color = MiuixTheme.colorScheme.errorContainer,
                disabledColor = MiuixTheme.colorScheme.errorContainer.copy(alpha = 0.38f),
                textColor = MiuixTheme.colorScheme.onErrorContainer,
                disabledTextColor = MiuixTheme.colorScheme.onErrorContainer.copy(alpha = 0.38f),
            ),
        )
    }

    WindowDialog(
        show = operationRunning,
        title = "正在更新书架",
        summary = "正在检索作品并匹配分组规则，请稍候。",
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
    onSubmit: (BookshelfGroupConfiguration) -> Unit,
) {
    var name by remember(show, group) { mutableStateOf(group?.name.orEmpty()) }
    var tagRules by remember(show, group) { mutableStateOf(group?.tagRules?.joinToString(" ").orEmpty()) }
    var matchTags by remember(show, group) { mutableStateOf(group?.matchFavoritesByTags ?: false) }
    var authorRules by remember(show, group) { mutableStateOf(group?.authorRules?.joinToString(" ").orEmpty()) }
    var matchAuthors by remember(show, group) { mutableStateOf(group?.matchByAuthors ?: false) }
    var authorSource by remember(show, group) {
        mutableStateOf(group?.authorMatchSource ?: BookshelfAuthorMatchSource.FAVORITES)
    }
    WindowDialog(
        show = show,
        title = "添加书架分组",
        summary = "可按收藏标签或作者自动纳入；同类规则中的多个词需要同时命中。",
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
            Switch(checked = matchTags, onCheckedChange = { matchTags = it }, enabled = !running)
        }
        if (matchTags) {
            Spacer(modifier = Modifier.height(10.dp))
            TextField(
                value = tagRules,
                onValueChange = { tagRules = it },
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
        Spacer(modifier = Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("按作者自动收录", style = MiuixTheme.textStyles.body2)
                Text(
                    "多个作者需同时命中，可从收藏或所有作品中收录",
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            Switch(checked = matchAuthors, onCheckedChange = { matchAuthors = it }, enabled = !running)
        }
        if (matchAuthors) {
            Spacer(modifier = Modifier.height(10.dp))
            TextField(
                value = authorRules,
                onValueChange = { authorRules = it },
                label = "作者匹配规则",
                enabled = !running,
                maxLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = "例如：作者甲 作者乙",
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(start = 12.dp, top = 5.dp),
            )
            WindowDropdownPreference(
                title = "作者检索范围",
                summary = authorSource.label,
                items = BookshelfAuthorMatchSource.entries.map(BookshelfAuthorMatchSource::label),
                selectedIndex = BookshelfAuthorMatchSource.entries.indexOf(authorSource),
                enabled = !running,
                onSelectedIndexChange = { index ->
                    BookshelfAuthorMatchSource.entries.getOrNull(index)?.let { authorSource = it }
                },
            )
        }
        Spacer(modifier = Modifier.height(14.dp))
        TextButton(
            text = if (running) "正在收录" else "确定",
            enabled = name.isNotBlank() &&
                (!matchTags || parseBookshelfTagRules(tagRules).isNotEmpty()) &&
                (!matchAuthors || parseBookshelfAuthorRules(authorRules).isNotEmpty()) &&
                !running,
            onClick = {
                onSubmit(
                    BookshelfGroupConfiguration(
                        name = name,
                        matchFavoritesByTags = matchTags,
                        tagRules = parseBookshelfTagRules(tagRules),
                        matchByAuthors = matchAuthors,
                        authorRules = parseBookshelfAuthorRules(authorRules),
                        authorMatchSource = authorSource,
                    ),
                )
            },
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
    onUpdate: (BookshelfGroup, BookshelfGroupConfiguration) -> Unit,
    onSave: (BookshelfGroup, BookshelfGroupConfiguration) -> Unit,
    onManualSelect: (BookshelfGroup) -> Unit,
) {
    var name by remember(show, selectedGroup) { mutableStateOf(selectedGroup?.name.orEmpty()) }
    var tagRules by remember(show, selectedGroup) {
        mutableStateOf(selectedGroup?.tagRules?.joinToString(" ").orEmpty())
    }
    var matchTags by remember(show, selectedGroup) {
        mutableStateOf(selectedGroup?.matchFavoritesByTags ?: false)
    }
    var authorRules by remember(show, selectedGroup) {
        mutableStateOf(selectedGroup?.authorRules?.joinToString(" ").orEmpty())
    }
    var matchAuthors by remember(show, selectedGroup) {
        mutableStateOf(selectedGroup?.matchByAuthors ?: false)
    }
    var authorSource by remember(show, selectedGroup) {
        mutableStateOf(selectedGroup?.authorMatchSource ?: BookshelfAuthorMatchSource.FAVORITES)
    }
    fun configuration() = BookshelfGroupConfiguration(
        name = name,
        matchFavoritesByTags = matchTags,
        tagRules = parseBookshelfTagRules(tagRules),
        matchByAuthors = matchAuthors,
        authorRules = parseBookshelfAuthorRules(authorRules),
        authorMatchSource = authorSource,
    )
    // 规则不完整就不让提交：开了开关却没填词，收录会退化成"全部命中"或直接空跑。
    val canApplyConfiguration = !running &&
        name.isNotBlank() &&
        (!matchTags || parseBookshelfTagRules(tagRules).isNotEmpty()) &&
        (!matchAuthors || parseBookshelfAuthorRules(authorRules).isNotEmpty())
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
                    onClick = { onUpdate(selectedGroup, configuration()) },
                    enabled = canApplyConfiguration,
                    minWidth = 42.dp,
                    minHeight = 42.dp,
                ) {
                    Icon(
                        imageVector = MiuixIcons.Refresh,
                        contentDescription = "按标签立即更新分组",
                        tint = MiuixTheme.colorScheme.primary,
                    )
                }
                Switch(checked = matchTags, onCheckedChange = { matchTags = it }, enabled = !running)
            }
            if (matchTags) {
                Spacer(modifier = Modifier.height(10.dp))
                TextField(
                    value = tagRules,
                    onValueChange = { tagRules = it },
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
            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("按作者自动收录", style = MiuixTheme.textStyles.body2)
                    Text(
                        "多个作者需同时命中；点击右侧刷新按钮重新收录",
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
                // 与标签规则同一个入口：改完作者或换了检索范围后能就地重新发起一次收录，
                // 不必退出去再进来。
                IconButton(
                    onClick = { onUpdate(selectedGroup, configuration()) },
                    enabled = canApplyConfiguration,
                    minWidth = 42.dp,
                    minHeight = 42.dp,
                ) {
                    Icon(
                        imageVector = MiuixIcons.Refresh,
                        contentDescription = "按作者立即更新分组",
                        tint = MiuixTheme.colorScheme.primary,
                    )
                }
                Switch(checked = matchAuthors, onCheckedChange = { matchAuthors = it }, enabled = !running)
            }
            if (matchAuthors) {
                Spacer(modifier = Modifier.height(10.dp))
                TextField(
                    value = authorRules,
                    onValueChange = { authorRules = it },
                    label = "作者匹配规则",
                    enabled = !running,
                    maxLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = "例如：作者甲 作者乙",
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(start = 12.dp, top = 5.dp),
                )
                WindowDropdownPreference(
                    title = "作者检索范围",
                    summary = authorSource.label,
                    items = BookshelfAuthorMatchSource.entries.map(BookshelfAuthorMatchSource::label),
                    selectedIndex = BookshelfAuthorMatchSource.entries.indexOf(authorSource),
                    enabled = !running,
                    onSelectedIndexChange = { index ->
                        BookshelfAuthorMatchSource.entries.getOrNull(index)?.let { authorSource = it }
                    },
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            TextButton(
                text = "添加当前分组漫画",
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
                    enabled = canApplyConfiguration,
                    onClick = { onSave(selectedGroup, configuration()) },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            }
        }
    }
}

private data class BookshelfGroupConfiguration(
    val name: String,
    val matchFavoritesByTags: Boolean,
    val tagRules: List<String>,
    val matchByAuthors: Boolean,
    val authorRules: List<String>,
    val authorMatchSource: BookshelfAuthorMatchSource,
)

@Composable
private fun ManualBookshelfPickerDialog(
    show: Boolean,
    group: BookshelfGroup?,
    source: ManualBookshelfSource,
    albums: List<HomeAlbum>,
    selectedIds: Set<String>,
    query: String,
    searchExpanded: Boolean,
    loading: Boolean,
    error: String?,
    onSourceChange: (ManualBookshelfSource) -> Unit,
    onQueryChange: (String) -> Unit,
    onSearchExpandedChange: (Boolean) -> Unit,
    onToggle: (String) -> Unit,
    onToggleAll: (Set<String>) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val filtered = albums.filter { album -> album.matchesBookshelfPickerQuery(query) }
    val visibleIds = filtered.mapTo(linkedSetOf(), HomeAlbum::id)
    val allVisibleSelected = visibleIds.isNotEmpty() && selectedIds.containsAll(visibleIds)
    val sourceEntry = DropdownEntry(
        items = ManualBookshelfSource.entries.map { option ->
            DropdownItem(
                text = option.label,
                selected = option == source,
                onClick = { onSourceChange(option) },
            )
        },
    )
    WindowDialog(
        show = show,
        title = "手动加入 ${group?.name.orEmpty()}",
        summary = "已选择 ${selectedIds.size} 部，确认后追加到当前分组。",
        onDismissRequest = onDismiss,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            WindowIconDropdownMenu(
                entry = sourceEntry,
                minWidth = 116.dp,
                minHeight = 42.dp,
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = source.label,
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.primary,
                    )
                    Icon(
                        imageVector = MiuixIcons.ExpandMore,
                        contentDescription = "切换内容来源",
                        modifier = Modifier.size(18.dp),
                        tint = MiuixTheme.colorScheme.primary,
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = { onSearchExpandedChange(!searchExpanded) },
                    minWidth = 42.dp,
                    minHeight = 42.dp,
                ) {
                    Icon(
                        imageVector = MiuixIcons.Basic.Search,
                        contentDescription = "搜索当前来源",
                        tint = MiuixTheme.colorScheme.primary,
                    )
                }
                IconButton(
                    onClick = { onToggleAll(visibleIds) },
                    enabled = visibleIds.isNotEmpty() && !loading,
                    minWidth = 42.dp,
                    minHeight = 42.dp,
                ) {
                    Icon(
                        imageVector = MiuixIcons.SelectAll,
                        contentDescription = if (allVisibleSelected) "取消全选" else "全选当前结果",
                        tint = MiuixTheme.colorScheme.primary,
                    )
                }
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
                    text = if (source == ManualBookshelfSource.FAVORITES) {
                        "没有匹配的收藏漫画"
                    } else {
                        "默认书架中没有匹配的漫画"
                    },
                    modifier = Modifier.align(Alignment.Center),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(filtered, key = HomeAlbum::id) { album ->
                        val selected = album.id in selectedIds
                        val itemColor by animateColorAsState(
                            targetValue = if (selected) {
                                MiuixTheme.colorScheme.primaryContainer
                            } else {
                                MiuixTheme.colorScheme.surfaceContainerHigh
                            },
                            animationSpec = tween(180),
                            label = "ManualBookshelfItemColor",
                        )
                        Surface(
                            onClick = { onToggle(album.id) },
                            shape = RoundedCornerShape(8.dp),
                            color = itemColor,
                            modifier = Modifier.padding(vertical = 3.dp).semantics(mergeDescendants = true) {
                                role = Role.Checkbox
                                toggleableState = ToggleableState(selected)
                            },
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
                                Checkbox(state = ToggleableState(selected), onClick = null, modifier = Modifier.clearAndSetSemantics {})
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
private fun BookshelfBatchGroupPickerDialog(
    show: Boolean,
    groups: List<BookshelfGroup>,
    selectedCount: Int,
    onDismiss: () -> Unit,
    onConfirm: (Set<String>) -> Unit,
) {
    var selectedGroupIds by remember(show, groups) { mutableStateOf(emptySet<String>()) }
    WindowDialog(
        show = show,
        title = "加入分组",
        summary = "将已选择的 $selectedCount 部漫画加入一个或多个分组。",
        onDismissRequest = onDismiss,
    ) {
        BookshelfGroupChecklist(
            groups = groups,
            selectedGroupIds = selectedGroupIds,
            onToggle = { groupId ->
                selectedGroupIds = if (groupId in selectedGroupIds) {
                    selectedGroupIds - groupId
                } else {
                    selectedGroupIds + groupId
                }
            },
        )
        Spacer(modifier = Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TextButton(
                text = "取消",
                onClick = onDismiss,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                text = "确定",
                enabled = selectedGroupIds.isNotEmpty(),
                onClick = { onConfirm(selectedGroupIds) },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
        }
    }
}

/** 导出范围。声明顺序即弹窗里的标签顺序。 */
private enum class BookshelfExportScope(val label: String) {
    ALL("全部"),
    SINGLE_GROUP("单个分组"),
    MULTI_GROUP("多选分组"),
}

/** [groupIds] 为 null 表示导出全部；只有全部才有"要不要带分组"的选择。 */
private data class BookshelfExportSelection(
    val groupIds: Set<String>?,
    val includeGroups: Boolean,
)

@Composable
private fun BookshelfExportDialog(
    show: Boolean,
    groups: List<BookshelfGroup>,
    running: Boolean,
    entryCounts: () -> Map<String, Int>,
    onDismiss: () -> Unit,
    onExport: (BookshelfExportSelection) -> Unit,
) {
    // 没有分组时"按分组导出"无从选择，只留"全部"，免得给出一个点进去空空如也的入口。
    val scopes = remember(groups.isEmpty()) {
        if (groups.isEmpty()) listOf(BookshelfExportScope.ALL) else BookshelfExportScope.entries
    }
    var scope by remember(show, scopes) { mutableStateOf(BookshelfExportScope.ALL) }
    var includeGroups by remember(show) { mutableStateOf(true) }
    var selectedGroupIds by remember(show, groups) { mutableStateOf(emptySet<String>()) }
    // 数量要读一次存储，只在弹窗开合时算，不跟着每次勾选重算。
    val counts = remember(show, groups) { entryCounts() }
    val totalCount = counts[ALL_BOOKSHELF_GROUP_ID] ?: 0
    val selectedCount = when (scope) {
        BookshelfExportScope.ALL -> totalCount
        else -> selectedGroupIds.sumOf { counts[it] ?: 0 }
    }
    WindowDialog(
        show = show,
        title = "导出书架",
        summary = "导出的文件会保存到手机“下载”目录，可以直接分享给别人导入。",
        onDismissRequest = onDismiss,
    ) {
        if (scopes.size > 1) {
            TabRowWithContour(
                tabs = scopes.map(BookshelfExportScope::label),
                selectedTabIndex = scopes.indexOf(scope).coerceAtLeast(0),
                onTabSelected = { index ->
                    scopes.getOrNull(index)?.let {
                        scope = it
                        selectedGroupIds = emptySet()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(12.dp))
        }
        when (scope) {
            BookshelfExportScope.ALL -> {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("包含书架分组", style = MiuixTheme.textStyles.body2)
                        Text(
                            text = if (groups.isEmpty()) {
                                "当前没有分组可以导出"
                            } else {
                                "一并导出 ${groups.size} 个分组及其规则、归属关系"
                            },
                            style = MiuixTheme.textStyles.footnote2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                    Checkbox(
                        state = if (includeGroups && groups.isNotEmpty()) {
                            ToggleableState.On
                        } else {
                            ToggleableState.Off
                        },
                        onClick = { includeGroups = !includeGroups },
                        enabled = !running && groups.isNotEmpty(),
                    )
                }
            }
            BookshelfExportScope.SINGLE_GROUP -> BookshelfGroupChecklist(
                groups = groups,
                selectedGroupIds = selectedGroupIds,
                counts = counts,
                // 单选：点哪个就只留哪个，再点一次取消。
                onToggle = { groupId ->
                    selectedGroupIds = if (groupId in selectedGroupIds) emptySet() else setOf(groupId)
                },
            )
            BookshelfExportScope.MULTI_GROUP -> BookshelfGroupChecklist(
                groups = groups,
                selectedGroupIds = selectedGroupIds,
                counts = counts,
                onToggle = { groupId ->
                    selectedGroupIds = if (groupId in selectedGroupIds) {
                        selectedGroupIds - groupId
                    } else {
                        selectedGroupIds + groupId
                    }
                },
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = if (scope == BookshelfExportScope.ALL) {
                "本次导出 $selectedCount 部漫画" +
                    (if (includeGroups && groups.isNotEmpty()) "、${groups.size} 个分组" else "")
            } else {
                "本次导出 ${selectedGroupIds.size} 个分组、$selectedCount 部漫画"
            },
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(12.dp))
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
                text = if (running) "正在导出" else "导出",
                enabled = !running && selectedCount > 0 &&
                    (scope == BookshelfExportScope.ALL || selectedGroupIds.isNotEmpty()),
                onClick = {
                    onExport(
                        BookshelfExportSelection(
                            groupIds = if (scope == BookshelfExportScope.ALL) null else selectedGroupIds,
                            includeGroups = includeGroups,
                        ),
                    )
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
        }
    }
}

/**
 * 分组多选清单。高度封顶后内部滚动：不封顶弹窗会顶穿屏幕，把下面的按钮挤出可见区域。
 * 分组数量没有上限（issue #10），所以用 LazyColumn 而不是 Column——
 * 上百个分组时不会一次性组合出上百行只为让用户看见其中三行。
 */
@Composable
private fun BookshelfGroupChecklist(
    groups: List<BookshelfGroup>,
    selectedGroupIds: Set<String>,
    onToggle: (String) -> Unit,
    counts: Map<String, Int>? = null,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 260.dp),
    ) {
        items(items = groups, key = BookshelfGroup::id) { group ->
            val selected = group.id in selectedGroupIds
            val itemColor by animateColorAsState(
                targetValue = if (selected) {
                    MiuixTheme.colorScheme.primaryContainer
                } else {
                    MiuixTheme.colorScheme.surfaceContainerHigh
                },
                animationSpec = tween(180),
                label = "BookshelfGroupSelectionColor",
            )
            Surface(
                onClick = { onToggle(group.id) },
                modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp).semantics(mergeDescendants = true) {
                    role = Role.Checkbox
                    toggleableState = ToggleableState(selected)
                },
                shape = RoundedCornerShape(8.dp),
                color = itemColor,
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = group.name,
                            style = MiuixTheme.textStyles.body2,
                            color = if (selected) {
                                MiuixTheme.colorScheme.onPrimaryContainer
                            } else {
                                MiuixTheme.colorScheme.onSurface
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        counts?.let {
                            Text(
                                text = "${it[group.id] ?: 0} 部漫画",
                                style = MiuixTheme.textStyles.footnote2,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                    }
                    Checkbox(state = ToggleableState(selected), onClick = null, modifier = Modifier.clearAndSetSemantics {})
                }
            }
        }
    }
}

/** 一次读盘算出"全部"和每个分组的漫画数，避免在弹窗里按分组逐个读。 */
internal fun bookshelfGroupCounts(
    repository: BookshelfRepository,
    groups: List<BookshelfGroup>,
): Map<String, Int> {
    val all = repository.entries(ALL_BOOKSHELF_GROUP_ID)
    return buildMap {
        put(ALL_BOOKSHELF_GROUP_ID, all.size)
        groups.forEach { group -> put(group.id, all.count { group.id in it.groupIds }) }
    }
}

private fun buildBookshelfImportMessage(outcome: BookshelfImportOutcome, replace: Boolean): String {
    val head = if (replace) {
        "已替换为导入的书架：${outcome.addedEntries} 部漫画、${outcome.addedGroups} 个分组。"
    } else {
        buildString {
            append("已合并导入：新增 ${outcome.addedEntries} 部漫画")
            if (outcome.mergedEntries > 0) append("，${outcome.mergedEntries} 部补充了分组归属")
            if (outcome.addedGroups > 0) append("，新增 ${outcome.addedGroups} 个分组")
            if (outcome.reusedGroups > 0) append("，${outcome.reusedGroups} 个同名分组已归并")
            append("。")
        }
    }
    return if (outcome.droppedEntries > 0) {
        head + "书架已达上限，有 ${outcome.droppedEntries} 部漫画未能导入。"
    } else {
        head
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

private suspend fun syncBookshelfGroup(
    group: BookshelfGroup,
    homeRepository: HomeRepository,
    accountDataRepository: AccountDataRepository,
    detailRepository: AlbumDetailRepository,
    bookshelfRepository: BookshelfRepository,
): BookshelfGroupSyncOutcome = withContext(Dispatchers.IO) {
    val candidates = when {
        group.matchByAuthors && group.authorMatchSource == BookshelfAuthorMatchSource.ALL_WORKS -> {
            when (val result = loadAllWorksByAuthors(homeRepository, group.authorRules)) {
                is JmxResult.Success -> result.value
                is JmxResult.Failure -> error(result.error.toUiMessage())
            }
        }
        else -> {
            when (val result = loadAllFavoriteAlbums(accountDataRepository)) {
                is JmxResult.Success -> result.value
                is JmxResult.Failure -> error(result.error.toUiMessage())
            }
        }
    }.distinctBy(HomeAlbum::id)

    val requiresDetail = group.matchFavoritesByTags ||
        (group.matchByAuthors && group.authorMatchSource == BookshelfAuthorMatchSource.ALL_WORKS)
    val prefiltered = if (group.matchByAuthors && !requiresDetail) {
        candidates.filter { matchesBookshelfAuthorRules(it.author, group.authorRules) }
    } else {
        candidates
    }
    val matched = if (!requiresDetail) {
        prefiltered
    } else {
        val semaphore = Semaphore(GROUP_DETAIL_CONCURRENCY)
        coroutineScope {
            prefiltered.map { album ->
                async {
                    semaphore.withPermit {
                        when (val result = detailRepository.load(album.id)) {
                            is AlbumDetailUiState.Content -> {
                                val detail = result.detail
                                val tagsMatched = !group.matchFavoritesByTags ||
                                    matchesBookshelfTagRules(detail.tags, group.tagRules)
                                val authorsMatched = !group.matchByAuthors ||
                                    matchesBookshelfAuthorRules(detail.authors.joinToString(" / "), group.authorRules)
                                detail.toHomeAlbum(homeRepository.currentImageHost).takeIf {
                                    tagsMatched && authorsMatched
                                }
                            }
                            else -> null
                        }
                    }
                }
            }.awaitAll().filterNotNull()
        }
    }
    BookshelfGroupSyncOutcome(
        candidates = candidates.size,
        matched = matched.size,
        changed = bookshelfRepository.addAllToGroup(matched, group.id),
    )
}

private suspend fun loadAllWorksByAuthors(
    homeRepository: HomeRepository,
    authorRules: List<String>,
): JmxResult<List<HomeAlbum>> = coroutineScope {
    val rules = authorRules.mapNotNull(::normalizeSearchTag).distinct()
    if (rules.isEmpty()) return@coroutineScope JmxResult.Success(emptyList())
    val semaphore = Semaphore(AUTHOR_SEARCH_CONCURRENCY)
    val resultByRule = rules.map { rule ->
        async {
            semaphore.withPermit { searchAllWorksForAuthor(homeRepository, rule) }
        }
    }.awaitAll()
    val failure = resultByRule.firstOrNull { it is JmxResult.Failure } as? JmxResult.Failure
    if (failure != null) return@coroutineScope failure

    val albumsByRule = resultByRule.map { (it as JmxResult.Success).value }
    val commonIds = albumsByRule
        .map { albums -> albums.mapTo(mutableSetOf(), HomeAlbum::id) }
        .reduce { current, ids -> current.apply { retainAll(ids) } }
    val merged = albumsByRule.flatten().distinctBy(HomeAlbum::id)
    // 搜索摘要有时不会返回完整作者字段，不能在这里提前排除；后续会用详情数据复核全部作者规则。
    JmxResult.Success(merged.filter { it.id in commonIds })
}

private suspend fun searchAllWorksForAuthor(
    homeRepository: HomeRepository,
    author: String,
): JmxResult<List<HomeAlbum>> = coroutineScope {
    val found = LinkedHashMap<String, HomeAlbum>()
    val variants = searchQueryVariants(author, ::toTraditionalChinese)
    var page = 1
    while (page <= MAX_AUTHOR_SEARCH_PAGES) {
        val requests = variants.flatMap { query -> AUTHOR_SEARCH_MAIN_TAGS.map { tag -> query to tag } }
        val responses = requests.map { (query, mainTag) ->
            async {
                homeRepository.core.albumApi.search(
                    query = query,
                    page = page,
                    order = JmxMagicConstants.ORDER_BY_LATEST,
                    mainTag = mainTag,
                )
            }
        }.awaitAll()
        val successful = responses.mapNotNull { (it as? JmxResult.Success)?.value }
        if (successful.isEmpty()) {
            val failure = responses.firstOrNull { it is JmxResult.Failure } as? JmxResult.Failure
            return@coroutineScope failure ?: JmxResult.Success(found.values.toList())
        }
        successful.flatMap { it.content }.forEach { summary ->
            if (summary.id.isNotBlank()) {
                found.putIfAbsent(summary.id, summary.toHomeAlbum(homeRepository.currentImageHost))
            }
        }
        val hasMore = successful.any { result ->
            val total = result.total
            result.content.isNotEmpty() && (total == null || found.size < total)
        }
        if (!hasMore) break
        page++
    }
    JmxResult.Success(found.values.toList())
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
private const val MAX_AUTHOR_SEARCH_PAGES = 30
private const val AUTHOR_SEARCH_CONCURRENCY = 2
private const val GROUP_DETAIL_CONCURRENCY = 6
/**
 * 按作者检索"所有作品"时用的 main_tag 取值。
 *
 * 2 是禁漫的作者维度搜索（官方爬虫 `search_author`，见 jm_client_interface.py），
 * 之前这里写的是 3——那是标签维度（`search_tag`），拿作者名去搜标签自然搜不到作品，
 * "按作者自动收录 + 所有作品"因此几乎总是空手而归。
 * 仍保留 0（全站搜索）作为兜底：作者名有别名或含分隔符时，作者维度会漏，全站能捞回来，
 * 多余的结果后面还要过一遍详情里的作者规则，不会误收。
 */
private val AUTHOR_SEARCH_MAIN_TAGS = listOf(0, 2)

private data class BookshelfGroupSyncOutcome(
    val candidates: Int,
    val matched: Int,
    val changed: Int,
)

private enum class ManualBookshelfSource(val label: String) {
    BOOKSHELF("默认书架"),
    FAVORITES("我的收藏"),
}

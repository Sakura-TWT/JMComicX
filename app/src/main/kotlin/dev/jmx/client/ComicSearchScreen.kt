package dev.jmx.client

import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.github.houbb.opencc4j.util.ZhConverterUtil
import dev.jmx.client.core.api.AlbumDetail
import dev.jmx.client.core.api.SearchQueryComposer
import dev.jmx.client.core.protocol.JmxMagicConstants
import dev.jmx.client.core.runtime.publicRead
import dev.jmx.client.core.result.JmxResult
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Badge
import top.yukonga.miuix.kmp.basic.BadgedBox
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.InputField
import top.yukonga.miuix.kmp.basic.SearchBar
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.Check
import top.yukonga.miuix.kmp.icon.basic.Search
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.Filter
import top.yukonga.miuix.kmp.icon.extended.Sort
import top.yukonga.miuix.kmp.menu.WindowIconDropdownMenu
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

private val SEARCH_FILTER_SAVER = listSaver<SearchTagFilter, String>(
    save = { listOf(it.includeTags.size.toString()) + it.includeTags + it.excludeTags },
    restore = { saved ->
        saved.firstOrNull()?.toIntOrNull()?.takeIf { it in 0 until saved.size }?.let { count ->
            SearchTagFilter(saved.drop(1).take(count), saved.drop(count + 1))
        } ?: SearchTagFilter()
    },
)

@Composable
internal fun ComicSearchScreen(
    homeRepository: HomeRepository,
    initialQuery: String? = null,
    manageSystemBar: Boolean = true,
    liftedAlbumId: String?,
    onDismiss: () -> Unit,
    onAlbumSelected: (HomeAlbum, Rect?) -> Unit,
    transitionProgress: () -> Float = { 1f },
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val repository = remember(homeRepository) { ComicSearchRepository(homeRepository) }
    val historyStore = remember(context) { SearchHistoryStore(context) }
    val statusBarPadding = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    var query by rememberSaveable { mutableStateOf("") }
    var submittedQuery by rememberSaveable { mutableStateOf<String?>(null) }
    val requestEpoch = remember(repository) { SearchRequestEpoch() }
    val searchGridState = rememberLazyGridState()
    DisposableEffect(requestEpoch) { onDispose { requestEpoch.advance() } }
    val submission = remember(requestEpoch) { SearchSubmissionState(requestEpoch) }
    var history by remember(historyStore) { mutableStateOf(historyStore.load()) }
    var deletingHistory by rememberSaveable { mutableStateOf(false) }
    var confirmClearHistory by rememberSaveable { mutableStateOf(false) }
    var initialQueryConsumed by rememberSaveable(initialQuery) { mutableStateOf(false) }
    var inputEnabled by rememberSaveable(initialQuery) { mutableStateOf(initialQuery.isNullOrBlank()) }
    var inputMode by rememberSaveable(initialQuery) { mutableStateOf(initialQuery.isNullOrBlank()) }
    var searchOrder by rememberSaveable { mutableStateOf(ComicSearchOrder.LATEST) }
    var tagFilter by rememberSaveable(stateSaver = SEARCH_FILTER_SAVER) { mutableStateOf(SearchTagFilter()) }
    var showTagFilter by remember { mutableStateOf(false) }
    val tagStore = remember(context) { SearchTagStore(context) }
    var userTags by remember(tagStore) { mutableStateOf(tagStore.load()) }
    val coroutineScope = rememberCoroutineScope()
    val activity = context.findActivity()

    // The activity owns theme/system-bar appearance. Search owns only its input mode.
    // A decor-level scrim cannot participate in Compose transition coordinates and created seams.
    DisposableEffect(activity, manageSystemBar) {
        val window = activity?.window
        if (!manageSystemBar || window == null) return@DisposableEffect onDispose {}
        val previousSoftInputMode = window.attributes.softInputMode
        @Suppress("DEPRECATION") // Required for IME insets on supported older Android releases.
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        onDispose { window.setSoftInputMode(previousSoftInputMode) }
    }

    fun dismissSearch() {
        submission.reset()
        focusManager.clearFocus(force = true)
        keyboardController?.hide()
        onDismiss()
    }

    fun cancelInputMode() {
        focusManager.clearFocus(force = true)
        keyboardController?.hide()
        deletingHistory = false
        if (submittedQuery == null) {
            dismissSearch()
        } else {
            query = submittedQuery.orEmpty()
            inputMode = false
        }
    }

    fun openAlbum(album: HomeAlbum, sourceBounds: Rect?) {
        focusManager.clearFocus(force = true)
        keyboardController?.hide()
        onAlbumSelected(album, sourceBounds)
    }

    fun beginSearch(resetScroll: Boolean = true) {
        if (submittedQuery == null && !tagFilter.enabled) {
            submission.reset()
            inputMode = true
            return
        }
        submission.begin(submittedQuery.orEmpty(), searchOrder, tagFilter)
        if (resetScroll) searchGridState.requestScrollToItem(0)
    }

    fun submitSearch(value: String) {
        val normalizedQuery = value.trim()
        if (normalizedQuery.isEmpty()) return
        focusManager.clearFocus(force = true)
        keyboardController?.hide()
        query = normalizedQuery
        history = historyStore.record(history, normalizedQuery)
        deletingHistory = false
        submittedQuery = normalizedQuery
        inputMode = false
        beginSearch()
    }

    LaunchedEffect(initialQuery, submission) {
        val value = initialQuery?.trim().orEmpty()
        if (!initialQueryConsumed && value.isNotEmpty()) {
            initialQueryConsumed = true
            submitSearch(value)
        } else if (submission.request == null) {
            beginSearch(resetScroll = false)
        }
        if (!inputEnabled) {
            delay(INITIAL_SEARCH_FOCUS_GUARD_MILLIS)
            inputEnabled = true
        }
    }

    val pendingRequest = submission.request
    LaunchedEffect(pendingRequest, repository) {
        val request = pendingRequest ?: return@LaunchedEffect
        submission.execute(request, repository::search)?.let { openAlbum(it, null) }
    }

    fun loadMore() {
        val content = submission.state as? ComicSearchUiState.Content ?: return
        if (!requestEpoch.accepts(content.generation) || content.isLoadingMore || content.endReached) return
        if (content.nextPage > MAX_SEARCH_PAGES) {
            submission.state = content.copy(endReached = true, browseLimited = true)
            return
        }
        val task = coroutineScope.launch(start = CoroutineStart.LAZY) {
            val result = repository.search(
                query = content.query,
                page = content.nextPage,
                order = content.order,
                tagFilter = content.tagFilter,
            )
            val latest = submission.state as? ComicSearchUiState.Content
            if (
                !requestEpoch.accepts(content.generation) || latest?.generation != content.generation ||
                latest.query != content.query || latest.nextPage != content.nextPage ||
                latest.order != content.order ||
                latest.tagFilter != content.tagFilter
            ) return@launch
            submission.state = when (result) {
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
                        endReached = mergedPage.endReached || content.nextPage >= MAX_SEARCH_PAGES,
                        browseLimited = mergedPage.limitReached || content.nextPage >= MAX_SEARCH_PAGES,
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
        if (requestEpoch.register(content.generation, task)) {
            submission.state = content.copy(isLoadingMore = true, loadMoreError = null)
            task.invokeOnCompletion { requestEpoch.finished(task) }
            task.start()
        }
    }

    fun handleBack() {
        when {
            deletingHistory -> deletingHistory = false
            inputMode -> cancelInputMode()
            else -> dismissSearch()
        }
    }
    BackHandler(onBack = ::handleBack)
    SearchBar(
        inputField = {
            InputField(
                query = query,
                onQueryChange = {
                    query = it
                    if (deletingHistory) deletingHistory = false
                },
                onSearch = ::submitSearch,
                enabled = inputEnabled,
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
                expanded = inputMode,
                onExpandedChange = { expanded ->
                    if (expanded) inputMode = true else cancelInputMode()
                },
                label = "搜索标题、标签或JM车号",
                modifier = Modifier.fillMaxWidth(),
            )
        },
        expanded = true,
        onExpandedChange = { if (!it) handleBack() },
        outsideEndAction = {
            Box(Modifier.width(112.dp).height(44.dp), contentAlignment = Alignment.CenterEnd) {
                AnimatedContent(
                    targetState = inputMode,
                    transitionSpec = {
                        ((fadeIn(tween(180)) + slideInHorizontally { it / 3 }) togetherWith
                            (fadeOut(tween(120)) + slideOutHorizontally { it / 3 })).using(null)
                    },
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.CenterEnd,
                    label = "SearchEndAction",
                ) { editing ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (SEARCH_TAG_FILTER_UI_ENABLED) {
                            SearchTagFilterButton(
                                activeCount = tagFilter.activeCount,
                                onClick = { showTagFilter = true },
                            )
                        }
                        if (editing) {
                            QuietTextAction("取消", ::cancelInputMode, modifier = Modifier.padding(end = 4.dp), emphasized = true)
                        } else {
                            SearchOrderDropdown(
                                selected = searchOrder,
                                onSelected = { order ->
                                    if (order != searchOrder) {
                                        searchOrder = order
                                        beginSearch()
                                    }
                                },
                            )
                        }
                    }
                }
            }
        },
        modifier = Modifier
            .fillMaxSize()
            .background(MiuixTheme.colorScheme.surface)
            .imePadding()
            .padding(top = statusBarPadding)
            .graphicsLayer { translationY = -24.dp.toPx() * (1f - transitionProgress()) },
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(MiuixTheme.colorScheme.surface),
        ) {
            AnimatedContent(
                targetState = inputMode,
                transitionSpec = {
                    ((fadeIn(tween(190)) + slideInHorizontally { if (targetState) -it / 8 else it / 8 }) togetherWith
                        (fadeOut(tween(130)) + slideOutHorizontally { if (targetState) it / 8 else -it / 8 })).using(null)
                },
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.TopStart,
                label = "SearchModeContent",
            ) { editing ->
                if (editing) {
                    SearchHistoryPanel(
                        history = history,
                        filterQuery = query,
                        editing = deletingHistory,
                        onEditingChange = { editing ->
                            focusManager.clearFocus(force = true)
                            keyboardController?.hide()
                            deletingHistory = editing
                        },
                        onSelected = ::submitSearch,
                        onDelete = { historyQuery ->
                            history = historyStore.remove(history, historyQuery)
                            if (history.isEmpty()) deletingHistory = false
                        },
                        onClearAll = { confirmClearHistory = true },
                    )
                } else {
                    when (val current = submission.state) {
                        ComicSearchUiState.Idle -> SearchMessage("输入关键词后执行搜索")
                        ComicSearchUiState.Loading -> SearchLoading()
                        is ComicSearchUiState.Empty -> SearchMessage("未找到“${current.query}”相关漫画")
                        is ComicSearchUiState.Error -> SearchError(
                            message = current.message,
                            onRetry = { beginSearch() },
                        )
                        is ComicSearchUiState.Content -> key(
                            current.query,
                            current.order,
                            current.tagFilter,
                        ) {
                            SearchResultGrid(
                                content = current,
                                gridState = searchGridState,
                                liftedAlbumId = liftedAlbumId,
                                onLoadMore = ::loadMore,
                                onAlbumSelected = { album, bounds -> openAlbum(album, bounds) },
                            )
                        }
                    }
                }
            }
        }
    }

    WindowDialog(
        show = confirmClearHistory,
        title = "清空搜索历史",
        summary = "清空全部搜索历史？此操作只影响这台设备。",
        onDismissRequest = { confirmClearHistory = false },
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(
                text = "保留历史",
                onClick = { confirmClearHistory = false },
                modifier = Modifier.weight(1f),
            )
            TextButton(
                text = "全部清空",
                onClick = {
                    history = historyStore.clear()
                    deletingHistory = false
                    confirmClearHistory = false
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.textButtonColors(
                    color = MiuixTheme.colorScheme.error,
                    textColor = MiuixTheme.colorScheme.onError,
                ),
            )
        }
    }

    if (SEARCH_TAG_FILTER_UI_ENABLED && showTagFilter) {
        SearchTagFilterDialog(
            filter = tagFilter,
            builtInTags = DEFAULT_SEARCH_TAGS,
            userTags = userTags,
            onAddUserTag = { tag ->
                tagStore.add(tag)
                userTags = tagStore.load()
            },
            onRemoveUserTag = { tag ->
                tagStore.remove(tag)
                userTags = tagStore.load()
            },
            onApply = { selected ->
                tagFilter = selected
                showTagFilter = false
                // 应用过滤后重新发起首页搜索：既有关键词、或仅标签过滤，都需要刷新。
                if (submittedQuery != null || selected.enabled) {
                    focusManager.clearFocus(force = true)
                    keyboardController?.hide()
                    deletingHistory = false
                    inputMode = false
                    beginSearch()
                }
            },
            onDismiss = { showTagFilter = false },
        )
    }
}

private const val SEARCH_TAG_FILTER_UI_ENABLED = true

/**
 * 标签过滤入口。
 *
 * 未启用时用与其他顶栏图标一致的中性色，不再显示蓝色，避免"看起来已经开着"的误导；
 * 启用后图标转为主题色，并叠加一个数字角标显示生效的标签数量，状态一眼可见。
 */
@Composable
private fun SearchTagFilterButton(
    activeCount: Int,
    onClick: () -> Unit,
) {
    val active = activeCount > 0
    IconButton(
        onClick = onClick,
        minWidth = 44.dp,
        minHeight = 44.dp,
    ) {
        BadgedBox(
            badge = {
                if (active) {
                    Badge(
                        containerColor = MiuixTheme.colorScheme.primary,
                        contentColor = MiuixTheme.colorScheme.onPrimary,
                    ) {
                        Text(text = activeCount.toString())
                    }
                }
            },
        ) {
            Icon(
                imageVector = MiuixIcons.Filter,
                contentDescription = if (active) "标签过滤已启用，$activeCount 个标签" else "标签过滤",
                modifier = Modifier.size(20.dp),
                tint = if (active) {
                    MiuixTheme.colorScheme.primary
                } else {
                    MiuixTheme.colorScheme.onSurfaceVariantActions
                },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SearchTagFilterDialog(
    filter: SearchTagFilter,
    builtInTags: List<String>,
    userTags: List<String>,
    onAddUserTag: (String) -> Unit,
    onRemoveUserTag: (String) -> Unit,
    onApply: (SearchTagFilter) -> Unit,
    onDismiss: () -> Unit,
) {
    // 本地工作副本：点按标签在 无 → 包含(+) → 排除(-) → 无 之间循环，应用时一次性回传。
    var working by remember(filter) { mutableStateOf(filter) }
    var input by remember { mutableStateOf("") }
    val userTagSet = remember(userTags) { userTags.mapNotNull(::normalizeSearchTag).toSet() }
    val tagOptions = remember(builtInTags, userTags) {
        (builtInTags + userTags).mapNotNull(::normalizeSearchTag).distinct()
    }
    val chipShape = RoundedCornerShape(8.dp)

    fun cycle(tag: String) {
        working = when (working.stateOf(tag)) {
            SearchTagState.NONE -> working.toggleInclude(tag)
            SearchTagState.INCLUDE -> working.toggleExclude(tag)
            SearchTagState.EXCLUDE -> working.toggleExclude(tag)
        }
    }

    fun deleteUserTag(tag: String) {
        onRemoveUserTag(tag)
        working = working.copy(
            includeTags = working.includeTags.filter { normalizeSearchTag(it) != tag },
            excludeTags = working.excludeTags.filter { normalizeSearchTag(it) != tag },
        )
    }

    fun addInputTag() {
        val tag = normalizeSearchTag(input) ?: return
        onAddUserTag(tag)
        // 新增即视为“包含”，若已存在则保持既有状态。
        if (working.stateOf(tag) != SearchTagState.INCLUDE) {
            working = working.toggleInclude(tag)
        }
        input = ""
    }

    val includeCount = working.normalizedIncludeTags.size
    val excludeCount = working.normalizedExcludeTags.size

    WindowDialog(
        show = true,
        title = "标签过滤",
        summary = if (!working.enabled) {
            "点按标签切换：包含 → 排除 → 取消"
        } else {
            buildList {
                if (includeCount > 0) add("包含 $includeCount")
                if (excludeCount > 0) add("排除 $excludeCount")
            }.joinToString(" · ")
        },
        onDismissRequest = onDismiss,
    ) {
        // 已选条件常驻显示：状态不藏在点击循环里，点一下即可直接移除该条件。
        if (working.enabled) {
            Text(
                text = "已选条件（点按移除）",
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Spacer(modifier = Modifier.height(8.dp))
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                working.normalizedIncludeTags.forEach { tag ->
                    SearchTagChip(
                        label = "＋$tag",
                        container = MiuixTheme.colorScheme.primaryContainer,
                        onContainer = MiuixTheme.colorScheme.onPrimaryContainer,
                        shape = chipShape,
                        onClick = { working = working.toggleInclude(tag) },
                    )
                }
                working.normalizedExcludeTags.forEach { tag ->
                    SearchTagChip(
                        label = "－$tag",
                        container = MiuixTheme.colorScheme.errorContainer,
                        onContainer = MiuixTheme.colorScheme.onErrorContainer,
                        shape = chipShape,
                        onClick = { working = working.toggleExclude(tag) },
                    )
                }
            }
        } else {
            Text(
                text = "尚未添加条件。＋ 表示结果必须包含，－ 表示结果必须排除。",
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
        Spacer(modifier = Modifier.height(14.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextField(
                value = input,
                onValueChange = { input = it },
                label = "新增自定义标签",
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                text = "添加",
                enabled = normalizeSearchTag(input) != null,
                onClick = ::addInputTag,
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "可选标签：点按依次切换 ＋包含 / －排除 / 取消，长按删除自定义标签",
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 260.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                tagOptions.forEach { tag ->
                    val tagState = working.stateOf(tag)
                    SearchTagChip(
                        label = when (tagState) {
                            SearchTagState.INCLUDE -> "＋$tag"
                            SearchTagState.EXCLUDE -> "－$tag"
                            SearchTagState.NONE -> tag
                        },
                        container = when (tagState) {
                            SearchTagState.INCLUDE -> MiuixTheme.colorScheme.primaryContainer
                            SearchTagState.EXCLUDE -> MiuixTheme.colorScheme.errorContainer
                            SearchTagState.NONE -> MiuixTheme.colorScheme.surfaceContainerHigh
                        },
                        onContainer = when (tagState) {
                            SearchTagState.INCLUDE -> MiuixTheme.colorScheme.onPrimaryContainer
                            SearchTagState.EXCLUDE -> MiuixTheme.colorScheme.onErrorContainer
                            SearchTagState.NONE -> MiuixTheme.colorScheme.onSurface
                        },
                        shape = chipShape,
                        onClick = { cycle(tag) },
                        onLongClick = if (tag in userTagSet) {
                            { deleteUserTag(tag) }
                        } else {
                            null
                        },
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                text = "清除",
                enabled = working.enabled,
                onClick = { working = SearchTagFilter.EMPTY },
            )
            TextButton(
                text = "应用",
                onClick = { onApply(working) },
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
        }
    }
}

@Composable
private fun SearchTagChip(
    label: String,
    container: Color,
    onContainer: Color,
    shape: Shape,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    Surface(shape = shape, color = container) {
        Box(
            modifier = Modifier
                .clip(shape)
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .padding(horizontal = 10.dp, vertical = 7.dp),
        ) {
            Text(
                text = label,
                style = MiuixTheme.textStyles.footnote1,
                color = onContainer,
            )
        }
    }
}

@Composable
private fun SearchOrderDropdown(
    selected: ComicSearchOrder,
    onSelected: (ComicSearchOrder) -> Unit,
) {
    val entry = DropdownEntry(
        items = ComicSearchOrder.entries.map { order ->
            DropdownItem(
                text = order.label,
                selected = order == selected,
                onClick = { onSelected(order) },
            )
        },
    )
    WindowIconDropdownMenu(entry = entry, minWidth = 68.dp, minHeight = 44.dp) {
        Row(Modifier.padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(MiuixIcons.Sort, contentDescription = "结果排序：${selected.label}", modifier = Modifier.size(20.dp), tint = MiuixTheme.colorScheme.onSurfaceVariantActions)
            Text(selected.label, color = MiuixTheme.colorScheme.onSurfaceVariantActions, style = MiuixTheme.textStyles.footnote1)
        }
    }
}


@Composable
private fun SearchResultGrid(
    content: ComicSearchUiState.Content,
    liftedAlbumId: String?,
    onLoadMore: () -> Unit,
    onAlbumSelected: (HomeAlbum, Rect) -> Unit,
    gridState: LazyGridState,
) {
    val footerKey = "search-footer"
    val footerVisible by remember(gridState) {
        derivedStateOf {
            gridState.layoutInfo.visibleItemsInfo.any { item -> item.key == footerKey }
        }
    }
    var loadMoreArmed by remember(content.generation) { mutableStateOf(true) }
    LaunchedEffect(footerVisible) {
        if (!footerVisible) loadMoreArmed = true
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive((108f * LocalDensity.current.fontScale.coerceIn(1f, 1.5f)).dp),
        state = gridState,
        modifier = Modifier.fillMaxSize().navigationBarsPadding(),
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
                        text = if (content.browseLimited) "已达到本次浏览上限 · ${content.albums.size} 部" else "已加载 ${content.albums.size} 部漫画",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    else -> QuietTextAction("加载更多", onLoadMore)
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

    /**
     * 单次服务端搜索。标签的包含/排除通过 [SearchQueryComposer] 拼进 search_query，由服务端
     * 一次性完成过滤，客户端不再逐条拉取详情。服务端自身完成简繁归一化，因此无需简繁变体扩散，
     * 每页只发一个请求。
     */
    suspend fun search(
        query: String,
        page: Int,
        order: ComicSearchOrder = ComicSearchOrder.LATEST,
        tagFilter: SearchTagFilter = SearchTagFilter(),
    ): ComicSearchResult {
        val normalizedQuery = query.trim()
        // 纯车号直达详情（无过滤语义）。
        val directId = normalizedQuery.toJmSearchIdOrNull()
        if (directId != null && !tagFilter.enabled) return loadDirectAlbum(directId)

        val composedQuery = SearchQueryComposer.compose(
            baseQuery = normalizedQuery,
            includeTags = tagFilter.normalizedIncludeTags,
            excludeTags = tagFilter.normalizedExcludeTags,
        )
        if (composedQuery.isBlank()) return ComicSearchResult.Error("请输入搜索关键词或至少一个包含标签。")

        return withContext(Dispatchers.IO) {
            try {
                when (val result = homeRepository.core.publicRead { homeRepository.core.albumApi.searchResolved(
                    query = composedQuery,
                    page = page,
                    order = order.apiValue,
                    mainTag = JmxMagicConstants.MAIN_TAG_ALL,
                ) }) {
                    is JmxResult.Success -> {
                        val searchPage = result.value
                        // 车号直达（服务端重定向）：无过滤时命中，直接进入详情。
                        val redirectId = searchPage.redirectAlbumId?.takeIf { it.isNotBlank() }
                        if (redirectId != null && !tagFilter.enabled && searchPage.content.size == 1) {
                            ComicSearchResult.Direct(
                                searchPage.content.first().toHomeAlbum(homeRepository.currentImageHost),
                            )
                        } else {
                            val albums = searchPage.content
                                .map { it.toHomeAlbum(homeRepository.currentImageHost) }
                                .distinctBy { it.id }
                            ComicSearchResult.Page(
                                albums = albums,
                                total = searchPage.total,
                                endReached = searchPage.content.isEmpty(),
                                tagFilter = tagFilter,
                            )
                        }
                    }
                    is JmxResult.Failure -> ComicSearchResult.Error(result.error.toUiMessage())
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                ComicSearchResult.Error(error.message ?: "搜索出现未知异常。")
            }
        }
    }

    private suspend fun loadDirectAlbum(albumId: String): ComicSearchResult {
        return when (val result = homeRepository.core.publicRead { homeRepository.core.albumApi.detailFull(albumId) }) {
            is JmxResult.Success -> ComicSearchResult.Direct(
                result.value.toHomeAlbum(homeRepository.currentImageHost),
            )
            is JmxResult.Failure -> ComicSearchResult.Error(result.error.toUiMessage())
        }
    }
}

internal enum class ComicSearchOrder(
    val apiValue: String,
    val label: String,
) {
    LATEST(JmxMagicConstants.ORDER_BY_LATEST, "最新"),
    MOST_VIEWED(JmxMagicConstants.ORDER_BY_VIEW, "最多点击"),
    MOST_PICTURES(JmxMagicConstants.ORDER_BY_PICTURE, "最多图片"),
    MOST_LIKED(JmxMagicConstants.ORDER_BY_LIKE, "最多爱心"),
}

internal sealed interface ComicSearchResult {
    data class Direct(val album: HomeAlbum) : ComicSearchResult
    data class Page(
        val albums: List<HomeAlbum>,
        val total: Int?,
        val endReached: Boolean,
        val tagFilter: SearchTagFilter = SearchTagFilter(),
    ) : ComicSearchResult
    data class Error(val message: String) : ComicSearchResult
}

internal data class SearchAlbumMerge(
    val albums: List<HomeAlbum>,
    val endReached: Boolean,
    val limitReached: Boolean = false,
)

internal const val MAX_SEARCH_RESULTS = 2000
private const val MAX_SEARCH_PAGES = 100

internal fun mergeSearchAlbums(
    existing: List<HomeAlbum>,
    incoming: List<HomeAlbum>,
    sourceEndReached: Boolean,
    maxAlbums: Int = MAX_SEARCH_RESULTS,
): SearchAlbumMerge {
    require(maxAlbums > 0)
    val merged = ArrayList<HomeAlbum>(minOf(maxAlbums, existing.size + incoming.size))
    val seen = HashSet<String>()
    existing.forEach { if (merged.size < maxAlbums && seen.add(it.id)) merged.add(it) }
    val previousCount = merged.size
    incoming.forEach { if (merged.size < maxAlbums && seen.add(it.id)) merged.add(it) }
    val limited = merged.size >= maxAlbums
    return SearchAlbumMerge(merged, sourceEndReached || merged.size == previousCount || limited, limited)
}

/** 仅标签过滤（无关键词）时用于展示/历史/空态的摘要文案。 */
internal fun tagFilterSummary(filter: SearchTagFilter): String {
    val parts = buildList {
        filter.normalizedIncludeTags.forEach { add("+$it") }
        filter.normalizedExcludeTags.forEach { add("-$it") }
    }
    return if (parts.isEmpty()) "标签过滤" else parts.joinToString(" ")
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

/**
 * 生成简/繁体查询变体。搜索结果页已改为依赖服务端自带的简繁归一化，不再需要变体扩散；
 * 但书架的「按作者搜索全部作品」仍复用这两个工具函数，故保留于此。
 */
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

internal fun toTraditionalChinese(text: String): String =
    runCatching { ZhConverterUtil.toTraditional(text) }.getOrDefault(text)

private val JM_SEARCH_ID_REGEX = Regex("(?i)^(?:JM)?\\s*(\\d+)$")
private const val INITIAL_SEARCH_FOCUS_GUARD_MILLIS = 180L

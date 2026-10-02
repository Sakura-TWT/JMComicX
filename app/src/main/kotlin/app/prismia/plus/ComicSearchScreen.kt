package app.prismia.plus

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsAnimationCompat
import androidx.core.view.WindowInsetsCompat
import com.github.houbb.opencc4j.util.ZhConverterUtil
import app.prismia.plus.core.api.AlbumDetail
import app.prismia.plus.core.api.SearchQueryComposer
import app.prismia.plus.core.protocol.JmxMagicConstants
import app.prismia.plus.core.result.JmxResult
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

@Composable
internal fun ComicSearchScreen(
    homeRepository: HomeRepository,
    initialQuery: String? = null,
    manageSystemBar: Boolean = true,
    liftedAlbumId: String?,
    onDismiss: () -> Unit,
    onAlbumSelected: (HomeAlbum, Rect?) -> Unit,
) {
    val context = LocalContext.current
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
    var initialQueryConsumed by rememberSaveable(initialQuery) { mutableStateOf(false) }
    var inputEnabled by rememberSaveable(initialQuery) { mutableStateOf(initialQuery.isNullOrBlank()) }
    var inputMode by rememberSaveable(initialQuery) { mutableStateOf(initialQuery.isNullOrBlank()) }
    var searchOrder by rememberSaveable { mutableStateOf(ComicSearchOrder.LATEST) }
    var tagFilter by remember { mutableStateOf(SearchTagFilter()) }
    var showTagFilter by remember { mutableStateOf(false) }
    val tagStore = remember(context) { SearchTagStore(context) }
    var userTags by remember(tagStore) { mutableStateOf(tagStore.load()) }
    val coroutineScope = rememberCoroutineScope()
    val searchSurfaceColor = MiuixTheme.colorScheme.surface
    val activity = context.findActivity()

    SideEffect {
        val window = activity?.window
        if (manageSystemBar && window != null) {
            applySearchSystemBarAppearance(window, searchSurfaceColor.toArgb(), searchSurfaceColor.luminance() > 0.5f)
        }
    }

    DisposableEffect(context, activity, searchSurfaceColor, manageSystemBar) {
        val window = activity?.window
        val decor = window?.decorView as? ViewGroup
        if (!manageSystemBar || window == null || decor == null) {
            return@DisposableEffect onDispose {}
        }

        val color = searchSurfaceColor.toArgb()
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        val previousLightStatusBars = controller.isAppearanceLightStatusBars
        val previousSoftInputMode = window.attributes.softInputMode
        @Suppress("DEPRECATION")
        val previousStatusBarColor = window.statusBarColor
        val scrim = View(context).apply {
            setBackgroundColor(color)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val initialStatusBarHeight = ViewCompat.getRootWindowInsets(decor)
            ?.getInsets(WindowInsetsCompat.Type.statusBars())
            ?.top
            ?: 0
        decor.addView(
            scrim,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                initialStatusBarHeight,
                Gravity.TOP,
            ),
        )
        ViewCompat.setOnApplyWindowInsetsListener(scrim) { view, insets ->
            val statusBarHeight = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            if (view.layoutParams.height != statusBarHeight) {
                view.layoutParams = view.layoutParams.apply { height = statusBarHeight }
            }
            insets
        }
        ViewCompat.requestApplyInsets(scrim)
        window.setBackgroundDrawable(color.toDrawable())
        @Suppress("DEPRECATION")
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        val lightStatusBars = searchSurfaceColor.luminance() > 0.5f
        val insetsAnimationCallback = object : WindowInsetsAnimationCompat.Callback(
            WindowInsetsAnimationCompat.Callback.DISPATCH_MODE_CONTINUE_ON_SUBTREE,
        ) {
            override fun onPrepare(animation: WindowInsetsAnimationCompat) {
                applySearchSystemBarAppearance(window, color, lightStatusBars)
            }

            override fun onStart(
                animation: WindowInsetsAnimationCompat,
                bounds: WindowInsetsAnimationCompat.BoundsCompat,
            ): WindowInsetsAnimationCompat.BoundsCompat {
                applySearchSystemBarAppearance(window, color, lightStatusBars)
                return bounds
            }

            override fun onProgress(
                insets: WindowInsetsCompat,
                runningAnimations: List<WindowInsetsAnimationCompat>,
            ): WindowInsetsCompat {
                applySearchSystemBarAppearance(window, color, lightStatusBars)
                return insets
            }

            override fun onEnd(animation: WindowInsetsAnimationCompat) {
                applySearchSystemBarAppearance(window, color, lightStatusBars)
            }
        }
        ViewCompat.setWindowInsetsAnimationCallback(window.decorView, insetsAnimationCallback)
        applySearchSystemBarAppearance(window, color, lightStatusBars)

        onDispose {
            ViewCompat.setWindowInsetsAnimationCallback(window.decorView, null)
            ViewCompat.setOnApplyWindowInsetsListener(scrim, null)
            decor.removeView(scrim)
            window.setSoftInputMode(previousSoftInputMode)
            @Suppress("DEPRECATION")
            window.statusBarColor = previousStatusBarColor
            controller.isAppearanceLightStatusBars = previousLightStatusBars
        }
    }

    fun dismissSearch() {
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
        searchRequestId++
    }

    LaunchedEffect(initialQuery) {
        val value = initialQuery?.trim().orEmpty()
        if (!initialQueryConsumed && value.isNotEmpty()) {
            initialQueryConsumed = true
            submitSearch(value)
            delay(INITIAL_SEARCH_FOCUS_GUARD_MILLIS)
            inputEnabled = true
        }
    }

    LaunchedEffect(submittedQuery, searchRequestId, searchOrder, tagFilter, repository) {
        val baseQuery = submittedQuery
        // 允许“仅标签”的过滤搜索：没有关键词但存在包含标签时也发起。
        if (baseQuery == null && !tagFilter.enabled) return@LaunchedEffect
        val queryText = baseQuery.orEmpty()
        val displayQuery = queryText.ifBlank { tagFilterSummary(tagFilter) }
        state = ComicSearchUiState.Loading
        when (val result = repository.search(
            queryText,
            page = 1,
            order = searchOrder,
            tagFilter = tagFilter,
        )) {
            is ComicSearchResult.Direct -> {
                state = ComicSearchUiState.Content(
                    query = queryText,
                    albums = listOf(result.album),
                    total = 1,
                    nextPage = 2,
                    order = searchOrder,
                    tagFilter = tagFilter,
                    endReached = true,
                )
                openAlbum(result.album, null)
            }
            is ComicSearchResult.Page -> {
                state = if (result.albums.isEmpty() && result.endReached) {
                    ComicSearchUiState.Empty(displayQuery)
                } else {
                    ComicSearchUiState.Content(
                        query = queryText,
                        albums = result.albums,
                        total = result.total,
                        nextPage = 2,
                        order = searchOrder,
                        tagFilter = tagFilter,
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
            val result = repository.search(
                query = content.query,
                page = content.nextPage,
                order = content.order,
                tagFilter = content.tagFilter,
            )
            val latest = state as? ComicSearchUiState.Content
            if (
                latest?.query != content.query ||
                latest.order != content.order ||
                latest.tagFilter != content.tagFilter
            ) return@launch
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
        when {
            deletingHistory -> deletingHistory = false
            inputMode -> cancelInputMode()
            else -> dismissSearch()
        }
    }
    SearchBar(
        inputField = {
            InputField(
                query = query,
                onQueryChange = {
                    query = it
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
        onExpandedChange = { if (!it) dismissSearch() },
        outsideEndAction = {
            AnimatedContent(
                targetState = inputMode,
                transitionSpec = {
                    (fadeIn(tween(180)) + slideInHorizontally { it / 3 }) togetherWith
                        (fadeOut(tween(120)) + slideOutHorizontally { it / 3 })
                },
                label = "SearchEndAction",
            ) { editing ->
                if (editing) {
                    // 输入态也保留标签过滤入口：可以先挑标签再敲关键词，甚至只用标签搜索。
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (SEARCH_TAG_FILTER_UI_ENABLED) {
                            SearchTagFilterButton(
                                activeCount = tagFilter.activeCount,
                                onClick = { showTagFilter = true },
                            )
                        }
                        Text(
                            text = "取消",
                            color = MiuixTheme.colorScheme.primary,
                            modifier = Modifier
                                .clickable(onClick = ::cancelInputMode)
                                .padding(start = 4.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
                        )
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (SEARCH_TAG_FILTER_UI_ENABLED) {
                            SearchTagFilterButton(
                                activeCount = tagFilter.activeCount,
                                onClick = { showTagFilter = true },
                            )
                        }
                        SearchOrderDropdown(
                            selected = searchOrder,
                            onSelected = { order ->
                                if (order != searchOrder) searchOrder = order
                            },
                        )
                    }
                }
            }
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
            AnimatedContent(
                targetState = inputMode,
                transitionSpec = {
                    (fadeIn(tween(190)) + slideInHorizontally { if (targetState) -it / 8 else it / 8 }) togetherWith
                        (fadeOut(tween(130)) + slideOutHorizontally { if (targetState) it / 8 else -it / 8 })
                },
                label = "SearchModeContent",
            ) { editing ->
                if (editing) {
                    SearchHistory(
                        history = history,
                        deleting = deletingHistory,
                        onDeletingChange = { deletingHistory = it },
                        onSelected = ::submitSearch,
                        onDelete = { historyQuery ->
                            history = historyStore.remove(history, historyQuery)
                            if (history.isEmpty()) deletingHistory = false
                        },
                    )
                } else {
                    when (val current = state) {
                        ComicSearchUiState.Idle -> SearchMessage("输入关键词后执行搜索")
                        ComicSearchUiState.Loading -> SearchLoading()
                        is ComicSearchUiState.Empty -> SearchMessage("未找到“${current.query}”相关漫画")
                        is ComicSearchUiState.Error -> SearchError(
                            message = current.message,
                            onRetry = { searchRequestId++ },
                        )
                        is ComicSearchUiState.Content -> key(
                            current.query,
                            current.order,
                            current.tagFilter,
                        ) {
                            SearchResultGrid(
                                content = current,
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
                if (submittedQuery != null || selected.enabled) searchRequestId++
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
        minWidth = 42.dp,
        minHeight = 42.dp,
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
    Row(
        modifier = Modifier.padding(end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = selected.label,
            color = MiuixTheme.colorScheme.primary,
            style = MiuixTheme.textStyles.footnote1,
        )
        WindowIconDropdownMenu(
            entry = entry,
            minWidth = 42.dp,
            minHeight = 42.dp,
        ) {
            Icon(
                imageVector = MiuixIcons.Sort,
                contentDescription = "结果排序：${selected.label}",
                modifier = Modifier.size(20.dp),
                tint = MiuixTheme.colorScheme.primary,
            )
        }
    }
}

private fun applySearchSystemBarAppearance(window: Window, color: Int, lightStatusBars: Boolean) {
    window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
    @Suppress("DEPRECATION")
    window.statusBarColor = color
    WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = lightStatusBars
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
    var loadMoreArmed by remember(content.query, content.tagFilter) { mutableStateOf(true) }
    LaunchedEffect(footerVisible) {
        if (!footerVisible) loadMoreArmed = true
    }
    LaunchedEffect(content.nextPage) {
        loadMoreArmed = true
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
                when (val result = homeRepository.core.albumApi.searchResolved(
                    query = composedQuery,
                    page = page,
                    order = order.apiValue,
                    mainTag = JmxMagicConstants.MAIN_TAG_ALL,
                )) {
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
        return when (val result = homeRepository.core.albumApi.detailFull(albumId)) {
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
        val order: ComicSearchOrder,
        val tagFilter: SearchTagFilter = SearchTagFilter(),
        val isLoadingMore: Boolean = false,
        val endReached: Boolean = false,
        val loadMoreError: String? = null,
    ) : ComicSearchUiState
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
        coverUrl = app.prismia.plus.core.image.ImageUrl.albumCover(imageHost, id),
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

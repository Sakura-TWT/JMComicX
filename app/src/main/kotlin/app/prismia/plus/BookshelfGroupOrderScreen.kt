package app.prismia.plus

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.activity.compose.BackHandler
import app.prismia.plus.effect.BlurredBar
import app.prismia.plus.effect.TopBarBlurStyle
import app.prismia.plus.effect.rememberBarBackdrop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.InputField
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.Search
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Lock
import top.yukonga.miuix.kmp.icon.extended.Sort
import top.yukonga.miuix.kmp.menu.WindowIconCascadingDropdownMenu
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog
import kotlin.math.roundToInt

/**
 * 分组序列：一行一个分组，从上到下就是书架顶部 tab 栏从左到右的顺序。
 *
 * 单击一行回书架并定位到该分组；按住行右侧的手柄上下拖动换位置，右上角"完成"落盘。
 * "自动排列"打开时按使用热度显示（见 [orderBookshelfGroups]），此时手柄不响应拖动——
 * 两套顺序同时生效只会让用户搞不清自己看到的是哪一份。手动顺序不会被自动排列写掉，
 * 关掉开关就原样回来。
 *
 * 为什么是"整行 + 独立手柄"而不是网格加长按：网格里一格只能塞下名字，序号和数量都得挤在角落，
 * 而"第几个 tab"恰恰是这个页面唯一要回答的问题；长按拖动则要求手指先按住不动等超时，
 * 超时前的任何位移都会被列表当成滚动吃掉，手感就是"拖不动"。手柄是专用触点，
 * 按下即进入拖动、立刻吃掉事件，列表再没有机会抢。
 */
@Composable
internal fun BookshelfGroupOrderScreen(
    innerPadding: PaddingValues,
    repository: BookshelfRepository,
    onBack: () -> Unit,
    onLocateGroup: (String) -> Unit,
    topBarBlurStyle: TopBarBlurStyle = TopBarBlurStyle.GAUSSIAN,
) {
    val backdrop = rememberBarBackdrop()
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val listState = rememberLazyListState()

    val initialGroups = remember(repository) { repository.groups() }
    var counts by remember(repository) { mutableStateOf(emptyMap<String, Int>()) }
    var ordered by remember(initialGroups) { mutableStateOf(initialGroups) }
    var autoOrder by remember(repository) { mutableStateOf(repository.autoGroupOrder()) }
    var dirty by remember(initialGroups) { mutableStateOf(false) }
    var showDiscardDialog by remember { mutableStateOf(false) }
    var hintMessage by remember { mutableStateOf<String?>(null) }
    var draggingId by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var dragPointerY by remember { mutableFloatStateOf(0f) }
    var autoScrollVelocity by remember { mutableFloatStateOf(0f) }
    // 这一次拖动到底有没有换过位置。只在手势回调里读写，不参与组合。
    val dragChanged = remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    // 搜索框一旦展开就不再收回（见 [searchGroup] 里的注释），这个状态只负责"第一次点进来"。
    var searchExpanded by remember { mutableStateOf(false) }
    // 刚被搜到的那一行：只用一圈描边示意，几秒后自己消失，不留下需要手动清掉的选中态。
    var locatedId by remember(initialGroups) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current

    // 数量要遍历整份书架，且与顺序无关：挪到 IO 线程算一次，别让打开这页先卡一帧。
    LaunchedEffect(repository, initialGroups) {
        counts = withContext(Dispatchers.IO) { bookshelfGroupCounts(repository, initialGroups) }
    }

    LaunchedEffect(locatedId) {
        if (locatedId == null) return@LaunchedEffect
        delay(GROUP_ORDER_LOCATE_HIGHLIGHT_MILLIS)
        locatedId = null
    }

    val rowStepPx = with(density) { (GROUP_ORDER_ROW_HEIGHT + GROUP_ORDER_ROW_GAP).toPx() }
    val edgeThresholdPx = with(density) { GROUP_ORDER_AUTOSCROLL_EDGE.toPx() }
    val autoScrollStepPx = with(density) { GROUP_ORDER_AUTOSCROLL_STEP.toPx() }

    fun stopDragging() {
        draggingId = null
        dragOffset = 0f
        dragPointerY = 0f
        autoScrollVelocity = 0f
    }

    fun beginDragging(id: String) {
        val self = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == id }
        if (self == null) {
            stopDragging()
            return
        }
        draggingId = id
        dragOffset = 0f
        dragPointerY = self.offset + self.size / 2f
        autoScrollVelocity = 0f
        dragChanged.value = false
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
    }

    /**
     * 把被拖动的行落到最近的槽位上。
     *
     * 行高固定，落位就是一道除法，不必去翻 layoutInfo 找最近的那一行：
     * 位移超过半行就换一格。落位是"边拖边换"而不是抬手才算——每挪过一格立刻改列表，
     * 其余行由 [androidx.compose.foundation.lazy.LazyItemScope.animateItem] 平移让位，
     * 用户看到的就是实时的顺序。
     */
    fun settleDragTarget() {
        val id = draggingId ?: return
        val from = ordered.indexOfFirst { it.id == id }
        if (from < 0) return
        val to = groupDragTargetIndex(from, dragOffset, rowStepPx, ordered.size)
        if (to == from) return
        val byId = ordered.associateBy(BookshelfGroup::id)
        ordered = moveGroupOrder(ordered.map(BookshelfGroup::id), from, to).mapNotNull(byId::get)
        // 槽位已经换了，位移要按换过的格数扣掉，否则这一行会自己跳一格出去。
        dragOffset -= (to - from) * rowStepPx
        dirty = true
        dragChanged.value = true
        haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
    }

    // 拖到上下边缘时自动滚动。滚动量要补回 dragOffset，否则这一行会跟着列表一起跑，脱离手指。
    LaunchedEffect(draggingId) {
        while (draggingId != null) {
            val info = listState.layoutInfo
            // LazyColumn 的 viewportStartOffset 包含 beforeContentPadding；直接使用它会把
            // 顶部触发区向搜索栏上方错开，刚换到首个分组时看起来像闪现并提前结束拖动。
            // 这里还原为内容真正可见的上下边界，顶部和底部使用同一套坐标规则。
            autoScrollVelocity = groupOrderAutoScrollVelocity(
                pointerY = dragPointerY,
                viewportStart = info.viewportStartOffset.toFloat() + info.beforeContentPadding,
                viewportEnd = info.viewportEndOffset.toFloat() - info.afterContentPadding,
                edgePx = edgeThresholdPx,
                stepPx = autoScrollStepPx,
            )
            val velocity = autoScrollVelocity
            if (velocity != 0f) {
                val consumed = listState.scrollBy(velocity)
                if (consumed != 0f) {
                    dragOffset += consumed
                    settleDragTarget()
                }
            }
            // 每帧重新读取速度：手指从上边缘移到下边缘时，滚动方向必须立即反转；
            // 到达列表端点时也不能退出循环，否则手指保持在边缘后再移动就无法恢复。
            withFrameNanos { }
        }
    }

    fun onHandleDrag(id: String, delta: Float) {
        if (draggingId != id) return
        dragOffset += delta
        dragPointerY += delta
        settleDragTarget()
        val info = listState.layoutInfo
        autoScrollVelocity = groupOrderAutoScrollVelocity(
            pointerY = dragPointerY,
            viewportStart = info.viewportStartOffset.toFloat() + info.beforeContentPadding,
            viewportEnd = info.viewportEndOffset.toFloat() - info.afterContentPadding,
            edgePx = edgeThresholdPx,
            stepPx = autoScrollStepPx,
        )
    }

    fun toggleAutoOrder() {
        val next = !autoOrder
        // 开启自动排列前先把没保存的手动顺序落盘：这一步不落，用户拖了半天的结果就白丢了，
        // 而"关掉自动排列后手动顺序原样回来"正是它要还原的东西。
        if (next && dirty) repository.setGroupOrder(ordered.map(BookshelfGroup::id))
        repository.setAutoGroupOrder(next)
        autoOrder = next
        ordered = repository.groups()
        dirty = false
        stopDragging()
    }

    fun saveAndExit() {
        // 自动排列开着时看到的是热度序，落盘会把手动顺序覆盖成它，所以只在手动模式下写。
        if (!autoOrder) repository.setGroupOrder(ordered.map(BookshelfGroup::id))
        onBack()
    }

    /**
     * 单击一行：把顺序落盘再去那个分组。
     *
     * "刚拖好顺序，顺手点进去看看"是这页最常见的动作，点一下就把调整丢掉说不过去，
     * 为它再弹一个"要保存吗"也太啰嗦——直接存，效果与按"完成"一致。
     */
    fun locateGroup(groupId: String) {
        if (!autoOrder && dirty) repository.setGroupOrder(ordered.map(BookshelfGroup::id))
        onLocateGroup(groupId)
    }

    fun requestBack() {
        if (dirty) showDiscardDialog = true else onBack()
    }

    BackHandler(enabled = dirty) { requestBack() }

    /**
     * 搜索框回车、或点一下搜索图标：在列表里找到那个分组并滚过去。
     *
     * 只滚不跳转——这页是"看顺序、调顺序"的地方，输个名字就被弹回书架并不是用户要的；
     * 想进去还有整行可以点。找不到时用既有的提示弹窗说一句，而不是静静地什么都不发生。
     */
    fun searchGroup() {
        val keyword = searchQuery.trim()
        if (keyword.isEmpty()) return
        // 只收键盘，不收搜索框：InputField 在 expanded 转 false 时会把输入框清空
        // （见它末尾的 LaunchedEffect(expanded)），词一没搜完就丢了，下一次回车等于搜空串。
        focusManager.clearFocus()
        val names = listOf(GROUP_ORDER_ALL_NAME) + ordered.map(BookshelfGroup::name)
        val match = findGroupOrderMatch(keyword, names)
        if (match < 0) {
            hintMessage = "没有找到名字含「$keyword」的分组。"
            return
        }
        // names 的 0 号是"全部"，而它正好也是列表里的第 0 项，下标可以直接当 item index 用。
        locatedId = if (match == 0) ALL_BOOKSHELF_GROUP_ID else ordered[match - 1].id
        scope.launch { listState.animateScrollToItem(match) }
    }

    val menuEntry = DropdownEntry(
        items = listOf(
            DropdownItem(
                text = "自动排列",
                selected = autoOrder,
                summary = "常用分组靠前",
                onClick = ::toggleAutoOrder,
            ),
        ),
    )

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = Color.Transparent,
        topBar = {
            // 顶栏与搜索框同在一个 BlurredBar 里：模糊区域必须把两者一起盖住，
            // 否则搜索框会浮在一块没模糊的底色上，滚动时下面的行从它旁边穿过去。
            BlurredBar(backdrop = backdrop, style = topBarBlurStyle) {
                Column {
                    SmallTopAppBar(
                        title = "分组序列",
                        color = if (backdrop != null) Color.Transparent else MiuixTheme.colorScheme.surface,
                        navigationIcon = {
                            IconButton(onClick = ::requestBack, minWidth = 42.dp, minHeight = 42.dp) {
                                Icon(
                                    imageVector = MiuixIcons.Back,
                                    contentDescription = "返回书架",
                                    tint = MiuixTheme.colorScheme.onBackground,
                                )
                            }
                        },
                        actions = {
                            WindowIconCascadingDropdownMenu(entry = menuEntry) {
                                Icon(
                                    imageVector = MiuixIcons.Sort,
                                    contentDescription = "排列方式",
                                    tint = if (autoOrder) {
                                        MiuixTheme.colorScheme.primary
                                    } else {
                                        MiuixTheme.colorScheme.onBackground
                                    },
                                )
                            }
                            // 只有真的调过顺序才给"完成"：没改动时它按下去与返回箭头毫无区别，
                            // 常驻在那里只是让人以为这页有什么必须确认的东西。
                            if (dirty) {
                                TextButton(
                                    text = "保存",
                                    onClick = ::saveAndExit,
                                    minHeight = 36.dp,
                                    colors = ButtonDefaults.textButtonColorsPrimary(),
                                    modifier = Modifier.padding(end = 8.dp),
                                )
                            }
                        },
                    )
                    // 用 InputField 而不是 SearchBar：SearchBar 一聚焦就铺开一层覆盖全屏的
                    // 搜索面板（那是它 expanded 时的 content），而这里要的只是一个输入框。
                    InputField(
                        query = searchQuery,
                        onQueryChange = { searchQuery = it },
                        onSearch = { searchGroup() },
                        expanded = searchExpanded,
                        onExpandedChange = { searchExpanded = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 12.dp, end = 12.dp, bottom = 8.dp),
                        label = "搜索分组名，回车定位",
                        leadingIcon = {
                            Box(
                                modifier = Modifier
                                    .padding(start = 8.dp, end = 4.dp)
                                    .clip(CircleShape)
                                    .clickable(onClick = ::searchGroup)
                                    .padding(start = 8.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                            ) {
                                Icon(
                                    imageVector = MiuixIcons.Basic.Search,
                                    contentDescription = "定位分组",
                                    tint = MiuixTheme.colorScheme.onSurfaceContainerHigh,
                                )
                            }
                        },
                    )
                }
            }
        },
    ) { pagePadding ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier)
                // 背景必须压在 layerBackdrop 之后：顶栏模糊要的是"这一页自己的像素"，
                // 顺序反了就采到空白，透出下面还活着的书架——那正是"二级界面还是透明的"。
                .background(MiuixTheme.colorScheme.surface),
            contentPadding = PaddingValues(
                start = 12.dp,
                end = 12.dp,
                top = pagePadding.calculateTopPadding() + 8.dp,
                bottom = pagePadding.calculateBottomPadding() +
                    innerPadding.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(GROUP_ORDER_ROW_GAP),
        ) {
            item(key = GROUP_ORDER_ALL_KEY, contentType = "group-order-row") {
                BookshelfGroupOrderRow(
                    order = 1,
                    title = GROUP_ORDER_ALL_NAME,
                    subtitle = groupOrderRowSubtitle(counts[ALL_BOOKSHELF_GROUP_ID], "固定在第一位"),
                    dragging = false,
                    onClick = { locateGroup(ALL_BOOKSHELF_GROUP_ID) },
                    located = locatedId == ALL_BOOKSHELF_GROUP_ID,
                ) {
                    Box(
                        modifier = Modifier.width(GROUP_ORDER_HANDLE_WIDTH).fillMaxHeight(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = MiuixIcons.Lock,
                            contentDescription = "不参与排序",
                            tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
            itemsIndexed(
                items = ordered,
                key = { _, group -> group.id },
                contentType = { _, _ -> "group-order-row" },
            ) { index, group ->
                val dragging = draggingId == group.id
                BookshelfGroupOrderRow(
                    order = index + 2,
                    title = group.name,
                    subtitle = groupOrderRowSubtitle(counts[group.id], null),
                    dragging = dragging,
                    onClick = { locateGroup(group.id) },
                    located = locatedId == group.id,
                    modifier = Modifier
                        .zIndex(if (dragging) 1f else 0f)
                        .then(if (dragging) Modifier else Modifier.animateItem())
                        .graphicsLayer {
                            if (!dragging) return@graphicsLayer
                            translationY = dragOffset
                            shape = RoundedCornerShape(GROUP_ORDER_ROW_CORNER)
                            clip = false
                            shadowElevation = GROUP_ORDER_DRAG_ELEVATION.toPx()
                        },
                ) {
                    Box(
                        modifier = Modifier
                            .width(GROUP_ORDER_HANDLE_WIDTH)
                            .fillMaxHeight()
                            .pointerInput(group.id, autoOrder) {
                                detectGroupOrderHandleDrag(
                                    blocked = { autoOrder },
                                    onBlockedTap = {
                                        hintMessage = "关闭「自动排列」后才能手动调整顺序。"
                                    },
                                    onStart = { beginDragging(group.id) },
                                    onDrag = { delta -> onHandleDrag(group.id, delta) },
                                    onEnd = {
                                        // 只在真的换过位置时给收尾反馈：误碰手柄时"按一下响两声"最像出错。
                                        if (draggingId == group.id && dragChanged.value) {
                                            haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                                        }
                                        stopDragging()
                                    },
                                )
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        GroupOrderDragHandleLines(
                            tint = if (autoOrder) {
                                MiuixTheme.colorScheme.onSurfaceVariantSummary
                            } else {
                                MiuixTheme.colorScheme.onSurfaceVariantActions
                            },
                        )
                    }
                }
            }
            item(key = GROUP_ORDER_HINT_KEY) {
                Text(
                    text = if (autoOrder) {
                        "自动排列已开启：常用分组自动靠前，手动顺序已保留。"
                    } else {
                        "单击一行进入该分组 · 按住右侧手柄上下拖动可换位置"
                    },
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                )
            }
        }
    }

    WindowDialog(
        show = showDiscardDialog,
        title = "保存顺序调整？",
        summary = "刚才拖动的新顺序还没有保存。",
        onDismissRequest = { showDiscardDialog = false },
    ) {
        TextButton(
            text = "保存",
            onClick = {
                showDiscardDialog = false
                saveAndExit()
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.textButtonColorsPrimary(),
        )
        Spacer(modifier = Modifier.height(8.dp))
        TextButton(
            text = "不保存",
            onClick = {
                showDiscardDialog = false
                onBack()
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }

    WindowDialog(
        show = hintMessage != null,
        title = "分组序列",
        summary = hintMessage,
        onDismissRequest = { hintMessage = null },
    ) {
        TextButton(
            text = "知道了",
            onClick = { hintMessage = null },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * 一行：左边序号、中间名字与漫画数、右边 [handle]。
 *
 * 只有序号与文字那块可点（回书架定位），手柄自带手势、不在可点区域里，
 * 两者从布局上就分开了，不存在"想拖却触发了跳转"。
 *
 * [located] 是"刚被搜索命中"的短暂状态，只加一圈描边、不改底色：整行换成一种强调色
 * 会让人以为这一行处在某种被选中的状态，而它其实只是刚刚被滚到眼前。
 * "全部"那行同理靠内容（锁图标 + "固定在第一位"）区分，不靠底色——
 * 之前给它涂 tertiaryContainer，深色下就是一块突兀的浅蓝。
 */
@Composable
private fun BookshelfGroupOrderRow(
    order: Int,
    title: String,
    subtitle: String?,
    dragging: Boolean,
    onClick: () -> Unit,
    located: Boolean,
    modifier: Modifier = Modifier,
    handle: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth().height(GROUP_ORDER_ROW_HEIGHT),
        shape = RoundedCornerShape(GROUP_ORDER_ROW_CORNER),
        color = if (dragging) {
            MiuixTheme.colorScheme.primaryContainer
        } else {
            MiuixTheme.colorScheme.surfaceContainerHigh
        },
        border = if (located) {
            BorderStroke(GROUP_ORDER_LOCATED_BORDER, MiuixTheme.colorScheme.primary)
        } else {
            null
        },
    ) {
        Row(modifier = Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clickable(onClick = onClick)
                    .padding(start = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier.width(GROUP_ORDER_INDEX_WIDTH),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = order.toString(),
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
                Column(modifier = Modifier.weight(1f).padding(end = 6.dp)) {
                    Text(
                        text = title,
                        style = MiuixTheme.textStyles.body1,
                        color = if (dragging) {
                            MiuixTheme.colorScheme.onPrimaryContainer
                        } else {
                            MiuixTheme.colorScheme.onSurface
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    subtitle?.let {
                        Text(
                            text = it,
                            style = MiuixTheme.textStyles.footnote2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            handle()
        }
    }
}

/**
 * 三条横线的拖动手柄。
 *
 * miuix 0.9.4 里没有这个图形：basic 只有箭头、勾、叉、搜索几个，extended 里最接近的
 * HorizontalSplit 画的是两块面板。所以手搓三个圆角条——比原先那个上下箭头更像"能抓起来拖"，
 * 上下箭头容易被读成"点一下会展开/折叠"。
 */
@Composable
private fun GroupOrderDragHandleLines(tint: Color) {
    Column(
        verticalArrangement = Arrangement.spacedBy(GROUP_ORDER_HANDLE_LINE_GAP),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        repeat(GROUP_ORDER_HANDLE_LINE_COUNT) {
            Box(
                modifier = Modifier
                    .width(GROUP_ORDER_HANDLE_LINE_WIDTH)
                    .height(GROUP_ORDER_HANDLE_LINE_HEIGHT)
                    .clip(CircleShape)
                    .background(tint),
            )
        }
    }
}

/**
 * 手柄手势：短暂长按后才进入拖动。
 *
 * 触发前不消费事件，避免轻触手柄就锁住列表；系统长按确认后才消费后续位移，
 * 这样既保留列表的自然滚动，也避免误触进入拖动状态。
 *
 * [blocked] 为真（自动排列开着）时一个事件都不吃：手柄这块照旧能滚列表，
 * 只有真的点了一下才弹一句说明。
 */
private suspend fun PointerInputScope.detectGroupOrderHandleDrag(
    blocked: () -> Boolean,
    onBlockedTap: () -> Unit,
    onStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onEnd: () -> Unit,
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        if (blocked()) {
            if (waitForUpOrCancellation() != null) onBlockedTap()
            return@awaitEachGesture
        }
        val longPress = withTimeoutOrNull(GROUP_ORDER_DRAG_HOLD_MILLIS) {
            awaitLongPressOrCancellation(down.id)
        } ?: return@awaitEachGesture
        longPress.consume()
        onStart()
        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            val delta = change.positionChange().y
            change.consume()
            if (!change.pressed) break
            if (delta != 0f) onDrag(delta)
        }
        onEnd()
    }
}

/** 行的副标题：有数量就报数量，[extra] 是给"全部"那行补的一句说明。 */
internal fun groupOrderRowSubtitle(count: Int?, extra: String?): String? {
    val parts = listOfNotNull(count?.let { "$it 部" }, extra)
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

/**
 * 搜索词落在 [names] 的哪一项：完全相同 > 从头开头 > 中间含有，一个都不中返回 -1。
 *
 * 分三档而不是直接取第一个含有的：分组名常有"日常"和"日常向漫画"这种前缀关系，
 * 输"日常"要的一定是那个正好叫「日常」的，而不是碰巧排在它前面的那个长名字。
 * 大小写与首尾空格一律忽略——分组名多半是从别处复制来的，尾部带个空格很常见。
 */
internal fun findGroupOrderMatch(query: String, names: List<String>): Int {
    val needle = query.trim().lowercase()
    if (needle.isEmpty()) return -1
    val normalized = names.map { it.trim().lowercase() }
    val exact = normalized.indexOfFirst { it == needle }
    if (exact >= 0) return exact
    val prefix = normalized.indexOfFirst { it.startsWith(needle) }
    if (prefix >= 0) return prefix
    return normalized.indexOfFirst { it.contains(needle) }
}

/**
 * 拖动位移落到哪个下标：行高一致，所以是精确的一道除法，越过半行才算换格。
 *
 * 越界一律夹回可用区间——往上拖过头不能顶掉锁定的"全部"，往下拖过头也只能停在最后一行。
 */
internal fun groupDragTargetIndex(
    fromIndex: Int,
    dragPx: Float,
    rowStepPx: Float,
    itemCount: Int,
): Int {
    if (itemCount <= 0 || fromIndex < 0 || fromIndex >= itemCount) return fromIndex
    if (!dragPx.isFinite() || !rowStepPx.isFinite() || rowStepPx <= 0f) return fromIndex
    val steps = (dragPx / rowStepPx).roundToInt()
    return (fromIndex + steps).coerceIn(0, itemCount - 1)
}

/**
 * 拖到上下边缘时每帧滚多少：往上是负值。
 *
 * 视口本身没边缘区那么高时不滚——否则列表中间也落在"边缘"里，一按手柄就自己跑。
 */
internal fun groupOrderAutoScrollVelocity(
    pointerY: Float,
    viewportStart: Float,
    viewportEnd: Float,
    edgePx: Float,
    stepPx: Float,
): Float {
    if (!pointerY.isFinite() || edgePx <= 0f || stepPx <= 0f) return 0f
    if (viewportEnd - viewportStart <= edgePx * 2f) return 0f
    return when {
        pointerY < viewportStart + edgePx -> -stepPx
        pointerY > viewportEnd - edgePx -> stepPx
        else -> 0f
    }
}

private const val GROUP_ORDER_ALL_KEY = "group-order-all"
private const val GROUP_ORDER_HINT_KEY = "group-order-hint"

/** "全部"那一行的名字：搜索时它也要参与匹配，所以不能只写在 UI 里。 */
private const val GROUP_ORDER_ALL_NAME = "全部"

/** 搜索命中后描边留多久。够看清是哪一行，又不至于让人以为这是个需要点掉的选中态。 */
private const val GROUP_ORDER_LOCATE_HIGHLIGHT_MILLIS = 1800L

/** 拖起来那一行的阴影，比卡片高一档就够，再高会盖住相邻行的圆角。 */
private val GROUP_ORDER_DRAG_ELEVATION = 6.dp

/** 搜索命中那一行的描边。1dp 在深色下几乎看不见，2dp 又太像输入框的聚焦态。 */
private val GROUP_ORDER_LOCATED_BORDER = 1.5.dp

private val GROUP_ORDER_ROW_HEIGHT = 64.dp
private val GROUP_ORDER_ROW_GAP = 8.dp
private val GROUP_ORDER_ROW_CORNER = 14.dp
private val GROUP_ORDER_INDEX_WIDTH = 32.dp

/** 手柄的触点宽度。三条横线只有 18dp 宽，触点必须自己撑够，不然按不准。 */
private val GROUP_ORDER_HANDLE_WIDTH = 46.dp

private const val GROUP_ORDER_HANDLE_LINE_COUNT = 3
private val GROUP_ORDER_HANDLE_LINE_WIDTH = 18.dp
private val GROUP_ORDER_HANDLE_LINE_HEIGHT = 2.dp
private val GROUP_ORDER_HANDLE_LINE_GAP = 4.dp

/** 专用手柄不需要系统完整长按时长；短暂确认足以过滤误触，也不会让拖动显得迟钝。 */
private const val GROUP_ORDER_DRAG_HOLD_MILLIS = 260L

/** 拖到距上下边缘多近开始自动滚动。 */
private val GROUP_ORDER_AUTOSCROLL_EDGE = 72.dp

/** 自动滚动每帧的位移；再快就会滑过用户想放的那一行。 */
private val GROUP_ORDER_AUTOSCROLL_STEP = 8.dp

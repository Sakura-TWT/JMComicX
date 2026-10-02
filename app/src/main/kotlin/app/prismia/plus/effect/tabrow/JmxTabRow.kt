// 本文件基于 MIUIX 的 TabRow 实现改写而来（vendored，非调用库 API），保留原始版权声明：
//
// Copyright 2025, compose-miuix-ui contributors
// SPDX-License-Identifier: Apache-2.0
//
// 源文件：miuix-ui/src/commonMain/kotlin/top/yukonga/miuix/kmp/basic/TabRow.kt
//
// 形状沿用上游 TabRow 的官方默认值（TabRowCornerRadius = 12dp 的 squircle、body1 字号、
// 12dp 内边距），不再做全圆角胶囊。与上游的差异只在交互与可读性：
// 1. 上游用 LazyRow + 等宽 tab（calculateTabWidth 把可用宽度均分并夹在 min/max 之间），
//    这里改为 Row + horizontalScroll 的变宽 tab：中文分类标题长短差异大，等宽会让
//    "最新"留白过多而"本本推荐"被截断；书架分组名由用户自定义，更不能截断。
// 2. 上游把指示器放在 LazyRow 外面，因此必须用 derivedStateOf 反推 scrollOffset
//    再做偏移补偿；这里指示器与 tab 同处滚动内容内部，偏移量直接就是内容坐标，无需补偿。
// 3. [selectionProgress]：指示器可直接跟随 Pager 的连续位置（currentPage + offsetFraction），
//    手指还在滑动时指示器与标签行滚动就已经同步移动，而不是等 settle 后再补一段 200ms 动画
//    （那是"切换很慢、有间隔、很割裂"的根因）。未提供时回落到上游的离散 tween 动画。
// 4. 指示器几何（偏移与宽度）都在 layout / placement 阶段读取，滑动期间不触发重组。
// 5. 标签字重恒定（上游选中项加粗）：变宽 tab 下加粗会改变实测宽度，连续插值的指示器
//    会在越过中点的瞬间抖一下，因此选中态只用颜色与色块区分。
// 6. 默认给出真实按压反馈（MIUIX SinkFeedback），上游默认 indication = null 即无反馈。
// 7. 上游整条 TabRow 铺一层不透明的 surface 轨道（background(colors.backgroundColor(false))），
//    未选中标签的可读性是由这层轨道保证的。我们把标签放进半透明模糊顶栏里，不能再铺整条
//    轨道（会变成一条突兀的色带），所以把这份保证下沉到每个标签自己的半透明填充上：
//    填充随色块覆盖度淡出，避免盖住滑过来的强调色块。
//
// 保留自上游的交互机制：selectable(role = Role.Tab) 语义、collectionInfo /
// collectionItemInfo 无障碍朗读、tween(200, LinearEasing) 指示器动画、
// 以 lastSettledSelectedTabIndex 判定"首次落位 snap、之后 animate"的滚动居中策略。
package app.prismia.plus.effect.tabrow

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Indication
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CollectionInfo
import androidx.compose.ui.semantics.CollectionItemInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.collectionInfo
import androidx.compose.ui.semantics.collectionItemInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastRoundToInt
import androidx.compose.ui.util.lerp
import androidx.compose.ui.graphics.lerp as lerpColor
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.math.abs
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.squircle.squircleBackground
import top.yukonga.miuix.kmp.squircle.squircleBorder
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.SinkFeedback

/**
 * 变宽标签行：未选中项是一层半透明填充 + 描边；选中项由一块会滑动的强调色块覆盖。
 *
 * @param tabs 标签文本。
 * @param selectedTabIndex 当前选中下标（无障碍选中态、以及未提供 [selectionProgress] 时的动画目标）。
 * @param onTabSelected 选中回调。
 * @param modifier 施加在固定高度之外的 modifier，可用于加外边距或 graphicsLayer。
 * @param colors 颜色配置。
 * @param height 标签高度（不含 [modifier] 里的外边距）。
 * @param cornerRadius 标签圆角，默认 MIUIX 官方 TabRow 的 12dp。
 * @param itemSpacing 标签间距。
 * @param contentPadding 滚动内容首尾内衬，随内容一起滚动。
 * @param minItemWidth 单个标签最小宽度，避免"熱"这类单字标签过窄。
 * @param badgedTabs 需要在右上角画红点的标签下标集合，用于"这一组里有内容更新了"之类的提示。
 * @param selectionProgress 连续选中位置，例如 `{ pagerState.currentPage + pagerState.currentPageOffsetFraction }`。
 *   提供时指示器与标签行滚动都实时跟随该值（跟手），不再等分页 settle；为 null 时按 [selectedTabIndex] 做离散动画。
 * @param scrollState 横向滚动状态，传入可外部控制。
 * @param interactionSource 交互源。
 * @param indication 按压反馈，默认 MIUIX [SinkFeedback]（按下轻微下沉）。
 * @param flingBehavior 滑动惯性行为。
 */
@Composable
fun JmxTabRow(
    tabs: List<String>,
    selectedTabIndex: Int,
    onTabSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
    colors: JmxTabRowColors = JmxTabRowDefaults.tabRowColors(),
    height: Dp = JmxTabRowDefaults.TabHeight,
    cornerRadius: Dp = JmxTabRowDefaults.CornerRadius,
    itemSpacing: Dp = JmxTabRowDefaults.ItemSpacing,
    contentPadding: Dp = JmxTabRowDefaults.ContentPadding,
    minItemWidth: Dp = JmxTabRowDefaults.MinItemWidth,
    badgedTabs: Set<Int> = emptySet(),
    selectionProgress: (() -> Float)? = null,
    scrollState: ScrollState = rememberScrollState(),
    interactionSource: MutableInteractionSource? = null,
    indication: Indication? = JmxTabRowDefaults.Indication,
    flingBehavior: FlingBehavior? = null,
) {
    if (tabs.isEmpty()) return
    val currentOnTabSelected by rememberUpdatedState(onTabSelected)
    val density = LocalDensity.current
    val spacingPx = with(density) { itemSpacing.toPx() }
    val contentPaddingPx = with(density) { contentPadding.toPx() }
    val selected = selectedTabIndex.coerceIn(tabs.indices)
    val lastIndex = tabs.lastIndex

    // 每个标签的实测宽度（px）。tabs 内容变化时重置，长度始终与 tabs 对齐。
    val itemWidths = remember(tabs) { mutableStateListOf<Int>().apply { repeat(tabs.size) { add(0) } } }
    // 内容坐标下每个标签的起始偏移（不含 contentPadding，那部分由 padding 提供）。
    val itemOffsets by remember(tabs, spacingPx) {
        derivedStateOf {
            var accumulated = 0f
            List(itemWidths.size) { index ->
                val offset = accumulated
                accumulated += itemWidths[index] + spacingPx
                offset
            }
        }
    }
    // 首帧宽度全为 0，此时不能落指示器，否则会先画一条 0 宽色块再弹开。
    val measured = (itemWidths.getOrNull(selected) ?: 0) > 0

    // 文字/描边配色跟随色块的覆盖程度，而不是在越过半页的瞬间硬切：色块只盖住半个标签时
    // 就把文字翻成 onPrimary，会有一两帧白字压在浅色玻璃上完全看不清。
    // 量化到 1/[EMPHASIS_STEPS] 再读，避免每帧都为了一点颜色变化重组整行。
    // 无跟手进度时用与指示器同时长的动画驱动，配色仍与色块位置同步而非提前翻色。
    val fallbackProgress by animateFloatAsState(
        targetValue = selected.toFloat(),
        animationSpec = tween(INDICATOR_DURATION_MILLIS, easing = LinearEasing),
        label = "JmxTabRowEmphasis",
    )
    val emphasisProgress by remember(selectionProgress, selected) {
        derivedStateOf {
            val raw = selectionProgress?.invoke() ?: fallbackProgress
            (raw * EMPHASIS_STEPS).fastRoundToInt() / EMPHASIS_STEPS.toFloat()
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .then(modifier)
            .height(height),
    ) {
        val viewportWidthPx = with(density) { this@BoxWithConstraints.maxWidth.toPx() }
        val indicatorOffset = remember { Animatable(0f) }
        val indicatorWidth = remember { Animatable(0f) }
        // < 0 表示尚未落位过：首次（含旋屏、首帧测量完成）直接 snap，之后才做动画，
        // 否则每次重进页面都会看到指示器从最左侧滑过来。沿用上游的判定方式。
        var lastSettledSelectedTabIndex by remember(tabs) { mutableIntStateOf(-1) }

        if (selectionProgress == null) {
            LaunchedEffect(selected, measured, itemOffsets) {
                if (!measured) return@LaunchedEffect
                val targetOffset = itemOffsets.getOrElse(selected) { 0f }
                val targetWidth = itemWidths[selected].toFloat()
                if (lastSettledSelectedTabIndex < 0) {
                    indicatorOffset.snapTo(targetOffset)
                    indicatorWidth.snapTo(targetWidth)
                } else {
                    indicatorOffset.animateTo(targetOffset, tween(INDICATOR_DURATION_MILLIS, easing = LinearEasing))
                    indicatorWidth.animateTo(targetWidth, tween(INDICATOR_DURATION_MILLIS, easing = LinearEasing))
                }
            }
        }

        // 选中项自动滚入视野并尽量居中；上游用 LazyListState.scrollToItem，这里按像素滚动。
        if (selectionProgress != null) {
            // 跟手模式：滚动位置也由同一个连续进度驱动，标签行与分页同步平移，不做二次动画。
            LaunchedEffect(measured, viewportWidthPx, contentPaddingPx, lastIndex) {
                if (!measured) return@LaunchedEffect
                snapshotFlow {
                    val raw = selectionProgress().coerceIn(0f, lastIndex.toFloat())
                    val low = raw.toInt().coerceIn(0, lastIndex)
                    val high = (low + 1).coerceAtMost(lastIndex)
                    val itemCenter = lerp(
                        itemOffsets[low] + itemWidths[low] / 2f,
                        itemOffsets[high] + itemWidths[high] / 2f,
                        raw - low,
                    ) + contentPaddingPx
                    (itemCenter - viewportWidthPx / 2f).fastRoundToInt().coerceIn(0, scrollState.maxValue)
                }
                    .distinctUntilChanged()
                    .collect { target -> scrollState.scrollTo(target) }
            }
        } else {
            // 故意不把 scrollState.maxValue / itemOffsets 作为 key：它们在协程内读取即可拿到最新值，
            // 作为 key 会让任何布局抖动都把用户手动滚动的位置拽回去。
            LaunchedEffect(selected, measured, viewportWidthPx) {
                if (!measured) return@LaunchedEffect
                val itemCenter = itemOffsets.getOrElse(selected) { 0f } + itemWidths[selected] / 2f + contentPaddingPx
                val target = (itemCenter - viewportWidthPx / 2f)
                    .fastRoundToInt()
                    .coerceIn(0, scrollState.maxValue)
                if (lastSettledSelectedTabIndex < 0) {
                    scrollState.scrollTo(target)
                } else {
                    scrollState.animateScrollTo(target)
                }
                lastSettledSelectedTabIndex = selected
            }
        }

        Box(
            modifier = Modifier
                .fillMaxHeight()
                .horizontalScroll(scrollState, flingBehavior = flingBehavior)
                // 内衬放在滚动内容内部，首尾留白因此随内容一起滚动。
                .padding(horizontal = contentPadding),
        ) {
            // 指示器与标签同在滚动内容内，偏移量直接是内容坐标，不需要减去滚动量。
            if (measured) {
                Box(
                    modifier = Modifier
                        // 在 layout / placement 阶段读取几何：滑动过程中不触发重组。
                        .layout { measurable, constraints ->
                            val offsetPx: Float
                            val widthPx: Float
                            if (selectionProgress != null) {
                                // 进度落在两个标签之间时，偏移与宽度都按比例插值出中间态，
                                // 于是宽窄不一的标签之间也能平滑过渡。
                                val raw = selectionProgress().coerceIn(0f, lastIndex.toFloat())
                                val low = raw.toInt().coerceIn(0, lastIndex)
                                val high = (low + 1).coerceAtMost(lastIndex)
                                val lowWidth = itemWidths[low].toFloat()
                                val highWidth = itemWidths[high].toFloat().takeIf { it > 0f } ?: lowWidth
                                offsetPx = lerp(itemOffsets[low], itemOffsets[high], raw - low)
                                widthPx = lerp(lowWidth, highWidth, raw - low)
                            } else {
                                offsetPx = indicatorOffset.value
                                widthPx = indicatorWidth.value
                            }
                            val width = widthPx.fastRoundToInt().coerceAtLeast(0)
                            val placeable = measurable.measure(
                                constraints.copy(minWidth = width, maxWidth = width),
                            )
                            layout(placeable.width, placeable.height) {
                                placeable.place(offsetPx.fastRoundToInt(), 0)
                            }
                        }
                        .fillMaxHeight()
                        .squircleBackground(
                            color = colors.indicatorColor(),
                            cornerRadius = cornerRadius,
                        ),
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxHeight()
                    // 明确朗读为"第 X 项，共 Y 项"。
                    .semantics { collectionInfo = CollectionInfo(rowCount = 1, columnCount = tabs.size) },
                horizontalArrangement = Arrangement.spacedBy(itemSpacing),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                tabs.forEachIndexed { index, tabText ->
                    // 1 表示色块完全盖住本项，0 表示完全没盖到。
                    val emphasis = (1f - abs(emphasisProgress - index)).coerceIn(0f, 1f)
                    JmxTabItem(
                        text = tabText,
                        index = index,
                        isSelected = index == selected,
                        onClick = { currentOnTabSelected(index) },
                        cornerRadius = cornerRadius,
                        minWidth = minItemWidth,
                        containerColor = colors.containerColor(emphasis),
                        outlineColor = colors.outlineColor(emphasis),
                        color = colors.contentColor(emphasis),
                        hasBadge = index in badgedTabs,
                        interactionSource = interactionSource,
                        indication = indication,
                        onWidthMeasured = { width ->
                            if (index < itemWidths.size && itemWidths[index] != width) itemWidths[index] = width
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun JmxTabItem(
    text: String,
    index: Int,
    isSelected: Boolean,
    onClick: () -> Unit,
    cornerRadius: Dp,
    minWidth: Dp,
    containerColor: Color,
    outlineColor: Color,
    color: Color,
    hasBadge: Boolean,
    interactionSource: MutableInteractionSource?,
    indication: Indication?,
    onWidthMeasured: (Int) -> Unit,
) {
    // 红点用 drawWithContent 画在标签之上，而不是塞进内容里：标签宽度是实测的，
    // 多一个子元素就会把宽度撑开，指示器的跟手插值随之跑偏。
    val badgeColor = MiuixTheme.colorScheme.error
    Box(
        modifier = Modifier
            .fillMaxHeight()
            // 只抬高下限、不设上限：标签按文字自适应变宽。
            .widthIn(min = minWidth)
            .onSizeChanged { onWidthMeasured(it.width) }
            // 红点画在这里而不是内容里：标签宽度是实测的，多塞一个子元素就会把宽度撑开，
            // 指示器的跟手插值随之跑偏。放在 padding 之前才能拿到标签的完整尺寸。
            .then(
                if (hasBadge) {
                    Modifier.drawWithContent {
                        drawContent()
                        val radius = TAB_BADGE_RADIUS.toPx()
                        drawCircle(
                            color = badgeColor,
                            radius = radius,
                            center = Offset(
                                x = size.width - radius - TAB_BADGE_INSET.toPx(),
                                y = radius + TAB_BADGE_INSET.toPx(),
                            ),
                        )
                    }
                } else {
                    Modifier
                },
            )
            // 半透明填充：顶栏是模糊层，标签背后就是滚动的漫画封面，只靠模糊无法保证
            // 文字对比度（封面亮色时未选中标签的文字与描边基本看不见）。
            // 随色块覆盖度淡出到全透明，否则会把滑过来的强调色块盖成一片脏白。
            .squircleBackground(color = containerColor, cornerRadius = cornerRadius)
            // 描边宽度恒定，靠 [outlineColor] 的透明度随色块覆盖比例淡出：
            // 改宽度会让 squircleBorder 在过渡中途出现半像素的粗细跳变。
            .squircleBorder(
                width = { 1.dp },
                color = { outlineColor },
                cornerRadius = cornerRadius,
            )
            .selectable(
                selected = isSelected,
                onClick = onClick,
                role = Role.Tab,
                interactionSource = interactionSource,
                indication = indication,
            )
            .semantics {
                collectionItemInfo = CollectionItemInfo(
                    rowIndex = 0,
                    rowSpan = 1,
                    columnIndex = index,
                    columnSpan = 1,
                )
            }
            .padding(horizontal = JmxTabRowDefaults.ItemHorizontalPadding),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = color,
            // 字重恒定：变宽标签下"选中加粗"会改变实测宽度，跟手插值的指示器会在中点抖动。
            fontWeight = JmxTabRowDefaults.LabelWeight,
            fontSize = MiuixTheme.textStyles.body1.fontSize,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

object JmxTabRowDefaults {
    /** 标签高度（上游 42dp，顶栏里略收紧给标题让空间）。 */
    val TabHeight = 40.dp

    /** 标签圆角，取上游 TabRowDefaults.TabRowCornerRadius。 */
    val CornerRadius = 12.dp

    /** 标签间距，取上游 TabRow 的 itemSpacing 默认值。 */
    val ItemSpacing = 9.dp

    /** 滚动内容首尾内衬。 */
    val ContentPadding = 12.dp

    /** 单个标签最小宽度。 */
    val MinItemWidth = 64.dp

    /** 标签内部左右留白，取上游 TabItem 的 12dp。 */
    val ItemHorizontalPadding = 12.dp

    /** 标签字重：选中与未选中一致，见 [JmxTabItem] 注释。 */
    val LabelWeight = FontWeight.Medium

    /** 默认按压反馈：MIUIX 的下沉效果，比涟漪更贴近 HyperOS 手感。 */
    val Indication: Indication = SinkFeedback(sinkAmount = 0.92f)

    /**
     * 顶栏是半透明模糊层，标签直接压在滚动的封面上，因此配色不能再用低透明度的中性色：
     * 未选中项给一层 [surfaceContainer] 的半透明填充（对应上游整条 TabRow 的不透明轨道），
     * 文字用 [onSurfaceSecondary]（0.8 alpha）而非更淡的 summary 色；
     * 选中项用不透明的强调色块 + [onPrimary] 文字（与底部悬浮导航栏的选中色一致）。
     */
    @Composable
    fun tabRowColors(
        containerColor: Color = MiuixTheme.colorScheme.surfaceContainer.copy(alpha = 0.72f),
        contentColor: Color = MiuixTheme.colorScheme.onSurfaceSecondary,
        selectedContentColor: Color = MiuixTheme.colorScheme.onPrimary,
        indicatorColor: Color = MiuixTheme.colorScheme.primary,
        outlineColor: Color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.28f),
    ): JmxTabRowColors = remember(
        containerColor,
        contentColor,
        selectedContentColor,
        indicatorColor,
        outlineColor,
    ) {
        JmxTabRowColors(
            containerColor = containerColor,
            contentColor = contentColor,
            selectedContentColor = selectedContentColor,
            indicatorColor = indicatorColor,
            outlineColor = outlineColor,
        )
    }
}

@Immutable
data class JmxTabRowColors(
    private val containerColor: Color,
    private val contentColor: Color,
    private val selectedContentColor: Color,
    private val indicatorColor: Color,
    private val outlineColor: Color,
) {
    /**
     * @param emphasis 强调色块对该标签的覆盖比例：0=完全未覆盖，1=完全覆盖。
     *   随覆盖比例插值而不是在越过一半时硬切，色块盖住半个标签时文字仍是可读的中间色。
     */
    @Stable
    internal fun contentColor(emphasis: Float): Color = when {
        emphasis <= 0f -> contentColor
        emphasis >= 1f -> selectedContentColor
        else -> lerpColor(contentColor, selectedContentColor, emphasis)
    }

    @Stable
    internal fun indicatorColor(): Color = indicatorColor

    /** 未选中填充随覆盖比例淡出，让位给画在它下面的强调色块。 */
    @Stable
    internal fun containerColor(emphasis: Float): Color = when {
        emphasis <= 0f -> containerColor
        emphasis >= 1f -> Color.Transparent
        else -> containerColor.copy(alpha = containerColor.alpha * (1f - emphasis))
    }

    /** 描边随覆盖比例淡出：色块盖上来之后不需要边框，否则会在色块边缘描出一圈脏边。 */
    @Stable
    internal fun outlineColor(emphasis: Float): Color = when {
        emphasis <= 0f -> outlineColor
        emphasis >= 1f -> Color.Transparent
        else -> outlineColor.copy(alpha = outlineColor.alpha * (1f - emphasis))
    }
}

/**
 * 覆盖比例的量化级数：配色按 1/8 的台阶变化，一次翻页只重组 8 次而不是每帧一次，
 * 肉眼看不出台阶（相邻两级的色差远小于可分辨阈值）。
 */
private const val EMPHASIS_STEPS = 8

private const val INDICATOR_DURATION_MILLIS = 200

/** 标签红点半径。对齐 MIUIX [top.yukonga.miuix.kmp.basic.BadgeDefaults] 的 6dp 直径。 */
private val TAB_BADGE_RADIUS = 3.dp

/** 红点距标签右上角的内缩量，让圆点完整落在圆角之内。 */
private val TAB_BADGE_INSET = 6.dp

package app.prismia.plus.effect

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.dp
import app.prismia.plus.effect.tabrow.JmxTabRow
import top.yukonga.miuix.kmp.basic.FloatingNavigationBar
import top.yukonga.miuix.kmp.basic.FloatingToolbarDefaults
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurColors
import top.yukonga.miuix.kmp.blur.BlurDefaults
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.ProgressiveBlur
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.blur.progressiveTextureBlur
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.blur.textureBlur
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 顶栏模糊样式：GAUSSIAN=高斯模糊，PROGRESSIVE=渐进模糊 */
enum class TopBarBlurStyle {
    GAUSSIAN,
    PROGRESSIVE;

    fun label(): String = when (this) {
        GAUSSIAN -> "高斯模糊"
        PROGRESSIVE -> "渐进模糊"
    }

    companion object {
        fun fromName(name: String?): TopBarBlurStyle =
            if (name == PROGRESSIVE.name) PROGRESSIVE else GAUSSIAN
    }
}

/** 悬浮底栏样式：DEFAULT=MIUIX 官方磨砂悬浮栏，IOS_LIKE=iOS 液态玻璃悬浮栏 */
enum class FloatingNavBarStyle {
    DEFAULT,
    IOS_LIKE;

    fun label(): String = when (this) {
        DEFAULT -> "MIUIX"
        IOS_LIKE -> "iOS-like"
    }

    companion object {
        fun fromName(name: String?): FloatingNavBarStyle =
            if (name == IOS_LIKE.name) IOS_LIKE else DEFAULT
    }
}

/**
 * 页面级共享的采样背景层：内容侧用 [Modifier.layerBackdrop] 挂载，
 * 顶栏/底栏在各自位置以 textureBlur / progressiveTextureBlur 采样。
 * 设备不支持 RuntimeShader 时返回 null，调用方自动退化为普通表面。
 */
@Composable
fun rememberBarBackdrop(): LayerBackdrop? {
    if (!isRuntimeShaderSupported()) return null
    val surfaceColor = MiuixTheme.colorScheme.surface
    return rememberLayerBackdrop {
        drawRect(surfaceColor)
        drawContent()
    }
}

/**
 * 顶栏模糊容器（对照 miuix example 的 BlurredBar 官方实现）：
 * - 高斯模糊：整条 textureBlur，半径 30f，表面色 0.82 透明度混合
 * - 渐进模糊：progressiveTextureBlur，顶部向下渐隐（曲线 2.2f），半径 26f，表面色 0.5，
 *   并叠一层同样自上而下渐隐到全透明的表面色遮罩来兜住对比度
 * backdrop 为 null 时不施加任何效果。
 */
@Composable
fun BlurredBar(
    backdrop: LayerBackdrop?,
    style: TopBarBlurStyle,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val blurActive = backdrop != null
    Box(
        modifier = when {
            !blurActive -> Modifier
            style == TopBarBlurStyle.GAUSSIAN -> Modifier.textureBlur(
                backdrop = backdrop,
                shape = RectangleShape,
                blurRadius = 30f,
                colors = barBlurColors(progressive = false),
            )
            else -> Modifier
        }.then(modifier),
    ) {
        if (blurActive && style == TopBarBlurStyle.PROGRESSIVE) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .progressiveTextureBlur(
                        backdrop = backdrop,
                        shape = RectangleShape,
                        gradient = ProgressiveBlur.Top.copy(curve = 2.2f),
                        blurRadius = 26f,
                        colors = barBlurColors(progressive = true),
                    )
                    // 渐进模糊在底边模糊量归零，只靠模糊无法保证文字对比度（滚过深色封面时
                    // 标题与标签基本不可读）。这里再叠一层自上而下渐隐到全透明的表面色遮罩：
                    // 顶部足够实、底边为 0 不产生硬边，避免又出现"突兀的色彩块"。
                    .background(progressiveScrimBrush()),
            )
        }
        content()
    }
}

@Composable
private fun progressiveScrimBrush(): Brush {
    val surface = MiuixTheme.colorScheme.surface
    // 标签行位于顶栏底部约 60%~100% 的区间，正好落在渐进模糊衰减到 0 的尾巴上。
    // 因此把遮罩的"实"段往下拉长、尾部保留一点底噪，让标签背后仍有可依托的底色；
    // 最后一站仍收到全透明，避免出现硬边色带。
    return Brush.verticalGradient(
        0.0f to surface.copy(alpha = 0.72f),
        0.62f to surface.copy(alpha = 0.56f),
        0.92f to surface.copy(alpha = 0.24f),
        1.0f to Color.Transparent,
    )
}

@Composable
private fun barBlurColors(progressive: Boolean): BlurColors = BlurDefaults.blurColors(
    blendColors = listOf(
        BlendColorEntry(color = MiuixTheme.colorScheme.surface.copy(if (progressive) 0.5f else 0.82f)),
    ),
)

/**
 * MIUIX 官方悬浮底栏 + 液态玻璃（磨砂）效果，对照 miuix example 的 AppContent.kt 简单版实现。
 * 直接使用官方 [FloatingNavigationBar]，尺寸/圆角/阴影由官方 Defaults 决定（不再本地手写常量）。
 * 开启模糊时把底栏底色设为透明，用 textureBlur 采样内容；backdrop 为 null（不支持 RuntimeShader）
 * 时退化为普通 surfaceContainer 悬浮栏。
 */
@Composable
fun BlurredFloatingNavigationBar(
    backdrop: LayerBackdrop?,
    content: @Composable () -> Unit,
) {
    val blurActive = backdrop != null
    val floatingShape = RoundedCornerShape(FloatingToolbarDefaults.CornerRadius)
    FloatingNavigationBar(
        modifier = if (blurActive) {
            Modifier.textureBlur(
                backdrop = backdrop,
                shape = floatingShape,
                blurRadius = 25f,
                colors = floatingBarBlurColors(),
            )
        } else {
            Modifier
        },
        color = if (blurActive) Color.Transparent else MiuixTheme.colorScheme.surfaceContainer,
        content = content,
    )
}

@Composable
private fun floatingBarBlurColors(): BlurColors = BlurDefaults.blurColors(
    blendColors = listOf(
        BlendColorEntry(color = MiuixTheme.colorScheme.surfaceContainer.copy(0.55f)),
    ),
)

/**
 * 顶栏分类/分组标签行。实现来自本仓库内 vendored 的 MIUIX TabRow
 * （[app.prismia.plus.effect.tabrow.JmxTabRow]），这里只负责放进顶栏 [BlurredBar]
 * 内部与标题连成同一片模糊区域，并给出顶栏专用的外边距。
 *
 * @param selectionProgress 分页器的连续位置，例如
 *   `{ pagerState.currentPage + pagerState.currentPageOffsetFraction }`。
 *   传入后指示器实时跟手，手动滑动分页时标签不再滞后于内容。
 * @param badgedTabs 需要在右上角画红点的标签下标集合。
 */
@Composable
fun BlurTabRow(
    tabs: List<String>,
    selectedIndex: Int,
    onTabSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
    selectionProgress: (() -> Float)? = null,
    badgedTabs: Set<Int> = emptySet(),
) {
    if (tabs.isEmpty()) return
    JmxTabRow(
        tabs = tabs,
        selectedTabIndex = selectedIndex,
        onTabSelected = onTabSelected,
        selectionProgress = selectionProgress,
        badgedTabs = badgedTabs,
        // 标签下面多留一段模糊余量：渐进模糊与遮罩都在顶栏底边衰减到 0，
        // 留白把这段衰减尾巴挪到标签下方，标签自身仍处在有模糊、有遮罩的区间内。
        modifier = modifier.padding(bottom = 14.dp),
    )
}

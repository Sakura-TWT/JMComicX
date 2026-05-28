@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package dev.jmx.client.ui.screens

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material.icons.outlined.Share
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jmx.client.BuildConfig
import dev.jmx.client.R
import dev.jmx.client.store.AppUpdateManager
import dev.jmx.client.store.DiagnosticLogManager
import dev.jmx.client.store.DiagnosticLogSession
import dev.jmx.client.ui.components.CommonScaffold
import dev.jmx.client.ui.glass.GlassPanel
import dev.jmx.client.ui.glass.LocalJmxGlassPalette
import dev.jmx.client.ui.razor.AppleChevron
import dev.jmx.client.ui.razor.RazorGlassButton
import dev.jmx.client.ui.razor.RazorIcon
import dev.jmx.client.ui.razor.RazorText
import kotlinx.coroutines.launch
import org.koin.compose.getKoin

private const val PROJECT_URL = "https://github.com/Sakura-TWT/JMComicX"

@Stable
private data class CreditItem(
    val name: String,
    val source: String,
    val url: String,
    val note: String
)

private val openSourceCredits = listOf(
    CreditItem(
        name = "AndroidLiquidGlass",
        source = "Kyant0/AndroidLiquidGlass",
        url = "https://github.com/Kyant0/AndroidLiquidGlass",
        note = "Liquid Glass visual system and interaction reference."
    ),
    CreditItem(
        name = "jm-mobile",
        source = "Dedicatus546/jm-mobile",
        url = "https://github.com/Dedicatus546/jm-mobile",
        note = "Original third-party JM client architecture and feature foundation."
    ),
    CreditItem(
        name = "jmcomic-api-java",
        source = "JMComic API reference",
        url = "https://jmcomic-api-java.readthedocs.io/zh-cn/latest/",
        note = "API behavior, model and feature reference documentation."
    ),
    CreditItem(
        name = "AndroidX Compose",
        source = "Google AndroidX",
        url = "https://developer.android.com/compose",
        note = "Declarative UI runtime, navigation, paging and graphics foundation."
    ),
    CreditItem(
        name = "Coil / OkHttp / Retrofit / Koin / Room",
        source = "Open-source Android ecosystem",
        url = "https://github.com/coil-kt/coil",
        note = "Image loading, networking, dependency injection and local persistence."
    )
)

@Composable
private fun rememberMotionBrush(
    label: String,
    colors: List<Color>,
    durationMillis: Int
): Brush {
    val transition = rememberInfiniteTransition(label = label)
    val shift by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = durationMillis, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "${label}Shift"
    )
    return Brush.linearGradient(
        colors = colors,
        start = Offset(x = 920f * shift, y = 0f),
        end = Offset(x = 920f * (1f - shift), y = 260f)
    )
}

@Composable
private fun SectionLabel(text: String) {
    val palette = LocalJmxGlassPalette.current
    RazorText(
        text = text,
        modifier = Modifier.padding(start = 6.dp, bottom = 8.dp),
        style = TextStyle(
            color = palette.tertiaryText,
            fontSize = 12.sp,
            lineHeight = 15.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.8.sp
        )
    )
}

@Composable
private fun AboutGlassCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    GlassPanel(
        modifier = modifier.fillMaxWidth(),
        cornerRadius = 34.dp,
        useContentSurface = true
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(22.dp),
            content = content
        )
    }
}

@Composable
private fun AboutActionRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    val palette = LocalJmxGlassPalette.current
    val interactionSource = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .background(palette.contentSurface.copy(alpha = 0.46f))
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            RazorText(
                text = title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(
                    color = palette.primaryText,
                    fontSize = 16.sp,
                    lineHeight = 20.sp,
                    fontWeight = FontWeight.SemiBold
                )
            )
            Spacer(modifier = Modifier.height(3.dp))
            RazorText(
                text = subtitle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(
                    color = palette.secondaryText,
                    fontSize = 13.sp,
                    lineHeight = 17.sp,
                    fontWeight = FontWeight.Medium
                )
            )
        }
        AppleChevron()
    }
}

@Composable
private fun AboutSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    val palette = LocalJmxGlassPalette.current
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val thumbOffset by animateDpAsState(
        targetValue = if (checked) 34.dp else 3.dp,
        animationSpec = spring(dampingRatio = 0.74f, stiffness = 520f),
        label = "aboutAutoUpdateThumbOffset"
    )
    val trackColor by animateColorAsState(
        targetValue = if (checked) {
            palette.accent.copy(alpha = 0.92f)
        } else {
            palette.primaryText.copy(alpha = 0.14f)
        },
        animationSpec = tween(durationMillis = 180),
        label = "aboutAutoUpdateTrackColor"
    )
    val borderColor by animateColorAsState(
        targetValue = if (checked) {
            palette.accent.copy(alpha = 0.28f)
        } else {
            palette.primaryText.copy(alpha = 0.16f)
        },
        animationSpec = tween(durationMillis = 180),
        label = "aboutAutoUpdateBorderColor"
    )
    val thumbScale by animateFloatAsState(
        targetValue = if (pressed) 0.90f else 1f,
        animationSpec = spring(dampingRatio = 0.70f, stiffness = 620f),
        label = "aboutAutoUpdateThumbScale"
    )
    Box(
        modifier = Modifier
            .size(width = 64.dp, height = 34.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(trackColor)
            .border(1.dp, borderColor, RoundedCornerShape(999.dp))
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = { onCheckedChange(!checked) }
            ),
        contentAlignment = Alignment.CenterStart
    ) {
        Box(
            modifier = Modifier
                .padding(start = thumbOffset)
                .size(width = if (pressed) 29.dp else 27.dp, height = 27.dp)
                .graphicsLayer {
                    scaleX = thumbScale
                    scaleY = thumbScale
                }
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.96f))
        )
    }
}

@Composable
private fun DiagnosticLogSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val red = Color(0xFFFF3B30)
    val trackColor by animateColorAsState(
        targetValue = if (checked) red else red.copy(alpha = 0.14f),
        animationSpec = tween(durationMillis = 180),
        label = "diagnosticLogTrackColor"
    )
    val thumbOffset by animateDpAsState(
        targetValue = if (checked) 34.dp else 3.dp,
        animationSpec = spring(dampingRatio = 0.72f, stiffness = 560f),
        label = "diagnosticLogThumbOffset"
    )
    val thumbScale by animateFloatAsState(
        targetValue = if (pressed) 0.90f else 1f,
        animationSpec = spring(dampingRatio = 0.70f, stiffness = 620f),
        label = "diagnosticLogThumbScale"
    )
    Box(
        modifier = Modifier
            .size(width = 64.dp, height = 34.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(trackColor)
            .border(1.dp, red.copy(alpha = if (checked) 0.36f else 0.26f), RoundedCornerShape(999.dp))
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = { onCheckedChange(!checked) }
            ),
        contentAlignment = Alignment.CenterStart
    ) {
        Box(
            modifier = Modifier
                .padding(start = thumbOffset)
                .size(width = if (pressed) 29.dp else 27.dp, height = 27.dp)
                .graphicsLayer {
                    scaleX = thumbScale
                    scaleY = thumbScale
                }
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.98f))
        )
    }
}

@Composable
private fun DiagnosticLogCard(
    enabled: Boolean,
    sessionCount: Int,
    onToggle: (Boolean) -> Unit,
    onOpen: () -> Unit
) {
    val palette = LocalJmxGlassPalette.current
    val interactionSource = remember { MutableInteractionSource() }
    AboutGlassCard(
        modifier = Modifier.combinedClickable(
            interactionSource = interactionSource,
            indication = null,
            onClick = {},
            onLongClick = onOpen
        )
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                RazorText(
                    text = "运行日志输出",
                    style = TextStyle(
                        color = Color(0xFFFF3B30),
                        fontSize = 16.sp,
                        lineHeight = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                )
                Spacer(modifier = Modifier.height(4.dp))
                RazorText(
                    text = if (enabled) {
                        "正在记录运行、网络、导航与异常日志。长按进入日志中心。"
                    } else {
                        "关闭中。长按可管理本地日志，开启后按启停时间自动分段。"
                    },
                    style = TextStyle(
                        color = palette.secondaryText,
                        fontSize = 13.sp,
                        lineHeight = 19.sp,
                        fontWeight = FontWeight.Medium
                    )
                )
                Spacer(modifier = Modifier.height(6.dp))
                RazorText(
                    text = "本地日志：$sessionCount 段",
                    style = TextStyle(
                        color = palette.tertiaryText,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                )
            }
            DiagnosticLogSwitch(
                checked = enabled,
                onCheckedChange = onToggle
            )
        }
    }
}

@Composable
private fun AppIntroCard(
    checkingUpdate: Boolean,
    autoCheckEnabled: Boolean,
    onCheckUpdate: () -> Unit,
    onAutoCheckChanged: (Boolean) -> Unit
) {
    val palette = LocalJmxGlassPalette.current
    val interactionSource = remember { MutableInteractionSource() }
    val logoBrush = rememberMotionBrush(
        label = "jmxLogoTextGradient",
        colors = listOf(
            Color(0xFF111111),
            Color(0xFF0A84FF),
            Color(0xFFFF7AB6),
            Color(0xFF111111)
        ),
        durationMillis = 4200
    )
    val transition = rememberInfiniteTransition(label = "jmxLogoFloat")
    val floatOffset by transition.animateFloat(
        initialValue = -4f,
        targetValue = 4f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2600, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "jmxLogoFloatOffset"
    )

    AboutGlassCard {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Image(
                painter = painterResource(R.drawable.jmx_logo),
                contentDescription = "JMX",
                modifier = Modifier
                    .size(118.dp)
                    .graphicsLayer {
                        translationY = floatOffset
                    }
                    .clip(CircleShape)
                    .border(
                        width = 1.4.dp,
                        brush = Brush.linearGradient(
                            listOf(
                                Color.White.copy(alpha = 0.92f),
                                palette.accent.copy(alpha = 0.42f),
                                Color.White.copy(alpha = 0.58f)
                            )
                        ),
                        shape = CircleShape
                    ),
                contentScale = ContentScale.Crop
            )
            Spacer(modifier = Modifier.height(18.dp))
            RazorText(
                text = "JMX",
                style = TextStyle(
                    brush = logoBrush,
                    fontSize = 42.sp,
                    lineHeight = 46.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = (-1.4).sp
                )
            )
            Spacer(modifier = Modifier.height(10.dp))
            RazorText(
                text = "Liquid Glass JM Client",
                style = TextStyle(
                    color = palette.secondaryText,
                    fontSize = 15.sp,
                    lineHeight = 19.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.2.sp
                )
            )
            Spacer(modifier = Modifier.height(18.dp))
            RazorText(
                text = "JMX 是面向 JMComic / 禁漫天堂的第三方 Android 客户端。",
                style = TextStyle(
                    color = palette.secondaryText,
                    fontSize = 14.sp,
                    lineHeight = 22.sp,
                    fontWeight = FontWeight.Medium
                )
            )
            Spacer(modifier = Modifier.height(18.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(palette.primaryText.copy(alpha = 0.06f))
            )
            Spacer(modifier = Modifier.height(14.dp))
            RazorText(
                text = "当前版本 v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                modifier = Modifier.fillMaxWidth(),
                style = TextStyle(
                    color = palette.primaryText,
                    fontSize = 14.sp,
                    lineHeight = 18.sp,
                    fontWeight = FontWeight.SemiBold
                )
            )
            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(22.dp))
                    .clickable(
                        interactionSource = interactionSource,
                        indication = null,
                        onClick = onCheckUpdate
                    )
                    .background(palette.accent.copy(alpha = 0.09f))
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    RazorText(
                        text = if (checkingUpdate) "正在检查更新..." else "点击此处检查更新",
                        style = TextStyle(
                            color = palette.primaryText,
                            fontSize = 14.sp,
                            lineHeight = 18.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    RazorText(
                        text = "通过 GitHub Releases 获取最新版本",
                        style = TextStyle(
                            color = palette.secondaryText,
                            fontSize = 12.sp,
                            lineHeight = 16.sp,
                            fontWeight = FontWeight.Medium
                        )
                    )
                }
                AppleChevron()
            }
            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(22.dp))
                    .background(palette.contentSurface.copy(alpha = 0.48f))
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    RazorText(
                        text = "自动检测更新",
                        style = TextStyle(
                            color = palette.primaryText,
                            fontSize = 14.sp,
                            lineHeight = 18.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    RazorText(
                        text = "启动时静默检查，有新版本才提示",
                        style = TextStyle(
                            color = palette.secondaryText,
                            fontSize = 12.sp,
                            lineHeight = 16.sp,
                            fontWeight = FontWeight.Medium
                        )
                    )
                }
                AboutSwitch(
                    checked = autoCheckEnabled,
                    onCheckedChange = onAutoCheckChanged
                )
            }
        }
    }
}

@Composable
private fun DeveloperCard() {
    val palette = LocalJmxGlassPalette.current
    val nameBrush = rememberMotionBrush(
        label = "developerNameGradient",
        colors = listOf(
            Color(0xFF5EA1FF),
            Color(0xFF57D9C8),
            Color(0xFFFFB45C),
            Color(0xFFFF7EB3)
        ),
        durationMillis = 3600
    )
    val transition = rememberInfiniteTransition(label = "developerAvatarMotion")
    val scale by transition.animateFloat(
        initialValue = 0.985f,
        targetValue = 1.015f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2400, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "developerAvatarScale"
    )

    AboutGlassCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Image(
                painter = painterResource(R.drawable.developer_avatar),
                contentDescription = "ASakura",
                modifier = Modifier
                    .size(82.dp)
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                    }
                    .clip(CircleShape)
                    .border(
                        width = 1.6.dp,
                        brush = Brush.linearGradient(
                            listOf(
                                Color.White.copy(alpha = 0.95f),
                                palette.accent.copy(alpha = 0.54f),
                                Color(0xFFFFB7D5).copy(alpha = 0.78f)
                            )
                        ),
                        shape = CircleShape
                    ),
                contentScale = ContentScale.Crop
            )
            Column(modifier = Modifier.weight(1f)) {
                RazorText(
                    text = "ASakura",
                    style = TextStyle(
                        brush = nameBrush,
                        fontSize = 31.sp,
                        lineHeight = 35.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = (-0.7).sp
                    )
                )
                Spacer(modifier = Modifier.height(5.dp))
                RazorText(
                    text = "Developer / Interface Direction",
                    style = TextStyle(
                        color = palette.secondaryText,
                        fontSize = 13.sp,
                        lineHeight = 17.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                )
                Spacer(modifier = Modifier.height(8.dp))
                RazorText(
                    text = "这里似乎能写点什么🤔。",
                    style = TextStyle(
                        color = palette.secondaryText,
                        fontSize = 13.sp,
                        lineHeight = 20.sp,
                        fontWeight = FontWeight.Medium
                    )
                )
            }
        }
    }
}

@Composable
fun AboutScreen(
    appUpdateManager: AppUpdateManager = getKoin().get(),
    diagnosticLogManager: DiagnosticLogManager = getKoin().get()
) {
    val mainNavController = LocalMainNavController.current
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    val updateUiState by appUpdateManager.uiState.collectAsState()
    val autoCheckEnabled by appUpdateManager.autoCheckEnabled.collectAsState()
    val logEnabled by diagnosticLogManager.enabled.collectAsState()
    val logSessions by diagnosticLogManager.sessions.collectAsState()
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(top = 20.dp, bottom = 132.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            AppIntroCard(
                checkingUpdate = updateUiState.checking,
                autoCheckEnabled = autoCheckEnabled,
                onCheckUpdate = {
                    scope.launch {
                        appUpdateManager.checkForUpdate(showResultToast = true, fromStartup = false)
                    }
                },
                onAutoCheckChanged = appUpdateManager::setAutoCheckEnabled
            )

            Column {
                SectionLabel("LEGAL")
                AboutActionRow(
                    title = "免责声明",
                    subtitle = "查看完整使用声明与责任边界",
                    onClick = { mainNavController.navigate("aboutDisclaimer") }
                )
            }

            Column {
                SectionLabel("DIAGNOSTICS")
                DiagnosticLogCard(
                    enabled = logEnabled,
                    sessionCount = logSessions.size,
                    onToggle = diagnosticLogManager::setEnabled,
                    onOpen = { mainNavController.navigate("diagnosticLogs") }
                )
            }

            Column {
                SectionLabel("DEVELOPER")
                DeveloperCard()
            }

            Column {
                SectionLabel("PROJECT")
                AboutGlassCard(
                    modifier = Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { uriHandler.openUri(PROJECT_URL) }
                    )
                ) {
                    RazorText(
                        text = "项目地址",
                        style = TextStyle(
                            color = LocalJmxGlassPalette.current.primaryText,
                            fontSize = 16.sp,
                            lineHeight = 20.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    RazorText(
                        text = PROJECT_URL,
                        style = TextStyle(
                            color = LocalJmxGlassPalette.current.secondaryText,
                            fontSize = 13.sp,
                            lineHeight = 18.sp,
                            fontWeight = FontWeight.Medium
                        )
                    )
                }
            }

            Column {
                SectionLabel("CREDITS")
                AboutActionRow(
                    title = "项目引用",
                    subtitle = "开源项目、API 文档与基础组件",
                    onClick = { mainNavController.navigate("aboutCredits") }
                )
            }
        }
    }
}

@Composable
fun AboutDisclaimerScreen() {
    val palette = LocalJmxGlassPalette.current
    CommonScaffold(title = "免责声明") {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            AboutGlassCard {
                RazorText(
                    text = "JMX 是面向 JMComic / 禁漫天堂的第三方客户端，仅用于技术研究、界面实验与个人学习用途；本项目不隶属于、代表或受 JMComic / 禁漫天堂官方授权。",
                    style = TextStyle(
                        color = palette.primaryText,
                        fontSize = 15.sp,
                        lineHeight = 24.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                )
                Spacer(modifier = Modifier.height(14.dp))
                RazorText(
                    text = "应用中展示的漫画、图片、文本与用户数据均来源于用户访问的第三方服务。JMX 不存储、不分发、不拥有相关内容版权，也不对第三方服务的可用性、准确性、合法性或内容安全作任何保证。",
                    style = TextStyle(
                        color = palette.secondaryText,
                        fontSize = 14.sp,
                        lineHeight = 23.sp,
                        fontWeight = FontWeight.Medium
                    )
                )
                Spacer(modifier = Modifier.height(12.dp))
                RazorText(
                    text = "用户应自行确认所在地法律法规及平台规则，并对账号登录、内容访问、下载缓存、数据同步等行为承担全部责任。若相关权利方认为本项目存在不当引用或侵权风险，请通过项目地址联系维护者处理。",
                    style = TextStyle(
                        color = palette.secondaryText,
                        fontSize = 14.sp,
                        lineHeight = 23.sp,
                        fontWeight = FontWeight.Medium
                    )
                )
                Spacer(modifier = Modifier.height(12.dp))
                RazorText(
                    text = "继续使用 JMX 即表示你理解并接受上述说明。",
                    style = TextStyle(
                        color = palette.primaryText,
                        fontSize = 14.sp,
                        lineHeight = 22.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                )
            }
        }
    }
}

@Composable
fun AboutCreditsScreen() {
    val palette = LocalJmxGlassPalette.current
    val uriHandler = LocalUriHandler.current
    CommonScaffold(title = "项目引用") {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            openSourceCredits.forEach { item ->
                AboutGlassCard {
                    RazorText(
                        text = item.name,
                        style = TextStyle(
                            color = palette.primaryText,
                            fontSize = 18.sp,
                            lineHeight = 22.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = (-0.25).sp
                        )
                    )
                    Spacer(modifier = Modifier.height(5.dp))
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = { uriHandler.openUri(item.url) }
                            )
                            .background(palette.accent.copy(alpha = 0.10f))
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RazorText(
                            text = item.source,
                            style = TextStyle(
                                color = palette.accent,
                                fontSize = 13.sp,
                                lineHeight = 17.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    RazorText(
                        text = item.note,
                        style = TextStyle(
                            color = palette.secondaryText,
                            fontSize = 14.sp,
                            lineHeight = 22.sp,
                            fontWeight = FontWeight.Medium
                        )
                    )
                }
            }
        }
    }
}

@Composable
private fun DiagnosticLogSessionCard(
    session: DiagnosticLogSession,
    selected: Boolean,
    selectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    diagnosticLogManager: DiagnosticLogManager
) {
    val palette = LocalJmxGlassPalette.current
    AboutGlassCard(
        modifier = Modifier.combinedClickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = onClick,
            onLongClick = onLongClick
        )
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(
                        if (selected) Color(0xFFFF3B30) else Color(0xFFFF3B30).copy(alpha = 0.12f)
                    ),
                contentAlignment = Alignment.Center
            ) {
                RazorIcon(
                    imageVector = Icons.Outlined.FolderZip,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = if (selected) Color.White else Color(0xFFFF3B30)
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                RazorText(
                    text = diagnosticLogManager.formatSessionTitle(session),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(
                        color = palette.primaryText,
                        fontSize = 15.sp,
                        lineHeight = 20.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                )
                Spacer(modifier = Modifier.height(4.dp))
                RazorText(
                    text = diagnosticLogManager.formatSessionSubtitle(session),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(
                        color = palette.secondaryText,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        fontWeight = FontWeight.Medium
                    )
                )
            }
            if (!selectionMode) {
                RazorGlassButton(onClick = onShare, modifier = Modifier.size(42.dp)) {
                    RazorIcon(
                        imageVector = Icons.Outlined.Share,
                        contentDescription = "分享",
                        modifier = Modifier.size(18.dp),
                        tint = palette.primaryText
                    )
                }
                RazorGlassButton(onClick = onDelete, modifier = Modifier.size(42.dp)) {
                    RazorIcon(
                        imageVector = Icons.Outlined.Delete,
                        contentDescription = "删除",
                        modifier = Modifier.size(18.dp),
                        tint = Color(0xFFFF3B30)
                    )
                }
            }
        }
    }
}

@Composable
fun DiagnosticLogScreen(
    diagnosticLogManager: DiagnosticLogManager = getKoin().get()
) {
    val context = LocalContext.current
    val palette = LocalJmxGlassPalette.current
    val sessions by diagnosticLogManager.sessions.collectAsState()
    val selectedIds = remember { mutableStateOf<Set<String>>(emptySet()) }
    val selectedSessions = sessions.filter { it.id in selectedIds.value }
    val selectionMode = selectedIds.value.isNotEmpty()

    CommonScaffold(
        title = if (selectionMode) "已选择 ${selectedIds.value.size}" else "运行日志",
        actions = {
            RazorGlassButton(
                onClick = { diagnosticLogManager.shareAllLogs(context) },
                modifier = Modifier.size(48.dp)
            ) {
                RazorIcon(
                    imageVector = Icons.Outlined.Share,
                    contentDescription = "分享全部",
                    modifier = Modifier.size(22.dp),
                    tint = palette.primaryText
                )
            }
        },
        bottomBar = {
            if (selectionMode) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 18.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    RazorGlassButton(
                        onClick = {
                            diagnosticLogManager.shareSessions(context, selectedSessions)
                            selectedIds.value = emptySet()
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        RazorText(
                            text = "分享选中",
                            style = TextStyle(
                                color = palette.primaryText,
                                fontSize = 15.sp,
                                lineHeight = 19.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        )
                    }
                    RazorGlassButton(
                        onClick = {
                            diagnosticLogManager.deleteSessions(selectedSessions)
                            selectedIds.value = emptySet()
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        RazorText(
                            text = "删除选中",
                            style = TextStyle(
                                color = Color(0xFFFF3B30),
                                fontSize = 15.sp,
                                lineHeight = 19.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        )
                    }
                }
            }
        }
    ) {
        if (sessions.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 24.dp),
                contentAlignment = Alignment.Center
            ) {
                RazorText(
                    text = "暂无本地日志\n在关于页开启运行日志输出后，关闭时会按时间段归档。",
                    style = TextStyle(
                        color = palette.secondaryText,
                        fontSize = 15.sp,
                        lineHeight = 24.sp,
                        fontWeight = FontWeight.Medium
                    )
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(sessions, key = { it.id }) { session ->
                    val selected = session.id in selectedIds.value
                    DiagnosticLogSessionCard(
                        session = session,
                        selected = selected,
                        selectionMode = selectionMode,
                        onClick = {
                            if (selectionMode) {
                                selectedIds.value = if (selected) {
                                    selectedIds.value - session.id
                                } else {
                                    selectedIds.value + session.id
                                }
                            }
                        },
                        onLongClick = {
                            selectedIds.value = selectedIds.value + session.id
                        },
                        onShare = {
                            diagnosticLogManager.shareSessions(context, listOf(session))
                        },
                        onDelete = {
                            diagnosticLogManager.deleteSessions(listOf(session))
                        },
                        diagnosticLogManager = diagnosticLogManager
                    )
                }
            }
        }
    }
}

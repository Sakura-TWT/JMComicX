package dev.jmx.client

import android.app.Activity
import android.graphics.Color as AndroidColor
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurBlendMode
import top.yukonga.miuix.kmp.blur.BlurDefaults
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.blur.textureBlur
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal data class ThirdPartyLibrary(
    val id: String,
    val name: String,
    val license: String,
    val description: String,
    val url: String,
)

internal val thirdPartyLibraries = listOf(
    ThirdPartyLibrary("miuix", "Compose Miuix", "Apache-2.0", "MIUI/HyperOS 风格 Compose 组件、图标与模糊效果。", "https://github.com/compose-miuix-ui/miuix"),
    ThirdPartyLibrary("androidx", "AndroidX / Jetpack Compose", "Apache-2.0", "Android 应用框架和声明式 UI 运行时。", "https://source.android.com/docs/setup/about/licenses"),
    ThirdPartyLibrary("kotlin", "Kotlin", "Apache-2.0", "项目使用的编程语言及协程基础。", "https://github.com/JetBrains/kotlin"),
    ThirdPartyLibrary("coil", "Coil", "Apache-2.0", "Android 图片请求、解码与缓存。", "https://github.com/coil-kt/coil"),
    ThirdPartyLibrary("okhttp", "OkHttp", "Apache-2.0", "HTTP 网络传输与连接管理。", "https://github.com/square/okhttp"),
    ThirdPartyLibrary("gson", "Gson", "Apache-2.0", "JSON 数据序列化与反序列化。", "https://github.com/google/gson"),
    ThirdPartyLibrary("opencc4j", "OpenCC4J", "Apache-2.0", "搜索关键词的简繁中文转换。", "https://github.com/houbb/opencc4j"),
    ThirdPartyLibrary("webp", "WebP ImageIO", "Apache-2.0", "WebP 图片格式读取支持。", "https://github.com/usefulness/webp-imageio"),
)

@Composable
internal fun AboutScreen(
    innerPadding: PaddingValues,
    onBack: () -> Unit,
    onProjectLicense: () -> Unit,
    onThirdParty: () -> Unit,
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val uriHandler = LocalUriHandler.current
    val version = remember(context) {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "开发版"
    }
    DisposableEffect(activity) {
        val window = activity?.window
        @Suppress("DEPRECATION")
        val previousStatusBarColor = window?.statusBarColor
        @Suppress("DEPRECATION")
        window?.statusBarColor = AndroidColor.TRANSPARENT
        onDispose {
            if (previousStatusBarColor != null) {
                @Suppress("DEPRECATION")
                window.statusBarColor = previousStatusBarColor
            }
        }
    }
    val listState = rememberLazyListState()
    val scrollProgress by remember {
        derivedStateOf {
            if (listState.firstVisibleItemIndex > 0) {
                1f
            } else {
                val spacer = listState.layoutInfo.visibleItemsInfo
                    .firstOrNull { it.key == ABOUT_HERO_SPACER_KEY }
                if (spacer != null && spacer.size > 0) {
                    (listState.firstVisibleItemScrollOffset.toFloat() / spacer.size)
                        .coerceIn(0f, 1f)
                } else {
                    0f
                }
            }
        }
    }
    val backdrop = rememberLayerBackdrop()
    val blurColors = BlurDefaults.blurColors(
        blendColors = listOf(
            BlendColorEntry(
                MiuixTheme.colorScheme.surface.copy(alpha = 0.76f),
                BlurBlendMode.SrcOver,
            ),
        ),
        saturation = 1.1f,
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MiuixTheme.colorScheme.surface),
    ) {
        MiuixAboutBackdrop(
            backdrop = backdrop,
            scrollProgress = { scrollProgress },
        )
        MiuixForegroundBlurHero(
            version = version,
            backdrop = backdrop,
            scrollProgress = { scrollProgress },
        )
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                bottom = innerPadding.calculateBottomPadding() + 24.dp,
            ),
        ) {
            item(key = ABOUT_HERO_SPACER_KEY) {
                Spacer(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(350.dp),
                )
            }
            item(key = "about-project") {
                Card(
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .textureBlur(
                            backdrop = backdrop,
                            shape = RoundedCornerShape(16.dp),
                            blurRadius = 52f,
                            colors = blurColors,
                        ),
                    colors = CardDefaults.defaultColors(
                        color = Color.Transparent,
                        contentColor = MiuixTheme.colorScheme.onSurfaceContainer,
                    ),
                ) {
                    ArrowPreference(
                        title = "项目地址",
                        endActions = { AboutValueText("GitHub") },
                        onClick = { uriHandler.openUri(PROJECT_URL) },
                    )
                    ArrowPreference(
                        title = "开源协议",
                        endActions = { AboutValueText("GPL-3.0") },
                        onClick = onProjectLicense,
                    )
                }
            }
            item(key = "about-open-source") {
                Card(
                    modifier = Modifier
                        .padding(horizontal = 12.dp, vertical = 12.dp)
                        .textureBlur(
                            backdrop = backdrop,
                            shape = RoundedCornerShape(16.dp),
                            blurRadius = 52f,
                            colors = blurColors,
                        ),
                    colors = CardDefaults.defaultColors(
                        color = Color.Transparent,
                        contentColor = MiuixTheme.colorScheme.onSurfaceContainer,
                    ),
                ) {
                    ArrowPreference(
                        title = "第三方开源库",
                        summary = "依赖、用途、协议及项目地址",
                        onClick = onThirdParty,
                    )
                }
            }
            item(key = "about-footer") {
                Text(
                    text = "JMComicX 由社区独立开发，与内容平台官方无关联。",
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(horizontal = 28.dp, vertical = 8.dp),
                )
            }
        }
        IconButton(
            onClick = onBack,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(
                    start = 12.dp,
                    top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 8.dp,
                )
                .zIndex(2f),
        ) {
            Icon(
                imageVector = MiuixIcons.Back,
                contentDescription = "返回",
                tint = MiuixTheme.colorScheme.onBackground,
            )
        }
    }
}

@Composable
private fun AboutValueText(value: String) {
    Text(
        text = value,
        style = MiuixTheme.textStyles.body2,
        color = MiuixTheme.colorScheme.onSurfaceVariantActions,
    )
}

@Composable
internal fun ProjectLicenseScreen(innerPadding: PaddingValues) {
    val uriHandler = LocalUriHandler.current
    InfoDetailScreen(
        innerPadding = innerPadding,
        title = "GNU GPL v3.0",
        body = "JMComicX 以 GNU General Public License v3.0 发布。你可以在遵守同一许可证、保留版权和源码可用性要求的前提下使用、研究、修改和再发布本项目。",
        action = "查看完整协议",
        onAction = { uriHandler.openUri(PROJECT_LICENSE_URL) },
    )
}

@Composable
internal fun ThirdPartyListScreen(
    innerPadding: PaddingValues,
    onLibrarySelected: (String) -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MiuixTheme.colorScheme.surface),
        contentPadding = PaddingValues(
            top = innerPadding.calculateTopPadding() + 8.dp,
            bottom = innerPadding.calculateBottomPadding() + 24.dp,
        ),
    ) {
        item(key = "third-party-card") {
            Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                thirdPartyLibraries.forEach { library ->
                    ArrowPreference(
                        title = library.name,
                        summary = library.license,
                        onClick = { onLibrarySelected(library.id) },
                    )
                }
            }
        }
    }
}

@Composable
internal fun ThirdPartyDetailScreen(
    innerPadding: PaddingValues,
    libraryId: String?,
) {
    val uriHandler = LocalUriHandler.current
    val library = thirdPartyLibraries.firstOrNull { it.id == libraryId }
    InfoDetailScreen(
        innerPadding = innerPadding,
        title = library?.name ?: "第三方开源库",
        body = library?.let { "${it.description}\n\n开源协议：${it.license}" } ?: "未找到对应项目。",
        action = library?.let { "打开项目地址" },
        onAction = { library?.url?.let(uriHandler::openUri) },
    )
}

@Composable
private fun InfoDetailScreen(
    innerPadding: PaddingValues,
    title: String,
    body: String,
    action: String?,
    onAction: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MiuixTheme.colorScheme.surface)
            .padding(
                start = 20.dp,
                top = innerPadding.calculateTopPadding() + 20.dp,
                end = 20.dp,
                bottom = innerPadding.calculateBottomPadding() + 20.dp,
            ),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = title,
            style = MiuixTheme.textStyles.title2,
            fontWeight = FontWeight.SemiBold,
            color = MiuixTheme.colorScheme.onSurface,
        )
        Text(
            text = body,
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        if (action != null) {
            TextButton(text = action, onClick = onAction)
        }
    }
}

private const val ABOUT_HERO_SPACER_KEY = "about-hero-spacer"
private const val PROJECT_URL = "https://github.com/Sakura-TWT/JMComicX"
private const val PROJECT_LICENSE_URL = "https://github.com/Sakura-TWT/JMComicX/blob/main/LICENSE"

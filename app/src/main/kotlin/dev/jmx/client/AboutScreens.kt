package dev.jmx.client

import android.app.Activity
import android.graphics.Color as AndroidColor
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
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
    val name: String,
    val license: String,
    val url: String,
)

internal val thirdPartyLibraries = listOf(
    ThirdPartyLibrary("Compose Miuix", "Apache-2.0", "https://github.com/compose-miuix-ui/miuix"),
    ThirdPartyLibrary("AndroidX / Jetpack Compose", "Apache-2.0", "https://source.android.com/docs/setup/about/licenses"),
    ThirdPartyLibrary("Kotlin", "Apache-2.0", "https://github.com/JetBrains/kotlin"),
    ThirdPartyLibrary("Coil", "Apache-2.0", "https://github.com/coil-kt/coil"),
    ThirdPartyLibrary("OkHttp", "Apache-2.0", "https://github.com/square/okhttp"),
    ThirdPartyLibrary("Gson", "Apache-2.0", "https://github.com/google/gson"),
    ThirdPartyLibrary("OpenCC4J", "Apache-2.0", "https://github.com/houbb/opencc4j"),
    ThirdPartyLibrary("WebP ImageIO", "Apache-2.0", "https://github.com/usefulness/webp-imageio"),
)

@Composable
internal fun AboutScreen(
    innerPadding: PaddingValues,
    onBack: () -> Unit,
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
    val density = LocalDensity.current
    var heroHeight by remember { mutableStateOf(170.dp) }
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

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = Color.Transparent,
        topBar = {
            SmallTopAppBar(
                title = "关于",
                color = if (scrollProgress == 1f) MiuixTheme.colorScheme.surface else Color.Transparent,
                titleColor = MiuixTheme.colorScheme.onSurface.copy(
                    alpha = ((scrollProgress - 0.35f) / 0.65f).coerceIn(0f, 1f),
                ),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = MiuixIcons.Back,
                            contentDescription = "返回",
                            tint = MiuixTheme.colorScheme.onBackground,
                        )
                    }
                },
            )
        },
    ) { pagePadding ->
        Box(modifier = Modifier.fillMaxSize()) {
            MiuixAboutBackdrop(
                backdrop = backdrop,
                scrollProgress = { scrollProgress },
            )
            MiuixForegroundBlurHero(
                version = version,
                backdrop = backdrop,
                scrollProgress = { scrollProgress },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .padding(top = pagePadding.calculateTopPadding() + ABOUT_LOGO_TOP_OFFSET)
                    .onSizeChanged { size ->
                        with(density) { heroHeight = size.height.toDp() }
                    },
            )
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    top = pagePadding.calculateTopPadding(),
                    bottom = pagePadding.calculateBottomPadding() +
                        innerPadding.calculateBottomPadding() + 24.dp,
                ),
            ) {
                item(key = ABOUT_HERO_SPACER_KEY) {
                    Spacer(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(heroHeight + ABOUT_LOGO_TOP_OFFSET + ABOUT_CONTENT_BOTTOM_GAP),
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
                            title = "开源协议",
                            endActions = { AboutValueText("GPL-3.0") },
                            onClick = { uriHandler.openUri(PROJECT_LICENSE_URL) },
                        )
                        ArrowPreference(
                            title = "第三方开源库",
                            summary = "依赖、用途、协议及项目地址",
                            onClick = onThirdParty,
                        )
                    }
                }
            }
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
internal fun ThirdPartyListScreen(
    innerPadding: PaddingValues,
) {
    val uriHandler = LocalUriHandler.current
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
                        onClick = { uriHandler.openUri(library.url) },
                    )
                }
            }
        }
    }
}

private const val ABOUT_HERO_SPACER_KEY = "about-hero-spacer"
private const val PROJECT_URL = "https://github.com/Sakura-TWT/JMComicX"
private const val PROJECT_LICENSE_URL = "https://www.gnu.org/licenses/gpl-3.0.html"
private val ABOUT_LOGO_TOP_OFFSET = 92.dp
private val ABOUT_CONTENT_BOTTOM_GAP = 126.dp

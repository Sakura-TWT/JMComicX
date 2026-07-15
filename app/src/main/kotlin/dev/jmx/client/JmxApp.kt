package dev.jmx.client

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import dev.jmx.client.core.api.AlbumSummary
import dev.jmx.client.core.image.ImageUrl
import dev.jmx.client.core.protocol.JmxProtocolConstants
import dev.jmx.client.core.result.JmxError
import dev.jmx.client.core.result.JmxResult
import dev.jmx.client.core.runtime.InitStepResult
import dev.jmx.client.core.runtime.JmxCore
import okhttp3.Headers
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Album
import top.yukonga.miuix.kmp.icon.extended.Contacts
import top.yukonga.miuix.kmp.icon.extended.Home
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType

@Composable
fun JmxApp() {
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    val tabs = remember {
        listOf(
            JmxTab("首页", MiuixIcons.Home),
            JmxTab("书架", MiuixIcons.Album),
            JmxTab("我的", MiuixIcons.Contacts),
        )
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            SmallTopAppBar(
                title = if (selectedTab == 0) "JMComicX" else tabs[selectedTab].label,
            )
        },
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { index, tab ->
                    NavigationBarItem(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        icon = tab.icon,
                        label = tab.label,
                    )
                }
            }
        },
    ) { innerPadding ->
        AnimatedContent(
            targetState = selectedTab,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
        ) { tab ->
            when (tab) {
                0 -> HomeScreen(innerPadding = innerPadding)
                1 -> ReservedScreen(innerPadding = innerPadding, title = "书架")
                else -> ReservedScreen(innerPadding = innerPadding, title = "我的")
            }
        }
    }
}

@Composable
private fun HomeScreen(innerPadding: PaddingValues) {
    val repository = remember { HomeRepository() }
    var reloadKey by rememberSaveable { mutableIntStateOf(0) }
    var state by remember { mutableStateOf<HomeUiState>(HomeUiState.Loading) }

    LaunchedEffect(repository, reloadKey) {
        state = HomeUiState.Loading
        state = repository.load()
    }

    AnimatedContent(
        targetState = state,
        transitionSpec = { fadeIn() togetherWith fadeOut() },
        modifier = Modifier
            .fillMaxSize()
            .padding(innerPadding),
    ) { current ->
        when (current) {
            HomeUiState.Loading -> LoadingHome()
            is HomeUiState.Content -> HomeAlbumGrid(
                title = current.title,
                albums = current.albums,
                onReload = { reloadKey++ },
            )
            is HomeUiState.Empty -> EmptyState(
                title = "暂无推荐",
                message = current.message,
                onRetry = { reloadKey++ },
            )
            is HomeUiState.Error -> EmptyState(
                title = "首页加载失败",
                message = current.message,
                onRetry = { reloadKey++ },
            )
        }
    }
}

@Composable
private fun HomeAlbumGrid(
    title: String,
    albums: List<HomeAlbum>,
    onReload: () -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            HomeHeader(title = title, count = albums.size, onReload = onReload)
        }
        items(
            items = albums,
            key = { it.id },
        ) { album ->
            AlbumCard(album = album)
        }
    }
}

@Composable
private fun HomeHeader(title: String, count: Int, onReload: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(
                text = title,
                style = MiuixTheme.textStyles.title3,
                color = MiuixTheme.colorScheme.onSurface,
            )
            Text(
                text = "已加载 $count 部",
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
        TextButton(text = "刷新", onClick = onReload)
    }
}

@Composable
private fun AlbumCard(album: HomeAlbum) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        insideMargin = PaddingValues(8.dp),
        pressFeedbackType = PressFeedbackType.Sink,
        onClick = {},
    ) {
        AlbumCover(album = album)
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = album.name,
            style = MiuixTheme.textStyles.footnote1,
            fontWeight = FontWeight.SemiBold,
            color = MiuixTheme.colorScheme.onSurface,
            overflow = TextOverflow.Ellipsis,
            maxLines = 2,
            minLines = 2,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = album.author,
            style = MiuixTheme.textStyles.footnote2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            overflow = TextOverflow.Ellipsis,
            maxLines = 1,
        )
    }
}

@Composable
private fun AlbumCover(album: HomeAlbum) {
    val palette = coverPalettes[album.paletteIndex % coverPalettes.size]
    val context = LocalContext.current
    val coverRequest = remember(album.coverUrl) {
        album.coverUrl?.let { url ->
            ImageRequest.Builder(context)
                .data(url)
                .headers(albumCoverHeaders)
                .crossfade(true)
                .build()
        }
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(3f / 4f)
            .clip(RoundedCornerShape(14.dp))
            .background(Brush.linearGradient(palette)),
    ) {
        FallbackCover(album = album)
        if (coverRequest != null) {
            AsyncImage(
                model = coverRequest,
                contentDescription = album.name,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
    }
}

@Composable
private fun FallbackCover(album: HomeAlbum) {
    Text(
        text = "#${album.id}",
        modifier = Modifier
            .padding(10.dp),
        style = MiuixTheme.textStyles.footnote2,
        color = Color.White.copy(alpha = 0.82f),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = album.initial,
            modifier = Modifier.graphicsLayer(alpha = 0.94f),
            style = MiuixTheme.textStyles.headline1,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}

@Composable
private fun LoadingHome() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(modifier = Modifier.height(14.dp))
            Text(
                text = "正在加载首页",
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}

@Composable
private fun EmptyState(
    title: String,
    message: String,
    onRetry: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = title,
                style = MiuixTheme.textStyles.title3,
                color = MiuixTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = message,
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(18.dp))
            TextButton(text = "重试", onClick = onRetry)
        }
    }
}

@Composable
private fun ReservedScreen(innerPadding: PaddingValues, title: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(innerPadding),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = if (title == "书架") MiuixIcons.Album else MiuixIcons.Contacts,
                contentDescription = title,
                modifier = Modifier.size(34.dp),
                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "$title 暂未开放",
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}

private class HomeRepository(
    private val core: JmxCore = JmxCore.create(),
) {
    suspend fun load(): HomeUiState {
        return runCatching {
            val init = core.initializer.initialize()
            val imageHost = (init.settingFetch as? InitStepResult.Success)
                ?.value
                ?.imageHost
                ?: ImageUrl.pickDefaultImageHost()
            when (val result = core.libraryApi.promotedSections()) {
                is JmxResult.Success -> {
                    val section = result.value.firstOrNull { it.title.isSerialUpdateTitle() }
                        ?: result.value.firstOrNull { it.content.isNotEmpty() }
                    val albums = section?.content.orEmpty()
                        .filter { it.id.isNotBlank() }
                        .distinctBy { it.id }
                        .take(60)
                        .mapIndexed { index, item -> item.toHomeAlbum(index, imageHost) }
                    if (albums.isEmpty()) {
                        HomeUiState.Empty("接口返回成功，但连载更新分组没有可展示的漫画。")
                    } else {
                        HomeUiState.Content(
                            title = section?.title?.cleanHomeSectionTitle() ?: "连载更新",
                            albums = albums,
                        )
                    }
                }
                is JmxResult.Failure -> HomeUiState.Error(result.error.toUiMessage())
            }
        }.getOrElse { HomeUiState.Error(it.message ?: "首页加载出现未知异常。") }
    }
}

private sealed interface HomeUiState {
    data object Loading : HomeUiState
    data class Content(val title: String, val albums: List<HomeAlbum>) : HomeUiState
    data class Empty(val message: String) : HomeUiState
    data class Error(val message: String) : HomeUiState
}

private data class HomeAlbum(
    val id: String,
    val name: String,
    val author: String,
    val initial: String,
    val paletteIndex: Int,
    val coverUrl: String?,
)

private data class JmxTab(
    val label: String,
    val icon: ImageVector,
)

private fun AlbumSummary.toHomeAlbum(index: Int, imageHost: String): HomeAlbum {
    val resolvedName = name?.takeIf { it.isNotBlank() } ?: "未命名漫画"
    return HomeAlbum(
        id = id,
        name = resolvedName,
        author = author?.takeIf { it.isNotBlank() } ?: "未知作者",
        initial = resolvedName.trim().firstOrNull()?.toString() ?: "J",
        paletteIndex = index,
        coverUrl = ImageUrl.resolveAlbumCover(
            imageHost = imageHost,
            albumId = id,
            rawImage = image,
        ),
    )
}

private fun String?.isSerialUpdateTitle(): Boolean {
    val normalized = this?.trim().orEmpty()
    return normalized.contains("连载") || normalized.contains("連載")
}

private fun String.cleanHomeSectionTitle(): String {
    return replace("→右滑看更多→", "")
        .replace("->右滑看更多->", "")
        .trim()
        .ifBlank { "连载更新" }
}

private fun JmxError.toUiMessage(): String {
    return when (this) {
        is JmxError.Network -> "网络请求失败：$message"
        is JmxError.Http -> "线路返回 HTTP $code：$message"
        is JmxError.Api -> "接口返回错误 $code：$message"
        is JmxError.Decode -> "响应解密或解析失败：$message"
        is JmxError.Schema -> "响应结构暂不匹配：$message"
        is JmxError.Domain -> "线路或域名不可用：$message"
        is JmxError.Unknown -> "未知错误：$message"
    }
}

private val albumCoverHeaders: Headers = Headers.Builder()
    .add("User-Agent", JmxProtocolConstants.MobileUserAgent)
    .add("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8")
    .add("Referer", "https://18comic.vip/")
    .build()

private val coverPalettes = listOf(
    listOf(Color(0xFF1D9A8A), Color(0xFFFFC857)),
    listOf(Color(0xFF3C7DD9), Color(0xFF74D3AE)),
    listOf(Color(0xFFD94E67), Color(0xFFFFB86B)),
    listOf(Color(0xFF4F6D7A), Color(0xFFB8F2E6)),
    listOf(Color(0xFF6A994E), Color(0xFFF2E8CF)),
    listOf(Color(0xFF99582A), Color(0xFFFFD6A5)),
)

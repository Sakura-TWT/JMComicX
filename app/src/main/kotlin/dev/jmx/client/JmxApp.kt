package dev.jmx.client

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Album
import top.yukonga.miuix.kmp.icon.extended.Contacts
import top.yukonga.miuix.kmp.icon.extended.Home

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

    val applicationContext = LocalContext.current.applicationContext
    val homeRepository = remember(applicationContext) { HomeRepository(applicationContext) }
    val detailRepository = remember(homeRepository) { AlbumDetailRepository(homeRepository.core) }
    var homeState by remember { mutableStateOf<HomeUiState>(HomeUiState.Loading) }
    var homeRequestId by rememberSaveable { mutableIntStateOf(0) }
    var isHomeRefreshing by remember { mutableStateOf(false) }
    var selectedHomeCategory by rememberSaveable { mutableIntStateOf(0) }
    var pendingLoadMoreCategoryId by remember { mutableStateOf<String?>(null) }
    var detailRequest by remember { mutableStateOf<AlbumDetailTransitionRequest?>(null) }

    LaunchedEffect(homeRepository, homeRequestId) {
        val previousState = homeState
        val wasRefreshing = isHomeRefreshing
        val previousCategoryId = (previousState as? HomeUiState.Content)
            ?.categories
            ?.getOrNull(selectedHomeCategory)
            ?.id
        val updatedState = homeRepository.load(preloadCategoryId = previousCategoryId)
        homeState = when {
            wasRefreshing && updatedState is HomeUiState.Error && previousState is HomeUiState.Content -> previousState
            else -> updatedState
        }
        if (updatedState is HomeUiState.Content) {
            selectedHomeCategory = previousCategoryId
                ?.let { id -> updatedState.categories.indexOfFirst { it.id == id }.takeIf { it >= 0 } }
                ?: 0
        }
        isHomeRefreshing = false
    }

    LaunchedEffect(homeRepository, pendingLoadMoreCategoryId) {
        val categoryId = pendingLoadMoreCategoryId ?: return@LaunchedEffect
        val content = homeState as? HomeUiState.Content
        val category = content?.categories?.firstOrNull { it.id == categoryId }
        if (category == null) {
            pendingLoadMoreCategoryId = null
            return@LaunchedEffect
        }
        val updatedCategory = homeRepository.loadMore(category)
        val latestContent = homeState as? HomeUiState.Content
        if (latestContent != null) {
            homeState = latestContent.copy(
                categories = latestContent.categories.map { current ->
                    if (current.id == categoryId) updatedCategory else current
                },
            )
        }
        pendingLoadMoreCategoryId = null
    }

    Box(modifier = Modifier.fillMaxSize()) {
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
                    0 -> HomeScreen(
                        innerPadding = innerPadding,
                        state = homeState,
                        isRefreshing = isHomeRefreshing,
                        selectedCategoryIndex = selectedHomeCategory,
                        onCategorySelected = { selectedHomeCategory = it },
                        liftedAlbumId = detailRequest?.album?.id,
                        onLoadMore = { categoryId ->
                            val content = homeState as? HomeUiState.Content
                            val category = content?.categories?.firstOrNull { it.id == categoryId }
                            if (
                                category != null &&
                                !category.isLoadingMore &&
                                !category.endReached &&
                                !isHomeRefreshing &&
                                pendingLoadMoreCategoryId == null
                            ) {
                                homeState = content.copy(
                                    categories = content.categories.map { current ->
                                        if (current.id == categoryId) {
                                            current.copy(isLoadingMore = true, loadMoreError = null)
                                        } else {
                                            current
                                        }
                                    },
                                )
                                pendingLoadMoreCategoryId = categoryId
                            }
                        },
                        onAlbumSelected = { album, sourceBounds ->
                            if (detailRequest == null) {
                                detailRequest = AlbumDetailTransitionRequest(album, sourceBounds)
                            }
                        },
                        onRefresh = {
                            if (!isHomeRefreshing && homeState is HomeUiState.Content) {
                                pendingLoadMoreCategoryId = null
                                isHomeRefreshing = true
                                homeRequestId++
                            }
                        },
                        onRetry = {
                            if (homeState !is HomeUiState.Loading) {
                                homeState = HomeUiState.Loading
                                homeRequestId++
                            }
                        },
                    )
                    1 -> ReservedScreen(innerPadding = innerPadding, title = "书架")
                    else -> ReservedScreen(innerPadding = innerPadding, title = "我的")
                }
            }
        }

        detailRequest?.let { request ->
            AlbumDetailTransitionHost(
                request = request,
                repository = detailRepository,
                onDismiss = { detailRequest = null },
            )
        }
    }
}

private data class JmxTab(
    val label: String,
    val icon: ImageVector,
)

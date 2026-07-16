package dev.jmx.client

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.zIndex
import dev.jmx.client.core.result.JmxResult
import dev.jmx.client.core.result.toUserMessage
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Contacts
import top.yukonga.miuix.kmp.icon.extended.Home
import top.yukonga.miuix.kmp.icon.basic.Search
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun JmxApp() {
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    val tabs = remember {
        listOf(
            JmxTab("首页", MiuixIcons.Home),
            JmxTab("我的", MiuixIcons.Contacts),
        )
    }

    val applicationContext = LocalContext.current.applicationContext
    val homeRepository = remember(applicationContext) { HomeRepository(applicationContext) }
    val detailRepository = remember(homeRepository) { AlbumDetailRepository(homeRepository.core) }
    val readerRepository = remember(homeRepository, applicationContext) {
        ComicReaderRepository(applicationContext, homeRepository.core)
    }
    val accountRepository = remember(homeRepository, applicationContext) {
        AccountRepository(applicationContext, homeRepository.core)
    }
    val coroutineScope = rememberCoroutineScope()
    var accountProfile by remember(accountRepository) { mutableStateOf(accountRepository.restore()) }
    var showLogin by rememberSaveable { mutableStateOf(false) }
    var loginSubmitting by remember { mutableStateOf(false) }
    var loginFailure by remember { mutableStateOf<LoginUiFailure?>(null) }
    var homeState by remember { mutableStateOf<HomeUiState>(HomeUiState.Loading) }
    var homeRequestId by rememberSaveable { mutableIntStateOf(0) }
    var isHomeRefreshing by remember { mutableStateOf(false) }
    var selectedHomeCategory by rememberSaveable { mutableIntStateOf(0) }
    var pendingLoadMoreCategoryId by remember { mutableStateOf<String?>(null) }
    var detailRequest by remember { mutableStateOf<AlbumDetailTransitionRequest?>(null) }
    var readerRequest by remember { mutableStateOf<ReaderLaunchRequest?>(null) }
    var searchExpanded by rememberSaveable { mutableStateOf(false) }
    val searchTransitionProgress by animateFloatAsState(
        targetValue = if (searchExpanded) 1f else 0f,
        label = "HomeSearchTopBarProgress",
    )
    val activeTab = selectedTab.coerceIn(0, tabs.lastIndex)

    fun requestLogin() {
        loginFailure = null
        showLogin = true
    }

    LaunchedEffect(selectedTab, tabs.size) {
        if (selectedTab !in tabs.indices) selectedTab = 0
    }

    LaunchedEffect(activeTab, accountProfile) {
        if (activeTab == ACCOUNT_TAB_INDEX && accountProfile == null) requestLogin()
    }

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
        pendingLoadMoreCategoryId = null
        if (latestContent != null) {
            homeState = latestContent.copy(
                categories = latestContent.categories.map { current ->
                    if (current.id == categoryId) updatedCategory else current
                },
            )
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MiuixTheme.colorScheme.surface),
    ) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                SmallTopAppBar(
                    title = if (activeTab == 0) "JMComicX" else tabs[activeTab].label,
                    modifier = Modifier.graphicsLayer {
                        translationY = size.height * searchTransitionProgress * 0.72f
                        alpha = 1f - searchTransitionProgress
                    },
                    actions = {
                        if (activeTab == 0) {
                            IconButton(onClick = { searchExpanded = true }) {
                                Icon(
                                    imageVector = MiuixIcons.Basic.Search,
                                    contentDescription = "搜索",
                                    tint = MiuixTheme.colorScheme.onBackground,
                                )
                            }
                        }
                    },
                )
            },
            bottomBar = {
                NavigationBar {
                    tabs.forEachIndexed { index, tab ->
                        NavigationBarItem(
                            selected = activeTab == index,
                            onClick = {
                                searchExpanded = false
                                selectedTab = index
                                if (index == ACCOUNT_TAB_INDEX && accountProfile == null) {
                                    requestLogin()
                                }
                            },
                            icon = tab.icon,
                            label = tab.label,
                        )
                    }
                }
            },
        ) { innerPadding ->
            AnimatedContent(
                targetState = activeTab,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
            ) { tab ->
                when (tab) {
                    0 -> HomeScreen(
                        innerPadding = innerPadding,
                        state = homeState,
                        isRefreshing = isHomeRefreshing,
                        selectedCategoryIndex = selectedHomeCategory,
                        onCategorySelected = { selectedHomeCategory = it },
                        liftedAlbumId = detailRequest
                            ?.takeIf {
                                it.origin == AlbumDetailOrigin.HOME && it.sourceBounds != null
                            }
                            ?.album
                            ?.id,
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
                                detailRequest = AlbumDetailTransitionRequest(
                                    album = album,
                                    sourceBounds = sourceBounds,
                                    origin = AlbumDetailOrigin.HOME,
                                )
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
                    else -> AccountScreen(
                        innerPadding = innerPadding,
                        profile = accountProfile,
                        imageHost = homeRepository.currentImageHost,
                        onLoginRequested = ::requestLogin,
                        onLogout = {
                            accountRepository.logout()
                            accountProfile = null
                            loginFailure = null
                            selectedTab = 0
                        },
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = searchExpanded && activeTab == 0,
            enter = fadeIn() + slideInVertically(initialOffsetY = { -it / 5 }),
            exit = fadeOut() + slideOutVertically(targetOffsetY = { -it / 5 }),
            modifier = Modifier
                .fillMaxSize()
                .zIndex(5f),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MiuixTheme.colorScheme.surface),
            ) {
                ComicSearchScreen(
                    homeRepository = homeRepository,
                    liftedAlbumId = detailRequest
                        ?.takeIf {
                            it.origin == AlbumDetailOrigin.SEARCH && it.sourceBounds != null
                        }
                        ?.album
                        ?.id,
                    onDismiss = { searchExpanded = false },
                    onAlbumSelected = { album, sourceBounds ->
                        if (detailRequest == null) {
                            detailRequest = AlbumDetailTransitionRequest(
                                album = album,
                                sourceBounds = sourceBounds,
                                origin = AlbumDetailOrigin.SEARCH,
                            )
                        }
                    },
                )
            }
        }

        detailRequest?.let { request ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(6f),
            ) {
                AlbumDetailTransitionHost(
                    request = request,
                    repository = detailRepository,
                    readerActive = readerRequest != null,
                    authenticated = accountProfile != null,
                    onRequireLogin = ::requestLogin,
                    onFavoriteChanged = { added ->
                        accountProfile?.let { profile ->
                            val current = profile.currentFavoriteCount ?: 0
                            val maximum = profile.maxFavoriteCount ?: Int.MAX_VALUE
                            val updated = profile.copy(
                                currentFavoriteCount = (current + if (added) 1 else -1)
                                    .coerceIn(0, maximum),
                            )
                            accountProfile = updated
                            accountRepository.update(updated)
                        }
                    },
                    onStartReading = { readerRequest = it },
                    onDismiss = { detailRequest = null },
                )
            }
        }

        readerRequest?.let { request ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(10f),
            ) {
                ComicReaderScreen(
                    request = request,
                    repository = readerRepository,
                    onBack = { readerRequest = null },
                )
            }
        }

        AccountLoginDialog(
            show = showLogin,
            initialUsername = accountRepository.lastUsername(),
            submitting = loginSubmitting,
            failure = loginFailure,
            onDismiss = {
                if (!loginSubmitting) {
                    showLogin = false
                    loginFailure = null
                }
            },
            onSubmit = { username, password ->
                if (!loginSubmitting) {
                    loginSubmitting = true
                    loginFailure = null
                    coroutineScope.launch {
                        when (val result = accountRepository.login(username, password)) {
                            is JmxResult.Success -> {
                                accountProfile = result.value
                                showLogin = false
                            }
                            is JmxResult.Failure -> {
                                val userMessage = result.error.toUserMessage()
                                loginFailure = LoginUiFailure(
                                    title = userMessage.title,
                                    message = "${userMessage.userMessage}\n详细信息：${result.error.toUiMessage()}",
                                )
                            }
                        }
                        loginSubmitting = false
                    }
                }
            },
        )
    }
}

private data class JmxTab(
    val label: String,
    val icon: ImageVector,
)

private const val ACCOUNT_TAB_INDEX = 1

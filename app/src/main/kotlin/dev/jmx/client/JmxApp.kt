package dev.jmx.client

import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.zIndex
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.NavDisplay
import androidx.navigation3.ui.NavDisplayTransitionEffects
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.WindowCompat
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
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Home
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.icon.basic.Search
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun JmxApp() {
    val tabs = remember {
        listOf(
            JmxTab("首页", MiuixIcons.Home),
            JmxTab("我的", MiuixIcons.Contacts),
        )
    }
    val mainPagerState = rememberJmxMainPagerState(tabs.size)
    val routeStack = remember { mutableStateListOf(JmxRoute.MAIN) }

    val context = LocalContext.current
    val applicationContext = context.applicationContext
    val uriHandler = LocalUriHandler.current
    val homeRepository = remember(applicationContext) { HomeRepository(applicationContext) }
    val updateManager = remember(applicationContext) { AppUpdateManager(applicationContext) }
    val updateState by updateManager.state.collectAsState()
    val accountRepository = remember(homeRepository, applicationContext) {
        AccountRepository(applicationContext, homeRepository.core)
    }
    val detailRepository = remember(homeRepository, accountRepository) {
        AlbumDetailRepository(homeRepository.core, accountRepository)
    }
    val readerRepository = remember(homeRepository, applicationContext) {
        ComicReaderRepository(applicationContext, homeRepository.core)
    }
    val accountDataRepository = remember(homeRepository, accountRepository) {
        AccountDataRepository(homeRepository.core, homeRepository, accountRepository)
    }
    val settingsRepository = remember(homeRepository, applicationContext) {
        AppSettingsRepository(applicationContext, homeRepository.core, homeRepository)
    }
    val coroutineScope = rememberCoroutineScope()
    var accountProfile by remember(accountRepository) { mutableStateOf(accountRepository.restore()) }
    var accountSessionRevision by rememberSaveable { mutableIntStateOf(0) }
    var showLogin by rememberSaveable { mutableStateOf(false) }
    var loginSubmitting by remember { mutableStateOf(false) }
    var loginFailure by remember { mutableStateOf<LoginUiFailure?>(null) }
    var pendingProtectedPage by remember { mutableStateOf<JmxRoute?>(null) }
    var autoCheckIn by rememberSaveable { mutableStateOf(settingsRepository.autoCheckInEnabled()) }
    var homeState by remember { mutableStateOf<HomeUiState>(HomeUiState.Loading) }
    var homeRequestId by rememberSaveable { mutableIntStateOf(0) }
    var isHomeRefreshing by remember { mutableStateOf(false) }
    var selectedHomeCategory by rememberSaveable { mutableIntStateOf(0) }
    var pendingLoadMoreCategoryId by remember { mutableStateOf<String?>(null) }
    var detailRequest by remember { mutableStateOf<AlbumDetailTransitionRequest?>(null) }
    var readerRequest by remember { mutableStateOf<ReaderLaunchRequest?>(null) }
    var searchExpanded by rememberSaveable { mutableStateOf(false) }
    var pendingSearchQuery by rememberSaveable { mutableStateOf<String?>(null) }
    val searchTransitionProgress by animateFloatAsState(
        targetValue = if (searchExpanded) 1f else 0f,
        label = "HomeSearchTopBarProgress",
    )
    val activeTab = mainPagerState.selectedPage.coerceIn(0, tabs.lastIndex)
    val searchSurfaceColor = MiuixTheme.colorScheme.surface

    fun prepareSearchSystemBar() {
        val window = context.findActivity()?.window ?: return
        val color = searchSurfaceColor.toArgb()
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        window.setBackgroundDrawable(color.toDrawable())
        @Suppress("DEPRECATION")
        window.statusBarColor = color
        WindowCompat.getInsetsController(window, window.decorView)
            .isAppearanceLightStatusBars = searchSurfaceColor.luminance() > 0.5f
    }

    fun requestLogin() {
        loginFailure = null
        showLogin = true
    }

    fun navigateAccount(page: JmxRoute) {
        if (routeStack.lastOrNull() == page) return
        routeStack.add(page)
    }

    fun navigateAccountBack() {
        if (routeStack.size > 1) {
            routeStack.removeAt(routeStack.lastIndex)
        } else if (activeTab == ACCOUNT_TAB_INDEX) {
            mainPagerState.animateToPage(0)
        }
    }

    fun openProtectedAccountPage(page: JmxRoute) {
        if (accountProfile == null) {
            pendingProtectedPage = page
            requestLogin()
        } else {
            navigateAccount(page)
        }
    }

    BackHandler(enabled = routeStack.size == 1 && activeTab == ACCOUNT_TAB_INDEX) {
        navigateAccountBack()
    }

    LaunchedEffect(mainPagerState.pagerState.currentPage) {
        mainPagerState.syncPage()
    }

    LaunchedEffect(accountProfile?.id, autoCheckIn, accountDataRepository) {
        val profile = accountProfile
        if (profile != null && autoCheckIn) accountDataRepository.autoCheckIn(profile)
    }

    LaunchedEffect(accountRepository) {
        accountRepository.restoreSession()?.let { accountProfile = it }
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

    LaunchedEffect(updateManager) {
        updateManager.checkForUpdates(manual = false, fromStartup = true)
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
        NavDisplay(
            backStack = routeStack,
            modifier = Modifier.fillMaxSize(),
            onBack = ::navigateAccountBack,
            transitionEffects = NavDisplayTransitionEffects.Default,
            entryProvider = entryProvider<JmxRoute> {
                entry<JmxRoute> { route ->
                    when (route) {
                        JmxRoute.MAIN -> Scaffold(
                            modifier = Modifier.fillMaxSize(),
                            bottomBar = {
                                NavigationBar {
                                    tabs.forEachIndexed { index, tab ->
                                        NavigationBarItem(
                                            selected = mainPagerState.selectedPage == index,
                                            onClick = {
                                                searchExpanded = false
                                                mainPagerState.animateToPage(index)
                                            },
                                            icon = tab.icon,
                                            label = tab.label,
                                        )
                                    }
                                }
                            },
                        ) { outerPadding ->
                            HorizontalPager(
                                state = mainPagerState.pagerState,
                                modifier = Modifier.fillMaxSize(),
                                userScrollEnabled = false,
                            ) { tab ->
                                when (tab) {
                                    0 -> Scaffold(
                                        modifier = Modifier.fillMaxSize(),
                                        topBar = {
                                            SmallTopAppBar(
                                                title = "JMComicX",
                                                modifier = Modifier.graphicsLayer {
                                                    translationY = size.height * searchTransitionProgress * 0.72f
                                                    alpha = 1f - searchTransitionProgress
                                                },
                                                actions = {
                                                    IconButton(onClick = {
                                                        prepareSearchSystemBar()
                                                        pendingSearchQuery = null
                                                        searchExpanded = true
                                                    }) {
                                                        Icon(
                                                            imageVector = MiuixIcons.Basic.Search,
                                                            contentDescription = "搜索",
                                                            tint = MiuixTheme.colorScheme.onBackground,
                                                        )
                                                    }
                                                },
                                            )
                                        },
                                    ) { pagePadding ->
                                        HomeScreen(
                                            innerPadding = PaddingValues(
                                                top = pagePadding.calculateTopPadding(),
                                                bottom = outerPadding.calculateBottomPadding(),
                                            ),
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
                                    }
                                    else -> Scaffold(
                                        modifier = Modifier.fillMaxSize(),
                                        topBar = {
                                            SmallTopAppBar(
                                                title = "我的",
                                                actions = {
                                                    IconButton(onClick = { navigateAccount(JmxRoute.SETTINGS) }) {
                                                        Icon(
                                                            imageVector = MiuixIcons.Settings,
                                                            contentDescription = "设置",
                                                            tint = MiuixTheme.colorScheme.onBackground,
                                                        )
                                                    }
                                                },
                                            )
                                        },
                                    ) { pagePadding ->
                                        AccountScreen(
                                            innerPadding = PaddingValues(
                                                top = pagePadding.calculateTopPadding(),
                                                bottom = outerPadding.calculateBottomPadding(),
                                            ),
                                            profile = accountProfile,
                                            imageHost = homeRepository.currentImageHost,
                                            onLoginRequested = ::requestLogin,
                                            onLogout = {
                                                accountRepository.logout()
                                                accountProfile = null
                                                loginFailure = null
                                            },
                                            onFavorites = { openProtectedAccountPage(JmxRoute.FAVORITES) },
                                            onHistory = { openProtectedAccountPage(JmxRoute.HISTORY) },
                                            onDaily = { openProtectedAccountPage(JmxRoute.DAILY) },
                                            onAbout = { navigateAccount(JmxRoute.ABOUT) },
                                        )
                                    }
                                }
                            }
                        }
                        JmxRoute.ABOUT -> AboutScreen(
                            innerPadding = PaddingValues(),
                            onBack = ::navigateAccountBack,
                            onThirdParty = { navigateAccount(JmxRoute.THIRD_PARTY) },
                        )
                        else -> Scaffold(
                            modifier = Modifier.fillMaxSize(),
                            topBar = {
                                SmallTopAppBar(
                                    title = route.title,
                                    navigationIcon = {
                                        IconButton(onClick = ::navigateAccountBack) {
                                            Icon(
                                                imageVector = MiuixIcons.Back,
                                                contentDescription = "返回",
                                                tint = MiuixTheme.colorScheme.onBackground,
                                            )
                                        }
                                    },
                                )
                            },
                        ) { innerPadding ->
                            when (route) {
                                JmxRoute.FAVORITES,
                                JmxRoute.HISTORY,
                                -> AccountCollectionScreen(
                                    innerPadding = innerPadding,
                                    kind = if (route == JmxRoute.FAVORITES) {
                                        AccountCollectionKind.FAVORITES
                                    } else {
                                        AccountCollectionKind.HISTORY
                                    },
                                    repository = accountDataRepository,
                                    sessionRevision = accountSessionRevision,
                                    liftedAlbumId = detailRequest
                                        ?.takeIf {
                                            it.origin == AlbumDetailOrigin.ACCOUNT && it.sourceBounds != null
                                        }
                                        ?.album
                                        ?.id,
                                    onAlbumSelected = { album, sourceBounds ->
                                        if (detailRequest == null) {
                                            detailRequest = AlbumDetailTransitionRequest(
                                                album = album,
                                                sourceBounds = sourceBounds,
                                                origin = AlbumDetailOrigin.ACCOUNT,
                                            )
                                        }
                                    },
                                    onRequireLogin = {
                                        pendingProtectedPage = route
                                        requestLogin()
                                    },
                                )
                                JmxRoute.DAILY -> accountProfile?.let {
                                    DailyCheckScreen(innerPadding, it, accountDataRepository)
                                } ?: AccountScreen(
                                    innerPadding = innerPadding,
                                    profile = null,
                                    imageHost = homeRepository.currentImageHost,
                                    onLoginRequested = ::requestLogin,
                                    onLogout = {},
                                    onFavorites = { openProtectedAccountPage(JmxRoute.FAVORITES) },
                                    onHistory = { openProtectedAccountPage(JmxRoute.HISTORY) },
                                    onDaily = { openProtectedAccountPage(JmxRoute.DAILY) },
                                    onAbout = { navigateAccount(JmxRoute.ABOUT) },
                                )
                                JmxRoute.THIRD_PARTY -> ThirdPartyListScreen(innerPadding)
                                JmxRoute.SETTINGS -> SettingsScreen(
                                    innerPadding = innerPadding,
                                    repository = settingsRepository,
                                    autoCheckIn = autoCheckIn,
                                    onAutoCheckInChanged = {
                                        autoCheckIn = it
                                        settingsRepository.setAutoCheckInEnabled(it)
                                    },
                                    autoCheckUpdates = updateState.autoCheckEnabled,
                                    checkingForUpdates = updateState.checking,
                                    currentVersion = updateManager.localVersionName,
                                    onAutoCheckUpdatesChanged = updateManager::setAutoCheckEnabled,
                                    onCheckForUpdates = {
                                        coroutineScope.launch {
                                            updateManager.checkForUpdates(manual = true)
                                        }
                                    },
                                    onImageHostChanged = { homeRequestId++ },
                                )
                                JmxRoute.MAIN,
                                JmxRoute.ABOUT,
                                -> Unit
                            }
                        }
                    }
                }
            },
        )

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
                    initialQuery = pendingSearchQuery,
                    manageSystemBar = detailRequest == null && readerRequest == null,
                    liftedAlbumId = detailRequest
                        ?.takeIf {
                            it.origin == AlbumDetailOrigin.SEARCH && it.sourceBounds != null
                        }
                        ?.album
                        ?.id,
                    onDismiss = {
                        searchExpanded = false
                        pendingSearchQuery = null
                    },
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
                    onSearchRequested = { query ->
                        prepareSearchSystemBar()
                        pendingSearchQuery = query
                        while (routeStack.size > 1) routeStack.removeAt(routeStack.lastIndex)
                        mainPagerState.animateToPage(0)
                        searchExpanded = true
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
                                accountSessionRevision++
                                showLogin = false
                                pendingProtectedPage?.let(::navigateAccount)
                                pendingProtectedPage = null
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

        AppUpdateDialog(
            info = updateState.availableUpdate,
            launchingDownload = updateState.launchingDownload,
            onLater = updateManager::dismissUpdate,
            onUpdate = {
                val info = updateState.availableUpdate ?: return@AppUpdateDialog
                coroutineScope.launch {
                    val url = updateManager.resolveDownloadUrl(info)
                    runCatching { uriHandler.openUri(url) }
                        .onSuccess { updateManager.dismissUpdate() }
                        .onFailure { updateManager.reportDownloadLaunchFailure() }
                }
            },
        )
        UpdateResultDialog(
            message = updateState.resultMessage,
            onDismiss = updateManager::dismissResultMessage,
        )
    }
}

private data class JmxTab(
    val label: String,
    val icon: ImageVector,
)

private const val ACCOUNT_TAB_INDEX = 1

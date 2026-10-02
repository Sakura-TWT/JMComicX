package dev.jmx.client

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
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
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.WindowCompat
import dev.jmx.client.core.result.JmxResult
import dev.jmx.client.core.result.toUserMessage
import dev.jmx.client.effect.BlurredBar
import dev.jmx.client.effect.FloatingNavBarStyle
import dev.jmx.client.effect.TopBarBlurStyle
import dev.jmx.client.effect.rememberBarBackdrop
import dev.jmx.client.effect.BlurredFloatingNavigationBar
import dev.jmx.client.effect.liquid.IosLiquidGlassNavigationBar
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.FloatingNavigationBarItem
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.NavigationItem
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.utils.springAnimateToPage
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.nav.core.NavDisplay
import top.yukonga.miuix.kmp.nav.core.navBackStackOf
import top.yukonga.miuix.kmp.nav.transition.NavTransitions
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Contacts
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Home
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.icon.extended.Notes
import top.yukonga.miuix.kmp.icon.basic.Search
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun JmxApp(
    themeMode: AppThemeMode,
    onThemeModeChanged: (AppThemeMode) -> Unit,
) {
    val tabs = remember {
        listOf(
            JmxTab("首页", MiuixIcons.Home),
            JmxTab("书架", MiuixIcons.Notes),
            JmxTab("我的", MiuixIcons.Contacts),
        )
    }
    val mainPagerState = rememberJmxMainPagerState(tabs.size)
    val liquidNavigationItems = remember(tabs) {
        tabs.map { NavigationItem(it.label, it.icon) }
    }
    val routeStack = remember { navBackStackOf(JmxRoute.MAIN) }

    val context = LocalContext.current
    val applicationContext = context.applicationContext
    val uriHandler = LocalUriHandler.current
    val lifecycleOwner = LocalLifecycleOwner.current
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
    var topBarBlurStyle by remember(settingsRepository) {
        mutableStateOf(settingsRepository.topBarBlurStyle())
    }
    var liquidGlassNavBar by remember(settingsRepository) {
        mutableStateOf(settingsRepository.liquidGlassNavBarEnabled())
    }
    var floatingNavBarStyle by remember(settingsRepository) {
        mutableStateOf(settingsRepository.floatingNavBarStyle())
    }
    var favoriteSortOrder by remember(settingsRepository) {
        mutableStateOf(settingsRepository.favoriteSortOrder())
    }
    var favoriteSortDirection by remember(settingsRepository) {
        mutableStateOf(settingsRepository.favoriteSortDirection())
    }
    val bookshelfRepository = remember(applicationContext) {
        BookshelfRepository(applicationContext)
    }
    val albumUpdateCenter = remember(
        applicationContext,
        homeRepository,
        bookshelfRepository,
        accountDataRepository,
    ) {
        AlbumUpdateCenter(
            context = applicationContext,
            core = homeRepository.core,
            bookshelfRepository = bookshelfRepository,
            accountDataRepository = accountDataRepository,
        )
    }
    val albumUpdateRecords by albumUpdateCenter.records.collectAsState()
    // 收藏成员单独订阅：角标只数收藏里的更新，得在收藏集合变化时重算。
    val albumFavoriteIds by albumUpdateCenter.favoriteAlbumIds.collectAsState()
    val scanningAlbumUpdates by albumUpdateCenter.scanning.collectAsState()
    val albumUpdateSummary = remember(albumUpdateRecords, scanningAlbumUpdates) {
        val pending = albumUpdateRecords.values.count(AlbumUpdateRecord::hasUpdate)
        when {
            scanningAlbumUpdates -> "正在检查书架与收藏中的漫画"
            albumUpdateRecords.isEmpty() -> "还没有检查过"
            pending > 0 -> "$pending 部漫画有更新"
            else -> "已跟踪 ${albumUpdateRecords.size} 部漫画，暂无更新"
        }
    }
    val coroutineScope = rememberCoroutineScope()
    val offlineManager = remember(applicationContext) { OfflineDownloadManager.get(applicationContext) }
    val offlineAlbums by offlineManager.albums.collectAsState()
    val offlineReady by offlineManager.ready.collectAsState()
    val offlineError by offlineManager.initializationError.collectAsState()
    val offlineProgress = remember(applicationContext) { OfflineReadingProgressStore(applicationContext) }
    var offlineBusy by remember { mutableStateOf(false) }
    var exportIds by remember { mutableStateOf<Set<String>?>(null) }
    var detailOfflineManifest by remember { mutableStateOf<ReaderOfflineManifest?>(null) }

    fun enqueueDownload(request: Triple<HomeAlbum, dev.jmx.client.core.api.AlbumDetail, Set<String>>) {
        coroutineScope.launch {
            try {
                offlineManager.enqueue(request.first, request.second, request.third)
                Toast.makeText(context, "已加入离线下载", Toast.LENGTH_SHORT).show()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Toast.makeText(context, failure.message ?: "无法启动下载", Toast.LENGTH_LONG).show()
            }
        }
    }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) Toast.makeText(context, "通知未开启，可在离线下载页查看进度", Toast.LENGTH_LONG).show()
    }
    fun requestDownload(album: HomeAlbum, detail: dev.jmx.client.core.api.AlbumDetail, ids: Set<String>) {
        enqueueDownload(Triple(album, detail, ids.toSet()))
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    fun downloadOperation(block: suspend () -> Unit) {
        if (offlineBusy) return
        offlineBusy = true
        coroutineScope.launch {
            try { block() }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (failure: Exception) { Toast.makeText(context, failure.message ?: "下载操作失败", Toast.LENGTH_LONG).show() }
            finally { offlineBusy = false }
        }
    }
    LaunchedEffect(offlineManager) {
        try { offlineManager.recoverPendingDownloads() }
        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { Toast.makeText(context, "下载任务恢复失败，可在离线下载页重试", Toast.LENGTH_LONG).show() }
    }
    var accountProfile by remember(accountRepository) { mutableStateOf(accountRepository.restore()) }
    var accountSessionRevision by rememberSaveable { mutableIntStateOf(0) }
    val favoriteSelection = remember(accountSessionRevision, accountProfile?.id) { FavoriteCollectionSelection() }
    var showLogin by rememberSaveable { mutableStateOf(false) }
    var loginSubmitting by remember { mutableStateOf(false) }
    var loginFailure by remember { mutableStateOf<LoginUiFailure?>(null) }
    var pendingProtectedPage by remember { mutableStateOf<JmxRoute?>(null) }
    var autoCheckIn by rememberSaveable { mutableStateOf(settingsRepository.autoCheckInEnabled()) }
    var accountSessionRestored by remember { mutableStateOf(false) }
    var foregroundRevision by remember { mutableIntStateOf(0) }
    var homeState by remember { mutableStateOf<HomeUiState>(HomeUiState.Loading) }
    var homeRequestId by rememberSaveable { mutableIntStateOf(0) }
    var isHomeRefreshing by remember { mutableStateOf(false) }
    // 首页分类的分页状态提到这里：顶栏标签行与首页内容区共用同一个 PagerState，
    // 标签指示器直接跟随分页进度，不再靠两份状态互相回写（那会滞后到分页停稳之后）。
    val homePagerState = rememberPagerState {
        (homeState as? HomeUiState.Content)?.categories?.size ?: 0
    }
    var pendingLoadMoreCategoryId by remember { mutableStateOf<String?>(null) }
    var detailRequest by remember { mutableStateOf<AlbumDetailTransitionRequest?>(null) }
    var readerRequest by remember { mutableStateOf<ReaderLaunchRequest?>(null) }
    var bookshelfRevision by remember { mutableIntStateOf(0) }
    // 从分组序列页点了某个分组：书架重建后由它把分页落到那一格，消费完立刻清空。
    var pendingBookshelfGroupId by remember { mutableStateOf<String?>(null) }
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
        when {
            accountProfile != null -> navigateAccount(page)
            // 会话还在后台恢复：本地有凭据就说明用户并没有退出登录，此时弹登录框只是打断他。
            // 记下目标页，恢复完成后由下面的 LaunchedEffect 接着跳过去。
            !accountSessionRestored && accountRepository.hasStoredCredentials() -> {
                pendingProtectedPage = page
            }
            else -> {
                pendingProtectedPage = page
                requestLogin()
            }
        }
    }

    BackHandler(enabled = routeStack.size == 1 && activeTab == ACCOUNT_TAB_INDEX) {
        navigateAccountBack()
    }

    LaunchedEffect(mainPagerState.pagerState.currentPage) {
        mainPagerState.syncPage()
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) foregroundRevision++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(accountRepository) {
        try {
            accountRepository.restoreSession()?.let { accountProfile = it }
        } finally {
            accountSessionRestored = true
        }
        // 恢复期间用户点过收藏/历史：现在才知道该直接放行还是确实需要手动登录。
        val pending = pendingProtectedPage ?: return@LaunchedEffect
        if (accountProfile != null) {
            pendingProtectedPage = null
            navigateAccount(pending)
        } else if (!showLogin) {
            requestLogin()
        }
    }

    LaunchedEffect(
        accountProfile?.id,
        autoCheckIn,
        accountSessionRestored,
        foregroundRevision,
        accountDataRepository,
        settingsRepository,
    ) {
        val profile = accountProfile
        if (profile == null || !autoCheckIn || !accountSessionRestored) return@LaunchedEffect
        if (settingsRepository.autoCheckInCompletedToday()) return@LaunchedEffect

        for (delayMillis in AUTO_CHECK_IN_RETRY_DELAYS_MILLIS) {
            if (delayMillis > 0L) delay(delayMillis)
            if (settingsRepository.autoCheckInCompletedToday()) return@LaunchedEffect
            when (accountDataRepository.autoCheckIn(profile)) {
                AutoCheckInResult.COMPLETED,
                AutoCheckInResult.ALREADY_SIGNED,
                -> {
                    settingsRepository.markAutoCheckInCompleted()
                    return@LaunchedEffect
                }
                // 服务端暂时没有活动时不写完成标记；前台重入和下次冷启动仍会重新探测。
                AutoCheckInResult.NO_ACTIVE_EVENT -> return@LaunchedEffect
                AutoCheckInResult.FAILED -> Unit
            }
        }
    }

    /**
     * 后台更新扫描。
     *
     * 冷启动与每次回到前台各探一次，真正出不出网由 [AlbumUpdateCenter.isStale] 决定
     * （一轮扫描是"每部漫画一次 /album"，不能随手就打）。
     * 登录之后才把收藏纳进来：未登录时收藏接口拿不到东西。
     */
    LaunchedEffect(
        accountProfile?.id,
        accountSessionRestored,
        foregroundRevision,
        bookshelfRevision,
        albumUpdateCenter,
    ) {
        albumUpdateCenter.scan(
            includeFavorites = accountProfile != null && accountSessionRestored,
            force = false,
        )
    }

    LaunchedEffect(homeRepository, homeRequestId) {
        val previousState = homeState
        val wasRefreshing = isHomeRefreshing
        val previousCategoryId = (previousState as? HomeUiState.Content)
            ?.categories
            ?.getOrNull(homePagerState.currentPage)
            ?.id
        val updatedState = homeRepository.load(preloadCategoryId = previousCategoryId)
        homeState = when {
            wasRefreshing && updatedState is HomeUiState.Error && previousState is HomeUiState.Content -> previousState
            else -> updatedState
        }
        if (updatedState is HomeUiState.Content) {
            val restoredPage = previousCategoryId
                ?.let { id -> updatedState.categories.indexOfFirst { it.id == id }.takeIf { it >= 0 } }
                ?: 0
            if (homePagerState.currentPage != restoredPage) {
                homePagerState.scrollToPage(restoredPage)
            }
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
            transition = NavTransitions.MiuixDefault,
        ) {
                entry<JmxRoute> { route ->
                    Box(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface)) {
                    when (route) {
                        JmxRoute.MAIN -> {
                            val navigationBackdrop = rememberBarBackdrop()
                            Scaffold(
                            modifier = Modifier.fillMaxSize(),
                            containerColor = Color.Transparent,
                            bottomBar = {
                                if (liquidGlassNavBar) {
                                    if (floatingNavBarStyle == FloatingNavBarStyle.IOS_LIKE) {
                                        IosLiquidGlassNavigationBar(
                                            items = liquidNavigationItems,
                                            selectedIndex = mainPagerState.selectedPage,
                                            onItemClick = { index ->
                                                searchExpanded = false
                                                mainPagerState.animateToPage(index)
                                            },
                                            backdrop = navigationBackdrop,
                                            isBlurActive = navigationBackdrop != null,
                                        )
                                    } else {
                                        BlurredFloatingNavigationBar(backdrop = navigationBackdrop) {
                                            tabs.forEachIndexed { index, tab ->
                                                FloatingNavigationBarItem(
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
                                    }
                                } else {
                                    BlurredBar(
                                        backdrop = navigationBackdrop,
                                        style = TopBarBlurStyle.GAUSSIAN,
                                        modifier = Modifier.background(
                                            if (navigationBackdrop != null) {
                                                Color.Transparent
                                            } else {
                                                MiuixTheme.colorScheme.surface
                                            },
                                        ),
                                    ) {
                                        NavigationBar(
                                            color = if (navigationBackdrop != null) {
                                                Color.Transparent
                                            } else {
                                                MiuixTheme.colorScheme.surface
                                            },
                                        ) {
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
                                    }
                                }
                            },
                        ) { outerPadding ->
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .then(
                                        if (navigationBackdrop != null) {
                                            Modifier.layerBackdrop(navigationBackdrop)
                                        } else {
                                            Modifier
                                        },
                                    ),
                            ) {
                                HorizontalPager(
                                    state = mainPagerState.pagerState,
                                    modifier = Modifier.fillMaxSize(),
                                    userScrollEnabled = false,
                                ) { tab ->
                                when (tab) {
                                    0 -> {
                                        val pageBackdrop = rememberBarBackdrop()
                                        Scaffold(
                                        modifier = Modifier.fillMaxSize(),
                                        containerColor = Color.Transparent,
                                        topBar = {
                                            BlurredBar(
                                                backdrop = pageBackdrop,
                                                style = topBarBlurStyle,
                                            ) {
                                                androidx.compose.foundation.layout.Column {
                                                SmallTopAppBar(
                                                    title = "JMComicX",
                                                    color = if (pageBackdrop != null) Color.Transparent else MiuixTheme.colorScheme.surface,
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
                                                val homeCategoryTitles = (homeState as? HomeUiState.Content)
                                                    ?.categories
                                                    ?.map { it.title }
                                                    ?: emptyList()
                                                dev.jmx.client.effect.BlurTabRow(
                                                    tabs = homeCategoryTitles,
                                                    selectedIndex = homePagerState.currentPage,
                                                    onTabSelected = { index ->
                                                        if (!searchExpanded && searchTransitionProgress == 0f && detailRequest == null && readerRequest == null) {
                                                            coroutineScope.launch { homePagerState.springAnimateToPage(index) }
                                                        }
                                                    },
                                                    // 指示器与标签行滚动跟随分页实时进度，手动滑动时不再滞后。
                                                    selectionProgress = {
                                                        homePagerState.currentPage +
                                                            homePagerState.currentPageOffsetFraction
                                                    },
                                                    modifier = Modifier.graphicsLayer {
                                                        alpha = 1f - searchTransitionProgress
                                                    },
                                                )
                                                }
                                            }
                                        },
                                    ) { pagePadding ->
                                        HomeScreen(
                                            innerPadding = PaddingValues(
                                                top = pagePadding.calculateTopPadding(),
                                                bottom = outerPadding.calculateBottomPadding(),
                                            ),
                                            backdrop = pageBackdrop,
                                            state = homeState,
                                            isRefreshing = isHomeRefreshing,
                                            pagerState = homePagerState,
                                            pagerEnabled = !searchExpanded && searchTransitionProgress == 0f && detailRequest == null && readerRequest == null,
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
                                    }
                                    1 -> BookshelfScreen(
                                        innerPadding = PaddingValues(
                                            bottom = outerPadding.calculateBottomPadding(),
                                        ),
                                        repository = bookshelfRepository,
                                        detailRepository = detailRepository,
                                        accountDataRepository = accountDataRepository,
                                        homeRepository = homeRepository,
                                        authenticated = accountProfile != null,
                                        onRequireLogin = ::requestLogin,
                                        revision = bookshelfRevision,
                                        liftedAlbumId = detailRequest
                                            ?.takeIf {
                                                it.origin == AlbumDetailOrigin.BOOKSHELF && it.sourceBounds != null
                                            }
                                            ?.album
                                            ?.id,
                                        onAlbumSelected = { album, sourceBounds ->
                                            if (detailRequest == null) {
                                                detailRequest = AlbumDetailTransitionRequest(
                                                    album = album,
                                                    sourceBounds = sourceBounds,
                                                    origin = AlbumDetailOrigin.BOOKSHELF,
                                                )
                                            }
                                        },
                                        onOpenGroupOrder = {
                                            navigateAccount(JmxRoute.GROUP_ORDER)
                                        },
                                        pendingGroupId = pendingBookshelfGroupId,
                                        onPendingGroupConsumed = { pendingBookshelfGroupId = null },
                                        topBarBlurStyle = topBarBlurStyle,
                                        updateRecords = albumUpdateRecords,
                                        onRefresh = {
                                            // 下拉刷新走强制扫描：用户明确要求"现在就看"，
                                            // 不能被最小扫描间隔挡住。
                                            albumUpdateCenter.scan(
                                                includeFavorites = accountProfile != null,
                                                force = true,
                                            )
                                        },
                                    )
                                    else -> {
                                        val pageBackdrop = rememberBarBackdrop()
                                        Scaffold(
                                        modifier = Modifier.fillMaxSize(),
                                        containerColor = Color.Transparent,
                                        topBar = {
                                            BlurredBar(
                                                backdrop = pageBackdrop,
                                                style = topBarBlurStyle,
                                            ) {
                                                SmallTopAppBar(
                                                    title = "我的",
                                                    color = if (pageBackdrop != null) Color.Transparent else MiuixTheme.colorScheme.surface,
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
                                            }
                                        },
                                    ) { pagePadding ->
                                        AccountScreen(
                                            innerPadding = PaddingValues(
                                                top = pagePadding.calculateTopPadding(),
                                                bottom = outerPadding.calculateBottomPadding(),
                                            ),
                                            backdrop = pageBackdrop,
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
                                            onDownloads = { navigateAccount(JmxRoute.DOWNLOADS) },
                                            // 只数收藏里的更新：仅在书架、没被收藏的漫画不该让收藏冒红点。
                                            // release 下 albumUpdateRecords 恒为空，角标自然不显示。
                                            favoriteUpdateCount = albumUpdateRecords
                                                .let { countPendingFavorites(it, albumFavoriteIds) },
                                        )
                                        }
                                    }
                                    }
                                }
                                    }
                                }
                        }
                        JmxRoute.DOWNLOADS -> OfflineDownloadsScreen(
                            state = OfflineDownloadsUiState(
                                items = offlineAlbums.map { it.toDownloadUiModel() },
                                loading = !offlineReady,
                                error = offlineError,
                                busy = offlineBusy,
                            ),
                            onBack = ::navigateAccountBack,
                            onRetry = { downloadOperation { offlineManager.retryInitialization() } },
                            onAlbumClick = { album, bounds ->
                                if (detailRequest == null) downloadOperation {
                                    val manifest = offlineManager.readerManifest(album.id)
                                    if (manifest != null) {
                                        detailOfflineManifest = manifest
                                        detailRequest = AlbumDetailTransitionRequest(album, bounds, AlbumDetailOrigin.DOWNLOADS)
                                    }
                                }
                            },
                            onDelete = { ids -> downloadOperation { offlineManager.delete(ids) } },
                            onExport = { exportIds = it },
                            onPause = { id -> downloadOperation { offlineManager.pause(setOf(id)) } },
                            onResume = { id -> downloadOperation { offlineManager.resume(setOf(id)) } },
                            onRetryDownload = { id -> downloadOperation { offlineManager.resume(setOf(id)) } },
                            onRetryDownloads = { ids -> downloadOperation { offlineManager.resume(ids) } },
                            topBarBlurStyle = topBarBlurStyle,
                            liftedAlbumId = detailRequest?.takeIf { it.origin == AlbumDetailOrigin.DOWNLOADS && it.sourceBounds != null }?.album?.id,
                        )
                        JmxRoute.ABOUT -> AboutScreen(
                            innerPadding = PaddingValues(),
                            onBack = ::navigateAccountBack,
                            onThirdParty = { navigateAccount(JmxRoute.THIRD_PARTY) },
                        )
                        // 分组序列自带顶栏（下拉菜单 + 完成），套不进下面 else 里那个通用顶栏。
                        JmxRoute.GROUP_ORDER -> BookshelfGroupOrderScreen(
                            innerPadding = PaddingValues(),
                            repository = bookshelfRepository,
                            onBack = {
                                // 顺序可能被改过，回书架时让它重新读一遍分组。
                                bookshelfRevision++
                                navigateAccountBack()
                            },
                            onLocateGroup = { groupId ->
                                pendingBookshelfGroupId = groupId
                                bookshelfRevision++
                                navigateAccountBack()
                            },
                            topBarBlurStyle = topBarBlurStyle,
                        )
                        else -> {
                            val pageBackdrop = rememberBarBackdrop()
                            Scaffold(
                            modifier = Modifier.fillMaxSize(),
                            containerColor = Color.Transparent,
                            topBar = {
                                // Favorites owns its real Scaffold.topBar; this parent is only a popup host.
                                // Its fallback status-bar padding is not inherited by the favorites screen.
                                if (route != JmxRoute.FAVORITES) {
                                BlurredBar(
                                    backdrop = pageBackdrop,
                                    style = topBarBlurStyle,
                                ) {
                                    SmallTopAppBar(
                                        title = route.title,
                                        color = if (pageBackdrop != null) Color.Transparent else MiuixTheme.colorScheme.surface,
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
                                }
                                }
                            },
                        ) { innerPadding ->
                            when (route) {
                                JmxRoute.FAVORITES,
                                JmxRoute.HISTORY,
                                -> AccountCollectionScreen(
                                    innerPadding = if (route == JmxRoute.FAVORITES) PaddingValues() else innerPadding,
                                    backdrop = pageBackdrop,
                                    kind = if (route == JmxRoute.FAVORITES) {
                                        AccountCollectionKind.FAVORITES
                                    } else {
                                        AccountCollectionKind.HISTORY
                                    },
                                    repository = accountDataRepository,
                                    selection = favoriteSelection,
                                    topBarBlurStyle = topBarBlurStyle,
                                    sessionRevision = accountSessionRevision,
                                    favoriteOrder = favoriteSortOrder,
                                    favoriteDirection = favoriteSortDirection,
                                    updateRecords = albumUpdateRecords,
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
                                    title = route.title.takeIf { route == JmxRoute.FAVORITES },
                                    onBack = ::navigateAccountBack.takeIf { route == JmxRoute.FAVORITES },
                                    topBarActions = {
                                        if (route == JmxRoute.FAVORITES && !favoriteSelection.selecting) {
                                            FavoriteSortAction(
                                                order = favoriteSortOrder,
                                                direction = favoriteSortDirection,
                                                onExpandedChange = { favoriteSelection.sortMenuExpanded = it },
                                                onOrderSelected = {
                                                    favoriteSortOrder = it
                                                    settingsRepository.setFavoriteSortOrder(it)
                                                },
                                                onDirectionSelected = {
                                                    favoriteSortDirection = it
                                                    settingsRepository.setFavoriteSortDirection(it)
                                                },
                                            )
                                        }
                                    },
                                )
                                JmxRoute.DAILY -> accountProfile?.let {
                                    DailyCheckScreen(
                                        innerPadding = innerPadding,
                                        profile = it,
                                        repository = accountDataRepository,
                                        onRequireLogin = {
                                            pendingProtectedPage = route
                                            requestLogin()
                                        },
                                    )
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
                                            onDownloads = { navigateAccount(JmxRoute.DOWNLOADS) },
                                )
                                JmxRoute.THIRD_PARTY -> ThirdPartyListScreen(innerPadding)
                                JmxRoute.SETTINGS -> SettingsScreen(
                                    innerPadding = innerPadding,
                                    backdrop = pageBackdrop,
                                    repository = settingsRepository,
                                    themeMode = themeMode,
                                    onThemeModeChanged = onThemeModeChanged,
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
                                    onContentLanguageChanged = {
                                        // 简繁由服务端渲染后返回，已加载的标题/简介/标签不会自行变化，
                                        // 必须像切换图源那样丢弃当前内容重新请求。
                                        homeRequestId++
                                        bookshelfRevision++
                                        accountSessionRevision++
                                    },
                                    onTopBarBlurStyleChanged = { topBarBlurStyle = it },
                                    onLiquidGlassNavBarChanged = { liquidGlassNavBar = it },
                                    onFloatingNavBarStyleChanged = { floatingNavBarStyle = it },
                                    comicUpdateSummary = albumUpdateSummary,
                                    scanningComicUpdates = scanningAlbumUpdates,
                                    onScanComicUpdates = {
                                        coroutineScope.launch {
                                            albumUpdateCenter.scan(
                                                includeFavorites = accountProfile != null,
                                                force = true,
                                            )
                                        }
                                    },
                                    onSimulateComicUpdates = {
                                        albumUpdateCenter.simulateUpdates(
                                            albums = SIMULATED_UPDATE_ALBUMS,
                                            chapters = SIMULATED_UPDATE_CHAPTERS,
                                        )
                                    },
                                    onClearComicUpdates = albumUpdateCenter::clearAllPending,
                                )
                                JmxRoute.MAIN,
                                JmxRoute.ABOUT,
                                JmxRoute.GROUP_ORDER,
                                JmxRoute.DOWNLOADS,
                                -> Unit
                            }
                        }
                        }
                    }
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

        // 点开就算"看过"：用户要求不必真的阅读。这里先按已知话数清掉提示，
        // 详情加载完还会用更准的章节数再对一次账（onChaptersLoaded）。
        LaunchedEffect(detailRequest?.album?.id) {
            detailRequest?.album?.id?.let { albumUpdateCenter.markSeen(it) }
        }

        val offlineDetailVersion = offlineAlbums.firstOrNull { it.id == detailRequest?.album?.id }
            ?.let { item -> item.chapters.map { it.id to it.completed } }
        LaunchedEffect(detailRequest?.album?.id, offlineDetailVersion) {
            val request = detailRequest
            if (request?.origin == AlbumDetailOrigin.DOWNLOADS) {
                detailOfflineManifest = offlineManager.readerManifest(request.album.id)
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
                    favoriteRepository = accountDataRepository,
                    sessionRevision = accountSessionRevision,
                    offlineAlbum = if (request.origin == AlbumDetailOrigin.DOWNLOADS) offlineAlbums.firstOrNull { it.id == request.album.id } else null,
                    offlineManifest = if (request.origin == AlbumDetailOrigin.DOWNLOADS) detailOfflineManifest else null,
                    onDownload = ::requestDownload,
                    readerActive = readerRequest != null,
                    authenticated = accountProfile != null,
                    onRequireLogin = ::requestLogin,
                    bookshelfRepository = bookshelfRepository,
                    onBookshelfChanged = { bookshelfRevision++ },
                    onFavoriteChanged = { added ->
                        accountSessionRevision++
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
                    onChaptersLoaded = { albumId, chapterCount ->
                        albumUpdateCenter.markSeen(albumId, chapterCount)
                    },
                    topBarBlurStyle = topBarBlurStyle,
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
                    onProgress = { progress ->
                        if (request.source is ReaderResourceSource.Offline) offlineProgress.save(progress)
                        bookshelfRepository.recordProgress(
                            albumId = progress.album.id,
                            chapterId = progress.chapterId,
                            chapterName = progress.chapterName,
                            pageIndex = progress.pageIndex,
                            pageCount = progress.pageCount,
                        )
                    },
                    onBack = {
                        readerRequest = null
                        bookshelfRevision++
                    },
                )
            }
        }

        DownloadExportDialogs(manager = offlineManager, selectedIds = exportIds, onDismiss = { exportIds = null })

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
                        try {
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
                        } catch (cancellation: kotlinx.coroutines.CancellationException) {
                            throw cancellation
                        } catch (error: Throwable) {
                            // 兜底：登录路径中任何未被 JmxResult 包裹的异常都在此转为可见的登录错误，
                            // 而非让协程未捕获异常直接使 App 闪退。
                            loginFailure = LoginUiFailure(
                                title = "登录失败",
                                message = error.message ?: "发生未知错误，请稍后重试。",
                            )
                        } finally {
                            loginSubmitting = false
                        }
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

private const val ACCOUNT_TAB_INDEX = 2
private val AUTO_CHECK_IN_RETRY_DELAYS_MILLIS = longArrayOf(0L, 15_000L, 45_000L)

package dev.jmx.client

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
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
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt
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
    val accountDataRepository = remember(homeRepository) {
        AccountDataRepository(homeRepository.core, homeRepository)
    }
    val settingsRepository = remember(homeRepository, applicationContext) {
        AppSettingsRepository(applicationContext, homeRepository.core, homeRepository)
    }
    val coroutineScope = rememberCoroutineScope()
    var accountProfile by remember(accountRepository) { mutableStateOf(accountRepository.restore()) }
    var showLogin by rememberSaveable { mutableStateOf(false) }
    var loginSubmitting by remember { mutableStateOf(false) }
    var loginFailure by remember { mutableStateOf<LoginUiFailure?>(null) }
    var accountPage by rememberSaveable { mutableStateOf(AccountPage.ROOT) }
    var accountNavigationForward by remember { mutableStateOf(true) }
    var selectedLibraryId by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingProtectedPage by remember { mutableStateOf<AccountPage?>(null) }
    var autoCheckIn by rememberSaveable { mutableStateOf(settingsRepository.autoCheckInEnabled()) }
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

    fun navigateAccount(page: AccountPage) {
        if (page == accountPage) return
        accountNavigationForward = true
        accountPage = page
    }

    fun navigateAccountBack() {
        if (accountPage == AccountPage.ROOT) {
            selectedTab = 0
        } else {
            accountNavigationForward = false
            accountPage = accountPage.parent()
        }
    }

    fun openProtectedAccountPage(page: AccountPage) {
        if (accountProfile == null) {
            pendingProtectedPage = page
            requestLogin()
        } else {
            navigateAccount(page)
        }
    }

    BackHandler(enabled = activeTab == ACCOUNT_TAB_INDEX) {
        navigateAccountBack()
    }

    LaunchedEffect(selectedTab, tabs.size) {
        if (selectedTab !in tabs.indices) selectedTab = 0
    }

    LaunchedEffect(accountProfile?.id, autoCheckIn, accountDataRepository) {
        val profile = accountProfile
        if (profile != null && autoCheckIn) accountDataRepository.autoCheckIn(profile)
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
                if (activeTab == 0) {
                    SmallTopAppBar(
                        title = "JMComicX",
                        modifier = Modifier.graphicsLayer {
                            translationY = size.height * searchTransitionProgress * 0.72f
                            alpha = 1f - searchTransitionProgress
                        },
                        actions = {
                            IconButton(onClick = { searchExpanded = true }) {
                                Icon(
                                    imageVector = MiuixIcons.Basic.Search,
                                    contentDescription = "搜索",
                                    tint = MiuixTheme.colorScheme.onBackground,
                                )
                            }
                        },
                    )
                } else if (accountPage != AccountPage.ABOUT) {
                    AnimatedContent(
                        targetState = accountPage,
                        transitionSpec = { accountPageTransition(accountNavigationForward) },
                        label = "AccountTopBarTransition",
                    ) { page ->
                        SmallTopAppBar(
                            title = page.title,
                            actions = {
                                if (page == AccountPage.ROOT) {
                                    IconButton(onClick = { navigateAccount(AccountPage.SETTINGS) }) {
                                        Icon(
                                            imageVector = MiuixIcons.Settings,
                                            contentDescription = "设置",
                                            tint = MiuixTheme.colorScheme.onBackground,
                                        )
                                    }
                                }
                            },
                            navigationIcon = {
                                if (page != AccountPage.ROOT) {
                                    IconButton(onClick = ::navigateAccountBack) {
                                        Icon(
                                            imageVector = MiuixIcons.Back,
                                            contentDescription = "返回",
                                            tint = MiuixTheme.colorScheme.onBackground,
                                        )
                                    }
                                }
                            },
                        )
                    }
                }
            },
            bottomBar = {
                AnimatedVisibility(
                    visible = activeTab == 0 || accountPage == AccountPage.ROOT,
                    enter = fadeIn() + slideInVertically(initialOffsetY = { it }),
                    exit = fadeOut() + slideOutVertically(targetOffsetY = { it }),
                ) {
                    NavigationBar {
                        tabs.forEachIndexed { index, tab ->
                            NavigationBarItem(
                                selected = activeTab == index,
                                onClick = {
                                    searchExpanded = false
                                    selectedTab = index
                                    if (index == ACCOUNT_TAB_INDEX) {
                                        accountNavigationForward = false
                                        accountPage = AccountPage.ROOT
                                    }
                                },
                                icon = tab.icon,
                                label = tab.label,
                            )
                        }
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
                    else -> AnimatedContent(
                        targetState = accountPage,
                        modifier = Modifier.fillMaxSize(),
                        transitionSpec = { accountPageTransition(accountNavigationForward) },
                        label = "AccountPageTransition",
                    ) { page ->
                        when (page) {
                            AccountPage.ROOT -> AccountScreen(
                                innerPadding = innerPadding,
                                profile = accountProfile,
                                imageHost = homeRepository.currentImageHost,
                                onLoginRequested = ::requestLogin,
                                onLogout = {
                                    accountRepository.logout()
                                    accountProfile = null
                                    loginFailure = null
                                },
                                onFavorites = { openProtectedAccountPage(AccountPage.FAVORITES) },
                                onHistory = { openProtectedAccountPage(AccountPage.HISTORY) },
                                onDaily = { openProtectedAccountPage(AccountPage.DAILY) },
                                onAbout = { navigateAccount(AccountPage.ABOUT) },
                            )
                            AccountPage.FAVORITES,
                            AccountPage.HISTORY,
                            -> AccountCollectionScreen(
                                innerPadding = innerPadding,
                                kind = if (page == AccountPage.FAVORITES) {
                                    AccountCollectionKind.FAVORITES
                                } else {
                                    AccountCollectionKind.HISTORY
                                },
                                repository = accountDataRepository,
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
                            )
                            AccountPage.DAILY -> accountProfile?.let {
                                DailyCheckScreen(innerPadding, it, accountDataRepository)
                            } ?: AccountScreen(
                                innerPadding = innerPadding,
                                profile = null,
                                imageHost = homeRepository.currentImageHost,
                                onLoginRequested = ::requestLogin,
                                onLogout = {},
                                onFavorites = { openProtectedAccountPage(AccountPage.FAVORITES) },
                                onHistory = { openProtectedAccountPage(AccountPage.HISTORY) },
                                onDaily = { openProtectedAccountPage(AccountPage.DAILY) },
                                onAbout = { navigateAccount(AccountPage.ABOUT) },
                            )
                            AccountPage.ABOUT -> AboutScreen(
                                innerPadding = innerPadding,
                                onBack = ::navigateAccountBack,
                                onProjectLicense = { navigateAccount(AccountPage.PROJECT_LICENSE) },
                                onThirdParty = { navigateAccount(AccountPage.THIRD_PARTY) },
                            )
                            AccountPage.PROJECT_LICENSE -> ProjectLicenseScreen(innerPadding)
                            AccountPage.THIRD_PARTY -> ThirdPartyListScreen(innerPadding) { libraryId ->
                                selectedLibraryId = libraryId
                                navigateAccount(AccountPage.THIRD_PARTY_DETAIL)
                            }
                            AccountPage.THIRD_PARTY_DETAIL -> ThirdPartyDetailScreen(
                                innerPadding,
                                selectedLibraryId,
                            )
                            AccountPage.SETTINGS -> SettingsScreen(
                                innerPadding = innerPadding,
                                repository = settingsRepository,
                                autoCheckIn = autoCheckIn,
                                onAutoCheckInChanged = {
                                    autoCheckIn = it
                                    settingsRepository.setAutoCheckInEnabled(it)
                                },
                                onImageHostChanged = { homeRequestId++ },
                            )
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
    }
}

private data class JmxTab(
    val label: String,
    val icon: ImageVector,
)

private const val ACCOUNT_TAB_INDEX = 1

internal enum class AccountPage(val title: String) {
    ROOT("我的"),
    FAVORITES("漫画收藏"),
    HISTORY("观看历史"),
    DAILY("每日签到"),
    ABOUT("关于"),
    PROJECT_LICENSE("开源协议"),
    THIRD_PARTY("第三方开源库"),
    THIRD_PARTY_DETAIL("开源库详情"),
    SETTINGS("设置"),
}

private fun AccountPage.parent(): AccountPage = when (this) {
    AccountPage.PROJECT_LICENSE,
    AccountPage.THIRD_PARTY,
    -> AccountPage.ABOUT
    AccountPage.THIRD_PARTY_DETAIL -> AccountPage.THIRD_PARTY
    else -> AccountPage.ROOT
}

private class MiuixNavEasing(response: Float, damping: Float) : Easing {
    private val decayRate: Float
    private val angularFrequency: Float
    private val phaseCoefficient: Float

    init {
        val omega = 2.0 * PI / response
        val stiffness = omega * omega
        val dampingCoefficient = damping * 4.0 * PI / response
        angularFrequency = (sqrt(4.0 * stiffness - dampingCoefficient * dampingCoefficient) / 2.0).toFloat()
        decayRate = (-dampingCoefficient / 2.0).toFloat()
        phaseCoefficient = decayRate / angularFrequency
    }

    override fun transform(fraction: Float): Float {
        val time = fraction.toDouble()
        val decay = exp(decayRate * time)
        return (
            decay * (
                -cos(angularFrequency * time) +
                    phaseCoefficient * sin(angularFrequency * time)
                ) + 1.0
            ).toFloat()
    }
}

private val MiuixNavigationEasing = MiuixNavEasing(response = 0.8f, damping = 0.95f)

private fun accountPageTransition(forward: Boolean): ContentTransform = if (forward) {
    slideInHorizontally(
        initialOffsetX = { it },
        animationSpec = tween(500, easing = MiuixNavigationEasing),
    ) togetherWith slideOutHorizontally(
        targetOffsetX = { -it / 4 },
        animationSpec = tween(500, easing = MiuixNavigationEasing),
    )
} else {
    slideInHorizontally(
        initialOffsetX = { -it / 4 },
        animationSpec = tween(500, easing = MiuixNavigationEasing),
    ) togetherWith slideOutHorizontally(
        targetOffsetX = { it },
        animationSpec = tween(500, easing = MiuixNavigationEasing),
    )
}

package dev.jmx.client.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dev.jmx.client.store.JmxDiagnostics
import dev.jmx.client.ui.components.NavigationInputBlocker
import dev.jmx.client.ui.screens.downloadScreen.DownloadScreen
import dev.jmx.client.ui.screens.readScreen.AlbumReadScreen
import dev.jmx.client.ui.screens.tabScreen.TabScreen
import dev.jmx.client.ui.viewModel.AlbumViewModel
import org.koin.compose.viewmodel.koinActivityViewModel

@Composable
fun AppScreen(
    albumViewModel: AlbumViewModel = koinActivityViewModel()
) {
    val mainNavController = rememberNavController()
    val backStackEntry by mainNavController.currentBackStackEntryAsState()
    val route = backStackEntry?.let { entry ->
        entry.destination.actualRoute(entry.arguments)
    }.orEmpty()
    androidx.compose.runtime.LaunchedEffect(route) {
        if (route.isNotBlank()) {
            JmxDiagnostics.updateCurrentRoute(route)
            JmxDiagnostics.i(
                "Navigation",
                "Navigate to route",
                metadata = mapOf(
                    "nav_type" to "route_visible",
                    "to_route" to route
                )
            )
        }
    }
    CompositionLocalProvider(
        LocalMainNavController provides mainNavController,
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            NavHost(
                modifier = Modifier.fillMaxSize(),
                navController = mainNavController,
                startDestination = "tab/home",
            ) {
                composable(
                    route = "tab/{tabName}?",
                    arguments = listOf(
                        navArgument(name = "tabName") {
                            type = NavType.StringType
                            defaultValue = null
                            nullable = true
                        }
                    ),
                ) { backStackEntry ->
                    val tabName = backStackEntry.arguments?.getString("tabName") ?: "home"
                    TabScreen(tabName = tabName)
                }
                composable("login") { LoginScreen() }
                composable(route = "userCollectAlbum") { UserCollectAlbumScreen() }
                composable(route = "userHistoryAlbum") { UserHistoryAlbumScreen() }
                composable(route = "userHistoryComment") { UserHistoryCommentScreen() }
                composable(route = "appLocalSetting") { LocalSettingScreen() }
                composable(
                    route = "albumDetail/{id}",
                    arguments = listOf(
                        navArgument(name = "id") { type = NavType.IntType; defaultValue = -1 }
                    ),
                ) { backStackEntry ->
                    val id = backStackEntry.arguments?.getInt("id") ?: -1
                    AlbumDetailScreen(id = id)
                }
                composable(
                    route = "albumChapter/{id}",
                    arguments = listOf(
                        navArgument(name = "id") { type = NavType.IntType; defaultValue = -1 }
                    ),
                ) {
                    AlbumChapterScreen()
                }
                composable(
                    route = "albumRelate/{id}",
                    arguments = listOf(
                        navArgument(name = "id") { type = NavType.IntType; defaultValue = -1 }
                    ),
                ) {
                    AlbumRelateListScreen()
                }
                composable(
                    route = "albumRead/{id}",
                    arguments = listOf(
                        navArgument(name = "id") { type = NavType.IntType; defaultValue = -1 }
                    ),
                ) { backStackEntry ->
                    val id = backStackEntry.arguments?.getInt("id") ?: -1
                    AlbumReadScreen(albumId = id)
                }
                composable(route = "albumSearch") { AlbumSearchScreen() }
                composable(route = "aboutDisclaimer") { AboutDisclaimerScreen() }
                composable(route = "aboutCredits") { AboutCreditsScreen() }
                composable(route = "diagnosticLogs") { DiagnosticLogScreen() }
                composable(
                    route = "albumSearchResult/{searchContent}",
                    arguments = listOf(
                        navArgument(name = "searchContent") { type = NavType.StringType }
                    ),
                ) { backStackEntry ->
                    val searchContent = backStackEntry.arguments!!.getString("searchContent")!!
                    albumViewModel.changeSearchAlbumContent(searchContent)
                    AlbumSearchResultScreen()
                }
                composable(route = "albumRecommend") { AlbumWeekRecommendScreen() }
                composable(
                    route = "comment/{albumId}",
                    arguments = listOf(
                        navArgument(name = "albumId") { type = NavType.IntType }
                    ),
                ) { backStackEntry ->
                    val albumId = backStackEntry.arguments?.getInt("albumId") ?: -1
                    AlbumCommentScreen(albumId = albumId)
                }
                composable(route = "sign") { SignInScreen() }
                composable(route = "download") { DownloadScreen() }
            }
            NavigationInputBlocker(mainNavController)
        }
    }
}

val LocalMainNavController = staticCompositionLocalOf<NavHostController> {
    error("none")
}

private fun NavDestination.actualRoute(arguments: android.os.Bundle?): String {
    val template = route.orEmpty()
    if (template.isBlank() || arguments == null) {
        return template
    }
    return template
        .replace("{tabName}?", arguments.getString("tabName").orEmpty())
        .replace("{tabName}", arguments.getString("tabName").orEmpty())
        .replace("{searchContent}", arguments.getString("searchContent").orEmpty())
        .replace("{albumId}", arguments.getInt("albumId", -1).toString())
        .replace("{id}", arguments.getInt("id", -1).toString())
        .trimEnd('/')
}

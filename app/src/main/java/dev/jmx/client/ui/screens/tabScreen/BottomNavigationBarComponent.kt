package dev.jmx.client.ui.screens.tabScreen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.navOptions
import dev.jmx.client.R
import dev.jmx.client.store.JmxDiagnostics
import dev.jmx.client.ui.glass.LiquidBottomBar
import dev.jmx.client.ui.glass.LiquidBottomBarItem
import dev.jmx.client.ui.razor.RazorIcon

private data class TabItem(
    val route: String,
    val label: String,
    val contentDescription: String,
    val icon: @Composable () -> Unit
)

@Composable
fun BottomNavigationBarComponent(
    modifier: Modifier = Modifier
) {
    val tabNavController = LocalTabNavController.current
    val backStackEntryState by tabNavController.currentBackStackEntryAsState()
    val currentRoute = backStackEntryState?.destination?.route
    val tabs = listOf(
        TabItem(
            route = "home",
            label = "漫画",
            contentDescription = "漫画"
        ) {
            RazorIcon(
                painter = painterResource(R.drawable.home_icon),
                contentDescription = null
            )
        },
        TabItem(
            route = "about",
            label = "关于",
            contentDescription = "关于"
        ) {
            RazorIcon(
                imageVector = Icons.Default.Info,
                contentDescription = null
            )
        },
        TabItem(
            route = "user",
            label = "我的",
            contentDescription = "我的"
        ) {
            RazorIcon(
                painter = painterResource(R.drawable.person_icon),
                contentDescription = null
            )
        }
    )
    val selectedIndex = tabs.indexOfFirst { it.route == currentRoute }.coerceAtLeast(0)
    var visualSelectedIndex by remember { mutableIntStateOf(selectedIndex) }
    LaunchedEffect(selectedIndex) {
        visualSelectedIndex = selectedIndex
    }

    fun navigate(index: Int) {
        val route = tabs[index].route
        if (route == currentRoute && visualSelectedIndex == selectedIndex) {
            return
        }
        val fromTab = tabs.getOrNull(selectedIndex)?.label.orEmpty()
        val toTab = tabs[index].label
        JmxDiagnostics.userAction(
            screen = "BottomNavigation",
            action = "tab_switch",
            target = toTab,
            metadata = mapOf(
                "from_tab" to fromTab,
                "to_tab" to toTab,
                "from_route" to currentRoute.orEmpty(),
                "to_route" to route,
                "index" to index
            )
        )
        JmxDiagnostics.i(
            "Navigation",
            "Bottom tab switched",
            metadata = mapOf(
                "nav_type" to "tab_switch",
                "from_route" to currentRoute.orEmpty(),
                "to_route" to route,
                "tab_name" to toTab
            )
        )
        visualSelectedIndex = index
        tabNavController.navigate(route, navOptions {
            launchSingleTop = true
            restoreState = true
            popUpTo(tabNavController.graph.startDestinationId) {
                saveState = true
            }
        })
    }

    AnimatedVisibility(
        visible = currentRoute != "login",
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier
    ) {
        LiquidBottomBar(
            selectedIndex = { visualSelectedIndex },
            onTabSelected = { index -> navigate(index) },
            tabsCount = tabs.size,
            modifier = Modifier.padding(start = 58.dp, end = 58.dp, bottom = 18.dp)
        ) {
            tabs.forEachIndexed { index, item ->
                LiquidBottomBarItem(
                    index = index,
                    selected = selectedIndex == index,
                    onClick = {},
                    icon = item.icon,
                    label = item.label
                )
            }
        }
    }
}

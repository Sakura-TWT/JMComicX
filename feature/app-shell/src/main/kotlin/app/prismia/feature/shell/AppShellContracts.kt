package app.prismia.feature.shell

import app.prismia.foundation.AppMode

/** State owned by the shared shell; each mode keeps its own navigation stack below it. */
data class AppShellState(
    val mode: AppMode = AppMode.COMIC,
    val isGlobalSearchOpen: Boolean = false,
    val comicRoute: String = "comic/home",
    val videoRoute: String = "video/home",
)

sealed interface AppShellIntent {
    data class SwitchMode(val mode: AppMode) : AppShellIntent
    data object OpenGlobalSearch : AppShellIntent
    data object CloseGlobalSearch : AppShellIntent
}

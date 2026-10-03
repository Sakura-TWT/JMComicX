package app.prismia.feature.shell

import app.prismia.foundation.AppMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.ArrayDeque

/**
 * State machine for the shared shell. Comic and video routes are kept in
 * separate stacks, so changing mode does not reset the user's place.
 */
class AppShellController(
    initialState: AppShellState = AppShellState(),
    private val initialComicRoute: String = "comic/home",
    private val initialVideoRoute: String = "video/home",
) {
    private val _state = MutableStateFlow(initialState)
    val state: StateFlow<AppShellState> = _state.asStateFlow()

    private val comicRoutes = ArrayDeque(listOf(initialState.comicRoute.takeIf(String::isNotBlank) ?: initialComicRoute))
    private val videoRoutes = ArrayDeque(listOf(initialState.videoRoute.takeIf(String::isNotBlank) ?: initialVideoRoute))

    init {
        require(initialState.mode == AppMode.COMIC || initialState.mode == AppMode.VIDEO)
    }

    fun dispatch(intent: AppShellIntent) {
        when (intent) {
            is AppShellIntent.SwitchMode -> switchMode(intent.mode)
            AppShellIntent.OpenGlobalSearch -> _state.value = _state.value.copy(isGlobalSearchOpen = true)
            AppShellIntent.CloseGlobalSearch -> _state.value = _state.value.copy(isGlobalSearchOpen = false)
        }
    }

    fun switchMode(mode: AppMode) {
        if (_state.value.mode != mode) _state.value = _state.value.copy(mode = mode)
    }

    fun push(route: String, mode: AppMode = _state.value.mode) {
        require(route.isNotBlank())
        routes(mode).addLast(route)
        publishRoute(mode)
    }

    fun pop(mode: AppMode = _state.value.mode): String? {
        val stack = routes(mode)
        if (stack.size <= 1) return null
        return stack.removeLast().also { publishRoute(mode) }
    }

    fun currentRoute(mode: AppMode = _state.value.mode): String = routes(mode).last()

    private fun routes(mode: AppMode): ArrayDeque<String> = when (mode) {
        AppMode.COMIC -> comicRoutes
        AppMode.VIDEO -> videoRoutes
    }

    private fun publishRoute(mode: AppMode) {
        val current = currentRoute(mode)
        _state.value = when (mode) {
            AppMode.COMIC -> _state.value.copy(comicRoute = current)
            AppMode.VIDEO -> _state.value.copy(videoRoute = current)
        }
    }
}

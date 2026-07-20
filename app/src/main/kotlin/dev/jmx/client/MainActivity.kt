package dev.jmx.client

import android.graphics.Color
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.drawable.toDrawable
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

class MainActivity : ComponentActivity() {
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (ReaderVolumeKeyDispatcher.shouldConsume(keyCode)) {
            if (event.repeatCount == 0) ReaderVolumeKeyDispatcher.dispatch(keyCode)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (ReaderVolumeKeyDispatcher.shouldConsume(keyCode)) return true
        return super.onKeyUp(keyCode, event)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)

        setContent {
            val context = LocalContext.current
            val themeStore = remember(context) { AppThemeModeStore(context) }
            var themeMode by remember(themeStore) { mutableStateOf(themeStore.load()) }
            val darkMode = themeMode.isDark(isSystemInDarkTheme())
            DisposableEffect(darkMode) {
                val systemBarColor = if (darkMode) Color.BLACK else LIGHT_WINDOW_BACKGROUND
                window.setBackgroundDrawable(systemBarColor.toDrawable())
                @Suppress("DEPRECATION")
                window.statusBarColor = systemBarColor
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(systemBarColor, systemBarColor) { darkMode },
                    navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { darkMode },
                )
                window.isNavigationBarContrastEnforced = false
                onDispose {}
            }

            JmxAppTheme(themeMode) {
                JmxApp(
                    themeMode = themeMode,
                    onThemeModeChanged = { updated ->
                        themeMode = updated
                        themeStore.save(updated)
                    },
                )
            }
        }
    }
}

private val LIGHT_WINDOW_BACKGROUND = Color.rgb(250, 250, 250)

@Composable
private fun JmxAppTheme(
    themeMode: AppThemeMode,
    content: @Composable () -> Unit,
) {
    val controller = remember(themeMode) { ThemeController(themeMode.toMiuixMode()) }
    MiuixTheme(controller = controller, content = content)
}

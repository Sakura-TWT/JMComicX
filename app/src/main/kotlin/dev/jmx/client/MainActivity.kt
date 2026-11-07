package dev.jmx.client

import android.graphics.Color
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
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
            JmxAppTheme(themeMode) {
                val surface = MiuixTheme.colorScheme.surface
                val useDarkIcons = surface.luminance() > 0.5f
                DisposableEffect(surface, useDarkIcons) {
                    enableEdgeToEdge(
                        statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { !useDarkIcons },
                        navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { !useDarkIcons },
                    )
                    window.setBackgroundDrawable(surface.toArgb().toDrawable())
                    window.isNavigationBarContrastEnforced = false
                    onDispose {}
                }
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

@Composable
private fun JmxAppTheme(
    themeMode: AppThemeMode,
    content: @Composable () -> Unit,
) {
    val controller = remember(themeMode) { ThemeController(themeMode.toMiuixMode()) }
    MiuixTheme(controller = controller, content = content)
}

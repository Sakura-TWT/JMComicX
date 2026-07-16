package dev.jmx.client

import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.core.graphics.drawable.toDrawable
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
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
        super.onCreate(savedInstanceState)

        setContent {
            val darkMode = isSystemInDarkTheme()
            DisposableEffect(darkMode) {
                window.setBackgroundDrawable(
                    (if (darkMode) Color.BLACK else LIGHT_WINDOW_BACKGROUND).toDrawable(),
                )
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { darkMode },
                    navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { darkMode },
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    window.isNavigationBarContrastEnforced = false
                }
                onDispose {}
            }

            JmxAppTheme {
                JmxApp()
            }
        }
    }
}

private val LIGHT_WINDOW_BACKGROUND = Color.rgb(250, 250, 250)

@Composable
private fun JmxAppTheme(content: @Composable () -> Unit) {
    val controller = remember { ThemeController(ColorSchemeMode.System) }
    MiuixTheme(controller = controller, content = content)
}

package dev.jmx.client

import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
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
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

class MainActivity : ComponentActivity() {
    @Volatile
    private var startupContentReady = false

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
        val startupStartedAt = SystemClock.elapsedRealtime()
        val startupSplash = installSplashScreen()
        super.onCreate(savedInstanceState)
        startupSplash.setKeepOnScreenCondition {
            !startupContentReady &&
                SystemClock.elapsedRealtime() - startupStartedAt < MAX_SPLASH_HOLD_MILLIS
        }
        startupSplash.setOnExitAnimationListener { provider ->
            provider.view.animate()
                .alpha(0f)
                .scaleX(1.04f)
                .scaleY(1.04f)
                .setDuration(SPLASH_EXIT_DURATION_MILLIS)
                .withEndAction(provider::remove)
                .start()
        }

        setContent {
            val darkMode = isSystemInDarkTheme()
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

            JmxAppTheme {
                JmxApp(onColdStartReady = { startupContentReady = true })
            }
        }
    }
}

private val LIGHT_WINDOW_BACKGROUND = Color.rgb(250, 250, 250)
private const val MAX_SPLASH_HOLD_MILLIS = 2_500L
private const val SPLASH_EXIT_DURATION_MILLIS = 220L

@Composable
private fun JmxAppTheme(content: @Composable () -> Unit) {
    val controller = remember { ThemeController(ColorSchemeMode.System) }
    MiuixTheme(controller = controller, content = content)
}

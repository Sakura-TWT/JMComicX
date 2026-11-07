package dev.jmx.client

import android.app.Application
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import org.robolectric.Shadows.shadowOf
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.background
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.printToString
import androidx.compose.ui.unit.Density
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.CompositionLocalProvider
import org.junit.Assume.assumeTrue
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import org.robolectric.Robolectric
import org.robolectric.android.controller.ActivityController
import org.robolectric.RuntimeEnvironment
import org.robolectric.shadows.ShadowDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController
import androidx.compose.runtime.remember
import java.io.File

class VisualActivityRule : TestRule {
    lateinit var activity: ComponentActivity
    override fun apply(base: Statement, description: Description): Statement = object : Statement() {
        override fun evaluate() {
            val phase = System.getProperty("jmx.visual.phase", "disabled")
            assumeTrue("Visual tests require explicit -PjmxVisualPhase=before|after", phase in listOf("before", "after"))
            require(System.getProperty("jmx.visual.run", "").orEmpty().matches(Regex("[a-zA-Z0-9_-]+"))) {
                "Supply -PjmxVisualRun=<new immutable run id>"
            }
            val controller = Robolectric.buildActivity(ComponentActivity::class.java)
            activity = controller.get()
            activity.setTheme(android.R.style.Theme_Material_Light_NoActionBar)
            controller.setup()
            try { base.evaluate() } finally { controller.pause().stop().destroy() }
        }
    }
}

abstract class VisualHarness {
    abstract val dark: Boolean
    @get:org.junit.Rule
    val compose = AndroidComposeTestRule(VisualActivityRule()) { it.activity }
    val theme: String get() = if (dark) "dark" else "light"
    private val phase get() = System.getProperty("jmx.visual.phase", "disabled")
    private val run get() = System.getProperty("jmx.visual.run", "")
    private val folder get() = File(checkNotNull(System.getProperty("jmx.visual.root")), "$phase/$run/$theme")

    fun render(content: @Composable () -> Unit) {
        compose.setContent {
            val mode = if (dark) AppThemeMode.DARK else AppThemeMode.LIGHT
            val controller = remember(mode) { ThemeController(mode.toMiuixMode()) }
            MiuixTheme(controller = controller) {
                CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
                    Box(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface)) { content() }
                }
            }
        }
        compose.waitForIdle()
    }

    fun capture(scenario: String, frame: String, note: String = "") {
        compose.waitForIdle()
        val activity = compose.activity
        val dialog = ShadowDialog.getLatestDialog()?.takeIf { it.isShowing }
        val view = dialog?.window?.decorView ?: activity.window.decorView
        lateinit var bitmap: Bitmap
        compose.runOnUiThread {
            check(view.width > 0 && view.height > 0) { "Unmeasured production Compose view" }
            bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            var result = -1
            PixelCopy.request(dialog?.window ?: activity.window, bitmap, { result = it }, Handler(Looper.getMainLooper()))
            shadowOf(Looper.getMainLooper()).idle()
            check(result == PixelCopy.SUCCESS) { "Native window PixelCopy failed: $result" }
        }
        val dir = File(folder, scenario).also { it.mkdirs() }
        val png = File(dir, "$frame.png")
        check(png.createNewFile()) { "Immutable capture already exists: $png; choose another jmxVisualRun" }
        png.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        val semantics = compose.onAllNodes(isRoot(), useUnmergedTree = true).printToString()
        File(dir, "$frame.txt").also { check(it.createNewFile()) }.writeText(
            "scenario=$scenario\nframe=$frame\ntheme=$theme\nphase=$phase\nrun=$run\n" +
            "renderer=Robolectric 4.17 native graphics\napi=36 Android 16 13921718\n" +
            "viewportDp=393x873\ndensity=1.0 fontScale=1.0\n" +
            "themeController=production AppThemeMode.toMiuixMode + MiuixTheme(controller)\n" +
            "capturePx=${bitmap.width}x${bitmap.height}\ncomposeClockMs=${compose.mainClock.currentTime}\n" +
            "window=${if (dialog != null) "production dialog decor" else "activity decor"}\n" +
            "note=$note\n\n$semantics"
        )
        println("VISUAL_CAPTURE ${png.absolutePath} ${bitmap.width}x${bitmap.height} $theme $scenario/$frame clock=${compose.mainClock.currentTime}")
        bitmap.recycle()
    }

    fun observation(scenario: String, text: String) {
        val file = File(folder, "$scenario-observation.txt")
        checkNotNull(file.parentFile).mkdirs()
        check(file.createNewFile()) { "Immutable observation already exists: $file" }
        file.writeText(text)
        println("VISUAL_OBSERVATION $scenario $text")
    }
}

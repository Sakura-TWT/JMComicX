package dev.jmx.client

import android.app.Application
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

abstract class SettingsVisualScenarios : VisualHarness() {
    @Test fun signalGradesWithoutDiagnosticPanel() {
        val hosts = listOf("fast", "normal", "moderate", "slow", "failed", "unknown").map { "https://$it.example.invalid" }
        val snapshot = EndpointSettingsState(
            automatic = false, selectedApiUrl = hosts.first(), activeApiUrl = hosts.first(), selectedImageUrl = null,
            apiEndpoints = listOf(
                EndpointProbeUi(hosts[0], 123L, true), EndpointProbeUi(hosts[1], 600L, true),
                EndpointProbeUi(hosts[2], 1200L, true), EndpointProbeUi(hosts[3], 4200L, true),
                EndpointProbeUi(hosts[4], success = false), EndpointProbeUi(hosts[5], success = true),
            ), imageEndpoints = emptyList(),
        )
        render {
            EndpointOptions(
                state = EndpointDialogState(snapshot), repository = org.mockito.Mockito.mock(AppSettingsRepository::class.java),
                onRefresh = {}, onSelectionChanged = {}, onImageHostChanged = {}, onProbeApi = {}, onProbeImage = {},
            )
        }
        capture("signal-grades", "00-all-grades", "Actual production rows, measured latency grades 4/3/2/1, failed/unknown; not RF signal or throughput")
        compose.onNodeWithText("最近实际加载 · P95").assertDoesNotExist()
        compose.onNodeWithText("请求至首字节", substring = true).assertDoesNotExist()
    }

    @Test fun routeAndImageSelection() {
        val repository = VisualFixtures.settingsRepository()
        render {
            SettingsScreen(
                innerPadding = PaddingValues(), repository = repository,
                themeMode = if (dark) AppThemeMode.DARK else AppThemeMode.LIGHT,
                onThemeModeChanged = {}, autoCheckIn = false, onAutoCheckInChanged = {},
                autoCheckUpdates = false, checkingForUpdates = false, currentVersion = "2.9.6",
                onAutoCheckUpdatesChanged = {}, onCheckForUpdates = {}, onImageHostChanged = {},
                onContentLanguageChanged = {}, onTopBarBlurStyleChanged = {},
                onLiquidGlassNavBarChanged = {}, onFloatingNavBarStyleChanged = {},
            )
        }
        capture("settings-route-image", "00-settings")
        compose.onNodeWithText("线路与图源").performScrollTo().performClick()
        compose.mainClock.advanceTimeBy(1000)
        compose.onNodeWithText("API 线路").assertExists()
        compose.onAllNodesWithText("123 ms").assertCountEquals(4)
        capture("settings-route-image", "01-dialog-api", "Actual private EndpointSelectionDialog reached from production SettingsScreen; fixture status 123ms/4200ms/unavailable")
        compose.onNodeWithText("全部重新测速").performScrollTo()
        capture("settings-route-image", "02-dialog-image")
        compose.onNodeWithText("跟随服务配置").performClick()
        compose.mainClock.advanceTimeBy(300)
        capture("settings-route-image", "03-image-auto-selected")
        observation("settings-route-image", "Production settings + private production dialog rendered. Five API rows/five image rows, long .invalid hostnames; fixed probe results, no core/application initialization, no services or real network. Image auto-selection performed through real production click callback with test repository state.")
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class, qualifiers = "w393dp-h873dp-notnight-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class SettingsVisualLightTest : SettingsVisualScenarios() { override val dark = false }

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class, qualifiers = "w393dp-h873dp-night-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class SettingsVisualDarkTest : SettingsVisualScenarios() { override val dark = true }

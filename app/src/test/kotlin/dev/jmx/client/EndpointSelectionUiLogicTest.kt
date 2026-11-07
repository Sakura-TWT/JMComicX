package dev.jmx.client

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import dev.jmx.client.core.result.JmxResult
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Answers
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class, qualifiers = "w393dp-h873dp-notnight-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class EndpointSelectionUiLogicTest {
    @get:org.junit.Rule
    val compose = androidx.compose.ui.test.junit4.AndroidComposeTestRule(SearchTestActivityRule()) { it.activity }

    private fun render(content: @androidx.compose.runtime.Composable () -> Unit) {
        compose.setContent { SearchRegressionTheme(content) }
        compose.waitForIdle()
    }
    private val api = "https://a-very-long-route-host-name-for-layout-reproduction.example.invalid"
    private val failedApi = "https://failed-api.example.invalid"
    private val image = "https://a-very-long-image-source-host-name-for-layout-reproduction.example.invalid"
    private var snapshot = EndpointSettingsState(
        automatic = false,
        selectedApiUrl = api,
        activeApiUrl = api,
        selectedImageUrl = image,
        apiEndpoints = listOf(EndpointProbeUi(api, 4200L, true), EndpointProbeUi(failedApi, success = false)),
        imageEndpoints = listOf(EndpointProbeUi(image, 123L, true)),
    )
    private var state by mutableStateOf(EndpointDialogState(snapshot))
    private var selectionCalls = 0
    private var imageChangeCalls = 0
    private var probeCalls = 0

    private fun renderOptions() {
        val repository = Mockito.mock(AppSettingsRepository::class.java) { call ->
            when (call.method.name) {
                "useAutomaticApi" -> { snapshot = snapshot.copy(automatic = true, selectedApiUrl = null); null }
                "useApiEndpoint" -> {
                    val url = call.arguments[0] as String
                    snapshot = snapshot.copy(automatic = false, selectedApiUrl = url, activeApiUrl = url)
                    JmxResult.Success(Unit)
                }
                "useAutomaticImageHost" -> { snapshot = snapshot.copy(selectedImageUrl = null); null }
                "useImageHost" -> { snapshot = snapshot.copy(selectedImageUrl = call.arguments[0] as String); null }
                else -> Answers.RETURNS_DEFAULTS.answer(call)
            }
        }
        render {
            Box(Modifier.width(321.dp).height(600.dp)) {
                EndpointOptions(
                    state = state,
                    repository = repository,
                    onRefresh = {},
                    onSelectionChanged = {
                        selectionCalls++
                        state = state.copy(endpoints = state.endpoints.withSelectionSnapshot(snapshot))
                    },
                    onImageHostChanged = { imageChangeCalls++ },
                    onProbeApi = { url ->
                        probeCalls++
                        state = state.copy(endpoints = state.endpoints.withApiProbe(EndpointProbeUi(url), snapshot.activeApiUrl))
                    },
                    onProbeImage = { probeCalls++ },
                )
            }
        }
    }

    @Test fun failedProbeDoesNotDisableManualApiSelection() {
        renderOptions()
        compose.onNodeWithText(failedApi.endpointDisplayName()).performScrollTo().performClick()
        compose.onNodeWithText(failedApi.endpointDisplayName())
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
        compose.runOnIdle {
            assertEquals(failedApi, snapshot.selectedApiUrl)
            assertFalse(snapshot.automatic)
            assertEquals(image, snapshot.selectedImageUrl)
            assertEquals(1, selectionCalls)
            assertEquals(0, imageChangeCalls)
        }
    }

    @Test fun automaticApiKeepsManualImageChoiceAndProbeFeedback() {
        renderOptions()
        compose.onNodeWithText("自动选择").performClick()
        compose.onNodeWithText("自动选择")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
        compose.runOnIdle {
            assertTrue(snapshot.automatic)
            assertNull(snapshot.selectedApiUrl)
            assertEquals(image, snapshot.selectedImageUrl)
            assertEquals(4200L, state.endpoints.apiEndpoints.first().latencyMillis)
            assertEquals(1, selectionCalls)
            assertEquals(0, imageChangeCalls)
        }
    }

    @Test fun imageFollowAndManualChoiceKeepApiSelectionAndCallbacks() {
        renderOptions()
        compose.onNodeWithText("跟随服务配置").performScrollTo().performClick()
        compose.onNodeWithText("跟随服务配置")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
        compose.runOnIdle { assertNull(snapshot.selectedImageUrl) }
        compose.onNodeWithText(image.endpointDisplayName()).performScrollTo().performClick()
        compose.onNodeWithText(image.endpointDisplayName())
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
        compose.runOnIdle {
            assertEquals(image, snapshot.selectedImageUrl)
            assertEquals(api, snapshot.selectedApiUrl)
            assertFalse(snapshot.automatic)
            assertEquals(2, imageChangeCalls)
            assertEquals(2, selectionCalls)
        }
    }

    @Test fun remeasureKeepsSelectionAndFixedAccessibleTouchSlot() {
        renderOptions()
        compose.onNodeWithText(api.endpointDisplayName()).performScrollTo()
        val probe = compose.onNodeWithContentDescription("重新测速 " + api.endpointDisplayName(), useUnmergedTree = true)
        val before = probe.fetchSemanticsNode().boundsInRoot
        assertTrue(before.width >= 44f)
        assertTrue(before.height >= 44f)
        probe.performClick()
        compose.onNodeWithText(api.endpointDisplayName())
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "已选择 · 探测中 · —"))
        // MIUIX 0.9.4 omits the disabled IconButton's click action rather than
        // Material's Disabled property. Inspect the actual 44dp unmerged button;
        // the selectable parent row must retain its independent click action.
        probe.assertHasNoClickAction()
        val after = probe.fetchSemanticsNode().boundsInRoot
        assertEquals(before.width, after.width, 0.01f)
        assertEquals(before.height, after.height, 0.01f)
        assertEquals(before.left, after.left, 0.01f)
        compose.runOnIdle {
            assertEquals(api, snapshot.selectedApiUrl)
            assertEquals(image, snapshot.selectedImageUrl)
            assertEquals(1, probeCalls)
            assertEquals(0, selectionCalls)
            assertEquals(0, imageChangeCalls)
        }
    }

    @Test fun latencyGradeIsAccessibleAndDiagnosticSectionIsAbsent() {
        renderOptions()
        compose.onNodeWithText(api.endpointDisplayName()).performScrollTo()
        compose.onNodeWithText(api.endpointDisplayName())
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "已选择 · 较慢 · 4200 ms · 探测延迟等级 1/4"))
        compose.onNodeWithText("最近实际加载 · P95").assertDoesNotExist()
        compose.onNodeWithText("请求至首字节", substring = true).assertDoesNotExist()
    }

    @Test fun longHostsHaveCompleteIdentityAndAtMostTwoLinesAtDialogWidth() {
        renderOptions()
        listOf(api, image).forEach { url ->
            val node = compose.onNodeWithText(url.endpointDisplayName(), useUnmergedTree = true)
            compose.onNodeWithText(url.endpointDisplayName()).performScrollTo()
            val layouts = mutableListOf<TextLayoutResult>()
            node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertEquals(1, layouts.size)
            assertEquals(url.endpointDisplayName(), layouts.single().layoutInput.text.text)
            assertFalse(layouts.single().hasVisualOverflow)
            assertTrue("Expected at most two lines for $url", layouts.single().lineCount <= 2)
            compose.onNodeWithContentDescription(url).assertExists()
        }
    }
}

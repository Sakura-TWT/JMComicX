package dev.jmx.client

import android.app.Application
import android.content.Context
import androidx.compose.ui.test.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.mockito.Answers
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.json.JSONArray
import java.util.concurrent.atomic.AtomicInteger

abstract class SearchVisualScenarios : VisualHarness() {
    private fun searchFixture(action: (AtomicInteger) -> Unit) {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("jmx_search_history", Context.MODE_PRIVATE).edit()
            .putString("queries", JSONArray(VisualFixtures.history).toString())
            .putBoolean("legacy_history_migrated", true).commit()
        val calls = AtomicInteger()
        val settings = Mockito.withSettings().defaultAnswer { call ->
            if (call.method.name == "search") {
                calls.incrementAndGet()
                VisualFixtures.page
            } else Answers.RETURNS_DEFAULTS.answer(call)
        }
        Mockito.mockConstruction(ComicSearchRepository::class.java, settings) { _, _ -> }.use {
            val home = Mockito.mock(HomeRepository::class.java)
            render {
                ComicSearchScreen(home, manageSystemBar = false, liftedAlbumId = null, onDismiss = {}, onAlbumSelected = { _, _ -> })
            }
            action(calls)
        }
    }

    @Test fun historyManagementFrames() = searchFixture {
        capture("history-management", "00-start")
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText(VisualFixtures.history.first()).performTouchInput { longClick() }
        compose.mainClock.advanceTimeBy(32)
        capture("history-management", "01-enter-32ms", "32ms after long-press; Android gesture injected by Compose test API")
        compose.mainClock.advanceTimeBy(96)
        capture("history-management", "02-enter-128ms")
        compose.mainClock.advanceTimeBy(240)
        compose.onNodeWithText("6 条记录 · 管理中").assertExists()
        capture("history-management", "03-management-settled")
        compose.onNodeWithText("完成").performClick()
        compose.mainClock.advanceTimeBy(64)
        capture("history-management", "04-exit-64ms")
        compose.mainClock.advanceTimeBy(240)
        compose.onNodeWithText("6 条记录").assertExists()
        capture("history-management", "05-end")
        compose.mainClock.advanceTimeBy(1000)
        capture("history-management", "06-end-plus-1000ms", "Extra settling frame to distinguish transient exit measurement from persistent row/card spacing")
        compose.mainClock.autoAdvance = true
    }

    @Test fun historySubmission() = searchFixture { calls ->
        capture("history-submit", "00-input")
        compose.onNodeWithText(VisualFixtures.history.first()).performClick()
        checkSubmission("history-submit", calls)
    }

    @Test fun imeSubmission() = searchFixture { calls ->
        compose.onNode(hasSetTextAction()).performTextInput("本地测试")
        capture("ime-submit", "00-input", "IME search action, not an emulated software keyboard screenshot")
        compose.onNode(hasSetTextAction()).performImeAction()
        checkSubmission("ime-submit", calls)
    }

    @Test fun buttonSubmission() = searchFixture { calls ->
        compose.onNode(hasSetTextAction()).performTextInput("本地测试")
        capture("button-submit", "00-input")
        compose.onNodeWithContentDescription("执行搜索").performClick()
        checkSubmission("button-submit", calls)
    }

    private fun checkSubmission(scenario: String, calls: AtomicInteger) {
        compose.mainClock.advanceTimeBy(1000)
        compose.waitForIdle()
        capture(scenario, "01-submitted-1000ms")
        val idle = compose.onAllNodesWithText("输入关键词后执行搜索").fetchSemanticsNodes().isNotEmpty()
        val text = "After 1000ms virtual Compose time: Idle message visible=$idle; fixture search repository calls=${calls.get()}. " +
            "No real API traffic. " + if (System.getProperty("jmx.visual.phase") == "before") {
                "Baseline source inference: pre-Loading scrollToItem waits on the Content-only grid. " +
                    "Result-state restoration is blocked: private non-saveable ComicSearchUiState in ComicSearchScreen; no product mutation or fabricated result UI."
            } else {
                "After requires actual deterministic Content and one fixture call; this harness makes no baseline-source root-cause claim about changed product code."
            }
        observation(scenario, text)
        if (System.getProperty("jmx.visual.phase") == "before") {
            check(idle && calls.get() == 0) { "Baseline changed; inspect observed output instead of assuming bug" }
        } else {
            check(!idle && calls.get() == 1) { "After must reach deterministic fixture repository exactly once" }
            compose.onNodeWithText(VisualFixtures.albums.first().name).assertExists()
            capture(scenario, "02-results")
        }
        compose.mainClock.autoAdvance = false
        compose.onNode(hasSetTextAction()).performClick()
        compose.mainClock.advanceTimeBy(32)
        capture(scenario, "03-reopen-history-32ms", "Same input action sequence in both phases; Before remains blocked Idle, not a results claim")
        compose.mainClock.advanceTimeBy(96)
        capture(scenario, "04-reopen-history-128ms")
        compose.mainClock.advanceTimeBy(256)
        compose.onNodeWithText("搜索历史").assertExists()
        capture(scenario, "05-reopen-history-settled")
        observation(scenario + "-reopen-history", "Actual editable production search field clicked after submission; production search history is visible. Before submitted state was Idle; After must first reach Content using the identical fixture/input actions.")
        compose.mainClock.autoAdvance = true
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class, qualifiers = "w393dp-h873dp-notnight-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class SearchVisualLightTest : SearchVisualScenarios() { override val dark = false }

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class, qualifiers = "w393dp-h873dp-night-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class SearchVisualDarkTest : SearchVisualScenarios() { override val dark = true }

package dev.jmx.client

import android.app.Application
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.mockito.Answers
import org.mockito.Mockito
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController
import java.util.concurrent.atomic.AtomicInteger

class SearchTestActivityRule : TestRule {
    lateinit var activity: ComponentActivity
    override fun apply(base: Statement, description: Description): Statement = object : Statement() {
        override fun evaluate() {
            val controller = Robolectric.buildActivity(ComponentActivity::class.java)
            activity = controller.get()
            activity.setTheme(android.R.style.Theme_Material_Light_NoActionBar)
            controller.setup()
            try { base.evaluate() } finally { controller.pause().stop().destroy() }
        }
    }
}

@Composable
internal fun SearchRegressionTheme(content: @Composable () -> Unit) {
    val controller = remember { ThemeController(AppThemeMode.LIGHT.toMiuixMode()) }
    MiuixTheme(controller = controller, content = content)
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class, qualifiers = "w393dp-h873dp-notnight-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class SearchSubmissionRegressionTest {
    @get:Rule val compose = AndroidComposeTestRule(SearchTestActivityRule()) { it.activity }
    private val album = HomeAlbum("fixture", "本地结果", "测试作者", "", "https://image.invalid")

    private fun fixture(
        initialQuery: String? = null,
        result: (Int, String) -> ComicSearchResult = { _, _ -> ComicSearchResult.Page(listOf(album), 1, true) },
        restored: Boolean = false,
        action: (AtomicInteger, AtomicInteger, AtomicInteger, StateRestorationTester) -> Unit,
    ) {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("jmx_search_history", Context.MODE_PRIVATE).edit()
            .putString("queries", JSONArray(listOf("本地测试", "其他历史")).toString())
            .putBoolean("legacy_history_migrated", true).commit()
        val calls = AtomicInteger()
        val selections = AtomicInteger()
        val dismissals = AtomicInteger()
        val settings = Mockito.withSettings().defaultAnswer { call ->
            if (call.method.name == "search") result(calls.incrementAndGet(), call.arguments.first() as String)
            else Answers.RETURNS_DEFAULTS.answer(call)
        }
        Mockito.mockConstruction(ComicSearchRepository::class.java, settings) { _, _ -> }.use {
            val home = Mockito.mock(HomeRepository::class.java)
            val restoration = StateRestorationTester(compose)
            val content: @Composable () -> Unit = {
                SearchRegressionTheme {
                    ComicSearchScreen(home, initialQuery = initialQuery, manageSystemBar = false,
                        liftedAlbumId = null, onDismiss = { dismissals.incrementAndGet() },
                        onAlbumSelected = { _, _ -> selections.incrementAndGet() })
                }
            }
            if (restored) restoration.setContent(content) else compose.setContent(content)
            compose.waitForIdle()
            action(calls, selections, dismissals, restoration)
        }
    }

    private fun assertContentOnce(calls: AtomicInteger) {
        compose.waitForIdle()
        compose.onNodeWithText(album.name).assertExists()
        compose.onNodeWithText("输入关键词后执行搜索").assertDoesNotExist()
        assertEquals(1, calls.get())
    }

    @Test fun historySubmissionDoesNotWaitForFirstGridLayout() = fixture { calls, _, _, _ ->
        compose.onNodeWithText("本地测试").performClick()
        assertContentOnce(calls)
    }

    @Test fun imeSubmissionLoadsExactlyOnce() = fixture { calls, _, _, _ ->
        compose.onNode(hasSetTextAction()).performTextInput("本地测试")
        compose.onNode(hasSetTextAction()).performImeAction()
        assertContentOnce(calls)
    }

    @Test fun buttonSubmissionLoadsExactlyOnceAndReopeningDoesNotRefetch() = fixture { calls, _, _, _ ->
        compose.onNode(hasSetTextAction()).performTextInput("本地测试")
        compose.onNodeWithContentDescription("执行搜索").performClick()
        assertContentOnce(calls)
        val input = compose.onNode(hasSetTextAction()).fetchSemanticsNode().boundsInRoot
        compose.onNode(hasSetTextAction()).performClick()
        compose.mainClock.advanceTimeBy(400)
        compose.onNodeWithText("搜索历史").assertExists()
        assertEquals(input.width, compose.onNode(hasSetTextAction()).fetchSemanticsNode().boundsInRoot.width, 0.01f)
        assertEquals(1, calls.get())
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithText(album.name).assertExists()
        assertEquals(1, calls.get())
    }

    @Test fun ordinaryInitialQueryAndSavedStateReloadContent() = fixture(initialQuery = " 本地测试 ", restored = true) { calls, _, _, restoration ->
        assertContentOnce(calls)
        restoration.emulateSavedInstanceStateRestore()
        compose.waitForIdle()
        compose.onNodeWithText(album.name).assertExists()
        assertEquals(2, calls.get())
        compose.onNode(hasSetTextAction()).assertTextContains("本地测试")
    }

    @Test fun sameQueryErrorRetryMakesExactlyOneNewFetch() = fixture(result = { count, _ ->
        if (count == 1) ComicSearchResult.Error("可恢复错误") else ComicSearchResult.Page(listOf(album), 1, true)
    }) { calls, _, _, _ ->
        compose.onNodeWithText("本地测试").performClick()
        compose.onNodeWithText("可恢复错误").assertExists()
        compose.onNodeWithText("重试").performClick()
        compose.onNodeWithText(album.name).assertExists()
        assertEquals(2, calls.get())
    }

    @Test fun jmDirectResultUsesExistingSelectionCallback() = fixture(initialQuery = "JM123", result = { _, query ->
        assertEquals("123", query.toJmSearchIdOrNull())
        ComicSearchResult.Direct(album)
    }) { calls, selections, _, _ ->
        assertContentOnce(calls)
        assertEquals(1, selections.get())
    }

    @Test fun historyClearRequiresConfirmationAndCanBeDismissed() = fixture { calls, _, _, _ ->
        val store = SearchHistoryStore(RuntimeEnvironment.getApplication())
        compose.onNodeWithText("管理").performClick()
        compose.onNodeWithText("清空全部").performClick()
        compose.onNodeWithText("清空搜索历史").assertExists()
        assertEquals(2, store.load().size)
        compose.onNodeWithText("保留历史").performClick()
        compose.mainClock.advanceTimeBy(500)
        assertEquals(2, store.load().size)
        compose.onNodeWithText("清空全部").performClick()
        compose.onNodeWithText("全部清空").performClick()
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithText("暂无搜索历史").assertExists()
        assertTrue(store.load().isEmpty())
        assertEquals(0, calls.get())
    }

    @Test fun managementBackThenInputCancelKeepsExistingSemantics() = fixture { calls, _, dismissals, _ ->
        compose.onNodeWithText("管理").performClick()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithText("管理").assertExists()
        assertEquals(0, dismissals.get())
        compose.onNodeWithText("取消").performClick()
        assertEquals(1, dismissals.get())
        assertEquals(0, calls.get())
    }

    @Test fun editingCancelAndRestorePreserveResultScroll() = fixture(initialQuery = "本地测试", restored = true, result = { _, _ ->
        ComicSearchResult.Page((0..29).map { HomeAlbum(it.toString(), "结果$it", "作者", "", "https://image.invalid") }, 30, true)
    }) { calls, _, _, restoration ->
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(18)
        compose.onNodeWithText("结果18").assertIsDisplayed()
        compose.onNode(hasSetTextAction()).performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement("未提交的词")
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithText("结果18").assertIsDisplayed()
        assertEquals(1, calls.get())
        restoration.emulateSavedInstanceStateRestore()
        compose.waitForIdle()
        compose.onNodeWithText("结果18").assertIsDisplayed()
        assertEquals(2, calls.get())
    }
}

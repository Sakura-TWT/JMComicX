package dev.jmx.client

import android.app.Application
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class, qualifiers = "w393dp-h873dp-notnight-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class SearchHistoryMotionTest {
    @get:Rule val compose = AndroidComposeTestRule(SearchTestActivityRule()) { it.activity }
    private val longTitle = "很长的搜索历史标题用于观察触摸目标与单行动画"
    private val history = listOf("春日旅行", "星空与海", longTitle, "城市记忆")

    private fun bounds(text: String): Rect = compose.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
    private fun assertSameRows(before: List<Rect>) {
        history.zip(before).forEach { (query, expected) ->
            val actual = bounds(query)
            assertEquals(expected.left, actual.left, 0.01f)
            assertEquals(expected.top, actual.top, 0.01f)
            assertEquals(expected.width, actual.width, 0.01f)
            assertEquals(expected.height, actual.height, 0.01f)
        }
    }

    @Test fun managementEnterAndExitDoNotRemeasureRowsOrRebound() {
        var editing by mutableStateOf(false)
        compose.setContent {
            SearchRegressionTheme {
                SearchHistoryPanel(history, editing, { editing = it }, {}, {}, {}, Modifier.fillMaxSize())
            }
        }
        val original = history.map(::bounds)
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("春日旅行").performTouchInput { longClick() }
        for (delta in listOf(32L, 96L, 240L)) {
            compose.mainClock.advanceTimeBy(delta)
            assertSameRows(original)
        }
        val delete = compose.onNodeWithContentDescription("删除搜索记录：春日旅行").fetchSemanticsNode().boundsInRoot
        assertTrue(delete.width >= 44f && delete.height >= 44f)
        compose.onNodeWithText("完成").performClick()
        for (delta in listOf(64L, 240L, 1000L)) {
            compose.mainClock.advanceTimeBy(delta)
            assertSameRows(original)
        }
        compose.onNodeWithContentDescription("删除搜索记录：春日旅行").assertDoesNotExist()
        compose.mainClock.autoAdvance = true
    }

    @Test fun managementTemporarilyShowsAllHistoryAndExitRestoresLocalFilter() {
        var editing by mutableStateOf(false)
        compose.setContent {
            SearchRegressionTheme {
                SearchHistoryPanel(history, editing, { editing = it }, {}, {}, {}, Modifier.fillMaxSize(), filterQuery = "城市")
            }
        }
        compose.onNodeWithText("1 / 4 条记录").assertExists()
        compose.onNodeWithText("春日旅行").assertDoesNotExist()
        val original = bounds("城市记忆")
        compose.onNodeWithText("管理").performClick()
        compose.onNodeWithText("4 条记录 · 管理中").assertExists()
        compose.onNodeWithText("春日旅行").assertExists()
        compose.onNodeWithText("完成").performClick()
        compose.mainClock.advanceTimeBy(400)
        compose.onNodeWithText("1 / 4 条记录").assertExists()
        assertEquals(original, bounds("城市记忆"))
    }

    @Test fun managementRowsCannotSubmitAndDeletionKeepsRemainingOrder() {
        var items by mutableStateOf(history)
        var editing by mutableStateOf(true)
        var selected = 0
        var cleared = 0
        compose.setContent {
            SearchRegressionTheme {
                SearchHistoryPanel(items, editing, { editing = it }, { selected++ }, { item -> items = items.filterNot { it == item } }, { cleared++ }, Modifier.fillMaxSize())
            }
        }
        compose.onNodeWithText("春日旅行").performTouchInput { click() }
        assertEquals(0, selected)
        compose.onNodeWithContentDescription("删除搜索记录：春日旅行").performClick()
        assertEquals(history.drop(1), items)
        compose.onNodeWithText("3 条记录 · 管理中").assertExists()
        compose.onNodeWithText("清空全部").performClick()
        assertEquals(1, cleared)
        compose.onNodeWithText("完成").performClick()
        compose.onNodeWithText("星空与海").performClick()
        assertEquals(1, selected)
    }
}

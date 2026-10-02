package app.prismia.plus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchTagFilterTest {
    @Test
    fun toggleIncludeAddsTagAndReportsIncludeState() {
        val filter = SearchTagFilter().toggleInclude("连载中")

        assertTrue(filter.enabled)
        assertEquals(listOf("连载中"), filter.normalizedIncludeTags)
        assertEquals(SearchTagState.INCLUDE, filter.stateOf("连载中"))
        // 服务端会对搜索词做简繁归一化，客户端的状态判断也应把繁体视为同一个标签。
        assertEquals(SearchTagState.INCLUDE, filter.stateOf("連載中"))
    }

    @Test
    fun togglingSameTagTwiceClearsIt() {
        val filter = SearchTagFilter().toggleInclude("全彩").toggleInclude("全彩")

        assertFalse(filter.enabled)
        assertEquals(SearchTagState.NONE, filter.stateOf("全彩"))
    }

    @Test
    fun toggleExcludeMovesTagOutOfIncludeSet() {
        val filter = SearchTagFilter()
            .toggleInclude("全彩")
            .toggleExclude("全彩")

        assertEquals(SearchTagState.EXCLUDE, filter.stateOf("全彩"))
        assertEquals(emptyList<String>(), filter.normalizedIncludeTags)
        assertEquals(listOf("全彩"), filter.normalizedExcludeTags)
    }

    @Test
    fun includeAndExcludeCoexistForDifferentTags() {
        val filter = SearchTagFilter()
            .toggleInclude("全彩")
            .toggleExclude("短篇")

        assertEquals(listOf("全彩"), filter.normalizedIncludeTags)
        assertEquals(listOf("短篇"), filter.normalizedExcludeTags)
        assertEquals(SearchTagState.INCLUDE, filter.stateOf("全彩"))
        assertEquals(SearchTagState.EXCLUDE, filter.stateOf("短篇"))
    }

    @Test
    fun normalizedTagsDedupeSimplifiedTraditionalAndPreferInclude() {
        val filter = SearchTagFilter(
            includeTags = listOf("连载中", "連載中"),
            excludeTags = listOf("連載中", "短篇"),
        )

        // 简繁同一标签只保留一次；同一标签同时出现在两边时“包含”优先。
        assertEquals(listOf("连载中"), filter.normalizedIncludeTags)
        assertEquals(listOf("短篇"), filter.normalizedExcludeTags)
    }

    @Test
    fun tagNormalizationTrimsAndConvertsToSimplifiedChinese() {
        assertEquals("连载中", normalizeSearchTag("  連載中  "))
        assertEquals("full color", normalizeSearchTag(" FULL COLOR "))
    }
}

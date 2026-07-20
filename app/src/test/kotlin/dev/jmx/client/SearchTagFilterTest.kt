package dev.jmx.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchTagFilterTest {
    @Test
    fun includeFilterMatchesTraditionalAlbumTags() {
        val filter = SearchTagFilter(tags = listOf("连载中"))

        assertTrue(filter.matches(listOf("連載中", "全彩")))
        assertFalse(filter.matches(listOf("已完结", "全彩")))
    }

    @Test
    fun includeFilterRequiresEverySelectedTag() {
        val filter = SearchTagFilter(tags = listOf("全彩", "韩漫"))

        assertTrue(filter.matches(listOf("全彩", "韓漫")))
        assertFalse(filter.matches(listOf("全彩")))
    }

    @Test
    fun excludeFilterRejectsAnySelectedTag() {
        val filter = SearchTagFilter(
            mode = SearchTagFilterMode.EXCLUDE,
            tags = listOf("全彩", "短篇"),
        )

        assertTrue(filter.matches(listOf("黑白", "长篇")))
        assertFalse(filter.matches(listOf("黑白", "短篇")))
    }

    @Test
    fun tagNormalizationTrimsAndConvertsToSimplifiedChinese() {
        assertEquals("连载中", normalizeSearchTag("  連載中  "))
        assertEquals("full color", normalizeSearchTag(" FULL COLOR "))
    }
}

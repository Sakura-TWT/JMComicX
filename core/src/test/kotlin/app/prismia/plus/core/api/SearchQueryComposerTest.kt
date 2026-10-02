package app.prismia.plus.core.api

import org.junit.Assert.assertEquals
import org.junit.Test

class SearchQueryComposerTest {

    @Test
    fun composesBaseWithIncludeAndExcludeTags() {
        val query = SearchQueryComposer.compose(
            baseQuery = "全彩",
            includeTags = listOf("连载中"),
            excludeTags = listOf("短篇"),
        )

        assertEquals("全彩 +连载中 -短篇", query)
    }

    @Test
    fun composesTagsOnlyWithoutBaseQuery() {
        val query = SearchQueryComposer.compose(
            baseQuery = "",
            includeTags = listOf("全彩", "韩漫"),
            excludeTags = listOf("短篇"),
        )

        assertEquals("+全彩 +韩漫 -短篇", query)
    }

    @Test
    fun includeWinsWhenSameTagIsBothIncludedAndExcluded() {
        val query = SearchQueryComposer.compose(
            baseQuery = "",
            includeTags = listOf("全彩"),
            excludeTags = listOf("全彩", "短篇"),
        )

        assertEquals("+全彩 -短篇", query)
    }

    @Test
    fun sanitizesPrefixesInnerWhitespaceAndDuplicates() {
        val query = SearchQueryComposer.compose(
            baseQuery = "  剑与远征  ",
            includeTags = listOf("+全彩", "全彩", " 连载 中 "),
            excludeTags = listOf("-短篇"),
        )

        // 去重、去掉多余的 +/- 前缀，单个标签内部空白只取首段（避免破坏 +/- 语义）。
        assertEquals("剑与远征 +全彩 +连载 -短篇", query)
    }

    @Test
    fun returnsEmptyWhenNothingMeaningfulProvided() {
        assertEquals("", SearchQueryComposer.compose(baseQuery = "   "))
    }
}

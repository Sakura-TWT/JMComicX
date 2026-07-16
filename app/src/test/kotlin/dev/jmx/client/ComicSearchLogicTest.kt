package dev.jmx.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ComicSearchLogicTest {
    @Test
    fun historyKeepsNewestUniqueQueriesWithinLimit() {
        assertEquals(
            listOf("女性向", "連載中", "JM123"),
            searchHistoryWith(
                current = listOf("連載中", "女性向", "JM123", "其他"),
                query = " 女性向 ",
                limit = 3,
            ),
        )
        assertEquals(
            listOf("jm123", "女性向"),
            searchHistoryWith(listOf("JM123", "女性向"), "jm123"),
        )
    }

    @Test
    fun openCcConvertsSimplifiedQueryToTraditional() {
        assertEquals("連載中與女性向", toTraditionalChinese("连载中与女性向"))
    }

    @Test
    fun queryVariantsIncludeOriginalAndTraditionalWithoutDuplicates() {
        assertEquals(
            listOf("连载中", "連載中"),
            searchQueryVariants("  连载中  ") { value ->
                if (value == "连载中") "連載中" else value
            },
        )
        assertEquals(
            listOf("女性向"),
            searchQueryVariants("女性向") { it },
        )
    }

    @Test
    fun jmIdSearchAcceptsDigitsAndOptionalPrefix() {
        assertEquals("1232836", "1232836".toJmSearchIdOrNull())
        assertEquals("1232836", "JM 1232836".toJmSearchIdOrNull())
        assertEquals("1232836", "jm1232836".toJmSearchIdOrNull())
        assertNull("JM12A".toJmSearchIdOrNull())
        assertNull("作品1232836".toJmSearchIdOrNull())
    }
}

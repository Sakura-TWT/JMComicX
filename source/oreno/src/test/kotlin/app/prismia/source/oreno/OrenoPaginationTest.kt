package app.prismia.source.oreno

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class OrenoPaginationTest {
    @Test fun arbitraryConsumerPageSizesNeitherDropNorRepeatSourceItems() = runBlocking {
        for (limit in listOf(1, 7, 32, 36, 50, 100)) {
            val requested = mutableListOf<Int>()
            val client = OrenoClient(object : OrenoTransport {
                override suspend fun get(path: String, query: Map<String, String>): String {
                    val page = query.getValue("page").toInt()
                    requested += page
                    val start = (page - 1) * 36 + 1
                    return pageHtml((start..minOf(start + 35, 110)).toList(), page < 4)
                }
            })
            val actual = mutableListOf<String>()
            var page = 0
            do {
                val result = client.browse(page++, limit)
                assertTrue(result.items.size <= limit)
                actual += result.items.map { it.key.remoteId }
            } while (result.hasMore && page < 120)
            assertEquals("limit=$limit", (1..110).map(Int::toString), actual)
            assertEquals(actual.size, actual.toSet().size)
            assertTrue(requested.all { it in 1..4 })
        }
    }

    @Test fun unknownNativePageSizeFailsBeforeSkippingAnyRecords() = runBlocking {
        val client = OrenoClient(object : OrenoTransport {
            override suspend fun get(path: String, query: Map<String, String>) = pageHtml((1..35).toList(), true)
        })
        assertTrue(runCatching { client.browse(0, 32) }.exceptionOrNull() is OrenoParseException)
    }

    @Test fun fullLastPageStopsWithoutAnExtraEmptyFetch() {
        val page = OrenoHtmlParser().parsePage(pageHtml((1..36).toList(), false), 1)
        assertEquals(36, page.items.size)
        assertFalse(page.hasMore)
    }

    @Test fun ellipsisPaginationUsesHrefInsteadOfVisibleNumbers() {
        val html = pageHtml((1..36).toList(), false).replace("</ul>", "<li><a href='?page=900'>»</a></li></ul>")
        assertTrue(OrenoHtmlParser().parsePage(html, 200).hasMore)
    }

    @Test fun rangeOverflowIsRejectedBeforeMakingARequest() = runBlocking {
        val client = OrenoClient(object : OrenoTransport {
            override suspend fun get(path: String, query: Map<String, String>): String = error("unexpected request")
        })
        assertTrue(runCatching { client.browse(Int.MAX_VALUE, 100) }.exceptionOrNull() is IllegalArgumentException)
    }

    private fun pageHtml(ids: List<Int>, more: Boolean): String = buildString {
        append("<div class='g-main-grid'>")
        if (ids.isEmpty()) append("<article>動画が見つかりません。</article>")
        ids.forEach { id -> append("<article><a href='/movies/$id'><h2 class='box-h2'>Clip $id</h2></a></article>") }
        append("</div><ul class='pagination'><li><a href='?page=1'>1</a></li>")
        if (more) append("<li><a href='?page=4'>»</a></li>")
        append("</ul>")
    }
}

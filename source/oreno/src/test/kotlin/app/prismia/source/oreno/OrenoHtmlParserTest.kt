package app.prismia.source.oreno

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class OrenoHtmlParserTest {
    @Test
    fun extractsVideoCardAndIwaraLink() {
        val html = """
            <article class="movie-card" data-views="123" data-favorites="7">
              <a href="/movie/42" title="Sample &amp; Clip"><img src="/thumb.jpg"></a>
              <a href="https://www.iwara.tv/video/abc123">play</a>
            </article>
        """.trimIndent()

        val item = OrenoHtmlParser().parseCards(html).single()
        assertEquals("42", item.id)
        assertEquals("Sample & Clip", item.title)
        assertEquals("abc123", item.iwaraVideoId)
        assertNotNull(item.thumbnailUrl)
        assertEquals(123L, item.viewCount)
        assertEquals(7L, item.likeCount)
    }

    @Test
    fun parsesPluralDetailRouteAndIwaraLink() {
        val html = """
            <html><head><title>Detail title</title></head>
            <body><header><h1 class="video-h1">Detail title</h1>
            <figure class="video-figure"><a href="https://www.iwara.tv/video/iw-1">play</a></figure></header></body></html>
        """.trimIndent()

        val item = OrenoHtmlParser().parseDetail(html, "359364")
        assertEquals("iw-1", item?.iwaraVideoId)
    }

    @Test
    fun parsesProductionStyleArticleWithoutCardClass() {
        val html = """
            <div class="g-main-grid"><article>
              <a href="https://oreno3d.com/movies/340810" class="box">
                <figure><div class="figure-text-in">Nested</div></figure>
                <h2 class="box-h2">Example</h2>
              </a>
            </article></div>
        """.trimIndent()

        val items = OrenoHtmlParser().parseCards(html)
        assertEquals(listOf("340810"), items.map { it.id })
    }
}

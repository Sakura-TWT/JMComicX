package app.prismia.source.oreno

import org.junit.Assert.*
import org.junit.Test

class OrenoMetadataTest {
    @Test fun parsesMainGridMetadataWithoutSidebarPollution() {
        val html = """
            <div class='g-main-grid'><article>
              <a href='/movies/42'><h2 class='box-h2'>A &amp; <span>B</span></h2></a>
              <img class='main-thumbnail' data-src='/cover.jpg' src='data:image/gif;base64,x'>
              <div class='box-text1'><div class='box-text-in'><i>face</i>Author</div></div>
              <div class='box-text2'><div class='box-text-in'><i>local_offer</i>TagA TagB</div></div>
              <div class='figure-text-in'>107.8k</div><div class='figure-text-in'>4,240</div>
            </article></div>
            <aside><article><a href='/movies/99'><h2>Unrelated</h2></a></article></aside>
        """.trimIndent()
        val record = OrenoHtmlParser().parseCards(html).single()
        assertEquals("42", record.id)
        assertEquals("A & B", record.title)
        assertEquals("Author", record.author)
        assertEquals(listOf("TagA", "TagB"), record.tags)
        assertEquals("https://oreno3d.com/cover.jpg", record.thumbnailUrl)
        assertEquals(107800L, record.viewCount)
        assertEquals(4240L, record.likeCount)
    }

    @Test fun detailAssociationCannotComeFromRelatedVideosOrLookalikeHosts() {
        val html = """
            <link rel='canonical' href='https://oreno3d.com/movies/42'>
            <header><h1 class='video-h1'>Main title</h1>
              <figure class='video-figure'><a href='https://www.iwara.tv.evil/video/bad'>bad</a>
                <a href='https://www.iwara.tv/video/real-id/title'>play</a></figure>
              <img class='video-img' src='/full.jpg'>
              <ul class='video-views'>
                <li><i class='material-icons video-text-icon'>favorite</i><div class='video-text'>2,273</div></li>
                <li><i class='material-icons video-text-icon'>remove_red_eye</i><div class='video-text'>54131</div></li>
              </ul>
            </header>
            <section class='video-section-tag'><a href='/authors/7'><i>face</i>Author</a>
              <a href='/tags/9'><i>local_offer</i>Tag</a></section>
            <blockquote class='video-information-comment'>A <b>description</b></blockquote>
            <div class='g-main-grid-related'><article><a href='https://www.iwara.tv/video/wrong'>wrong</a></article></div>
        """.trimIndent()
        val result = requireNotNull(OrenoHtmlParser().parseDetail(html, "42"))
        assertEquals("real-id", result.iwaraVideoId)
        assertEquals("Author", result.author)
        assertEquals(listOf("Tag"), result.tags)
        assertEquals("A description", result.description)
        assertEquals("https://oreno3d.com/full.jpg", result.thumbnailUrl)
        assertEquals(54131L, result.viewCount)
        assertEquals(2273L, result.likeCount)
        assertThrows(OrenoParseException::class.java) { OrenoHtmlParser().parseDetail(html, "43") }
    }

    @Test fun explicitEmptyResultIsDistinctFromChangedMarkup() {
        assertTrue(OrenoHtmlParser().parseCards("<div class='g-main-grid'><article>動画が見つかりません。</article></div>").isEmpty())
        for (html in listOf("", "<html><h1>Proxy error</h1></html>", "<article><h2>Unrecognized card</h2></article>")) {
            assertThrows(OrenoParseException::class.java) { OrenoHtmlParser().parseCards(html) }
        }
    }
}

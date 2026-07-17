package dev.jmx.client.core.api

import dev.jmx.client.core.crypto.AesEcbPkcs7
import dev.jmx.client.core.crypto.JmxHash
import dev.jmx.client.core.network.ApiEndpointManager
import dev.jmx.client.core.network.DefaultRetryPolicy
import dev.jmx.client.core.network.JmxApiClient
import dev.jmx.client.core.network.JmxHttpClient
import dev.jmx.client.core.protocol.ApiClock
import dev.jmx.client.core.protocol.ApiTokenProvider
import dev.jmx.client.core.protocol.JmxMagicConstants
import dev.jmx.client.core.protocol.JmxProtocolConstants
import dev.jmx.client.core.result.JmxResult
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ApiExtendedFacadeTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun interactionApiPostsLikeFavoriteAndComment() {
        val api = InteractionApi(createClient())
        server.enqueue(encryptedResponse("""{"status":"ok","msg":"liked"}"""))
        server.enqueue(encryptedResponse("""{"status":"ok","msg":"favorited","type":"add"}"""))
        server.enqueue(encryptedResponse("""{"status":"ok","msg":"commented"}"""))

        val like = kotlinx.coroutines.runBlocking { api.likeAlbum("123") }
        val favorite = kotlinx.coroutines.runBlocking { api.favoriteAlbum("123") }
        val comment = kotlinx.coroutines.runBlocking {
            api.commentAlbum("123", content = "hello", status = "false", commentId = "99")
        }

        assertTrue(like is JmxResult.Success)
        assertEquals("liked", (like as JmxResult.Success).value.message)
        assertTrue(favorite is JmxResult.Success)
        assertEquals("add", (favorite as JmxResult.Success).value.type)
        assertTrue(comment is JmxResult.Success)
        val likeRequest = server.takeRequest()
        assertEquals("/like", likeRequest.path)
        assertEquals("id=123", likeRequest.body.readUtf8())
        val favoriteRequest = server.takeRequest()
        assertEquals("/favorite", favoriteRequest.path)
        assertEquals("aid=123", favoriteRequest.body.readUtf8())
        val commentRequest = server.takeRequest()
        assertEquals("/comment", commentRequest.path)
        assertEquals("comment=hello&aid=123&status=false&comment_id=99", commentRequest.body.readUtf8())
    }

    @Test
    fun interactionApiParsesAlbumComments() {
        val api = InteractionApi(createClient())
        server.enqueue(
            encryptedResponse(
                """{"total":1,"comments":[{"CID":"c1","UID":"u1","username":"alice","comment":"hi","replys":[{"CID":"r1","comment":"reply"}]}]}"""
            )
        )

        val result = kotlinx.coroutines.runBlocking { api.albumComments(albumId = "456", page = 0) }

        assertTrue(result is JmxResult.Success)
        val page = (result as JmxResult.Success).value
        assertEquals(1, page.total)
        assertEquals("c1", page.comments.single().id)
        assertEquals("u1", page.comments.single().userId)
        assertEquals("alice", page.comments.single().username)
        assertEquals("r1", page.comments.single().replies.single().id)
        assertEquals("reply", page.comments.single().replies.single().content)
        assertEquals("/forum?page=1&aid=456&mode=manhua", server.takeRequest().path)
    }

    @Test
    fun libraryApiParsesListsAndDailyData() {
        val api = LibraryApi(createClient())
        server.enqueue(encryptedResponse("""[{"id":"1","name":"promo"}]"""))
        server.enqueue(
            encryptedResponse(
                """
                [{
                  "id":"26",
                  "title":"連載更新→右滑看更多→",
                  "slug":"series",
                  "type":"album",
                  "filter_val":"26",
                  "content":[{"id":"100","name":"serial album","author":"writer","image":"cover.jpg"}]
                }]
                """.trimIndent()
            )
        )
        server.enqueue(encryptedResponse("""{"total":2,"content":[{"id":"2","name":"fav"}]}"""))
        server.enqueue(
            encryptedResponse(
                """{"daily_id":7,"event_name":"daily","currentProgress":"28","three_days_coin":"150","three_days_exp":"150","seven_days_coin":"350","seven_days_exp":"350","record":[[{"date":"2026-07-14","signed":true,"bonus":false}]]}"""
            )
        )

        val promoted = kotlinx.coroutines.runBlocking { api.promotedAlbums(timestampMillis = 10L) }
        val sections = kotlinx.coroutines.runBlocking { api.promotedSections(timestampMillis = 11L) }
        val favorites = kotlinx.coroutines.runBlocking { api.favoriteAlbums(page = 2, order = "mr", folderId = 3) }
        val daily = kotlinx.coroutines.runBlocking { api.dailyInfo(userId = "42") }

        assertTrue(promoted is JmxResult.Success)
        assertEquals("promo", (promoted as JmxResult.Success).value.single().name)
        assertTrue(sections is JmxResult.Success)
        val serialSection = (sections as JmxResult.Success).value.single()
        assertEquals("26", serialSection.id)
        assertEquals("連載更新→右滑看更多→", serialSection.title)
        assertEquals("serial album", serialSection.content.single().name)
        assertEquals("cover.jpg", serialSection.content.single().image)
        assertTrue(favorites is JmxResult.Success)
        assertEquals("fav", (favorites as JmxResult.Success).value.content.single().name)
        assertTrue(daily is JmxResult.Success)
        assertEquals(7, (daily as JmxResult.Success).value.dailyId)
        assertEquals(true, daily.value.records.single().signed)
        assertEquals(150, daily.value.threeDaysCoin)
        assertEquals(150, daily.value.threeDaysExp)
        assertEquals(350, daily.value.sevenDaysCoin)
        assertEquals(350, daily.value.sevenDaysExp)
        assertEquals("/promote?_=10", server.takeRequest().path)
        assertEquals("/promote?_=11", server.takeRequest().path)
        assertEquals("/favorite?page=2&o=mr&folder_id=3", server.takeRequest().path)
        assertEquals("/daily?user_id=42", server.takeRequest().path)
    }

    @Test
    fun libraryApiAcceptsArrayPagesForWatchAndWeekFilter() {
        val api = LibraryApi(createClient())
        server.enqueue(encryptedResponse("""[{"id":"7","name":"watched","author":"reader"}]"""))
        server.enqueue(encryptedResponse("""[{"id":"8","name":"weekly","author":"editor"}]"""))

        val watched = kotlinx.coroutines.runBlocking { api.watchList(page = 0) }
        val weekly = kotlinx.coroutines.runBlocking { api.weekFilter(page = 0, categoryId = "cat", typeId = "type") }

        assertTrue(watched is JmxResult.Success)
        assertEquals(null, (watched as JmxResult.Success).value.total)
        assertEquals("watched", watched.value.content.single().name)
        assertTrue(weekly is JmxResult.Success)
        assertEquals("weekly", (weekly as JmxResult.Success).value.content.single().name)
        assertEquals("/watch_list?page=1", server.takeRequest().path)
        assertEquals("/week/filter?page=1&id=cat&type=type", server.takeRequest().path)
    }

    @Test
    fun libraryApiPagesSupportedPromoteSectionTypes() {
        val api = LibraryApi(createClient())
        server.enqueue(encryptedResponse("""{"total":90,"content":[{"id":"29-1","name":"promoted"}]}"""))
        server.enqueue(encryptedResponse("""{"total":80,"content":[{"id":"han-1","name":"hanman"}]}"""))
        server.enqueue(encryptedResponse("""{"total":40,"content":[{"id":"group-1","name":"group"}]}"""))

        val promoted = kotlinx.coroutines.runBlocking {
            api.promotedSectionPage(
                section = HomePromoteSection(
                    id = "29",
                    title = "C107",
                    slug = "",
                    type = "promote",
                    filterValue = "29",
                    content = emptyList(),
                    raw = emptyMap(),
                ),
                page = 0,
            )
        }
        val category = kotlinx.coroutines.runBlocking {
            api.promotedSectionPage(
                section = HomePromoteSection(
                    id = "999",
                    title = "韓漫更新",
                    slug = "hanman",
                    type = "category_id",
                    filterValue = "5",
                    content = emptyList(),
                    raw = emptyMap(),
                ),
                page = 2,
            )
        }
        val searched = kotlinx.coroutines.runBlocking {
            api.promotedSectionPage(
                section = HomePromoteSection(
                    id = "998",
                    title = "禁漫漢化組",
                    slug = "禁漫漢化組",
                    type = "not_in_category_id",
                    filterValue = "",
                    content = emptyList(),
                    raw = emptyMap(),
                ),
                page = 3,
            )
        }

        assertEquals("promoted", (promoted as JmxResult.Success).value.content.single().name)
        assertEquals("hanman", (category as JmxResult.Success).value.content.single().name)
        assertEquals("group", (searched as JmxResult.Success).value.content.single().name)
        assertEquals("/promote_list?id=29&page=1", server.takeRequest().path)
        assertEquals("/categories/filter?page=2&order=&c=hanman&o=mr", server.takeRequest().path)
        assertEquals(
            "/search?search_query=%E7%A6%81%E6%BC%AB%E6%BC%A2%E5%8C%96%E7%B5%84&page=3&o=mr&main_tag=0&t=a",
            server.takeRequest().path,
        )
    }

    @Test
    fun libraryApiParsesUserComments() {
        val api = LibraryApi(createClient())
        server.enqueue(
            encryptedResponse(
                """{"total":1,"list":[{"comment_id":"c9","user_id":"42","user_name":"bob","content":"history","likes":5}]}"""
            )
        )

        val result = kotlinx.coroutines.runBlocking { api.userComments(page = 0, userId = "42") }

        assertTrue(result is JmxResult.Success)
        val page = (result as JmxResult.Success).value
        assertEquals(1, page.total)
        assertEquals("c9", page.comments.single().id)
        assertEquals("bob", page.comments.single().username)
        assertEquals(5, page.comments.single().likes)
        assertEquals("/forum?page=1&uid=42", server.takeRequest().path)
    }

    @Test
    fun libraryApiPostsDailyCheckAndParsesAction() {
        val api = LibraryApi(createClient())
        server.enqueue(encryptedResponse("""{"msg":"signed"}"""))

        val result = kotlinx.coroutines.runBlocking { api.dailyCheck(userId = "42", dailyId = "7") }

        assertTrue(result is JmxResult.Success)
        assertEquals("signed", (result as JmxResult.Success).value.message)
        val request = server.takeRequest()
        assertEquals("/daily_chk", request.path)
        assertEquals("user_id=42&daily_id=7", request.body.readUtf8())
    }

    @Test
    fun libraryApiParsesWeekInfoAndCategoryFilter() {
        val api = LibraryApi(createClient())
        server.enqueue(
            encryptedResponse(
                """{"categories":[{"id":"w","time":"week","title":"本周"}],"type":[{"id":"a","title":"全部"}]}"""
            )
        )
        server.enqueue(
            encryptedResponse(
                """{"total":1,"list":[{"id":"9","name":"category album","author":"tester"}]}"""
            )
        )

        val week = kotlinx.coroutines.runBlocking { api.week() }
        val filtered = kotlinx.coroutines.runBlocking {
            api.categoriesFilter(
                CategoryFilter(
                    page = 0,
                    time = JmxMagicConstants.TIME_WEEK,
                    category = JmxMagicConstants.CATEGORY_DOUJIN,
                    order = JmxMagicConstants.ORDER_BY_VIEW
                )
            )
        }

        assertTrue(week is JmxResult.Success)
        val weekInfo = (week as JmxResult.Success).value
        assertEquals("w", weekInfo.categories.single().id)
        assertEquals("本周", weekInfo.categories.single().title)
        assertEquals("a", weekInfo.types.single().id)
        assertTrue(filtered is JmxResult.Success)
        assertEquals("category album", (filtered as JmxResult.Success).value.content.single().name)
        assertEquals("/week", server.takeRequest().path)

        assertEquals("/categories/filter?page=1&order=&c=doujin&o=mv_w", server.takeRequest().path)
    }

    private fun createClient(): JmxApiClient {
        val tokenProvider = ApiTokenProvider(
            clock = object : ApiClock {
                override fun nowSeconds(): Long = TS
            },
            versionProvider = { JmxProtocolConstants.DefaultApiVersion }
        )
        return JmxApiClient(
            JmxHttpClient(
                endpointManager = ApiEndpointManager(listOf(server.url("/").toString())),
                tokenProvider = tokenProvider,
                retryPolicy = DefaultRetryPolicy(maxAttempts = 1)
            )
        )
    }

    private fun encryptedResponse(dataJson: String): MockResponse {
        val encrypted = AesEcbPkcs7.encryptStringToBase64(
            dataJson,
            JmxHash.md5Hex("$TS${JmxProtocolConstants.DataSecret}")
        )
        return MockResponse().setResponseCode(200).setBody("""{"code":200,"data":"$encrypted"}""")
    }

    private companion object {
        const val TS = 1700566805L
    }
}

package dev.jmx.client.core.api

import dev.jmx.client.core.crypto.AesEcbPkcs7
import dev.jmx.client.core.crypto.JmxHash
import dev.jmx.client.core.network.ApiEndpointManager
import dev.jmx.client.core.network.DefaultRetryPolicy
import dev.jmx.client.core.network.JmxApiClient
import dev.jmx.client.core.network.JmxHttpClient
import dev.jmx.client.core.protocol.ApiClock
import dev.jmx.client.core.protocol.ApiTokenProvider
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
                """{"total":1,"comments":[{"id":"c1","uid":"u1","username":"alice","comment":"hi","replys":[{"id":"r1","comment":"reply"}]}]}"""
            )
        )

        val result = kotlinx.coroutines.runBlocking { api.albumComments(albumId = "456", page = 0) }

        assertTrue(result is JmxResult.Success)
        val page = (result as JmxResult.Success).value
        assertEquals(1, page.total)
        assertEquals("alice", page.comments.single().username)
        assertEquals("reply", page.comments.single().replies.single().content)
        assertEquals("/forum?page=1&aid=456&mode=manhua", server.takeRequest().path)
    }

    @Test
    fun libraryApiParsesListsAndDailyData() {
        val api = LibraryApi(createClient())
        server.enqueue(encryptedResponse("""[{"id":"1","name":"promo"}]"""))
        server.enqueue(encryptedResponse("""{"total":2,"content":[{"id":"2","name":"fav"}]}"""))
        server.enqueue(
            encryptedResponse(
                """{"daily_id":7,"event_name":"daily","currentProgress":"1","record":[[{"date":"2026-07-14","signed":true,"bonus":false}]]}"""
            )
        )

        val promoted = kotlinx.coroutines.runBlocking { api.promotedAlbums(timestampMillis = 10L) }
        val favorites = kotlinx.coroutines.runBlocking { api.favoriteAlbums(page = 2, order = "mr", folderId = 3) }
        val daily = kotlinx.coroutines.runBlocking { api.dailyInfo(userId = "42") }

        assertTrue(promoted is JmxResult.Success)
        assertEquals("promo", (promoted as JmxResult.Success).value.single().name)
        assertTrue(favorites is JmxResult.Success)
        assertEquals("fav", (favorites as JmxResult.Success).value.content.single().name)
        assertTrue(daily is JmxResult.Success)
        assertEquals(7, (daily as JmxResult.Success).value.dailyId)
        assertEquals(true, daily.value.records.single().signed)
        assertEquals("/promote?_=10", server.takeRequest().path)
        assertEquals("/favorite?page=2&o=mr&folder_id=3", server.takeRequest().path)
        assertEquals("/daily?user_id=42", server.takeRequest().path)
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

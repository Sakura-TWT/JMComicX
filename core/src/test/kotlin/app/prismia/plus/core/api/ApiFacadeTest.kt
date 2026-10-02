package app.prismia.plus.core.api

import app.prismia.plus.core.crypto.AesEcbPkcs7
import app.prismia.plus.core.crypto.JmxHash
import app.prismia.plus.core.cache.InMemoryKeyValueStore
import app.prismia.plus.core.cache.ProtocolStateStore
import app.prismia.plus.core.network.ApiEndpointManager
import app.prismia.plus.core.network.ApiEndpointSelection
import app.prismia.plus.core.network.DefaultRetryPolicy
import app.prismia.plus.core.network.JmxApiClient
import app.prismia.plus.core.network.JmxHttpClient
import app.prismia.plus.core.network.defaultOkHttpClient
import app.prismia.plus.core.protocol.ApiClock
import app.prismia.plus.core.protocol.ApiTokenProvider
import app.prismia.plus.core.protocol.JmxProtocolConstants
import app.prismia.plus.core.protocol.MutableApiVersionProvider
import app.prismia.plus.core.result.JmxResult
import app.prismia.plus.core.session.CookieStore
import app.prismia.plus.core.session.InMemoryCookieStore
import app.prismia.plus.core.session.SessionManager
import app.prismia.plus.core.session.StoreBackedCookieJar
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ApiFacadeTest {
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
    fun settingApiUpdatesRuntimeVersion() {
        val versionProvider = MutableApiVersionProvider("1.0.0")
        server.enqueue(encryptedResponse("""{"jm3_version":"2.0.26","img_host":"https://img.test","app_shunts":[{"id":"1","name":"线路 1"}]}"""))
        val settingApi = SettingApi(createClient(versionProvider = versionProvider), versionProvider)

        val result = kotlinx.coroutines.runBlocking { settingApi.fetchSetting() }

        assertTrue(result is JmxResult.Success)
        assertEquals("2.0.26", versionProvider.current())
        assertEquals("https://img.test", (result as JmxResult.Success).value.imageHost)
    }

    @Test
    fun userApiInstallsAvsCookieAfterLogin() {
        val endpointManager = ApiEndpointManager(listOf(server.url("/").toString()))
        val store = InMemoryCookieStore()
        val session = SessionManager(store)
        server.enqueue(
            encryptedResponse(
                """{"s":"avs-value","uid":1,"username":"alice","email":"a@test","photo":"avatar.jpg","level":3,"level_name":"Lv3","exp":20,"nextLevelExp":100,"expPercent":0.2,"album_favorites":4,"album_favorites_max":40,"coin":"9"}"""
            )
        )
        val userApi = UserApi(createClient(endpointManager = endpointManager, cookieStore = store), session)

        val result = kotlinx.coroutines.runBlocking { userApi.login("user", "pass") }

        assertTrue(result is JmxResult.Success)
        val sessionResult = (result as JmxResult.Success).value
        val profile = sessionResult.profile!!
        assertEquals("avs-value", session.cookies().single().value)
        assertEquals(1, profile.id)
        assertEquals("alice", profile.username)
        assertEquals("avatar.jpg", profile.avatar)
        assertEquals(3, profile.level)
        assertEquals(9, profile.coin)
        val recorded = server.takeRequest()
        assertEquals("username=user&password=pass", recorded.body.readUtf8())
    }

    @Test
    fun userApiCommitsTemporaryCookiesOnlyAfterSuccessfulLogin() {
        val endpointManager = ApiEndpointManager(listOf(server.url("/").toString()))
        val store = InMemoryCookieStore()
        val session = SessionManager(store)
        server.enqueue(
            encryptedResponse("""{"s":"json-avs","uid":"1"}""")
                .addHeader("Set-Cookie", "OTHER=extra; Path=/; HttpOnly")
                .addHeader("Set-Cookie", "AVS=set-cookie-avs; Path=/; HttpOnly")
        )
        val userApi = UserApi(createClient(endpointManager = endpointManager, cookieStore = store), session)

        val result = kotlinx.coroutines.runBlocking { userApi.login("user", "pass") }

        assertTrue(result is JmxResult.Success)
        val cookies = store.load(server.url("/album"))
        assertEquals("json-avs", cookies.single { it.name == "AVS" }.value)
        assertEquals("extra", cookies.single { it.name == "OTHER" }.value)
    }

    @Test
    fun userApiDoesNotPolluteSessionCookiesWhenLoginFails() {
        val endpointManager = ApiEndpointManager(listOf(server.url("/").toString()))
        val store = InMemoryCookieStore()
        val session = SessionManager(store)
        session.installAvsCookie(server.url("/").toString(), "old-avs")
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Set-Cookie", "AVS=bad-avs; Path=/; HttpOnly")
                .setBody("""{"code":403,"msg":"denied"}""")
        )
        val userApi = UserApi(createClient(endpointManager = endpointManager, cookieStore = store), session)

        val result = kotlinx.coroutines.runBlocking { userApi.login("user", "bad-pass") }

        assertTrue(result is JmxResult.Failure)

        assertEquals("old-avs", store.load(server.url("/album")).single { it.name == "AVS" }.value)
        assertEquals(1, session.cookies().size)
    }

    @Test
    fun userApiStartsLoginWithCleanTemporarySession() {
        val endpointManager = ApiEndpointManager(listOf(server.url("/").toString()))
        val store = InMemoryCookieStore()
        val session = SessionManager(store)
        val guest = okhttp3.Cookie.Builder()
            .name("GUEST")
            .value("seed")
            .hostOnlyDomain(server.url("/").host)
            .path("/")
            .build()
        store.save(server.url("/"), listOf(guest))
        server.enqueue(encryptedResponse("""{"s":"avs-new","uid":"1","username":"x"}"""))
        val userApi = UserApi(
            createClient(endpointManager = endpointManager, cookieStore = store),
            session
        )

        val result = kotlinx.coroutines.runBlocking { userApi.login("user", "pass") }

        assertTrue(result is JmxResult.Success)
        val recorded = server.takeRequest()
        val cookieHeader = recorded.getHeader("Cookie").orEmpty()
        assertTrue("login must not carry stale guest cookie, got=$cookieHeader", !cookieHeader.contains("GUEST=seed"))
    }

    @Test
    fun userApiSyncsAvsToAdditionalHostsAfterLogin() {
        val endpointManager = ApiEndpointManager(listOf(server.url("/").toString()))
        val store = InMemoryCookieStore()
        val session = SessionManager(store)
        server.enqueue(encryptedResponse("""{"s":"synced-avs","uid":"9","username":"bob"}"""))
        val userApi = UserApi(
            apiClient = createClient(endpointManager = endpointManager, cookieStore = store),
            sessionManager = session,
            sessionSyncHosts = { listOf("https://mirror-a.test", "https://mirror-b.test") }
        )

        val result = kotlinx.coroutines.runBlocking { userApi.login("user", "pass") }

        assertTrue(result is JmxResult.Success)
        assertEquals("synced-avs", (result as JmxResult.Success).value.avs)
        assertEquals(
            "synced-avs",
            store.load("https://mirror-a.test/album".toHttpUrl()).single().value
        )
        assertEquals(
            "synced-avs",
            store.load("https://mirror-b.test/album".toHttpUrl()).single().value
        )
    }

    @Test
    fun userApiLogoutClearsSession() {
        val store = InMemoryCookieStore()
        val session = SessionManager(store)
        session.installAvsCookie(server.url("/").toString(), "avs")
        val userApi = UserApi(createClient(cookieStore = store), session)

        userApi.logout()

        assertEquals(0, session.cookies().size)
    }

    /**
     * 登录必须记住是哪台机器签发的会话。
     *
     * 服务端把 AVS 绑在签发它的域名上（同一个 AVS 换到镜像域名请求 /favorite 回 401），
     * 所以选路要跟着会话走；但这不是用户的选择，不该冒充"手动线路"。
     */
    @Test
    fun userApiRemembersLoginHostAsSessionEndpoint() {
        val stateStore = ProtocolStateStore(InMemoryKeyValueStore())
        stateStore.updateApiHosts(listOf(server.url("/").toString()))
        val endpointManager = ApiEndpointManager(protocolStateStore = stateStore)
        val store = InMemoryCookieStore()
        val session = SessionManager(store)
        server.enqueue(encryptedResponse("""{"s":"avs-value","uid":1,"username":"alice"}"""))
        val userApi = UserApi(
            apiClient = createClient(endpointManager = endpointManager, cookieStore = store),
            sessionManager = session,
            endpointManager = endpointManager
        )

        val result = kotlinx.coroutines.runBlocking { userApi.login("user", "pass") }

        assertTrue(result is JmxResult.Success)
        assertEquals(server.url("/").toString(), endpointManager.sessionEndpoint()?.toString())
        assertEquals(server.url("/").toString(), stateStore.sessionApiHost())
        assertTrue(endpointManager.selection() is ApiEndpointSelection.Auto)
        assertEquals(null, stateStore.manualApiHost())
    }

    /** 退出登录只解开会话亲和；用户自己选的线路是设置项，不该被顺手清掉。 */
    @Test
    fun userApiLogoutClearsSessionEndpointButKeepsManualEndpoint() {
        val stateStore = ProtocolStateStore(InMemoryKeyValueStore())
        val endpointManager = ApiEndpointManager(protocolStateStore = stateStore)
        endpointManager.useSessionEndpoint("session.test")
        endpointManager.useManualEndpoint("manual.test")
        val session = SessionManager(InMemoryCookieStore())
        val userApi = UserApi(
            apiClient = createClient(endpointManager = endpointManager),
            sessionManager = session,
            endpointManager = endpointManager
        )

        userApi.logout()

        assertEquals(null, endpointManager.sessionEndpoint())
        assertEquals(null, stateStore.sessionApiHost())
        assertEquals("https://manual.test/", stateStore.manualApiHost())
    }

    @Test
    fun albumApiSearchResolvedExpandsRedirectAid() {
        server.enqueue(encryptedResponse("""{"search_query":"438516","total":1,"redirect_aid":"438516","content":[]}"""))
        server.enqueue(
            encryptedResponse(
                """{"id":"438516","name":"redirected","author":["x"],"images":6}"""
            )
        )
        val albumApi = AlbumApi(createClient())

        val result = kotlinx.coroutines.runBlocking {
            albumApi.searchResolved(query = "438516", page = 1)
        }

        assertTrue(result is JmxResult.Success)
        val page = (result as JmxResult.Success).value
        assertEquals("438516", page.redirectAlbumId)
        assertEquals(1, page.content.size)
        assertEquals("redirected", page.content.single().name)
        assertEquals("/search?search_query=438516&page=1&o=mr&main_tag=0&t=a", server.takeRequest().path)
        assertEquals("/album?id=438516", server.takeRequest().path)
    }

    @Test
    fun albumApiParsesFullAlbumDetail() {
        server.enqueue(
            encryptedResponse(
                """
                {
                  "id":123,
                  "name":"album",
                  "description":"desc",
                  "author":["alice","bob"],
                  "total_photos":2463,
                  "total_views":99,
                  "likes":10,
                  "comment_total":3,
                  "tags":["tag"],
                  "actors":["role"],
                  "works":["work"],
                  "is_favorite":true,
                  "liked":false,
                  "related_list":[{"id":"8","name":"related","author":"carol","image":"cover.jpg"}],
                  "series":[{"id":"9","name":"第1话","sort":"1"}],
                  "series_id":"7",
                  "price":"5",
                  "purchased":true
                }
                """.trimIndent()
            )
        )
        val albumApi = AlbumApi(createClient())

        val result = kotlinx.coroutines.runBlocking { albumApi.detailFull("123") }

        assertTrue(result is JmxResult.Success)
        val detail = (result as JmxResult.Success).value
        assertEquals("123", detail.id)
        assertEquals("album", detail.name)
        assertEquals(listOf("alice", "bob"), detail.authors)
        assertEquals(2463, detail.imageCount)
        assertEquals(99, detail.totalViews)
        assertEquals(true, detail.isFavorite)
        assertEquals("related", detail.related.single().name)
        assertEquals("第1话", detail.series.single().name)
        assertEquals(5, detail.price)
        assertEquals(true, detail.purchased)
        assertEquals("/album?id=123", server.takeRequest().path)
    }

    @Test
    fun albumApiDetailKeepsSummaryCompatibility() {
        server.enqueue(encryptedResponse("""{"id":"321","name":"summary","author":"solo","images":12}"""))
        val albumApi = AlbumApi(createClient())

        val result = kotlinx.coroutines.runBlocking { albumApi.detail("321") }

        assertTrue(result is JmxResult.Success)
        val summary = (result as JmxResult.Success).value
        assertEquals("321", summary.id)
        assertEquals("summary", summary.name)
        assertEquals("solo", summary.author)
        assertEquals(12, summary.imageCount)
        assertEquals("/album?id=321", server.takeRequest().path)
    }

    @Test
    fun albumApiSearchAcceptsArrayResponse() {
        server.enqueue(encryptedResponse("""[{"album_id":"8","title":"array result","authors":"author"}]"""))
        val albumApi = AlbumApi(createClient())

        val result = kotlinx.coroutines.runBlocking {
            albumApi.search(query = "demo", page = 0, order = "mr", mainTag = 2, time = "week")
        }

        assertTrue(result is JmxResult.Success)
        val page = (result as JmxResult.Success).value
        assertEquals(null, page.total)
        assertEquals("8", page.content.single().id)
        assertEquals("array result", page.content.single().name)
        assertEquals("/search?search_query=demo&page=1&o=mr&main_tag=2&t=week", server.takeRequest().path)
    }

    private fun createClient(
        endpointManager: ApiEndpointManager = ApiEndpointManager(listOf(server.url("/").toString())),
        versionProvider: MutableApiVersionProvider = MutableApiVersionProvider(JmxProtocolConstants.DefaultApiVersion),
        cookieStore: CookieStore? = null
    ): JmxApiClient {
        val tokenProvider = ApiTokenProvider(
            clock = object : ApiClock {
                override fun nowSeconds(): Long = TS
            },
            apiVersionProvider = versionProvider
        )
        return JmxApiClient(
            JmxHttpClient(
                endpointManager = endpointManager,
                tokenProvider = tokenProvider,
                okHttpClient = cookieStore
                    ?.let { defaultOkHttpClient(StoreBackedCookieJar(it)) }
                    ?: defaultOkHttpClient(),
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

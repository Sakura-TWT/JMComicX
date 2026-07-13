package dev.jmx.client.core.runtime

import dev.jmx.client.core.cache.InMemoryKeyValueStore
import dev.jmx.client.core.crypto.AesEcbPkcs7
import dev.jmx.client.core.crypto.JmxHash
import dev.jmx.client.core.image.DecodedImageRows
import dev.jmx.client.core.image.ImagePipeline
import dev.jmx.client.core.image.ImageRowCodec
import dev.jmx.client.core.image.InMemoryImageOutputStore
import dev.jmx.client.core.image.RestoredImageBytes
import dev.jmx.client.core.network.DefaultRetryPolicy
import dev.jmx.client.core.network.ApiEndpointSelection
import dev.jmx.client.core.protocol.ApiClock
import dev.jmx.client.core.protocol.JmxProtocolConstants
import dev.jmx.client.core.result.JmxError
import dev.jmx.client.core.result.JmxResult
import dev.jmx.client.core.session.InMemoryCookieStore
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class JmxCoreTest {
    private lateinit var server: MockWebServer
    private lateinit var domainServer: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        domainServer = MockWebServer()
        domainServer.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
        domainServer.shutdown()
    }

    @Test
    fun coreUsesStoredProtocolStateForEndpointAndVersion() {
        val ts = 1700566805L
        val keyValueStore = InMemoryKeyValueStore(
            mapOf(
                "protocol.api.version" to "1.0.0",
                "protocol.api.hosts" to server.url("/").toString()
            )
        )
        val core = JmxCore.create(
            JmxCoreConfig(
                keyValueStore = keyValueStore,
                apiClock = fixedClock(ts),
                retryPolicy = DefaultRetryPolicy(maxAttempts = 1)
            )
        )
        server.enqueue(encryptedResponse(ts, """{"jm3_version":"2.1.0","img_host":"https://img.test","app_shunts":[]}"""))

        val result = kotlinx.coroutines.runBlocking { core.settingApi.fetchSetting() }

        assertTrue(result is JmxResult.Success)
        assertEquals("2.1.0", core.apiVersionProvider.current())
        assertEquals("2.1.0", core.protocolStateStore.apiVersion())
        val recorded = server.takeRequest()
        assertEquals("/setting", recorded.path)
        assertEquals("1700566805,1.0.0", recorded.headers["tokenparam"])
    }

    @Test
    fun coreSharesSessionAcrossUserApiAndCookieStore() {
        val ts = 1700566805L
        val cookieStore = InMemoryCookieStore()
        val core = JmxCore.create(
            JmxCoreConfig(
                keyValueStore = InMemoryKeyValueStore(
                    mapOf("protocol.api.hosts" to server.url("/").toString())
                ),
                cookieStore = cookieStore,
                apiClock = fixedClock(ts),
                retryPolicy = DefaultRetryPolicy(maxAttempts = 1)
            )
        )
        server.enqueue(encryptedResponse(ts, """{"s":"avs-value","uid":"1"}"""))

        val result = kotlinx.coroutines.runBlocking { core.userApi.login("user", "pass") }

        assertTrue(result is JmxResult.Success)
        assertEquals("avs-value", core.sessionManager.cookies().single().value)
        assertEquals("username=user&password=pass", server.takeRequest().body.readUtf8())
    }

    @Test
    fun coreExposesExtendedApiFacades() {
        val ts = 1700566805L
        val core = JmxCore.create(
            JmxCoreConfig(
                keyValueStore = InMemoryKeyValueStore(
                    mapOf("protocol.api.hosts" to server.url("/").toString())
                ),
                apiClock = fixedClock(ts),
                retryPolicy = DefaultRetryPolicy(maxAttempts = 1)
            )
        )
        server.enqueue(encryptedResponse(ts, """{"status":"ok","msg":"liked"}"""))
        server.enqueue(encryptedResponse(ts, """{"total":1,"content":[{"id":"1","name":"fav"}]}"""))

        val like = kotlinx.coroutines.runBlocking { core.interactionApi.likeAlbum("1") }
        val favorites = kotlinx.coroutines.runBlocking { core.libraryApi.favoriteAlbums(page = 1, order = "mr") }

        assertTrue(like is JmxResult.Success)
        assertTrue(favorites is JmxResult.Success)
        assertEquals("/like", server.takeRequest().path)
        assertEquals("/favorite?page=1&o=mr&folder_id=0", server.takeRequest().path)
    }

    @Test
    fun initializerRefreshesDomainsThenFetchesSetting() {
        val ts = 1700566805L
        domainServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(encryptDomain("""{"Server":["${server.url("/")}"]}"""))
        )
        server.enqueue(encryptedResponse(ts, """{"jm3_version":"2.2.0","img_host":"https://img.test","app_shunts":[]}"""))
        val core = JmxCore.create(
            JmxCoreConfig(
                keyValueStore = InMemoryKeyValueStore(mapOf("protocol.api.hosts" to "https://old.test")),
                apiClock = fixedClock(ts),
                retryPolicy = DefaultRetryPolicy(maxAttempts = 1),
                domainServerUrls = listOf(domainServer.url("/domains").toString())
            )
        )
        core.sessionManager.installAvsCookie("https://old.test", "secret")

        val result = kotlinx.coroutines.runBlocking { core.initializer.initialize() }

        assertTrue(result.domainRefresh is InitStepResult.Success)
        assertTrue(result.settingFetch is InitStepResult.Success)
        assertTrue(result.isFullySuccessful)
        assertEquals("2.2.0", core.protocolStateStore.apiVersion())
        assertEquals(server.url("/").toString(), core.protocolStateStore.apiHosts().single())
        assertEquals(2, core.sessionManager.cookies().count { it.value == "secret" })
        val settingRequest = server.takeRequest()
        assertEquals("AVS=secret", settingRequest.headers["Cookie"])
        assertEquals("/setting", settingRequest.path)
    }

    @Test
    fun initializerKeepsGoingWhenDomainRefreshFails() {
        val ts = 1700566805L
        domainServer.enqueue(MockResponse().setResponseCode(503).setBody("down"))
        server.enqueue(encryptedResponse(ts, """{"jm3_version":"2.3.0","img_host":"https://img.test","app_shunts":[]}"""))
        val core = JmxCore.create(
            JmxCoreConfig(
                keyValueStore = InMemoryKeyValueStore(mapOf("protocol.api.hosts" to server.url("/").toString())),
                apiClock = fixedClock(ts),
                retryPolicy = DefaultRetryPolicy(maxAttempts = 1),
                domainServerUrls = listOf(domainServer.url("/domains").toString())
            )
        )

        val result = kotlinx.coroutines.runBlocking { core.initializer.initialize() }

        assertTrue(result.domainRefresh is InitStepResult.Failure)
        assertTrue(result.settingFetch is InitStepResult.Success)
        assertTrue(!result.isFullySuccessful)
        assertEquals("2.3.0", core.protocolStateStore.apiVersion())
        assertEquals("/setting", server.takeRequest().path)
    }

    @Test
    fun healthSnapshotReportsRuntimeState() {
        val domainUrls = listOf("https://domain-a.test/domains", "https://domain-b.test/domains")
        val core = JmxCore.create(
            JmxCoreConfig(
                keyValueStore = InMemoryKeyValueStore(
                    mapOf(
                        "protocol.api.version" to "2.5.0",
                        "protocol.api.hosts" to "https://api-a.test\nhttps://api-b.test"
                    )
                ),
                downloadConcurrency = 7,
                domainServerUrls = domainUrls
            )
        )
        core.endpointManager.markFailure("https://api-a.test/".toHttpUrl(), "timeout")
        core.endpointManager.markSuccess("https://api-b.test/".toHttpUrl(), latencyMillis = 750)
        core.endpointManager.useManualEndpoint("https://manual.test")
        core.sessionManager.installAvsCookie("https://api-a.test", "secret")

        val health = core.healthSnapshot()

        assertEquals("2.5.0", health.apiVersion)
        assertEquals(2, health.endpoints.size)
        assertEquals("https://api-a.test/", health.endpoints[0].url)
        assertEquals(1, health.endpoints[0].failureCount)
        assertEquals(1, health.endpoints[0].consecutiveFailureCount)
        assertTrue(health.endpoints[0].lastFailureAtMillis != null)
        assertTrue(health.endpoints[0].unavailableUntilMillis == null)
        assertTrue(health.endpoints[0].isAvailable)
        assertEquals("timeout", health.endpoints[0].lastFailureMessage)
        assertEquals(750L, health.endpoints[1].lastLatencyMillis)
        assertEquals(750L, health.endpoints[1].averageLatencyMillis)
        assertEquals("manual", health.endpointSelection.mode)
        assertEquals("https://manual.test/", health.endpointSelection.manualUrl)
        assertEquals(1, health.cookieCount)
        assertEquals(domainUrls, health.domainServerUrls)
        assertEquals(7, health.downloadConcurrency)
    }

    @Test
    fun endpointControllerSwitchesManualEndpointAndSyncsSession() {
        val cookieStore = InMemoryCookieStore()
        val core = JmxCore.create(
            JmxCoreConfig(
                keyValueStore = InMemoryKeyValueStore(
                    mapOf("protocol.api.hosts" to "https://api-a.test\nhttps://api-b.test")
                ),
                cookieStore = cookieStore
            )
        )
        core.sessionManager.installAvsCookie("https://api-a.test", "secret")

        val report = core.endpointController.useManualEndpoint("manual.test/path")

        assertTrue(report is JmxResult.Success)
        val value = (report as JmxResult.Success).value
        assertTrue(value.selection is ApiEndpointSelection.Manual)
        assertEquals(1, value.syncedAvsCookieCount)
        assertEquals("manual", value.health.endpointSelection.mode)
        assertEquals("https://manual.test/", value.health.endpointSelection.manualUrl)
        assertEquals(1, cookieStore.load("https://manual.test/album".toHttpUrl()).size)
    }

    @Test
    fun endpointControllerRestoresAutoSelection() {
        val core = JmxCore.create(
            JmxCoreConfig(
                keyValueStore = InMemoryKeyValueStore(
                    mapOf("protocol.api.hosts" to "https://api-a.test\nhttps://api-b.test")
                )
            )
        )
        core.endpointController.useManualEndpoint("manual.test")

        val report = core.endpointController.useAutoSelection()

        assertEquals("auto", report.health.endpointSelection.mode)
        assertEquals(null, report.health.endpointSelection.manualUrl)
        assertEquals(0, report.syncedAvsCookieCount)
    }

    @Test
    fun endpointControllerRejectsInvalidManualEndpoint() {
        val core = JmxCore.create()

        val report = core.endpointController.useManualEndpoint("bad host")

        assertTrue(report is JmxResult.Failure)
        assertTrue((report as JmxResult.Failure).error.message.contains("手动 API 域名无效"))
    }

    @Test
    fun probeRunnerChecksDomainSettingSessionAndChapterTemplate() {
        val ts = 1700566805L
        val imageHost = server.url("/").toString().trimEnd('/')
        domainServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(encryptDomain("""{"Server":["${server.url("/")}"]}"""))
        )
        server.enqueue(encryptedResponse(ts, """{"jm3_version":"2.6.0","img_host":"$imageHost","app_shunts":[{"id":"1","name":"线路 1"}]}"""))
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/html")
                .setBody(chapterHtml(imageHost))
        )
        val core = JmxCore.create(
            JmxCoreConfig(
                keyValueStore = InMemoryKeyValueStore(mapOf("protocol.api.hosts" to "https://old.test")),
                apiClock = fixedClock(ts),
                retryPolicy = DefaultRetryPolicy(maxAttempts = 1),
                domainServerUrls = listOf(domainServer.url("/domains").toString())
            )
        )
        core.sessionManager.installAvsCookie("https://old.test", "secret")

        val report = kotlinx.coroutines.runBlocking {
            core.probeRunner.run(
                JmxCoreProbeScenario(
                    refreshDomains = true,
                    fetchSetting = true,
                    requireSession = true,
                    chapterTemplate = ChapterTemplateProbe(
                        chapterId = "123",
                        shunt = "1",
                        timestampSeconds = ts
                    )
                )
            )
        }

        assertTrue(report.isSuccessful)
        assertEquals(emptyList<JmxDiagnosticIssue>(), report.issues)
        assertTrue(report.domainRefresh.isSuccessful)
        assertEquals("2.6.0", report.setting.valueOrNull()!!.apiVersion)
        assertTrue(report.session.valueOrNull()!!.hasAvs)
        assertEquals(listOf("00001.webp"), report.chapterTemplate.valueOrNull()!!.imageFileNames)
        assertEquals("2.6.0", report.afterHealth.apiVersion)
        assertEquals(2, report.afterHealth.cookieCount)
        assertEquals("/setting", server.takeRequest().path)
        assertTrue(server.takeRequest().path!!.startsWith("/chapter_view_template?id=123&"))
    }

    @Test
    fun probeRunnerReportsMissingRequiredSession() {
        val core = JmxCore.create(
            JmxCoreConfig(
                keyValueStore = InMemoryKeyValueStore(mapOf("protocol.api.hosts" to server.url("/").toString())),
                retryPolicy = DefaultRetryPolicy(maxAttempts = 1),
                domainServerUrls = listOf(domainServer.url("/domains").toString())
            )
        )

        val report = kotlinx.coroutines.runBlocking {
            core.probeRunner.run(
                JmxCoreProbeScenario(
                    refreshDomains = false,
                    fetchSetting = false,
                    requireSession = true,
                    chapterTemplate = null
                )
            )
        }

        assertTrue(!report.isSuccessful)
        assertTrue(report.domainRefresh.isSkipped)
        assertTrue(report.setting.isSkipped)
        assertTrue(report.chapterTemplate.isSkipped)
        assertTrue(report.session.errorOrNull()!!.message.contains("AVS"))
        assertEquals(listOf("session"), report.failedSteps)
        assertEquals(listOf("domain_refresh", "setting", "chapter_template"), report.skippedSteps)
        assertEquals(JmxDiagnosticSeverity.Error, report.issues.first { it.step == "session" }.severity)
    }

    @Test
    fun probeRunnerKeepsChapterTemplateDiagnosticsOnFailure() {
        val ts = 1700566805L
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/html")
                .setBody(
                    """
                    const result = {"images":[]};
                    const config = {"imghost":"https://img.test"};
                    var aid = 300000;
                    """.trimIndent()
                )
        )
        val core = JmxCore.create(
            JmxCoreConfig(
                keyValueStore = InMemoryKeyValueStore(mapOf("protocol.api.hosts" to server.url("/").toString())),
                apiClock = fixedClock(ts),
                retryPolicy = DefaultRetryPolicy(maxAttempts = 1)
            )
        )

        val report = kotlinx.coroutines.runBlocking {
            core.probeRunner.run(
                JmxCoreProbeScenario(
                    refreshDomains = false,
                    fetchSetting = false,
                    chapterTemplate = ChapterTemplateProbe(
                        chapterId = "123",
                        shunt = "1",
                        timestampSeconds = ts
                    )
                )
            )
        }

        assertTrue(!report.isSuccessful)
        val error = report.chapterTemplate.errorOrNull()
        assertTrue(error is JmxError.Schema)
        require(error is JmxError.Schema)
        assertEquals("result.images", error.field)
        assertTrue(error.message.contains("缺失字段：result.images"))
        assertTrue(error.message.contains("config.jmid"))
        assertTrue(error.message.contains("HTML 样本"))
        assertEquals(listOf("chapter_template"), report.failedSteps)
        assertTrue(report.issues.single { it.step == "chapter_template" }.message.contains("HTML 样本"))
    }

    @Test
    fun smokeRunnerExercisesInitLoginAlbumChapterAndImageTransfer() {
        val ts = 1700566805L
        val imageBytes = byteArrayOf(0, 1, 2, 3, 4, 5)
        val imageHost = server.url("/").toString().trimEnd('/')
        domainServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(encryptDomain("""{"Server":["${server.url("/")}"]}"""))
        )
        server.enqueue(encryptedResponse(ts, """{"jm3_version":"2.5.2","img_host":"$imageHost","app_shunts":[{"id":"1","name":"线路 1"}]}"""))
        server.enqueue(encryptedResponse(ts, """{"s":"avs-value","uid":"42"}"""))
        server.enqueue(
            encryptedResponse(
                ts,
                """
                {
                  "id":"321",
                  "name":"album",
                  "description":"detail",
                  "author":["author"],
                  "images":1,
                  "tags":["tag"],
                  "series":[{"id":"123","name":"chapter","sort":"1"}]
                }
                """.trimIndent()
            )
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/html")
                .setBody(chapterHtml(imageHost))
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "image/webp")
                .setBody(Buffer().write(imageBytes))
        )
        val outputStore = InMemoryImageOutputStore()
        val core = JmxCore.create(
            JmxCoreConfig(
                keyValueStore = InMemoryKeyValueStore(mapOf("protocol.api.hosts" to "https://old.test")),
                apiClock = fixedClock(ts),
                retryPolicy = DefaultRetryPolicy(maxAttempts = 1),
                domainServerUrls = listOf(domainServer.url("/domains").toString())
            )
        )

        val report = kotlinx.coroutines.runBlocking {
            core.smokeRunner.run(
                JmxCoreSmokeScenario(
                    username = "user",
                    password = "pass",
                    albumId = "321",
                    chapterId = "123",
                    shunt = "1",
                    imageRowCodec = RowBytesCodec(width = 1, height = imageBytes.size, contentType = "image/webp"),
                    imageOutputStore = outputStore,
                    maxImageBytes = 128
                )
            )
        }

        assertTrue(report.isSuccessful)
        assertEquals(emptyList<JmxDiagnosticIssue>(), report.issues)
        assertTrue(report.initialization.valueOrNull()!!.isFullySuccessful)
        assertEquals("avs-value", report.login.valueOrNull()!!.avs)
        assertEquals("album", report.albumDetail.valueOrNull()!!.name)
        assertEquals(listOf("00001.webp"), report.chapterTemplate.valueOrNull()!!.imageFileNames)
        val transfer = report.imageTransfer.valueOrNull()!!
        assertEquals(1, transfer.totalCount)
        assertEquals(1, transfer.storedCount)
        assertEquals(0, transfer.failedCount)
        val restored = (transfer.restoreResults.single().result as JmxResult.Success).value
        assertTrue(restored.restored)
        assertArrayEquals(
            ImagePipeline().restoreRows(
                source = imageBytes,
                imageHeight = imageBytes.size,
                bytesPerRow = 1,
                segmentCount = restored.plan.segmentCount
            ),
            restored.bytes
        )
        val record = outputStore.records().single()
        assertEquals("00001", record.key.filename)
        assertTrue(record.restored)
        assertArrayEquals(restored.bytes, outputStore.bytes(record.key))
        assertEquals("00001.webp", record.key.displayFilename)
        assertEquals("webp", record.key.extension)
        assertEquals("2.5.2", report.health.apiVersion)
        assertEquals(1, report.health.cookieCount)
        assertEquals("/setting", server.takeRequest().path)
        assertEquals("username=user&password=pass", server.takeRequest().body.readUtf8())
        assertEquals("/album?id=321", server.takeRequest().path)
        assertTrue(server.takeRequest().path!!.startsWith("/chapter_view_template?id=123&"))
        assertEquals("/media/photos/123/00001.webp", server.takeRequest().path)
    }

    @Test
    fun smokeRunnerReportsPartialInitializationIssues() {
        val ts = 1700566805L
        domainServer.enqueue(MockResponse().setResponseCode(503).setBody("down"))
        server.enqueue(encryptedResponse(ts, """{"jm3_version":"2.7.0","img_host":"https://img.test","app_shunts":[]}"""))
        server.enqueue(encryptedResponse(ts, """{"s":"avs-value","uid":"42"}"""))
        server.enqueue(encryptedResponse(ts, """{"id":"321","name":"album","author":["author"],"images":1}"""))
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/html")
                .setBody(chapterHtml(server.url("/").toString().trimEnd('/')))
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "image/webp")
                .setBody(Buffer().write(byteArrayOf(0, 1, 2, 3, 4, 5)))
        )
        val core = JmxCore.create(
            JmxCoreConfig(
                keyValueStore = InMemoryKeyValueStore(mapOf("protocol.api.hosts" to server.url("/").toString())),
                apiClock = fixedClock(ts),
                retryPolicy = DefaultRetryPolicy(maxAttempts = 1),
                domainServerUrls = listOf(domainServer.url("/domains").toString())
            )
        )

        val report = kotlinx.coroutines.runBlocking {
            core.smokeRunner.run(
                JmxCoreSmokeScenario(
                    username = "user",
                    password = "pass",
                    albumId = "321",
                    chapterId = "123",
                    shunt = "1",
                    imageRowCodec = RowBytesCodec(width = 1, height = 6, contentType = "image/webp"),
                    imageOutputStore = InMemoryImageOutputStore()
                )
            )
        }

        assertTrue(!report.isSuccessful)
        assertEquals(listOf("initialize.domain_refresh"), report.failedSteps)
        assertEquals(JmxDiagnosticSeverity.Warning, report.issues.single().severity)
        assertTrue(report.issues.single().message.contains("域名服务器"))
    }

    private fun encryptedResponse(ts: Long, dataJson: String): MockResponse {
        val encrypted = AesEcbPkcs7.encryptStringToBase64(
            dataJson,
            JmxHash.md5Hex("$ts${JmxProtocolConstants.DataSecret}")
        )
        return MockResponse().setResponseCode(200).setBody("""{"code":200,"data":"$encrypted"}""")
    }

    private fun encryptDomain(json: String): String {
        return AesEcbPkcs7.encryptStringToBase64(
            json,
            JmxHash.md5Hex(JmxProtocolConstants.DomainServerSecret)
        )
    }

    private fun fixedClock(ts: Long): ApiClock {
        return object : ApiClock {
            override fun nowSeconds(): Long = ts
        }
    }

    private fun chapterHtml(imageHost: String): String {
        return """
            <script>
            const result = {"images":["00001.webp"]};
            const config = {"imghost":"$imageHost","jmid":"123","cache":""};
            var aid = 300000;
            var scramble_id = 1;
            var speed = "0";
            </script>
        """.trimIndent()
    }

    private class RowBytesCodec(
        private val width: Int,
        private val height: Int,
        private val contentType: String?
    ) : ImageRowCodec {
        override fun decode(bytes: ByteArray, contentType: String?): JmxResult<DecodedImageRows> {
            return JmxResult.Success(
                DecodedImageRows(
                    width = width,
                    height = height,
                    bytesPerRow = width,
                    rows = bytes
                )
            )
        }

        override fun encode(image: DecodedImageRows, sourceContentType: String?): JmxResult<RestoredImageBytes> {
            return JmxResult.Success(RestoredImageBytes(image.rows, contentType))
        }
    }
}

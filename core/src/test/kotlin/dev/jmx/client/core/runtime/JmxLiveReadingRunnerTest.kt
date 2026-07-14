package dev.jmx.client.core.runtime

import dev.jmx.client.core.cache.InMemoryKeyValueStore
import dev.jmx.client.core.crypto.AesEcbPkcs7
import dev.jmx.client.core.crypto.JmxHash
import dev.jmx.client.core.network.DefaultRetryPolicy
import dev.jmx.client.core.protocol.ApiClock
import dev.jmx.client.core.protocol.JmxProtocolConstants
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files

class JmxLiveReadingRunnerTest {
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
    fun readingRunnerDownloadsAndStoresImagesWithDefaultHeaders() {
        val ts = 1700566805L
        val imageHost = server.url("/").toString().trimEnd('/')
        val imageBytes = byteArrayOf(10, 20, 30, 40)

        server.enqueue(encrypted(ts, """{"jm3_version":"2.0.27","img_host":"$imageHost","app_shunts":[{"id":"1","title":"1"}]}"""))

        server.enqueue(encrypted(ts, """{"id":"438516","name":"demo","author":["a"],"images":2}"""))

        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/html")
                .setBody(
                    """
                    <script>
                    const result = {"images":["00001.webp","00002.webp"]};
                    const config = {"imghost":"$imageHost","jmid":"438516","cache":""};
                    var aid = 100;
                    var scramble_id = 220980;
                    var speed = "0";
                    </script>
                    """.trimIndent()
                )
        )

        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "image/webp")
                .setBody(Buffer().write(imageBytes))
        )

        val out = Files.createTempDirectory("jmx-read-test")
        val core = JmxCore.create(
            JmxCoreConfig(
                keyValueStore = InMemoryKeyValueStore(
                    mapOf("protocol.api.hosts" to server.url("/").toString())
                ),
                apiClock = fixedClock(ts),
                retryPolicy = DefaultRetryPolicy(maxAttempts = 1),
                domainServerUrls = emptyList()
            )
        )

        val report = kotlinx.coroutines.runBlocking {
            core.readingRunner.run(
                JmxLiveReadingScenario(
                    albumId = "438516",
                    chapterId = "438516",
                    maxImages = 1,
                    initialize = true,
                    outputDirectory = out,
                    imageRowCodec = PassthroughBytesCodec()
                )
            )
        }

        assertTrue(report.acceptance.meetsMinimum)
        assertEquals(1, report.imageTransfer.valueOrNull()!!.storedCount)

        val settingReq = server.takeRequest()
        val albumReq = server.takeRequest()
        val templateReq = server.takeRequest()
        val imageReq = server.takeRequest()
        assertEquals("/setting", settingReq.path)
        assertTrue(albumReq.path!!.startsWith("/album"))
        assertTrue(templateReq.path!!.startsWith("/chapter_view_template"))
        assertTrue(imageReq.path!!.contains("/media/photos/"))
        assertEquals("com.JMComic3.app", imageReq.getHeader("X-Requested-With"))
        assertTrue(Files.list(out).use { it.findAny().isPresent })
    }

    private fun encrypted(ts: Long, json: String): MockResponse {
        val body = AesEcbPkcs7.encryptStringToBase64(
            plain = json,
            key = JmxHash.md5Hex("$ts${JmxProtocolConstants.DataSecret}")
        )
        return MockResponse().setResponseCode(200).setBody("""{"code":200,"data":"$body"}""")
    }

    private fun fixedClock(ts: Long): ApiClock = object : ApiClock {
        override fun nowSeconds(): Long = ts
    }

    private class PassthroughBytesCodec : dev.jmx.client.core.image.ImageRowCodec {
        override fun decode(
            bytes: ByteArray,
            contentType: String?
        ): dev.jmx.client.core.result.JmxResult<dev.jmx.client.core.image.DecodedImageRows> {
            return dev.jmx.client.core.result.JmxResult.Success(
                dev.jmx.client.core.image.DecodedImageRows(
                    width = 1,
                    height = bytes.size,
                    bytesPerRow = 1,
                    rows = bytes
                )
            )
        }

        override fun encode(
            image: dev.jmx.client.core.image.DecodedImageRows,
            sourceContentType: String?
        ): dev.jmx.client.core.result.JmxResult<dev.jmx.client.core.image.RestoredImageBytes> {
            return dev.jmx.client.core.result.JmxResult.Success(
                dev.jmx.client.core.image.RestoredImageBytes(image.rows, sourceContentType)
            )
        }
    }
}

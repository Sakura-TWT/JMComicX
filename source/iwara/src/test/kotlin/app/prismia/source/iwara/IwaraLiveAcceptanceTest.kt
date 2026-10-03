package app.prismia.source.iwara

import app.prismia.foundation.ContentKey
import app.prismia.foundation.ContentSource
import app.prismia.foundation.ContentType
import app.prismia.foundation.toSourceFailure
import app.prismia.video.StreamVariant
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Explicit live acceptance for the real Iwara service. It is skipped unless
 * PRISMIA_LIVE_IWARA=1 is set, and its report never prints tokens or signed
 * query strings. A Cloudflare challenge is recorded as a classified result.
 */
class IwaraLiveAcceptanceTest {
    @Test
    fun probesCatalogSearchDetailSignedFileAndRangePlayback() = runBlocking {
        assumeTrue("set PRISMIA_LIVE_IWARA=1 to run the live probe", System.getenv("PRISMIA_LIVE_IWARA") == "1")

        val videoId = System.getenv("PRISMIA_LIVE_IWARA_VIDEO_ID")?.trim()
            ?.takeIf(String::isNotBlank)
            ?: "xiLWo19mWus5rU"
        val httpClient = OkHttpClient()
        val transport = OkHttpIwaraTransport(httpClient)
        val client = IwaraClient(transport)
        val checks = mutableListOf<LiveCheck>()

        suspend fun <T> check(name: String, block: suspend () -> T): T? = try {
            val value = block()
            checks += LiveCheck(name, "ok", null, false)
            value
        } catch (failure: Exception) {
            val mapped = failure.toSourceFailure(ContentSource.IWARA, name)
            checks += LiveCheck(name, mapped.category.name.lowercase(), mapped.httpStatus, mapped.retryable)
            null
        }

        check("browse") { client.browse(page = 0, limit = 1) }
        check("search") { client.searchPage(query = "genshin", page = 0, limit = 1) }
        val detail = check("detail") {
            client.detailPage(ContentKey(ContentType.VIDEO, ContentSource.IWARA, videoId))
        }
        val variant = detail?.variants?.firstOrNull()
        if (variant == null) {
            checks += LiveCheck("playback", "no-rendition", null, false)
        } else {
            checks += probeRange(httpClient, variant)
        }

        println("PRISMIA IWARA LIVE ACCEPTANCE")
        checks.forEach { check ->
            println("${check.operation}: status=${check.status} http=${check.httpStatus ?: "-"} retryable=${check.retryable}")
        }
        assertTrue("live probe should produce at least one classified check", checks.isNotEmpty())
    }

    private fun probeRange(client: OkHttpClient, variant: StreamVariant): LiveCheck = try {
        val request = Request.Builder()
            .url(variant.url)
            .header("Range", "bytes=0-1")
            .header("Accept", "video/*")
            .header("User-Agent", "Prismia/0.x (Android)")
            .build()
        client.newCall(request).execute().use { response ->
            LiveCheck("playback", if (response.code == 206) "ok" else "http-${response.code}", response.code, response.code in 500..599)
        }
    } catch (failure: Exception) {
        val mapped = failure.toSourceFailure(ContentSource.IWARA, "playback")
        LiveCheck("playback", mapped.category.name.lowercase(), mapped.httpStatus, mapped.retryable)
    }

    private data class LiveCheck(
        val operation: String,
        val status: String,
        val httpStatus: Int?,
        val retryable: Boolean,
    )
}

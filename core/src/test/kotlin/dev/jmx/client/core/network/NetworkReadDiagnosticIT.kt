package dev.jmx.client.core.network

import com.google.gson.GsonBuilder
import dev.jmx.client.core.result.JmxResult
import dev.jmx.client.core.runtime.JmxCore
import dev.jmx.client.core.runtime.JmxCoreConfig
import dev.jmx.client.core.image.ImageUrl
import kotlinx.coroutines.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Explicitly gated anonymous READS only. Never run the existing authenticated/live write suites. */
class NetworkReadDiagnosticIT {
    @Test fun anonymousBusinessReadTimingAndActualWeekTypes() = runBlocking {
        val phase = System.getenv("JMX_NETWORK_DIAG_PHASE")
        assumeTrue("Explicit R3 anonymous read authorization required", phase in listOf("baseline", "post"))
        val core = JmxCore.create(JmxCoreConfig(contentLanguageProvider = { "CN" }))
        val output = linkedMapOf<String, Any?>("phase" to phase, "anonymous" to true, "businessWrites" to false)
        val timings = mutableListOf<Map<String, Any?>>()
        suspend fun <T> measure(route: String, block: suspend () -> JmxResult<T>): T? {
            val start = System.nanoTime()
            val result = withTimeoutOrNull(25_000) { block() }
            timings.add(mapOf("route" to route, "millis" to (System.nanoTime() - start) / 1_000_000,
                "success" to (result is JmxResult.Success), "failureKind" to (result as? JmxResult.Failure)?.error?.javaClass?.simpleName))
            return (result as? JmxResult.Success)?.value
        }
        val probes = core.endpointProber.probeAll()
        output["settingProbes"] = probes.map { mapOf("host" to it.url.normalizedBaseUrlOrNull()?.host, "success" to it.success, "millis" to it.latencyMillis) }
        val fastest = probes.filter { it.success }.minByOrNull { it.latencyMillis }
        if (fastest != null) {
            core.endpointManager.useManualEndpoint(fastest.url)
            output["selectedHost"] = fastest.url.normalizedBaseUrlOrNull()?.host
            measure("/setting") { core.settingApi.fetchSetting() }
            val sections = measure("/promote") { core.libraryApi.promotedSections() }
            output["homeSections"] = sections?.size
            val week = measure("/week") { core.libraryApi.week(forceRefresh = true) }
            output["weekTypes"] = week?.types?.map { mapOf("id" to it.id, "title" to it.title) }
            output["weekIssues"] = week?.categories?.size
            if (week != null && week.categories.isNotEmpty() && week.types.isNotEmpty()) {
                val page = measure("/week/filter") { core.libraryApi.weekFilter(1, week.categories.first().id, week.types.first().id, forceRefresh = true) }
                output["weeklyPageCount"] = page?.content?.size
            }
            val albumId = sections?.firstNotNullOfOrNull { section -> section.content.firstOrNull()?.id }
            if (albumId != null) {
                // IDs/titles and bodies are deliberately not printed or persisted.
                val detail = measure("/album") { core.albumApi.detailFull(albumId) }
                val comments = measure("/forum") { core.interactionApi.albumComments(albumId, 1) }
                output["commentCount"] = comments?.comments?.size
                val chapterId = detail?.series?.firstOrNull()?.id ?: detail?.id
                if (chapterId != null) {
                    val photo = measure("/chapter") { core.chapterApi.detail(chapterId) }
                    output["chapterPages"] = photo?.pageArr?.size
                    val image = photo?.pageArr?.firstOrNull()
                    if (image != null) {
                        val host = photo.imageDomain ?: core.imageHostRegistry.current()
                        val url = ImageUrl.ofFileName(host, photo.id, image)
                        val start = System.nanoTime()
                        val imageResult = runCatching {
                            core.imageHttpClient.newCall(okhttp3.Request.Builder().url(url).build()).execute().use { response ->
                                if (!response.isSuccessful) false else {
                                    val buffer = okio.Buffer()
                                    response.body.source().read(buffer, 65_536)
                                    buffer.clear()
                                    true
                                }
                            }
                        }.getOrDefault(false)
                        timings.add(mapOf("route" to "image-prefix", "millis" to (System.nanoTime() - start) / 1_000_000, "success" to imageResult))
                    }
                }
            }
        }
        output["timings"] = timings
        output["stageSummaries"] = core.requestMetricsRecorder.stageSummaries()
        output["usableEndpoint"] = fastest != null
        val dir = File("../app/build/qa-v2-network-r3").apply { mkdirs() }
        val file = File(dir, "anonymous-$phase-${System.currentTimeMillis()}.json")
        file.writeText(GsonBuilder().setPrettyPrinting().create().toJson(output), Charsets.UTF_8)
        println("R3 anonymous diagnostic: endpointUsable=${fastest != null}; calls=${timings.size}; success=${timings.count { it["success"] == true }}; report=${file.name}")
        // Transport availability is evidence, not a flaky unit assertion or fabricated acceptance.
    }
}

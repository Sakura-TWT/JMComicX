package dev.jmx.client.core.runtime

import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class JmxLiveConnectivityIT {
    @Test
    fun liveConnectivityAgainstRealServers() {
        assumeTrue(
            "Set -Djmx.live=true or JMX_LIVE=1 to run real connectivity checks",
            isLiveEnabled()
        )

        val core = JmxCore.create()
        val report = kotlinx.coroutines.runBlocking {
            core.connectivityRunner.run(JmxLiveConnectivityScenario())
        }

        val markdown = JmxLiveConnectivityMarkdownRenderer().render(report)
        val outDir = File("build/live-reports").apply { mkdirs() }
        val outFile = File(outDir, "connectivity.md")
        outFile.writeText(markdown, Charsets.UTF_8)

        println("===== LIVE CONNECTIVITY SUMMARY =====")
        println("meetsMinimum=${report.acceptance.meetsMinimum}")
        println("gates=${report.acceptance.passedCount}/${report.acceptance.totalCount}")
        println("usableEndpoint=${report.acceptance.hasUsableEndpoint}")
        println("settingOk=${report.acceptance.settingOk}")
        println("albumOk=${report.acceptance.albumOk}")
        println("searchOk=${report.acceptance.searchOk}")
        println("chapterTemplateOk=${report.acceptance.chapterTemplateOk}")
        println("apiVersion=${report.afterHealth.apiVersion}")
        println("endpoints=${report.afterHealth.endpoints.size}")
        println("domainRefresh=${statusOf(report.domainRefresh)}")
        println("endpointProbe=${statusOf(report.endpointProbe)}")
        report.endpointProbe.valueOrNull()?.results?.forEach { item ->
            println(
                "  probe ${item.url} ok=${item.success} http=${item.statusCode} " +
                    "latency=${item.latencyMillis}ms err=${item.error?.message ?: "-"}"
            )
        }
        println("setting=${statusOf(report.setting)} detail=${report.setting.valueOrNull()?.let {
            "ver=${it.apiVersion} host=${it.imageHost} shunts=${it.shunts.size}"
        } ?: report.setting.errorOrNull()?.message}")
        println("album=${statusOf(report.albumDetail)} detail=${report.albumDetail.valueOrNull()?.let {
            "id=${it.id} name=${it.name} series=${it.series.size}"
        } ?: report.albumDetail.errorOrNull()?.message}")
        println("search=${statusOf(report.search)} detail=${report.search.valueOrNull()?.let {
            "total=${it.total} content=${it.content.size} redirect=${it.redirectAlbumId}"
        } ?: report.search.errorOrNull()?.message}")
        println("chapter=${statusOf(report.chapterTemplate)} detail=${report.chapterTemplate.valueOrNull()?.let {
            "id=${it.chapterId} images=${it.imageFileNames.size} scramble=${it.scrambleId}"
        } ?: report.chapterTemplate.errorOrNull()?.message}")
        println("issues=${report.issues.size}")
        report.issues.take(12).forEach { issue ->
            println("  [${issue.severity}] ${issue.step}: ${issue.message}")
        }
        println("reportFile=${outFile.absolutePath}")
        println("=====================================")

        assertNotNull(report.acceptance)
        assertNotNull(markdown)
    }

    private fun isLiveEnabled(): Boolean {
        val prop = System.getProperty("jmx.live")
        val env = System.getenv("JMX_LIVE")
        return prop.equals("true", ignoreCase = true) ||
            prop == "1" ||
            env.equals("1", ignoreCase = true) ||
            env.equals("true", ignoreCase = true)
    }

    private fun statusOf(step: JmxLiveConnectivityStep<*>): String {
        return when {
            step.isSkipped -> "SKIP"
            step.isSuccessful -> "OK"
            else -> "FAIL"
        }
    }
}

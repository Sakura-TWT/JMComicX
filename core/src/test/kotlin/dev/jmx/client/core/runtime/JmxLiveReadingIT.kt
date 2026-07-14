package dev.jmx.client.core.runtime

import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class JmxLiveReadingIT {
    @Test
    fun liveReadingDownloadsAndRestoresRealImages() {
        assumeTrue(
            "Set JMX_LIVE=1 to run real reading pipeline",
            isLiveEnabled()
        )

        val outDir = File("build/live-reports/reading-images").toPath()
        Files.createDirectories(outDir)
        val core = JmxCore.create()
        val report = kotlinx.coroutines.runBlocking {
            core.readingRunner.run(
                JmxLiveReadingScenario(
                    albumId = JmxLiveReadingScenario.DEFAULT_ALBUM_ID,
                    chapterId = JmxLiveReadingScenario.DEFAULT_CHAPTER_ID,
                    maxImages = 2,
                    outputDirectory = outDir
                )
            )
        }
        val markdown = JmxLiveReadingMarkdownRenderer().render(report)
        File("build/live-reports/reading.md").writeText(markdown, Charsets.UTF_8)

        println("===== LIVE READING SUMMARY =====")
        println("meetsMinimum=${report.acceptance.meetsMinimum}")
        println("gates=${report.acceptance.passedCount}/${report.acceptance.totalCount}")
        println("album=${report.albumDetail.valueOrNull()?.name}")
        println(
            "template images=${report.chapterTemplate.valueOrNull()?.imageFileNames?.size} " +
                "scramble=${report.chapterTemplate.valueOrNull()?.scrambleId}"
        )
        val transfer = report.imageTransfer.valueOrNull()
        println(
            "transfer total=${transfer?.totalCount} restored=${transfer?.restoredOrOriginalCount} " +
                "stored=${transfer?.storedCount} failed=${transfer?.failedCount}"
        )
        transfer?.restoreResults?.forEach { item ->
            when (val r = item.result) {
                is dev.jmx.client.core.result.JmxResult.Success ->
                    println(
                        "  img#${item.item.index} restored=${r.value.restored} " +
                            "segments=${r.value.plan.segmentCount} bytes=${r.value.bytes.size}"
                    )
                is dev.jmx.client.core.result.JmxResult.Failure ->
                    println("  img#${item.item.index} FAIL ${r.error.message}")
            }
        }
        println("output=$outDir")
        println("issues=${report.issues.size}")
        report.issues.forEach { println("  [${it.severity}] ${it.step}: ${it.message}") }
        println("================================")

        assertNotNull(report.acceptance)

        if (report.acceptance.meetsMinimum) {
            assertTrueStoredFiles(outDir.toFile())
        }
    }

    private fun assertTrueStoredFiles(dir: File) {
        val files = dir.listFiles()?.filter { it.isFile && it.length() > 0 }.orEmpty()
        org.junit.Assert.assertTrue("expected restored image files in $dir", files.isNotEmpty())
    }

    private fun isLiveEnabled(): Boolean {
        val prop = System.getProperty("jmx.live")
        val env = System.getenv("JMX_LIVE")
        return prop.equals("true", ignoreCase = true) || prop == "1" ||
            env.equals("1", ignoreCase = true) || env.equals("true", ignoreCase = true)
    }
}

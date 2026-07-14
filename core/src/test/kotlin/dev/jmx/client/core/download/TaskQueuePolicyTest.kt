package dev.jmx.client.core.download

import dev.jmx.client.core.chapter.ChapterTemplate
import dev.jmx.client.core.image.DecodedImageRows
import dev.jmx.client.core.image.ImageRowCodec
import dev.jmx.client.core.image.RestoredImageBytes
import dev.jmx.client.core.result.JmxResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger

class TaskQueuePolicyTest {
    @Test
    fun respectsMaxConcurrentChapterTasks() = runBlocking {
        val active = AtomicInteger(0)
        val peak = AtomicInteger(0)
        val out1 = Files.createTempDirectory("tq1")
        val out2 = Files.createTempDirectory("tq2")
        val out3 = Files.createTempDirectory("tq3")
        val manager = ChapterDownloadTaskManager(
            templateFetcher = { _, _ ->
                JmxResult.Success(
                    ChapterTemplate(
                        albumId = 1,
                        scrambleId = 220980,
                        speed = "0",
                        imageHost = "https://img.test",
                        chapterId = "1",
                        cacheSuffix = "",
                        imageFileNames = listOf("00001.gif")
                    )
                )
            },
            downloader = object : Downloader {
                override suspend fun download(
                    request: DownloadRequest,
                    sink: ByteSink
                ): JmxResult<DownloadResult> {
                    val now = active.incrementAndGet()
                    peak.updateAndGet { maxOf(it, now) }
                    delay(120)
                    active.decrementAndGet()
                    sink.write(byteArrayOf(1))
                    return JmxResult.Success(
                        DownloadResult(request.url, 200, "image/gif", 1, 1)
                    )
                }
            },
            imageRowCodec = Passthrough(),
            executionPolicy = TaskExecutionPolicy(maxConcurrentTasks = 1, minIntervalBetweenTaskStartsMillis = 0),
            downloadConcurrency = 1
        )
        val a = manager.enqueue(ChapterDownloadTaskSpec("1", "1", outputDirectory = out1))
        val b = manager.enqueue(ChapterDownloadTaskSpec("1", "2", outputDirectory = out2))
        val c = manager.enqueue(ChapterDownloadTaskSpec("1", "3", outputDirectory = out3))
        manager.start(a.id)
        manager.start(b.id)
        manager.start(c.id)
        waitAll(manager, listOf(a.id, b.id, c.id))
        assertEquals(1, manager.peakConcurrentTasks)
        assertTrue(peak.get() <= 1)
    }

    @Test
    fun enforcesMinIntervalBetweenTaskStarts() = runBlocking {
        val starts = mutableListOf<Long>()
        val out1 = Files.createTempDirectory("tr1")
        val out2 = Files.createTempDirectory("tr2")
        val manager = ChapterDownloadTaskManager(
            templateFetcher = { _, _ ->
                JmxResult.Success(
                    ChapterTemplate(
                        albumId = 1,
                        scrambleId = 220980,
                        speed = "0",
                        imageHost = "https://img.test",
                        chapterId = "1",
                        cacheSuffix = "",
                        imageFileNames = listOf("00001.gif")
                    )
                )
            },
            downloader = object : Downloader {
                override suspend fun download(
                    request: DownloadRequest,
                    sink: ByteSink
                ): JmxResult<DownloadResult> {
                    synchronized(starts) { starts += System.currentTimeMillis() }
                    sink.write(byteArrayOf(1))
                    return JmxResult.Success(
                        DownloadResult(request.url, 200, "image/gif", 1, 1)
                    )
                }
            },
            imageRowCodec = Passthrough(),
            executionPolicy = TaskExecutionPolicy(
                maxConcurrentTasks = 2,
                minIntervalBetweenTaskStartsMillis = 80L
            )
        )
        val a = manager.enqueue(ChapterDownloadTaskSpec("1", "1", outputDirectory = out1))
        val b = manager.enqueue(ChapterDownloadTaskSpec("1", "2", outputDirectory = out2))
        manager.start(a.id)
        manager.start(b.id)
        waitAll(manager, listOf(a.id, b.id))
        assertEquals(2, starts.size)
        val gap = starts.maxOrNull()!! - starts.minOrNull()!!
        assertTrue("expected spacing >= 80ms, gap=$gap", gap >= 70L)
    }

    private suspend fun waitAll(manager: ChapterDownloadTaskManager, ids: List<String>) {
        var spins = 0
        while (spins < 300) {
            val states = ids.map { manager.get(it)!!.state }
            if (states.all {
                    it == ChapterDownloadTaskState.Completed ||
                        it == ChapterDownloadTaskState.Failed ||
                        it == ChapterDownloadTaskState.Cancelled
                }
            ) {
                return
            }
            delay(20)
            spins++
        }
    }

    private class Passthrough : ImageRowCodec {
        override fun decode(bytes: ByteArray, contentType: String?): JmxResult<DecodedImageRows> {
            return JmxResult.Success(DecodedImageRows(1, bytes.size, 1, bytes))
        }

        override fun encode(image: DecodedImageRows, sourceContentType: String?): JmxResult<RestoredImageBytes> {
            return JmxResult.Success(RestoredImageBytes(image.rows, sourceContentType))
        }
    }
}

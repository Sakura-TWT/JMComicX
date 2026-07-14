package dev.jmx.client.core.download

import dev.jmx.client.core.chapter.ChapterTemplate
import dev.jmx.client.core.image.DecodedImageRows
import dev.jmx.client.core.image.ImageRowCodec
import dev.jmx.client.core.image.RestoredImageBytes
import dev.jmx.client.core.result.JmxError
import dev.jmx.client.core.result.JmxResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class ChapterDownloadTaskManagerTest {
    @Test
    fun enqueueStartCompletesTaskAndWritesFiles() = runBlocking {
        val out = Files.createTempDirectory("jmx-task")
        val manager = ChapterDownloadTaskManager(
            templateFetcher = { _, _ ->
                JmxResult.Success(
                    ChapterTemplate(
                        albumId = 100,
                        scrambleId = 220980,
                        speed = "0",
                        imageHost = "https://img.test",
                        chapterId = "100",
                        cacheSuffix = "",
                        imageFileNames = listOf("00001.gif")
                    )
                )
            },
            downloader = FakeDownloader { request, sink ->
                sink.write(byteArrayOf(1, 2, 3))
                JmxResult.Success(
                    DownloadResult(
                        url = request.url,
                        statusCode = 200,
                        contentType = "image/gif",
                        contentLength = 3,
                        bytesWritten = 3
                    )
                )
            },
            imageRowCodec = PassthroughCodec(),
            downloadConcurrency = 2
        )

        val enqueued = manager.enqueue(
            ChapterDownloadTaskSpec(
                albumId = "100",
                chapterId = "100",
                outputDirectory = out
            )
        )
        assertEquals(ChapterDownloadTaskState.Pending, enqueued.state)

        val started = manager.start(enqueued.id)
        assertTrue(started is JmxResult.Success)

        var snapshot = manager.get(enqueued.id)!!
        var spins = 0
        while (
            snapshot.state == ChapterDownloadTaskState.Pending ||
            snapshot.state == ChapterDownloadTaskState.Running
        ) {
            delay(20)
            snapshot = manager.get(enqueued.id)!!
            spins++
            if (spins > 200) break
        }

        assertEquals(ChapterDownloadTaskState.Completed, snapshot.state)
        assertEquals(1, snapshot.progress?.total)
        assertEquals(1, snapshot.progress?.completed)
        assertTrue(Files.newDirectoryStream(out).use { it.iterator().hasNext() })
    }

    @Test
    fun templateFailureMarksTaskFailed() = runBlocking {
        val out = Files.createTempDirectory("jmx-task-fail")
        val manager = ChapterDownloadTaskManager(
            templateFetcher = { _, _ ->
                JmxResult.Failure(JmxError.Network("template down"))
            },
            downloader = FakeDownloader { _, _ ->
                JmxResult.Failure(JmxError.Network("unused"))
            }
        )
        val task = manager.enqueue(
            ChapterDownloadTaskSpec(albumId = "1", chapterId = "1", outputDirectory = out)
        )
        manager.start(task.id)
        var snapshot = manager.get(task.id)!!
        var spins = 0
        while (snapshot.state == ChapterDownloadTaskState.Running || snapshot.state == ChapterDownloadTaskState.Pending) {
            delay(20)
            snapshot = manager.get(task.id)!!
            if (++spins > 100) break
        }
        assertEquals(ChapterDownloadTaskState.Failed, snapshot.state)
        assertTrue(snapshot.error is JmxError.Network)
    }

    private class FakeDownloader(
        private val handler: suspend (DownloadRequest, ByteSink) -> JmxResult<DownloadResult>
    ) : Downloader {
        override suspend fun download(request: DownloadRequest, sink: ByteSink): JmxResult<DownloadResult> {
            return handler(request, sink)
        }
    }

    private class PassthroughCodec : ImageRowCodec {
        override fun decode(bytes: ByteArray, contentType: String?): JmxResult<DecodedImageRows> {
            return JmxResult.Success(DecodedImageRows(1, bytes.size, 1, bytes))
        }

        override fun encode(image: DecodedImageRows, sourceContentType: String?): JmxResult<RestoredImageBytes> {
            return JmxResult.Success(RestoredImageBytes(image.rows, sourceContentType))
        }
    }
}

package dev.jmx.client.core.download

import dev.jmx.client.core.chapter.ChapterTemplate
import dev.jmx.client.core.image.DecodedImageRows
import dev.jmx.client.core.image.ImageOutputKey
import dev.jmx.client.core.image.ImageOutputRecord
import dev.jmx.client.core.image.ImageOutputStore
import dev.jmx.client.core.image.ImageRestoreResult
import dev.jmx.client.core.image.ImageRowCodec
import dev.jmx.client.core.image.RestoredImageBytes
import dev.jmx.client.core.result.JmxResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class ChapterDownloadTaskPersistTest {
    @Test
    fun saveReloadAndResumeSkipsCompletedImages() = runBlocking {
        val out = Files.createTempDirectory("jmx-persist-out")
        val storeFile = Files.createTempFile("jmx-tasks-", ".json")
        val store = FileChapterDownloadTaskStore(storeFile)
        var downloadCalls = 0

        fun manager(): ChapterDownloadTaskManager {
            return ChapterDownloadTaskManager(
                templateFetcher = { _, _ ->
                    JmxResult.Success(
                        ChapterTemplate(
                            albumId = 100,
                            scrambleId = 220980,
                            speed = "0",
                            imageHost = "https://img.test",
                            chapterId = "100",
                            cacheSuffix = "",
                            imageFileNames = listOf("00001.gif", "00002.gif")
                        )
                    )
                },
                downloader = object : Downloader {
                    override suspend fun download(
                        request: DownloadRequest,
                        sink: ByteSink
                    ): JmxResult<DownloadResult> {
                        downloadCalls++
                        sink.write(byteArrayOf(9, 9, 9))
                        return JmxResult.Success(
                            DownloadResult(
                                url = request.url,
                                statusCode = 200,
                                contentType = "image/gif",
                                contentLength = 3,
                                bytesWritten = 3
                            )
                        )
                    }
                },
                imageRowCodec = PassthroughCodec(),
                downloadConcurrency = 2,
                taskStore = store
            )
        }

        val first = manager()
        val enqueued = first.enqueue(
            ChapterDownloadTaskSpec(
                albumId = "100",
                chapterId = "100",
                outputDirectory = out,
                maxImages = 2
            )
        )

        val mid = enqueued.copy(
            state = ChapterDownloadTaskState.Failed,
            completedImageFileNames = listOf("00001.gif"),
            progress = ChapterDownloadProgress(2, 1, 1, 3),
            error = dev.jmx.client.core.result.JmxError.Network("partial")
        )
        store.save(mid.toPersisted(mid.completedImageFileNames))

        downloadCalls = 0
        val second = manager()
        val loaded = second.get(enqueued.id)
        requireNotNull(loaded)
        assertEquals(ChapterDownloadTaskState.Failed, loaded.state)
        assertEquals(listOf("00001.gif"), loaded.completedImageFileNames)

        val start = second.start(enqueued.id)
        assertTrue(start is JmxResult.Success)
        var snapshot = second.get(enqueued.id)!!
        var spins = 0
        while (
            snapshot.state == ChapterDownloadTaskState.Pending ||
            snapshot.state == ChapterDownloadTaskState.Running
        ) {
            delay(25)
            snapshot = second.get(enqueued.id)!!
            if (++spins > 200) break
        }

        assertEquals(ChapterDownloadTaskState.Completed, snapshot.state)

        assertEquals(1, downloadCalls)
        assertTrue(snapshot.completedImageFileNames.contains("00001.gif"))
        assertTrue(snapshot.completedImageFileNames.contains("00002.gif"))

        val third = manager()
        assertEquals(ChapterDownloadTaskState.Completed, third.get(enqueued.id)!!.state)
    }

    @Test
    fun storeFailureDoesNotMarkImageCompletedSoResumeRetries() = runBlocking {
        val out = Files.createTempDirectory("jmx-store-fail")
        val store = InMemoryChapterDownloadTaskStore()
        var downloads = 0
        var storeAttempts = 0

        fun manager(failStore: Boolean): ChapterDownloadTaskManager {
            return ChapterDownloadTaskManager(
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
                downloader = object : Downloader {
                    override suspend fun download(
                        request: DownloadRequest,
                        sink: ByteSink
                    ): JmxResult<DownloadResult> {
                        downloads++
                        sink.write(byteArrayOf(1, 2, 3))
                        return JmxResult.Success(
                            DownloadResult(request.url, 200, "image/gif", 3, 3)
                        )
                    }
                },
                imageRowCodec = PassthroughCodec(),
                taskStore = store,
                outputStoreFactory = {
                    object : ImageOutputStore {
                        override fun put(
                            key: ImageOutputKey,
                            result: ImageRestoreResult
                        ): JmxResult<ImageOutputRecord> {
                            storeAttempts++
                            return if (failStore) {
                                JmxResult.Failure(
                                    dev.jmx.client.core.result.JmxError.Schema(
                                        "disk full",
                                        field = "image.output"
                                    )
                                )
                            } else {
                                JmxResult.Success(
                                    ImageOutputRecord(
                                        key = key,
                                        sourceUrl = result.plan.sourceUrl,
                                        contentType = result.contentType,
                                        byteCount = result.bytes.size,
                                        restored = result.restored
                                    )
                                )
                            }
                        }
                    }
                }
            )
        }

        val first = manager(failStore = true)
        val task = first.enqueue(
            ChapterDownloadTaskSpec(albumId = "100", chapterId = "100", outputDirectory = out)
        )
        first.start(task.id)
        var snap = first.get(task.id)!!
        var spins = 0
        while (snap.state == ChapterDownloadTaskState.Running || snap.state == ChapterDownloadTaskState.Pending) {
            delay(20)
            snap = first.get(task.id)!!
            if (++spins > 200) break
        }
        assertEquals(ChapterDownloadTaskState.Failed, snap.state)
        assertTrue(snap.completedImageFileNames.isEmpty())
        assertEquals(1, downloads)
        assertEquals(1, storeAttempts)

        downloads = 0
        storeAttempts = 0
        val second = manager(failStore = false)

        assertEquals(ChapterDownloadTaskState.Failed, second.get(task.id)!!.state)
        second.start(task.id)
        snap = second.get(task.id)!!
        spins = 0
        while (snap.state == ChapterDownloadTaskState.Running || snap.state == ChapterDownloadTaskState.Pending) {
            delay(20)
            snap = second.get(task.id)!!
            if (++spins > 200) break
        }
        assertEquals(ChapterDownloadTaskState.Completed, snap.state)
        assertEquals(listOf("00001.gif"), snap.completedImageFileNames)
        assertEquals(1, downloads)
        assertEquals(1, storeAttempts)
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

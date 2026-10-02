package dev.jmx.client

import dev.jmx.client.core.api.AlbumChapter
import dev.jmx.client.core.api.AlbumDetail
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files

class OfflinePagePipelineTest {
    @Test fun boundedRollingPrefetchPublishesInOrderWithoutWaitingForWholeWindow() = runBlocking {
        withTimeout(5_000) {
            val started = Channel<Int>(Channel.UNLIMITED)
            val finished = Channel<Int>(Channel.UNLIMITED)
            val gates = List(8) { CompletableDeferred<Unit>() }
            val published = mutableListOf<Int>()
            val cleaned = mutableListOf<Int>()
            val task = launch {
                OfflinePagePipeline(4).run(0, 1_000_000, prepare = { index ->
                    started.send(index)
                    gates[index].await()
                    finished.send(index)
                    index
                }, publish = { published += it }, cleanup = { cleaned += it })
            }
            assertEquals(listOf(0, 1, 2, 3), List(4) { started.receive() })
            gates[2].complete(Unit)
            gates[1].complete(Unit)
            assertEquals(listOf(2, 1), List(2) { finished.receive() })
            yield()
            assertTrue(published.isEmpty())
            assertTrue(started.tryReceive().isFailure) // No fifth page or unbounded ready buffer.
            gates[0].complete(Unit)
            assertEquals(0, finished.receive())
            assertEquals(listOf(4, 5, 6), List(3) { started.receive() })
            assertEquals(listOf(0, 1, 2), published)
            // Page 3 is still blocked, yet page 4 has started: the old awaitAll window could not do this.
            assertTrue(started.tryReceive().isFailure)
            task.cancelAndJoin()
            assertEquals((0..6).toList(), cleaned.sorted())
        }
    }

    @Test fun laterFailureKeepsPrefixAndDurableOutOfOrderPagesForResume() = diskTest { disk ->
        val ready3 = CompletableDeferred<Unit>()
        val network = mutableListOf<Int>()
        val result = runCatching {
            disk.run(prepare = { index ->
                network += index
                if (index == 2) {
                    ready3.await()
                    throw IOException("synthetic exhausted transient failure")
                }
                disk.stage(index).also { if (index == 3) ready3.complete(Unit) }
            })
        }
        assertEquals(2, (result.exceptionOrNull() as OfflinePageFailure).pageIndex)
        disk.reload()
        assertEquals(2, disk.album.downloadedPages)
        assertNotNull(disk.staging.find(3))
        assertFalse(disk.hasPartFiles())
        val resumed = mutableListOf<Int>()
        disk.run(prepare = { index -> resumed += index; disk.stage(index) })
        assertFalse(0 in resumed || 1 in resumed || 3 in resumed)
        assertTrue(2 in resumed)
        disk.reload()
        assertTrue(disk.album.completed)
        assertTrue(disk.album.chapters.single().pages.all { OfflineFiles.valid(disk.root, it) })
    }

    @Test fun cancellationJoinsWritersBeforeCleanupAndPreservesCommittedAndReadyPages() = diskTest { disk ->
        val ready3 = CompletableDeferred<Unit>()
        val committed0 = CompletableDeferred<Unit>()
        val task = launch {
            disk.run(prepare = { index ->
                if (index == 1) {
                    val part = File(disk.staging.target(index).path + ".part")
                    try { awaitCancellation() }
                    finally {
                        withContext(NonCancellable) {
                            yield()
                            part.writeText("late cancelled writer")
                        }
                    }
                }
                disk.stage(index).also { if (index == 3) ready3.complete(Unit) }
            }, published = { if (it == 0) committed0.complete(Unit) })
        }
        committed0.await()
        ready3.await()
        task.cancelAndJoin()
        disk.reload()
        assertEquals(1, disk.album.downloadedPages)
        assertNotNull(disk.staging.find(3))
        assertFalse(disk.hasPartFiles())
        val resumed = mutableListOf<Int>()
        disk.run(prepare = { index -> resumed += index; disk.stage(index) })
        assertFalse(0 in resumed || 3 in resumed)
        assertTrue(disk.album.completed)
    }

    @Test fun metadataFailureAfterRenameRetainsReceiptAndResumesWithoutRedownload() = diskTest { disk ->
        val committed0 = CompletableDeferred<Unit>()
        val result = runCatching { disk.run(
            prepare = { index -> if (index > 0) committed0.await(); disk.stage(index) },
            failPublicationAt = 1,
            published = { if (it == 0) committed0.complete(Unit) },
        ) }
        assertEquals(1, (result.exceptionOrNull() as OfflinePageFailure).pageIndex)
        disk.reload()
        assertEquals(1, disk.album.downloadedPages)
        val retained = requireNotNull(disk.staging.find(1))
        assertEquals(OfflineFiles.resolve(disk.root, retained.page.relativePath), retained.file)
        val resumed = mutableListOf<Int>()
        disk.run(prepare = { index -> resumed += index; disk.stage(index) })
        assertFalse(0 in resumed || 1 in resumed)
        disk.reload()
        assertTrue(disk.album.completed)
        assertFalse(disk.hasPartFiles())
    }

    @Test fun receiptsRequireMatchingSourceDigestAndPositiveLengthAndNeverTrustOrphans() = diskTest { disk ->
        val staged = disk.stage(1)
        assertNotNull(disk.staging.find(1))
        assertNull(OfflinePageStaging(disk.root, "10", "11", "different-source").find(1))
        staged.file.writeText("damage!") // Same length as image-1.
        assertNull(disk.staging.find(1))
        val orphan = disk.staging.target(2)
        orphan.writeText("orphan")
        assertNull(disk.staging.find(2))
        disk.stage(1)
        assertNotNull(disk.staging.find(1))
        File(disk.staging.target(1).path + ".ready.json").writeText("{broken")
        assertNull(disk.staging.find(1))
    }

    @Test fun completeResumeDoesNoWork() = runBlocking {
        OfflinePagePipeline().run<Int>(7, 7, prepare = { error("unexpected prepare") },
            publish = { error("unexpected publish") }, cleanup = { error("unexpected cleanup") })
    }

    private fun diskTest(block: suspend kotlinx.coroutines.CoroutineScope.(Disk) -> Unit) = runBlocking {
        val root = Files.createTempDirectory("jmx-rolling-synthetic").toFile()
        try { withTimeout(5_000) { block(Disk(root)) } } finally { root.deleteRecursively() }
    }

    private class Disk(val root: File) {
        val staging = OfflinePageStaging(root, "10", "11", "synthetic-fingerprint")
        val metadata = File(root, "library.json")
        var album = OfflineAlbum("10", "Title", detail(),
            listOf(OfflineChapter("11", "Chapter", "1", 6, sourceFingerprint = "synthetic-fingerprint")),
            OfflineDownloadStatus.DOWNLOADING)
        init { OfflineFiles.atomicWrite(metadata, OfflineMetadata.encode(listOf(album))) }

        fun stage(index: Int): OfflineStagedPage {
            val part = File(staging.target(index).path + ".part")
            part.parentFile!!.mkdirs()
            FileOutputStream(part).use { it.write("image-$index".toByteArray()); it.fd.sync() }
            return staging.save(index, part, "png")
        }

        suspend fun run(
            prepare: suspend (Int) -> OfflineStagedPage = { stage(it) },
            failPublicationAt: Int? = null,
            published: (Int) -> Unit = {},
        ) {
            OfflinePagePipeline().run(album.downloadedPages, 6, prepare = { staging.find(it) ?: prepare(it) },
                publish = { batch ->
                    batch.forEachIndexed { offset, staged ->
                        assertEquals(album.downloadedPages + offset, staged.page.index)
                        staging.promote(staged)
                        if (staged.page.index == failPublicationAt) throw IOException("synthetic full storage")
                    }
                    val next = album.copy(chapters = listOf(album.chapters.single().let {
                        it.copy(pages = it.pages + batch.map { it.page })
                    }))
                    OfflineFiles.atomicWrite(metadata, OfflineMetadata.encode(listOf(next)))
                    album = next
                    batch.forEach { staging.committed(it.page.index); published(it.page.index) }
                }, cleanup = staging::cleanupIncomplete)
        }

        fun reload() { album = OfflineFiles.recover(root, OfflineMetadata.decode(metadata.readText()).single()) }
        fun hasPartFiles() = root.walkTopDown().any { it.name.endsWith(".part") || it.name.endsWith(".tmp") }
    }

    companion object {
        private fun detail() = AlbumDetail("10", "Title", null, emptyList(), 6, 0, 0, 0,
            emptyList(), emptyList(), emptyList(), null, null, emptyList(),
            listOf(AlbumChapter("11", "Chapter", "1")), "10", null, null, emptyMap())
    }
}

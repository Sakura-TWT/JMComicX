package app.prismia.data

import app.prismia.foundation.ContentKey
import app.prismia.foundation.ContentSource
import app.prismia.foundation.ContentType
import app.prismia.video.VideoWork
import app.prismia.database.InMemoryPrismiaDatabase
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class VideoContentStoreTest {
    @Test
    fun tombstoneRemovesCachedWorkAndWriteClearsTombstone() = runBlocking {
        val store = InMemoryVideoContentStore()
        val work = VideoWork(ContentKey(ContentType.VIDEO, ContentSource.IWARA, "v1"), "Clip")

        store.write(work)
        store.tombstone(work.key, "removed upstream")
        assertNull(store.read(work.key))
        assertTrue(store.isTombstoned(work.key))

        store.write(work)
        assertFalse(store.isTombstoned(work.key))
        assertTrue(store.read(work.key) == work)
    }

    @Test
    fun fileStoreRoundTripsRecordsAndTombstonesAcrossInstances() = runBlocking {
        val directory = Files.createTempDirectory("prismia-video-store").toFile()
        try {
            val file = File(directory, "content.bin")
            val work = work()
            FileVideoContentStore(file).apply {
                write(work)
                tombstone(ContentKey(ContentType.VIDEO, ContentSource.IWARA, "gone"), "removed upstream")
            }

            val reopened = FileVideoContentStore(file)
            assertEquals(work, reopened.read(work.key))
            assertTrue(reopened.isTombstoned(ContentKey(ContentType.VIDEO, ContentSource.IWARA, "gone")))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun fileStoreKeepsRichVideoFields() = runBlocking {
        val directory = Files.createTempDirectory("prismia-video-store-rich").toFile()
        try {
            val work = work().copy(
                author = "artist",
                coverUrl = "https://example.test/cover.jpg",
                durationMs = 123_000L,
                description = "description",
                tags = listOf("a", "b"),
                availability = app.prismia.video.VideoAvailability.PLAYABLE,
                sourceLinks = mapOf(ContentSource.ORENO3D to "o1", ContentSource.IWARA to "w1"),
            )
            val file = File(directory, "content.bin")
            FileVideoContentStore(file).write(work)
            assertEquals(work, FileVideoContentStore(file).read(work.key))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun fileStoreRejectsCorruptSnapshotInsteadOfReturningEmptyData() = runBlocking {
        val directory = Files.createTempDirectory("prismia-video-store-corrupt").toFile()
        try {
            val file = File(directory, "content.bin")
            file.writeBytes(byteArrayOf(0x01, 0x02, 0x03))

            assertThrows(VideoContentStoreException::class.java) {
                runBlocking { FileVideoContentStore(file).read(ContentKey(ContentType.VIDEO, ContentSource.IWARA, "w1")) }
            }
            Unit
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun legacySnapshotMigratesIntoDatabaseBoundary() = runBlocking {
        val source = InMemoryVideoContentStore()
        val work = work()
        val gone = ContentKey(ContentType.VIDEO, ContentSource.IWARA, "gone")
        source.write(work)
        source.tombstone(gone, "removed upstream")

        val target = InMemoryPrismiaDatabase()
        LegacyVideoContentStoreMigrator(source).migrateInto(target)

        target.transaction {
            assertEquals(work, videos.read(work.key))
            assertTrue(videos.isTombstoned(gone))
        }
    }

    private fun work() = VideoWork(
        key = ContentKey(ContentType.VIDEO, ContentSource.IWARA, "w1"),
        title = "title",
    )
}

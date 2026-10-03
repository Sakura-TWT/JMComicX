package app.prismia.data

import app.prismia.database.InMemoryPrismiaDatabase
import app.prismia.database.PlaybackProgress
import app.prismia.foundation.ContentKey
import app.prismia.foundation.ContentSource
import app.prismia.foundation.ContentType
import app.prismia.video.VideoWork
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoLibraryStoreTest {
    @Test
    fun lifecycleWritesStayCoherentAndTombstoneCleansDerivedState() = runBlocking {
        val key = ContentKey(ContentType.VIDEO, ContentSource.IWARA, "v1")
        val library = VideoLibraryStore(InMemoryPrismiaDatabase())
        library.upsert(VideoWork(key, "Clip"))
        library.setFavorite(key, true)
        library.saveProgress(PlaybackProgress(key, 50, 100, 10))

        val before = library.snapshot()
        assertEquals(setOf(key), before.favorites)
        assertEquals(50L, before.progress.getValue(key).positionMs)

        library.tombstone(key, "removed")
        val after = library.snapshot()
        assertTrue(after.records.isEmpty())
        assertEquals("removed", after.tombstones[key])
        assertFalse(key in after.favorites)
        assertFalse(key in after.progress)
    }
}

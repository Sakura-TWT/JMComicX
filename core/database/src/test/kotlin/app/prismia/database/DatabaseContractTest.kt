package app.prismia.database

import app.prismia.foundation.ContentKey
import app.prismia.foundation.ContentSource
import app.prismia.foundation.ContentType
import app.prismia.video.VideoWork
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DatabaseContractTest {
    private val key = ContentKey(ContentType.VIDEO, ContentSource.IWARA, "v1")

    @Test
    fun transactionCommitsVideoProgressAndFavoriteTogether() = runBlocking {
        val database = InMemoryPrismiaDatabase()
        val work = VideoWork(key, "Clip")

        database.transaction {
            videos.write(work)
            playback.write(PlaybackProgress(key, positionMs = 42, updatedAtEpochSeconds = 10))
            favorites.set(key, true)
        }

        database.transaction {
            assertEquals(work, videos.read(key))
            assertEquals(42L, playback.read(key)?.positionMs)
            assertTrue(favorites.contains(key))
        }
    }

    @Test
    fun failedTransactionDoesNotPublishPartialState() = runBlocking {
        val database = InMemoryPrismiaDatabase()

        runCatching {
            database.transaction {
                videos.write(VideoWork(key, "Clip"))
                error("synthetic migration failure")
            }
        }

        database.transaction {
            assertEquals(null, videos.read(key))
            assertFalse(favorites.contains(key))
        }
    }

    @Test
    fun migrationPlanRequiresContiguousSingleVersionSteps() {
        val plan = DatabaseMigrationPlan(
            listOf(
                DatabaseMigration(1, 2) {},
                DatabaseMigration(2, 3) {},
            ),
        )

        assertEquals(listOf(1, 2), plan.path(1, 3).map { it.fromVersion })
    }
}

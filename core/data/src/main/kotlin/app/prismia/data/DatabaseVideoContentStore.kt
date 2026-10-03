package app.prismia.data

import app.prismia.database.PrismiaDatabase
import app.prismia.foundation.ContentKey
import app.prismia.video.VideoWork

/**
 * Adapter from the data-layer store API to the platform-neutral database
 * contract. Android uses [app.prismia.database.android.PrismiaSqliteDatabase],
 * while JVM tests can inject [app.prismia.database.InMemoryPrismiaDatabase].
 */
class DatabaseVideoContentStore(
    private val database: PrismiaDatabase,
) : VideoContentStore {
    override suspend fun read(key: ContentKey): VideoWork? = database.transaction {
        videos.read(key)
    }

    override suspend fun write(work: VideoWork) {
        database.transaction {
            videos.write(work)
        }
    }

    override suspend fun tombstone(key: ContentKey, reason: String?) {
        database.transaction {
            videos.tombstone(key, reason)
        }
    }

    override suspend fun isTombstoned(key: ContentKey): Boolean = database.transaction {
        videos.isTombstoned(key)
    }

    override suspend fun snapshot(): VideoContentSnapshot = database.transaction {
        VideoContentSnapshot(
            records = videos.records().associateBy(VideoWork::key),
            tombstones = videos.tombstones(),
        )
    }
}

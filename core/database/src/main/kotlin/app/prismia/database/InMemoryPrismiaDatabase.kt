package app.prismia.database

import app.prismia.foundation.ContentKey
import app.prismia.video.VideoWork
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Deterministic reference implementation used by domain/data tests. */
class InMemoryPrismiaDatabase(
    override val schema: DatabaseSchema = DatabaseSchema(),
) : PrismiaDatabase {
    private val mutex = Mutex()
    private var state = State()

    override suspend fun <T> transaction(block: suspend DatabaseTransaction.() -> T): T = mutex.withLock {
        val working = state.deepCopy()
        val transaction = Transaction(working)
        val result = transaction.block()
        state = working
        result
    }

    private data class State(
        val records: LinkedHashMap<ContentKey, VideoWork> = linkedMapOf(),
        val tombstones: LinkedHashMap<ContentKey, String?> = linkedMapOf(),
        val progress: LinkedHashMap<ContentKey, PlaybackProgress> = linkedMapOf(),
        val favorites: LinkedHashSet<ContentKey> = linkedSetOf(),
    ) {
        fun deepCopy() = State(
            records = LinkedHashMap(records),
            tombstones = LinkedHashMap(tombstones),
            progress = LinkedHashMap(progress),
            favorites = LinkedHashSet(favorites),
        )
    }

    private class Transaction(private val state: State) : DatabaseTransaction {
        override val videos: VideoContentDao = object : VideoContentDao {
            override suspend fun read(key: ContentKey): VideoWork? = state.records[key]

            override suspend fun write(work: VideoWork) {
                state.records[work.key] = work
                state.tombstones.remove(work.key)
            }

            override suspend fun tombstone(key: ContentKey, reason: String?) {
                state.records.remove(key)
                state.tombstones[key] = reason?.takeIf(String::isNotBlank)
            }

            override suspend fun isTombstoned(key: ContentKey): Boolean = key in state.tombstones

            override suspend fun records(): List<VideoWork> = state.records.values.toList()

            override suspend fun tombstones(): Map<ContentKey, String?> = state.tombstones.toMap()
        }

        override val playback: PlaybackProgressDao = object : PlaybackProgressDao {
            override suspend fun read(key: ContentKey): PlaybackProgress? = state.progress[key]

            override suspend fun write(progress: PlaybackProgress) {
                state.progress[progress.key] = progress
            }

            override suspend fun remove(key: ContentKey) {
                state.progress.remove(key)
            }

            override suspend fun records(): List<PlaybackProgress> = state.progress.values.toList()
        }

        override val favorites: FavoriteDao = object : FavoriteDao {
            override suspend fun contains(key: ContentKey): Boolean = key in state.favorites

            override suspend fun set(key: ContentKey, favorite: Boolean) {
                if (favorite) state.favorites += key else state.favorites -= key
            }

            override suspend fun keys(): Set<ContentKey> = state.favorites.toSet()
        }
    }
}

package app.prismia.data

import app.prismia.database.PlaybackProgress
import app.prismia.database.PrismiaDatabase
import app.prismia.foundation.ContentKey
import app.prismia.video.VideoWork

/**
 * Transactional facade for the video library lifecycle.
 *
 * Callers do not coordinate content, favorites, and playback progress by
 * hand. A single database transaction keeps those records coherent, while
 * the underlying implementation can be SQLite today or Room later.
 */
class VideoLibraryStore(
    private val database: PrismiaDatabase,
) {
    suspend fun upsert(work: VideoWork) = database.transaction {
        videos.write(work)
    }

    suspend fun read(key: ContentKey): VideoWork? = database.transaction {
        videos.read(key)
    }

    suspend fun setFavorite(key: ContentKey, favorite: Boolean) = database.transaction {
        favorites.set(key, favorite)
    }

    suspend fun isFavorite(key: ContentKey): Boolean = database.transaction {
        favorites.contains(key)
    }

    suspend fun saveProgress(progress: PlaybackProgress) = database.transaction {
        playback.write(progress)
    }

    suspend fun readProgress(key: ContentKey): PlaybackProgress? = database.transaction {
        playback.read(key)
    }

    suspend fun tombstone(key: ContentKey, reason: String? = null) = database.transaction {
        videos.tombstone(key, reason)
        playback.remove(key)
        favorites.set(key, false)
    }

    suspend fun snapshot(): VideoLibrarySnapshot = database.transaction {
        VideoLibrarySnapshot(
            records = videos.records(),
            tombstones = videos.tombstones(),
            favorites = favorites.keys(),
            progress = playback.records().associateBy(PlaybackProgress::key),
        )
    }
}

data class VideoLibrarySnapshot(
    val records: List<VideoWork>,
    val tombstones: Map<ContentKey, String?>,
    val favorites: Set<ContentKey>,
    val progress: Map<ContentKey, PlaybackProgress>,
)

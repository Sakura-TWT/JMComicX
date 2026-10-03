package app.prismia.database

import app.prismia.foundation.ContentKey
import app.prismia.video.VideoWork

/** Schema identity is independent from any concrete Room/SQLite implementation. */
data class DatabaseSchema(
    val name: String = "prismia",
    val version: Int = CURRENT_SCHEMA_VERSION,
) {
    init {
        require(name.isNotBlank()) { "database schema name must not be blank" }
        require(version >= 1) { "database schema version must be positive" }
    }
}

const val CURRENT_SCHEMA_VERSION: Int = 1

data class PlaybackProgress(
    val key: ContentKey,
    val positionMs: Long,
    val durationMs: Long? = null,
    val updatedAtEpochSeconds: Long,
    val completed: Boolean = false,
) {
    init {
        require(positionMs >= 0) { "playback position must be non-negative" }
        require(durationMs == null || durationMs >= 0) { "playback duration must be non-negative" }
        require(updatedAtEpochSeconds >= 0) { "playback timestamp must be non-negative" }
    }
}

interface VideoContentDao {
    suspend fun read(key: ContentKey): VideoWork?
    suspend fun write(work: VideoWork)
    suspend fun tombstone(key: ContentKey, reason: String? = null)
    suspend fun isTombstoned(key: ContentKey): Boolean
    suspend fun records(): List<VideoWork>
    suspend fun tombstones(): Map<ContentKey, String?>
}

interface PlaybackProgressDao {
    suspend fun read(key: ContentKey): PlaybackProgress?
    suspend fun write(progress: PlaybackProgress)
    suspend fun remove(key: ContentKey)
    suspend fun records(): List<PlaybackProgress>
}

interface FavoriteDao {
    suspend fun contains(key: ContentKey): Boolean
    suspend fun set(key: ContentKey, favorite: Boolean)
    suspend fun keys(): Set<ContentKey>
}

interface DatabaseTransaction {
    val videos: VideoContentDao
    val playback: PlaybackProgressDao
    val favorites: FavoriteDao
}

interface PrismiaDatabase {
    val schema: DatabaseSchema

    /** The implementation must commit all DAO mutations atomically. */
    suspend fun <T> transaction(block: suspend DatabaseTransaction.() -> T): T
}

/** Ordered, validated migration steps shared by Android SQLite/Room backends. */
data class DatabaseMigration(
    val fromVersion: Int,
    val toVersion: Int,
    val migrate: suspend DatabaseTransaction.() -> Unit,
) {
    init {
        require(fromVersion >= 1) { "migration source version must be positive" }
        require(toVersion == fromVersion + 1) { "migrations must advance exactly one version" }
    }
}

class DatabaseMigrationPlan(
    migrations: Collection<DatabaseMigration>,
) {
    private val bySource = migrations.associateBy(DatabaseMigration::fromVersion)

    init {
        require(bySource.size == migrations.size) { "duplicate database migration source version" }
    }

    fun path(fromVersion: Int, toVersion: Int): List<DatabaseMigration> {
        require(fromVersion >= 1) { "fromVersion must be positive" }
        require(toVersion >= fromVersion) { "toVersion must not be older than fromVersion" }
        val result = mutableListOf<DatabaseMigration>()
        var cursor = fromVersion
        while (cursor < toVersion) {
            val step = bySource[cursor]
                ?: error("missing database migration $cursor -> ${cursor + 1}")
            result += step
            cursor = step.toVersion
        }
        return result
    }
}

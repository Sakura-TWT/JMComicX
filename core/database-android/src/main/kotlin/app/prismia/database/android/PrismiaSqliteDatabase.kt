package app.prismia.database.android

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import app.prismia.database.DatabaseSchema
import app.prismia.database.DatabaseTransaction
import app.prismia.database.FavoriteDao
import app.prismia.database.PlaybackProgress
import app.prismia.database.PlaybackProgressDao
import app.prismia.database.PrismiaDatabase
import app.prismia.database.VideoContentDao
import app.prismia.foundation.ContentKey
import app.prismia.foundation.ContentSource
import app.prismia.foundation.ContentType
import app.prismia.video.VideoAvailability
import app.prismia.video.VideoWork
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Android SQLite implementation of the platform-neutral [PrismiaDatabase]
 * contract.
 *
 * The contract stays independent from Android so the data layer can be tested
 * with [app.prismia.database.InMemoryPrismiaDatabase]. This implementation is
 * intentionally small and uses bound arguments for every value. It can be
 * replaced with Room later without changing callers or the migration boundary.
 */
class PrismiaSqliteDatabase(
    context: Context,
    name: String = DEFAULT_DATABASE_NAME,
) : SQLiteOpenHelper(context.applicationContext, name, null, SCHEMA_VERSION), PrismiaDatabase {
    override val schema: DatabaseSchema = DatabaseSchema(version = SCHEMA_VERSION)

    private val transactionMutex = Mutex()

    init {
        // WAL keeps readers independent from the writer while the explicit
        // transaction boundary still provides atomic cross-table updates.
        setWriteAheadLoggingEnabled(true)
    }

    override suspend fun <T> transaction(block: suspend DatabaseTransaction.() -> T): T =
        transactionMutex.withLock {
            withContext(Dispatchers.IO) {
                val database = writableDatabase
                database.beginTransaction()
                try {
                    val result = SqlTransaction(database).block()
                    database.setTransactionSuccessful()
                    result
                } finally {
                    database.endTransaction()
                }
            }
        }

    /** Returns whether no video records or tombstones have been written yet. */
    suspend fun isContentEmpty(): Boolean = withContext(Dispatchers.IO) {
        val database = readableDatabase
        database.rawQuery(
            "SELECT EXISTS(SELECT 1 FROM video_records LIMIT 1) OR " +
                "EXISTS(SELECT 1 FROM video_tombstones LIMIT 1)",
            null,
        ).use { cursor ->
            cursor.moveToFirst() && cursor.getInt(0) == 0
        }
    }

    /** Metadata is deliberately separate from content and never stores tokens. */
    suspend fun readMetadata(key: String): String? = withContext(Dispatchers.IO) {
        require(key.isNotBlank()) { "metadata key must not be blank" }
        readableDatabase.query(
            TABLE_METADATA,
            arrayOf(COLUMN_METADATA_VALUE),
            "$COLUMN_METADATA_KEY = ?",
            arrayOf(key),
            null,
            null,
            null,
            "1",
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }

    /**
     * Stores a migration marker. Callers should write the marker only after
     * the corresponding migration transaction has committed successfully.
     */
    suspend fun writeMetadata(key: String, value: String) = withContext(Dispatchers.IO) {
        require(key.isNotBlank()) { "metadata key must not be blank" }
        val values = ContentValues().apply {
            put(COLUMN_METADATA_KEY, key)
            put(COLUMN_METADATA_VALUE, value)
        }
        writableDatabase.insertWithOnConflict(
            TABLE_METADATA,
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE,
        ).also { check(it != -1L) { "unable to write database metadata" } }
    }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
        db.rawQuery("PRAGMA busy_timeout = 5000", null).use { }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(CREATE_VIDEO_RECORDS)
        db.execSQL(CREATE_VIDEO_TAGS)
        db.execSQL(CREATE_VIDEO_SOURCE_LINKS)
        db.execSQL(CREATE_VIDEO_TOMBSTONES)
        db.execSQL(CREATE_PLAYBACK_PROGRESS)
        db.execSQL(CREATE_FAVORITES)
        db.execSQL(CREATE_METADATA)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Version one is the first released persistence schema. Keep upgrade
        // handling explicit so a future version cannot silently lose content.
        if (oldVersion < 1 && newVersion >= 1) onCreate(db)
        require(oldVersion <= newVersion) { "database downgrade is not supported" }
        if (newVersion > SCHEMA_VERSION) {
            error("unsupported Prismia database schema version $newVersion")
        }
    }

    override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        error("database downgrade from $oldVersion to $newVersion is not supported")
    }

    private class SqlTransaction(
        private val database: SQLiteDatabase,
    ) : DatabaseTransaction {
        override val videos: VideoContentDao = SqlVideoContentDao(database)
        override val playback: PlaybackProgressDao = SqlPlaybackProgressDao(database)
        override val favorites: FavoriteDao = SqlFavoriteDao(database)
    }

    private class SqlVideoContentDao(
        private val database: SQLiteDatabase,
    ) : VideoContentDao {
        override suspend fun read(key: ContentKey): VideoWork? {
            return database.query(
                TABLE_VIDEO_RECORDS,
                VIDEO_COLUMNS,
                VIDEO_KEY_SELECTION,
                keyArgs(key),
                null,
                null,
                null,
                "1",
            ).use { cursor ->
                if (!cursor.moveToFirst()) return null
                readWork(cursor, database)
            }
        }

        override suspend fun write(work: VideoWork) {
            val values = ContentValues().apply {
                put(COLUMN_CONTENT_TYPE, work.key.contentType.name)
                put(COLUMN_SOURCE, work.key.source.name)
                put(COLUMN_REMOTE_ID, work.key.remoteId)
                put(COLUMN_TITLE, work.title)
                put(COLUMN_AUTHOR, work.author)
                put(COLUMN_COVER_URL, work.coverUrl)
                put(COLUMN_DURATION_MS, work.durationMs)
                put(COLUMN_DESCRIPTION, work.description)
                put(COLUMN_AVAILABILITY, work.availability.name)
            }
            val row = database.insertWithOnConflict(
                TABLE_VIDEO_RECORDS,
                null,
                values,
                SQLiteDatabase.CONFLICT_REPLACE,
            )
            check(row != -1L) { "unable to persist video record" }
            database.delete(TABLE_VIDEO_TAGS, VIDEO_KEY_SELECTION, keyArgs(work.key))
            database.delete(TABLE_VIDEO_SOURCE_LINKS, VIDEO_KEY_SELECTION, keyArgs(work.key))
            database.delete(TABLE_VIDEO_TOMBSTONES, VIDEO_KEY_SELECTION, keyArgs(work.key))

            work.tags.forEachIndexed { ordinal, tag ->
                val tagValues = ContentValues().apply {
                    put(COLUMN_CONTENT_TYPE, work.key.contentType.name)
                    put(COLUMN_SOURCE, work.key.source.name)
                    put(COLUMN_REMOTE_ID, work.key.remoteId)
                    put(COLUMN_ORDINAL, ordinal)
                    put(COLUMN_TAG, tag)
                }
                check(database.insert(TABLE_VIDEO_TAGS, null, tagValues) != -1L) {
                    "unable to persist video tag"
                }
            }
            work.sourceLinks.forEach { (source, link) ->
                val linkValues = ContentValues().apply {
                    put(COLUMN_CONTENT_TYPE, work.key.contentType.name)
                    put(COLUMN_SOURCE, work.key.source.name)
                    put(COLUMN_REMOTE_ID, work.key.remoteId)
                    put(COLUMN_LINK_SOURCE, source.name)
                    put(COLUMN_LINK, link)
                }
                check(database.insert(TABLE_VIDEO_SOURCE_LINKS, null, linkValues) != -1L) {
                    "unable to persist video source link"
                }
            }
        }

        override suspend fun tombstone(key: ContentKey, reason: String?) {
            database.delete(TABLE_VIDEO_RECORDS, VIDEO_KEY_SELECTION, keyArgs(key))
            database.delete(TABLE_VIDEO_TAGS, VIDEO_KEY_SELECTION, keyArgs(key))
            database.delete(TABLE_VIDEO_SOURCE_LINKS, VIDEO_KEY_SELECTION, keyArgs(key))
            val values = ContentValues().apply {
                put(COLUMN_CONTENT_TYPE, key.contentType.name)
                put(COLUMN_SOURCE, key.source.name)
                put(COLUMN_REMOTE_ID, key.remoteId)
                put(COLUMN_REASON, reason?.takeIf(String::isNotBlank))
            }
            check(
                database.insertWithOnConflict(
                    TABLE_VIDEO_TOMBSTONES,
                    null,
                    values,
                    SQLiteDatabase.CONFLICT_REPLACE,
                ) != -1L,
            ) { "unable to persist video tombstone" }
        }

        override suspend fun isTombstoned(key: ContentKey): Boolean = database.query(
            TABLE_VIDEO_TOMBSTONES,
            arrayOf(COLUMN_REMOTE_ID),
            VIDEO_KEY_SELECTION,
            keyArgs(key),
            null,
            null,
            null,
            "1",
        ).use { it.moveToFirst() }

        override suspend fun records(): List<VideoWork> = database.query(
            TABLE_VIDEO_RECORDS,
            VIDEO_COLUMNS,
            null,
            null,
            null,
            null,
            "$COLUMN_REMOTE_ID ASC",
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(readWork(cursor, database))
            }
        }

        override suspend fun tombstones(): Map<ContentKey, String?> = database.query(
            TABLE_VIDEO_TOMBSTONES,
            TOMBSTONE_COLUMNS,
            null,
            null,
            null,
            null,
            "$COLUMN_REMOTE_ID ASC",
        ).use { cursor ->
            buildMap {
                while (cursor.moveToNext()) {
                    val key = readKey(cursor)
                    put(key, cursor.getNullableString(cursor.getColumnIndexOrThrow(COLUMN_REASON)))
                }
            }
        }

        private fun readWork(cursor: android.database.Cursor, database: SQLiteDatabase): VideoWork {
            val key = readKey(cursor)
            val tags = database.query(
                TABLE_VIDEO_TAGS,
                arrayOf(COLUMN_TAG),
                VIDEO_KEY_SELECTION,
                keyArgs(key),
                null,
                null,
                "$COLUMN_ORDINAL ASC",
            ).use { tagCursor ->
                buildList {
                    while (tagCursor.moveToNext()) add(tagCursor.getString(0))
                }
            }
            val links = database.query(
                TABLE_VIDEO_SOURCE_LINKS,
                arrayOf(COLUMN_LINK_SOURCE, COLUMN_LINK),
                VIDEO_KEY_SELECTION,
                keyArgs(key),
                null,
                null,
                "$COLUMN_LINK_SOURCE ASC",
            ).use { linkCursor ->
                buildMap {
                    while (linkCursor.moveToNext()) {
                        val source = enumValue<ContentSource>(linkCursor.getString(0))
                        put(source, linkCursor.getString(1))
                    }
                }
            }
            return VideoWork(
                key = key,
                title = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_TITLE)),
                author = cursor.getNullableString(cursor.getColumnIndexOrThrow(COLUMN_AUTHOR)),
                coverUrl = cursor.getNullableString(cursor.getColumnIndexOrThrow(COLUMN_COVER_URL)),
                durationMs = cursor.getNullableLong(cursor.getColumnIndexOrThrow(COLUMN_DURATION_MS)),
                description = cursor.getNullableString(cursor.getColumnIndexOrThrow(COLUMN_DESCRIPTION)),
                tags = tags,
                availability = enumValue(cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_AVAILABILITY))),
                sourceLinks = links,
            )
        }

        private fun readKey(cursor: android.database.Cursor): ContentKey = ContentKey(
            contentType = enumValue(cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_CONTENT_TYPE))),
            source = enumValue(cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_SOURCE))),
            remoteId = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_REMOTE_ID)),
        )
    }

    private class SqlPlaybackProgressDao(
        private val database: SQLiteDatabase,
    ) : PlaybackProgressDao {
        override suspend fun read(key: ContentKey): PlaybackProgress? = database.query(
            TABLE_PLAYBACK_PROGRESS,
            PROGRESS_COLUMNS,
            VIDEO_KEY_SELECTION,
            keyArgs(key),
            null,
            null,
            null,
            "1",
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            PlaybackProgress(
                key = key,
                positionMs = cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_POSITION_MS)),
                durationMs = cursor.getNullableLong(cursor.getColumnIndexOrThrow(COLUMN_DURATION_MS)),
                updatedAtEpochSeconds = cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_UPDATED_AT)),
                completed = cursor.getInt(cursor.getColumnIndexOrThrow(COLUMN_COMPLETED)) != 0,
            )
        }

        override suspend fun write(progress: PlaybackProgress) {
            val values = ContentValues().apply {
                put(COLUMN_CONTENT_TYPE, progress.key.contentType.name)
                put(COLUMN_SOURCE, progress.key.source.name)
                put(COLUMN_REMOTE_ID, progress.key.remoteId)
                put(COLUMN_POSITION_MS, progress.positionMs)
                put(COLUMN_DURATION_MS, progress.durationMs)
                put(COLUMN_UPDATED_AT, progress.updatedAtEpochSeconds)
                put(COLUMN_COMPLETED, if (progress.completed) 1 else 0)
            }
            check(
                database.insertWithOnConflict(
                    TABLE_PLAYBACK_PROGRESS,
                    null,
                    values,
                    SQLiteDatabase.CONFLICT_REPLACE,
                ) != -1L,
            ) { "unable to persist playback progress" }
        }

        override suspend fun remove(key: ContentKey) {
            database.delete(TABLE_PLAYBACK_PROGRESS, VIDEO_KEY_SELECTION, keyArgs(key))
        }

        override suspend fun records(): List<PlaybackProgress> = database.query(
            TABLE_PLAYBACK_PROGRESS,
            PROGRESS_COLUMNS,
            null,
            null,
            null,
            null,
            "$COLUMN_UPDATED_AT ASC",
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        PlaybackProgress(
                            key = ContentKey(
                                contentType = enumValue(cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_CONTENT_TYPE))),
                                source = enumValue(cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_SOURCE))),
                                remoteId = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_REMOTE_ID)),
                            ),
                            positionMs = cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_POSITION_MS)),
                            durationMs = cursor.getNullableLong(cursor.getColumnIndexOrThrow(COLUMN_DURATION_MS)),
                            updatedAtEpochSeconds = cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_UPDATED_AT)),
                            completed = cursor.getInt(cursor.getColumnIndexOrThrow(COLUMN_COMPLETED)) != 0,
                        ),
                    )
                }
            }
        }
    }

    private class SqlFavoriteDao(
        private val database: SQLiteDatabase,
    ) : FavoriteDao {
        override suspend fun contains(key: ContentKey): Boolean = database.query(
            TABLE_FAVORITES,
            arrayOf(COLUMN_REMOTE_ID),
            VIDEO_KEY_SELECTION,
            keyArgs(key),
            null,
            null,
            null,
            "1",
        ).use { it.moveToFirst() }

        override suspend fun set(key: ContentKey, favorite: Boolean) {
            if (favorite) {
                val values = ContentValues().apply {
                    put(COLUMN_CONTENT_TYPE, key.contentType.name)
                    put(COLUMN_SOURCE, key.source.name)
                    put(COLUMN_REMOTE_ID, key.remoteId)
                }
                check(
                    database.insertWithOnConflict(
                        TABLE_FAVORITES,
                        null,
                        values,
                        SQLiteDatabase.CONFLICT_REPLACE,
                    ) != -1L,
                ) { "unable to persist favorite" }
            } else {
                database.delete(TABLE_FAVORITES, VIDEO_KEY_SELECTION, keyArgs(key))
            }
        }

        override suspend fun keys(): Set<ContentKey> = database.query(
            TABLE_FAVORITES,
            KEY_COLUMNS,
            null,
            null,
            null,
            null,
            "$COLUMN_REMOTE_ID ASC",
        ).use { cursor ->
            buildSet {
                while (cursor.moveToNext()) add(readKey(cursor))
            }
        }

        private fun readKey(cursor: android.database.Cursor): ContentKey = ContentKey(
            contentType = enumValue(cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_CONTENT_TYPE))),
            source = enumValue(cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_SOURCE))),
            remoteId = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_REMOTE_ID)),
        )
    }

    private companion object {
        const val SCHEMA_VERSION = 1
        const val DEFAULT_DATABASE_NAME = "prismia.db"
        const val TABLE_VIDEO_RECORDS = "video_records"
        const val TABLE_VIDEO_TAGS = "video_tags"
        const val TABLE_VIDEO_SOURCE_LINKS = "video_source_links"
        const val TABLE_VIDEO_TOMBSTONES = "video_tombstones"
        const val TABLE_PLAYBACK_PROGRESS = "playback_progress"
        const val TABLE_FAVORITES = "favorites"
        const val TABLE_METADATA = "metadata"

        const val COLUMN_CONTENT_TYPE = "content_type"
        const val COLUMN_SOURCE = "source"
        const val COLUMN_REMOTE_ID = "remote_id"
        const val COLUMN_TITLE = "title"
        const val COLUMN_AUTHOR = "author"
        const val COLUMN_COVER_URL = "cover_url"
        const val COLUMN_DURATION_MS = "duration_ms"
        const val COLUMN_DESCRIPTION = "description"
        const val COLUMN_AVAILABILITY = "availability"
        const val COLUMN_ORDINAL = "ordinal"
        const val COLUMN_TAG = "tag"
        const val COLUMN_LINK_SOURCE = "link_source"
        const val COLUMN_LINK = "link"
        const val COLUMN_REASON = "reason"
        const val COLUMN_POSITION_MS = "position_ms"
        const val COLUMN_UPDATED_AT = "updated_at"
        const val COLUMN_COMPLETED = "completed"
        const val COLUMN_METADATA_KEY = "key"
        const val COLUMN_METADATA_VALUE = "value"

        val KEY_COLUMNS = arrayOf(COLUMN_CONTENT_TYPE, COLUMN_SOURCE, COLUMN_REMOTE_ID)
        val VIDEO_COLUMNS = arrayOf(
            COLUMN_CONTENT_TYPE,
            COLUMN_SOURCE,
            COLUMN_REMOTE_ID,
            COLUMN_TITLE,
            COLUMN_AUTHOR,
            COLUMN_COVER_URL,
            COLUMN_DURATION_MS,
            COLUMN_DESCRIPTION,
            COLUMN_AVAILABILITY,
        )
        val TOMBSTONE_COLUMNS = arrayOf(*KEY_COLUMNS, COLUMN_REASON)
        val PROGRESS_COLUMNS = arrayOf(
            *KEY_COLUMNS,
            COLUMN_POSITION_MS,
            COLUMN_DURATION_MS,
            COLUMN_UPDATED_AT,
            COLUMN_COMPLETED,
        )

        const val VIDEO_KEY_SELECTION =
            "$COLUMN_CONTENT_TYPE = ? AND $COLUMN_SOURCE = ? AND $COLUMN_REMOTE_ID = ?"

        const val CREATE_VIDEO_RECORDS = """
            CREATE TABLE video_records (
                content_type TEXT NOT NULL,
                source TEXT NOT NULL,
                remote_id TEXT NOT NULL,
                title TEXT NOT NULL,
                author TEXT,
                cover_url TEXT,
                duration_ms INTEGER,
                description TEXT,
                availability TEXT NOT NULL,
                PRIMARY KEY (content_type, source, remote_id)
            )
        """
        const val CREATE_VIDEO_TAGS = """
            CREATE TABLE video_tags (
                content_type TEXT NOT NULL,
                source TEXT NOT NULL,
                remote_id TEXT NOT NULL,
                ordinal INTEGER NOT NULL,
                tag TEXT NOT NULL,
                PRIMARY KEY (content_type, source, remote_id, ordinal),
                FOREIGN KEY (content_type, source, remote_id)
                    REFERENCES video_records(content_type, source, remote_id)
                    ON DELETE CASCADE
            )
        """
        const val CREATE_VIDEO_SOURCE_LINKS = """
            CREATE TABLE video_source_links (
                content_type TEXT NOT NULL,
                source TEXT NOT NULL,
                remote_id TEXT NOT NULL,
                link_source TEXT NOT NULL,
                link TEXT NOT NULL,
                PRIMARY KEY (content_type, source, remote_id, link_source),
                FOREIGN KEY (content_type, source, remote_id)
                    REFERENCES video_records(content_type, source, remote_id)
                    ON DELETE CASCADE
            )
        """
        const val CREATE_VIDEO_TOMBSTONES = """
            CREATE TABLE video_tombstones (
                content_type TEXT NOT NULL,
                source TEXT NOT NULL,
                remote_id TEXT NOT NULL,
                reason TEXT,
                PRIMARY KEY (content_type, source, remote_id)
            )
        """
        const val CREATE_PLAYBACK_PROGRESS = """
            CREATE TABLE playback_progress (
                content_type TEXT NOT NULL,
                source TEXT NOT NULL,
                remote_id TEXT NOT NULL,
                position_ms INTEGER NOT NULL,
                duration_ms INTEGER,
                updated_at INTEGER NOT NULL,
                completed INTEGER NOT NULL,
                PRIMARY KEY (content_type, source, remote_id)
            )
        """
        const val CREATE_FAVORITES = """
            CREATE TABLE favorites (
                content_type TEXT NOT NULL,
                source TEXT NOT NULL,
                remote_id TEXT NOT NULL,
                PRIMARY KEY (content_type, source, remote_id)
            )
        """
        const val CREATE_METADATA = """
            CREATE TABLE metadata (
                key TEXT PRIMARY KEY,
                value TEXT NOT NULL
            )
        """

        fun keyArgs(key: ContentKey): Array<String> = arrayOf(
            key.contentType.name,
            key.source.name,
            key.remoteId,
        )

        inline fun <reified T : Enum<T>> enumValue(value: String): T =
            runCatching { enumValueOf<T>(value) }
                .getOrElse { throw IllegalStateException("invalid database enum value: $value", it) }

        fun android.database.Cursor.getNullableString(index: Int): String? =
            if (isNull(index)) null else getString(index)

        fun android.database.Cursor.getNullableLong(index: Int): Long? =
            if (isNull(index)) null else getLong(index)

        fun readKey(cursor: android.database.Cursor): ContentKey = ContentKey(
            contentType = enumValue(cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_CONTENT_TYPE))),
            source = enumValue(cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_SOURCE))),
            remoteId = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_REMOTE_ID)),
        )
    }
}

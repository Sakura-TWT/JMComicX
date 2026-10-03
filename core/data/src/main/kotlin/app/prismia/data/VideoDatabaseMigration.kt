package app.prismia.data

import app.prismia.database.PrismiaDatabase

/** One-way bridge from the durable pre-database snapshot to the new database boundary. */
class LegacyVideoContentStoreMigrator(
    private val source: VideoContentStore,
) {
    suspend fun migrateInto(target: PrismiaDatabase) {
        val snapshot = source.snapshot()
        target.transaction {
            snapshot.records.values.forEach { videos.write(it) }
            snapshot.tombstones.forEach { (key, reason) -> videos.tombstone(key, reason) }
        }
    }
}

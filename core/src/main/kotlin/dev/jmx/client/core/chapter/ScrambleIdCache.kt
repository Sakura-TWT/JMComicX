package dev.jmx.client.core.chapter

class ScrambleIdCache {
    private val lock = Any()
    private val byPhotoId = linkedMapOf<String, Int>()

    fun get(photoId: String): Int? = synchronized(lock) { byPhotoId[photoId] }

    fun get(photoId: String, albumId: String?): Int? = synchronized(lock) {
        byPhotoId[photoId] ?: albumId?.let { byPhotoId[it] }
    }

    fun put(photoId: String, scrambleId: Int, albumId: String? = null) {
        synchronized(lock) {
            byPhotoId[photoId] = scrambleId
            if (!albumId.isNullOrBlank()) {
                byPhotoId[albumId] = scrambleId
            }
            while (byPhotoId.size > MAX_ENTRIES) {
                val eldest = byPhotoId.entries.iterator()
                if (!eldest.hasNext()) break
                eldest.next()
                eldest.remove()
            }
        }
    }

    fun clear() = synchronized(lock) { byPhotoId.clear() }

    fun size(): Int = synchronized(lock) { byPhotoId.size }

    private companion object {
        const val MAX_ENTRIES = 512
    }
}

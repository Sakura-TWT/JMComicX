package dev.jmx.client

import android.content.Context
import androidx.core.content.edit

internal class OfflineReadingProgressStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("jmx_offline_reading", Context.MODE_PRIVATE)

    fun chapterId(albumId: String): String? = preferences.getString("jm:$albumId:chapter", null)
    fun pageIndex(albumId: String): Int = preferences.getInt("jm:$albumId:page", 0).coerceAtLeast(0)

    fun save(progress: ReaderProgressUpdate) {
        preferences.edit {
            putString("jm:${progress.album.id}:chapter", progress.chapterId)
            putInt("jm:${progress.album.id}:page", progress.pageIndex.coerceAtLeast(0))
        }
    }
}

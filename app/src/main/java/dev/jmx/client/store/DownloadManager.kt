package dev.jmx.client.store

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import dev.jmx.client.data.models.Album
import dev.jmx.client.database.dao.DownloadAlbumDao
import dev.jmx.client.database.model.DownloadAlbum
import dev.jmx.client.worker.DownloadAlbumWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

class DownloadManager(
    private val context: Context,
    private val downloadAlbumDao: DownloadAlbumDao,
    private val scope: CoroutineScope,
    private val toastManager: ToastManager,
) {
    fun downloadAlbum(album: Album) {
        scope.launch(Dispatchers.IO) {
            JmxDiagnostics.userAction(
                screen = "AlbumDetail",
                action = "click",
                target = "download_album",
                metadata = mapOf(
                    "album_id" to album.id,
                    "album_name_length" to album.name.length
                )
            )
            downloadAlbumDao.insert(
                DownloadAlbum(
                    id = album.id,
                    name = album.name,
                    authorList = album.authorList,
                    coverPath = "",
                    zipPath = "",
                    progress = 0f,
                    status = "pending",
                    createTime = System.currentTimeMillis()
                )
            )
            JmxDiagnostics.i(
                "Download",
                "Download task created",
                metadata = mapOf(
                    "task_id" to album.id,
                    "album_id" to album.id,
                    "album_name_length" to album.name.length,
                    "status" to "pending"
                )
            )
            toastManager.showAsync("创建下载任务成功")
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED) // 必须有网
                .build()
            val downloadRequest = OneTimeWorkRequestBuilder<DownloadAlbumWorker>()
                .setConstraints(constraints)
                .setInputData(
                    workDataOf(
                        "albumId" to album.id
                    )
                ) // 传递参数
                .setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.SECONDS) // 重试策略
                .build()
            WorkManager.getInstance(context).enqueue(downloadRequest)
            JmxDiagnostics.i(
                "Download",
                "Download worker enqueued",
                metadata = mapOf(
                    "task_id" to album.id,
                    "worker_id" to downloadRequest.id
                )
            )
        }
    }
}

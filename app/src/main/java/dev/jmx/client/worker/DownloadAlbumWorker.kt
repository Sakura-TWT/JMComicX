package dev.jmx.client.worker

import android.content.Context
import android.graphics.Bitmap
import androidx.core.graphics.drawable.toBitmap
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import coil.ImageLoader
import coil.request.CachePolicy
import coil.request.ErrorResult
import coil.request.ImageRequest
import coil.request.SuccessResult
import dev.jmx.client.cache.deleteDownloadTempDir
import dev.jmx.client.cache.getDownloadDir
import dev.jmx.client.cache.trimDownloadTempCache
import dev.jmx.client.database.dao.DownloadAlbumDao
import dev.jmx.client.database.model.UpdateAlbumCover
import dev.jmx.client.database.model.UpdateAlbumProgress
import dev.jmx.client.database.model.UpdateAlbumStatus
import dev.jmx.client.database.model.UpdateAlbumZipPath
import dev.jmx.client.repository.AlbumRepository
import dev.jmx.client.data.remote.model.AlbumImageListResponse
import dev.jmx.client.data.remote.model.NetworkResult
import dev.jmx.client.store.LocalSettingManager
import dev.jmx.client.store.JmxDiagnostics
import dev.jmx.client.store.RemoteSettingManager
import dev.jmx.client.store.ToastManager
import dev.jmx.client.utils.tryCreateDir
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class DownloadAlbumWorker(
    private val appContext: Context,
    params: WorkerParameters,
    private val downloadAlbumDao: DownloadAlbumDao,
    private val remoteSettingManager: RemoteSettingManager,
    private val localSettingManager: LocalSettingManager,
    private val albumRepository: AlbumRepository,
    private val toastManager: ToastManager,
    private val imageLoader: ImageLoader,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val albumId = inputData.getInt("albumId", -1)
        if (albumId == -1) {
            JmxDiagnostics.e(
                "Download",
                "Download worker missing album id",
                metadata = mapOf("worker_run_attempt" to runAttemptCount)
            )
            return Result.failure()
        }
        val startMs = System.currentTimeMillis()
        JmxDiagnostics.i(
            "Download",
            "Download task started",
            metadata = mapOf(
                "task_id" to albumId,
                "album_id" to albumId,
                "worker_run_attempt" to runAttemptCount
            )
        )
        return try {
            downloadAlbumDao.updateStatus(
                UpdateAlbumStatus(
                    albumId,
                    "downloading"
                )
            )
            JmxDiagnostics.d(
                "Download",
                "Download status changed",
                metadata = mapOf("task_id" to albumId, "status" to "downloading")
            )
            val coverPath = downloadCover(albumId)
            downloadAlbumDao.updateCover(
                UpdateAlbumCover(
                    albumId,
                    coverPath
                )
            )
            val picPathList =
                downloadImageList(albumId, localSettingManager.localSettingState.value.shunt)
            val zipPath = zipPicPathList(albumId, picPathList)
            deleteDownloadTempDir(appContext, albumId)
            trimDownloadTempCache(appContext)
            downloadAlbumDao.updateZipPath(
                UpdateAlbumZipPath(
                    albumId,
                    zipPath
                )
            )
            downloadAlbumDao.updateStatus(
                UpdateAlbumStatus(
                    albumId,
                    "complete"
                )
            )
            val costMs = System.currentTimeMillis() - startMs
            val zipFile = File(zipPath)
            JmxDiagnostics.i(
                "Download",
                "Download task completed",
                metadata = mapOf(
                    "task_id" to albumId,
                    "album_id" to albumId,
                    "cost_ms" to costMs,
                    "image_count" to picPathList.size,
                    "final_file_size_bytes" to zipFile.length(),
                    "target_path" to zipFile.name
                )
            )
            toastManager.showAsync("下载成功")
            Result.success()
        } catch (e: Exception) {
            JmxDiagnostics.e(
                "Download",
                "Download task failed",
                e,
                metadata = mapOf(
                    "task_id" to albumId,
                    "album_id" to albumId,
                    "worker_run_attempt" to runAttemptCount,
                    "will_retry" to (runAttemptCount < 3)
                )
            )
            if (runAttemptCount < 3) {
                Result.retry() // 如果失败了，系统会自动尝试重试
            } else {
                downloadAlbumDao.updateStatus(
                    UpdateAlbumStatus(
                        albumId,
                        "error"
                    )
                )
                Result.failure()
            }
        }
    }

    private suspend fun downloadCover(albumId: Int): String {
        return withContext(Dispatchers.IO) {
            val coverUrl =
                "${remoteSettingManager.remoteSettingState.value.imgHost}/media/albums/${albumId}_3x4.jpg"
            JmxDiagnostics.i(
                "Download",
                "Download cover started",
                metadata = mapOf(
                    "task_id" to albumId,
                    "download_url" to coverUrl,
                    "target_path" to "download/cover/${albumId}.jpg"
                )
            )
            val request = ImageRequest.Builder(appContext)
                .data(coverUrl)
                .allowHardware(false)
                .diskCachePolicy(CachePolicy.DISABLED)
                .memoryCachePolicy(CachePolicy.DISABLED)
                .build()

            when (val result = imageLoader.execute(request)) {
                is ErrorResult -> {
                    JmxDiagnostics.e(
                        "Download",
                        "Download cover failed",
                        result.throwable,
                        metadata = mapOf("task_id" to albumId, "download_url" to coverUrl)
                    )
                    ""
                }

                is SuccessResult -> {
                    val bitmap = result.drawable.toBitmap()
                    val dir = getAlbumCoverDownloadDir()
                    val file = File(dir, "${albumId}.jpg")
                    FileOutputStream(file).use { out ->
                        bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, 50, out)
                    }
                    JmxDiagnostics.i(
                        "Download",
                        "Download cover completed",
                        metadata = mapOf(
                            "task_id" to albumId,
                            "target_path" to file.name,
                            "final_file_size_bytes" to file.length()
                        )
                    )
                    file.absolutePath
                }
            }
        }
    }

    private suspend fun downloadImageList(albumId: Int, shunt: String): List<String> {
        return withContext(Dispatchers.IO) {
            when (val data = albumRepository.getAlbumImageList(albumId, shunt)) {
                is NetworkResult.Error -> {
                    throw IllegalStateException(data.message)
                }

                is NetworkResult.Success<AlbumImageListResponse> -> {
                    if (data.data.list.isEmpty()) {
                        throw IllegalStateException("Album image list is empty")
                    }

                    val dir = getAlbumImageListDownloadDir(albumId)
                    JmxDiagnostics.i(
                        "Download",
                        "Download image list started",
                        metadata = mapOf(
                            "task_id" to albumId,
                            "album_id" to albumId,
                            "image_count" to data.data.list.size,
                            "shunt" to shunt
                        )
                    )
                    var lastLoggedPercent = -10
                    data.data.list.mapIndexed { index, url ->
                        val request = ImageRequest.Builder(appContext)
                            .data(url)
                            .allowHardware(false)
                            .diskCachePolicy(CachePolicy.DISABLED)
                            .memoryCachePolicy(CachePolicy.DISABLED)
                            .build()

                        when (val result = imageLoader.execute(request)) {
                            is ErrorResult -> {
                                throw IllegalStateException(
                                    result.throwable.message ?: "Download image failed"
                                )
                            }

                            is SuccessResult -> {
                                val bitmap = result.drawable.toBitmap()
                                val file = File(dir, "$index.webp")
                                FileOutputStream(file).use { out ->
                                    bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, 50, out)
                                }
                                downloadAlbumDao.updateProgress(
                                    UpdateAlbumProgress(
                                        albumId,
                                        (index + 1).toFloat() / data.data.list.size
                                    )
                                )
                                val percent = (((index + 1).toFloat() / data.data.list.size) * 100).toInt()
                                if (percent >= lastLoggedPercent + 10 || percent == 100) {
                                    lastLoggedPercent = percent
                                    JmxDiagnostics.i(
                                        "Download",
                                        "Download image progress",
                                        metadata = mapOf(
                                            "task_id" to albumId,
                                            "downloaded_count" to (index + 1),
                                            "total_count" to data.data.list.size,
                                            "progress_percent" to percent,
                                            "downloaded_bytes" to file.length()
                                        )
                                    )
                                }
                                file.absolutePath
                            }
                        }
                    }
                }
            }
        }
    }

    private fun zipPicPathList(albumId: Int, picPathList: List<String>): String {
        val zipFile = File(getDownloadDir(appContext), "$albumId.zip")
        JmxDiagnostics.i(
            "Download",
            "Compress downloaded images started",
            metadata = mapOf(
                "task_id" to albumId,
                "source_file_count" to picPathList.size,
                "target_path" to zipFile.name
            )
        )
        ZipOutputStream(FileOutputStream(zipFile)).use { zipOut ->
            picPathList.forEach { source ->
                val file = File(source)
                if (file.exists()) {
                    val entryName = "$albumId/${file.name}"
                    val zipEntry = ZipEntry(entryName)
                    zipOut.putNextEntry(zipEntry)
                    FileInputStream(file).use { fis ->
                        fis.copyTo(zipOut)
                    }
                    zipOut.closeEntry()
                }
            }
        }
        JmxDiagnostics.i(
            "Download",
            "Compress downloaded images completed",
            metadata = mapOf(
                "task_id" to albumId,
                "target_path" to zipFile.name,
                "final_file_size_bytes" to zipFile.length()
            )
        )
        return zipFile.absolutePath
    }

    private fun getAlbumImageListDownloadDir(albumId: Int): File {
        val dir = getDownloadDir(appContext)
        return tryCreateDir(File(dir, "$albumId"))
    }

    private fun getAlbumCoverDownloadDir(): File {
        val dir = getDownloadDir(appContext)
        return tryCreateDir(File(dir, "cover"))
    }
}

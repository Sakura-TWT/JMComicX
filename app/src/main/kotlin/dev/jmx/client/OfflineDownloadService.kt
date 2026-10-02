package dev.jmx.client

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.content.pm.PackageManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** User-initiated dataSync service. Android 15+ grants a finite daily budget; timeout means pause,
 * never START_STICKY, alarm/worker relaunch, or retrying startForeground in a loop.
 */
class OfflineDownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var manager: OfflineDownloadManager
    private var queue: Job? = null
    private var stoppedNormally = false
    private var foregroundStarted = false

    @SuppressLint("MissingPermission") // FGS start is permitted without notification grant; updates check it.
    override fun onCreate() {
        super.onCreate()
        manager = OfflineDownloadManager.get(this)
        val notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel(CHANNEL, "离线下载", NotificationManager.IMPORTANCE_LOW))
        try {
            // Must precede metadata scanning/network IO, even when POST_NOTIFICATIONS was denied.
            startForeground(NOTIFICATION_ID, notification(null), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            foregroundStarted = true
        } catch (error: RuntimeException) {
            stoppedNormally = true
            manager.interruptForServiceStop("系统暂不允许前台下载，请返回应用后继续")
            stopSelf()
            return
        }
        scope.launch {
            manager.albums.collectLatest { albums ->
                if (foregroundStarted && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                    try {
                        notifications.notify(NOTIFICATION_ID, notification(
                            albums.firstOrNull { it.status == OfflineDownloadStatus.DOWNLOADING }
                                ?: albums.firstOrNull { it.status == OfflineDownloadStatus.QUEUED },
                        ))
                    } catch (_: SecurityException) { /* Grant may be revoked between check and notify. */ }
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!foregroundStarted) return START_NOT_STICKY
        if (intent?.action == ACTION_PAUSE) {
            stoppedNormally = true
            queue?.cancel()
            manager.interruptForServiceStop("下载已暂停，可在下载管理中继续")
            finishService()
            return START_NOT_STICKY
        }
        if (queue?.isActive != true) {
            queue = scope.launch {
                try {
                    do {
                        manager.runQueue()
                        // onStartCommand may race with the last page; drain any newly queued album.
                    } while (manager.albums.value.any { it.status == OfflineDownloadStatus.QUEUED })
                    stoppedNormally = true
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    stoppedNormally = true
                    manager.interruptForServiceStop("下载服务已停止，请检查存储空间后继续")
                } finally {
                    finishService()
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        // Stop inside the platform's grace period, not after IO/export leases finish.
        stoppedNormally = true
        manager.interruptForServiceStop("前台下载已达到系统时限，请返回应用后手动继续")
        queue?.cancel()
        finishService()
    }

    override fun onDestroy() {
        if (!stoppedNormally) manager.interruptForServiceStop("下载服务已中断，请返回应用后继续")
        foregroundStarted = false
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun finishService() {
        foregroundStarted = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun notification(album: OfflineAlbum?): Notification {
        val contentIntent = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val pauseIntent = PendingIntent.getService(this, 1,
            Intent(this, OfflineDownloadService::class.java).setAction(ACTION_PAUSE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val downloaded = album?.downloadedPages ?: 0
        val total = album?.totalPages ?: 0
        val chapter = album?.currentChapterName ?: "正在读取章节信息"
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(album?.title ?: "离线下载")
            .setContentText("$chapter · $downloaded / $total 页")
            .setStyle(Notification.BigTextStyle().bigText("$chapter\n已下载 $downloaded / $total 页"))
            .setProgress(total.coerceAtLeast(1), downloaded, total == 0)
            .setContentIntent(contentIntent)
            .addAction(Notification.Action.Builder(null, "暂停下载", pauseIntent).build())
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setShowWhen(false)
            .build()
    }

    private companion object {
        const val CHANNEL = "offline_downloads"
        const val NOTIFICATION_ID = 19001
        const val ACTION_PAUSE = "dev.jmx.client.offline.PAUSE"
    }
}

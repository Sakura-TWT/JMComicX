package dev.jmx.client.store

import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.telephony.TelephonyManager
import androidx.core.content.FileProvider
import androidx.core.content.edit
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dev.jmx.client.BuildConfig
import dev.jmx.client.storage.UserStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class DiagnosticLogSession(
    val id: String,
    val startedAt: Long,
    val endedAt: Long? = null,
    val fileName: String
)

object JmxDiagnostics {
    @Volatile
    private var manager: DiagnosticLogManager? = null

    fun attach(manager: DiagnosticLogManager) {
        this.manager = manager
    }

    fun updateCurrentRoute(route: String) {
        manager?.updateCurrentRoute(route)
    }

    fun setForeground(foreground: Boolean) {
        manager?.setForeground(foreground)
    }

    fun nextRequestId(): String {
        return manager?.nextRequestId() ?: "request-${System.currentTimeMillis()}"
    }

    fun userAction(
        screen: String,
        action: String,
        target: String,
        metadata: Map<String, Any?> = emptyMap()
    ) {
        manager?.recordUserAction(screen, action, target, metadata)
    }

    fun d(category: String, message: String, metadata: Map<String, Any?> = emptyMap()) {
        manager?.write("DEBUG", category, message, metadata = metadata)
    }

    fun i(category: String, message: String, metadata: Map<String, Any?> = emptyMap()) {
        manager?.write("INFO", category, message, metadata = metadata)
    }

    fun w(
        category: String,
        message: String,
        throwable: Throwable? = null,
        metadata: Map<String, Any?> = emptyMap()
    ) {
        manager?.write("WARN", category, message, throwable, metadata)
    }

    fun e(
        category: String,
        message: String,
        throwable: Throwable? = null,
        metadata: Map<String, Any?> = emptyMap()
    ) {
        manager?.write("ERROR", category, message, throwable, metadata)
    }

    fun wtf(
        category: String,
        message: String,
        throwable: Throwable? = null,
        metadata: Map<String, Any?> = emptyMap()
    ) {
        manager?.write("WTF", category, message, throwable, metadata)
    }
}

class DiagnosticLogManager(
    private val context: Context,
    private val gson: Gson,
    private val toastManager: ToastManager,
    private val userStorage: UserStorage,
    private val appScope: CoroutineScope
) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences("jmx-diagnostic-log", Context.MODE_PRIVATE)
    private val logDir = File(appContext.filesDir, "diagnostic_logs")
    private val sharedDir = File(appContext.cacheDir, "shared_logs")
    private val sessionMetaFile = File(logDir, "sessions.json")
    private val lock = Any()
    private val processSessionId = "process-${FILE_TIME_FORMAT.format(Date())}-${UUID.randomUUID().toString().take(8)}"
    private val requestCounter = AtomicLong(0)
    private val recentUserActions = ArrayDeque<String>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var previousExceptionHandler: Thread.UncaughtExceptionHandler? = null
    private var currentRoute: String = "unknown"
    private var foreground = false
    private var lastNetworkType: String = "UNKNOWN"
    private var powerReceiver: BroadcastReceiver? = null

    private val _enabled = MutableStateFlow(preferences.getBoolean(KEY_ENABLED, false))
    val enabled = _enabled.asStateFlow()

    private val _sessions = MutableStateFlow<List<DiagnosticLogSession>>(emptyList())
    val sessions = _sessions.asStateFlow()

    fun initialize() {
        ensureDirs()
        cleanSharedArchives(maxAgeMs = DELETE_ALL_SHARED_ARCHIVES)
        JmxDiagnostics.attach(this)
        _sessions.value = loadSessions()
        lastNetworkType = networkType()
        installCrashHandler()
        installMainThreadWatchdog()
        registerNetworkMonitor()
        logPowerSnapshot("diagnostics_initialize")
        registerPowerMonitor()

        if (_enabled.value) {
            val activeId = preferences.getString(KEY_ACTIVE_SESSION_ID, null)
            val activeSession = _sessions.value.firstOrNull { it.id == activeId && it.endedAt == null }
            if (activeSession == null) {
                startSession(startReason = "process_restore_without_active_file")
            } else {
                write(
                    "WARN",
                    "Diagnostics",
                    "Previous diagnostic session did not close cleanly; process restored and continues logging",
                    metadata = mapOf("previous_log_session_id" to activeSession.id)
                )
            }
        }
    }

    fun setEnabled(value: Boolean) {
        if (value == _enabled.value) {
            return
        }
        if (value) {
            startSession(startReason = "user_enabled")
            write("INFO", "Diagnostics", "Diagnostic log output enabled by user")
            logPowerSnapshot("user_enabled")
        } else {
            write("INFO", "Diagnostics", "Diagnostic log output disabled by user")
            stopSession()
        }
    }

    fun setForeground(value: Boolean) {
        foreground = value
        write(
            "INFO",
            "Lifecycle",
            if (value) "Application moved to foreground" else "Application moved to background",
            metadata = mapOf("foreground" to value)
        )
    }

    fun updateCurrentRoute(route: String) {
        if (route.isBlank()) return
        currentRoute = route
    }

    fun nextRequestId(): String {
        return "$processSessionId-http-${requestCounter.incrementAndGet()}"
    }

    fun recordUserAction(
        screen: String,
        action: String,
        target: String,
        metadata: Map<String, Any?> = emptyMap()
    ) {
        val actionSummary = "${ISO_FORMAT.format(Date())}|screen=$screen|action=$action|target=$target"
        synchronized(lock) {
            recentUserActions.addLast(actionSummary)
            while (recentUserActions.size > RECENT_ACTION_LIMIT) {
                recentUserActions.removeFirst()
            }
        }
        write(
            "INFO",
            "UserAction",
            "User action: $action on $target",
            metadata = metadata + mapOf(
                "screen" to screen,
                "action" to action,
                "target" to target
            )
        )
    }

    fun write(
        level: String,
        category: String,
        message: String,
        throwable: Throwable? = null,
        metadata: Map<String, Any?> = emptyMap()
    ) {
        if (!shouldWrite(level)) {
            return
        }
        appendLine(formatLine(level, category, message, throwable, metadata))
    }

    fun shareAllLogs(context: Context) {
        shareSessions(context, _sessions.value, "jmx-all-logs")
    }

    fun shareSessions(context: Context, targetSessions: List<DiagnosticLogSession>, label: String = "jmx-logs") {
        val files = targetSessions.mapNotNull { session ->
            File(logDir, session.fileName).takeIf { it.exists() }
        }
        if (files.isEmpty()) {
            toastManager.showAsync("没有可分享的日志")
            return
        }
        cleanSharedArchives(maxAgeMs = DELETE_ALL_SHARED_ARCHIVES)
        val zipFile = createZip(label, files)
        write(
            "INFO",
            "Diagnostics",
            "Share diagnostic log archive",
            metadata = mapOf(
                "archive_name" to zipFile.name,
                "archive_size_bytes" to zipFile.length(),
                "source_file_count" to files.size
            )
        )
        shareZip(context, zipFile)
        scheduleSharedArchiveDelete(zipFile)
    }

    fun deleteSessions(targetSessions: List<DiagnosticLogSession>) {
        if (targetSessions.isEmpty()) {
            return
        }
        synchronized(lock) {
            val targetIds = targetSessions.map { it.id }.toSet()
            val activeId = preferences.getString(KEY_ACTIVE_SESSION_ID, null)
            val deletingActiveSession = activeId in targetIds
            if (!deletingActiveSession) {
                write(
                    "INFO",
                    "Diagnostics",
                    "Delete diagnostic log sessions",
                    metadata = mapOf("delete_count" to targetSessions.size)
                )
            }
            targetSessions.forEach { session ->
                File(logDir, session.fileName).delete()
            }
            val nextSessions = _sessions.value.filterNot { it.id in targetIds }
            _sessions.value = nextSessions
            saveSessions(nextSessions)
            if (deletingActiveSession) {
                preferences.edit {
                    putBoolean(KEY_ENABLED, false)
                    remove(KEY_ACTIVE_SESSION_ID)
                }
                _enabled.value = false
            }
        }
    }

    fun formatSessionTitle(session: DiagnosticLogSession): String {
        val start = SESSION_TITLE_FORMAT.format(Date(session.startedAt))
        val end = session.endedAt?.let { SESSION_TIME_FORMAT.format(Date(it)) } ?: "进行中"
        return "日志 $start—$end"
    }

    fun formatSessionSubtitle(session: DiagnosticLogSession): String {
        val file = File(logDir, session.fileName)
        val sizeKb = (file.length() / 1024f).coerceAtLeast(0f)
        return "${session.fileName} · ${"%.1f".format(Locale.US, sizeKb)} KB"
    }

    private fun startSession(startReason: String) {
        synchronized(lock) {
            ensureDirs()
            val now = System.currentTimeMillis()
            val id = FILE_TIME_FORMAT.format(Date(now))
            val session = DiagnosticLogSession(
                id = id,
                startedAt = now,
                fileName = "jmx-log-$id.log"
            )
            val nextSessions = listOf(session) + _sessions.value
            _sessions.value = nextSessions
            saveSessions(nextSessions)
            preferences.edit {
                putBoolean(KEY_ENABLED, true)
                putString(KEY_ACTIVE_SESSION_ID, session.id)
            }
            _enabled.value = true
            appendLineBlocking(
                """
                |================================================================================
                |JMX DIAGNOSTIC LOG SESSION START
                |log_session_id=${session.id}
                |process_session_id=$processSessionId
                |start_reason=$startReason
                |started_at=${ISO_FORMAT.format(Date(session.startedAt))}
                |app_version=${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})
                |build=${buildId()}
                |device=${Build.MANUFACTURER} ${Build.MODEL}
                |android=${Build.VERSION.RELEASE} api=${Build.VERSION.SDK_INT}
                |network=${networkType()}
                |================================================================================
                """.trimMargin()
            )
        }
    }

    private fun stopSession() {
        synchronized(lock) {
            val activeId = preferences.getString(KEY_ACTIVE_SESSION_ID, null)
            val now = System.currentTimeMillis()
            appendLineBlocking(
                """
                |================================================================================
                |JMX DIAGNOSTIC LOG SESSION END
                |log_session_id=${activeId.orEmpty()}
                |process_session_id=$processSessionId
                |ended_at=${ISO_FORMAT.format(Date(now))}
                |================================================================================
                """.trimMargin()
            )
            val nextSessions = _sessions.value.map { session ->
                if (session.id == activeId && session.endedAt == null) {
                    session.copy(endedAt = now)
                } else {
                    session
                }
            }
            _sessions.value = nextSessions
            saveSessions(nextSessions)
            preferences.edit {
                putBoolean(KEY_ENABLED, false)
                remove(KEY_ACTIVE_SESSION_ID)
            }
            _enabled.value = false
        }
    }

    private fun installCrashHandler() {
        if (previousExceptionHandler != null) {
            return
        }
        previousExceptionHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            synchronized(lock) {
                if (!_enabled.value) {
                    startSession(startReason = "uncaught_exception")
                }
                appendLineBlocking(
                    formatLine(
                        level = "ERROR",
                        category = "Crash",
                        message = "Uncaught exception on ${thread.name}",
                        throwable = throwable,
                        metadata = mapOf(
                            "crash_thread" to thread.name,
                            "last_route" to currentRoute,
                            "recent_user_actions" to recentActionsSnapshot().joinToString(" || ")
                        )
                    )
                )
            }
            previousExceptionHandler?.uncaughtException(thread, throwable)
        }
    }

    private fun installMainThreadWatchdog() {
        mainHandler.post(object : Runnable {
            private var lastTick = System.currentTimeMillis()

            override fun run() {
                val now = System.currentTimeMillis()
                val delayMs = now - lastTick - MAIN_THREAD_WATCHDOG_INTERVAL_MS
                if (delayMs > MAIN_THREAD_WARN_MS) {
                    val stack = Looper.getMainLooper().thread.stackTrace.joinToString("\n") { "    at $it" }
                    write(
                        if (delayMs > MAIN_THREAD_ERROR_MS) "ERROR" else "WARN",
                        "Performance",
                        "Main thread message processing is slow",
                        metadata = mapOf(
                            "block_ms" to delayMs,
                            "main_thread_stack" to stack
                        )
                    )
                }
                lastTick = now
                mainHandler.postDelayed(this, MAIN_THREAD_WATCHDOG_INTERVAL_MS)
            }
        })
    }

    private fun registerNetworkMonitor() {
        val connectivityManager = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return
        runCatching {
            connectivityManager.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    logNetworkChange("available")
                }

                override fun onLost(network: Network) {
                    logNetworkChange("lost")
                }

                override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                    logNetworkChange("capabilities_changed")
                }
            })
        }.onFailure {
            write("WARN", "SystemState", "Network callback registration failed", it)
        }
    }

    private fun registerPowerMonitor() {
        if (powerReceiver != null) {
            return
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_LOW)
            addAction(Intent.ACTION_BATTERY_OKAY)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
            }
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                logPowerSnapshot("power_broadcast", intent?.action.orEmpty())
            }
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                appContext.registerReceiver(receiver, filter)
            }
            powerReceiver = receiver
        }.onFailure {
            powerReceiver = null
            write("WARN", "SystemState", "Power monitor snapshot failed", it)
        }
    }

    private fun logPowerSnapshot(reason: String, action: String = "") {
        val batteryIntent = appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val percent = if (level >= 0 && scale > 0) level * 100 / scale else -1
        val plugged = batteryIntent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val powerManager = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val powerSaveMode = powerManager?.isPowerSaveMode ?: false
        val levelName = if (percent in 0..15 || powerSaveMode) "WARN" else "INFO"
        write(
            levelName,
            "SystemState",
            "Power state snapshot",
            metadata = mapOf(
                "reason" to reason,
                "action" to action,
                "battery_percent" to percent,
                "plugged" to (plugged != 0),
                "power_save_mode" to powerSaveMode
            )
        )
    }

    private fun logNetworkChange(reason: String) {
        val nextType = networkType()
        if (nextType != lastNetworkType) {
            val previous = lastNetworkType
            lastNetworkType = nextType
            write(
                "INFO",
                "SystemState",
                "Network state changed",
                metadata = mapOf(
                    "reason" to reason,
                    "from" to previous,
                    "to" to nextType
                )
            )
        }
    }

    private fun createZip(label: String, files: List<File>): File {
        ensureDirs()
        val zipFile = File(sharedDir, "$label-${FILE_TIME_FORMAT.format(Date())}.zip")
        ZipOutputStream(FileOutputStream(zipFile)).use { zipOut ->
            val manifest = buildString {
                appendLine("JMX diagnostic log archive")
                appendLine("created_at=${ISO_FORMAT.format(Date())}")
                appendLine("process_session_id=$processSessionId")
                appendLine("app_version=${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                appendLine("build=${buildId()}")
                appendLine("file_count=${files.size}")
                files.forEach { file ->
                    appendLine("file=${file.name}, bytes=${file.length()}")
                }
            }
            zipOut.putNextEntry(ZipEntry("manifest.txt"))
            zipOut.write(manifest.toByteArray(Charsets.UTF_8))
            zipOut.closeEntry()
            files.forEach { file ->
                zipOut.putNextEntry(ZipEntry(file.name))
                FileInputStream(file).use { input -> input.copyTo(zipOut) }
                zipOut.closeEntry()
            }
        }
        return zipFile
    }

    private fun shareZip(context: Context, zipFile: File) {
        val uri: Uri = FileProvider.getUriForFile(
            appContext,
            "${BuildConfig.APPLICATION_ID}.fileprovider",
            zipFile
        )
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "JMX 运行日志")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(shareIntent, "分享 JMX 运行日志").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching {
            val startIntent = if (context === appContext) {
                chooser
            } else {
                chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(startIntent)
            toastManager.showAsync("日志压缩包已交给系统分享")
        }.onFailure {
            toastManager.showAsync("无法打开系统分享")
            write("ERROR", "Diagnostics", "Open share sheet failed", it)
        }
    }

    private fun scheduleSharedArchiveDelete(zipFile: File) {
        appScope.launch(Dispatchers.IO) {
            delay(SHARED_ARCHIVE_TTL_MS)
            val deleted = zipFile.delete()
            write(
                "DEBUG",
                "Diagnostics",
                "Temporary shared log archive cleanup finished",
                metadata = mapOf(
                    "archive_name" to zipFile.name,
                    "deleted" to deleted
                )
            )
        }
    }

    private fun cleanSharedArchives(maxAgeMs: Long) {
        ensureDirs()
        val now = System.currentTimeMillis()
        sharedDir
            .listFiles { file -> file.isFile && file.extension.equals("zip", ignoreCase = true) }
            .orEmpty()
            .forEach { file ->
                if (maxAgeMs <= 0 || now - file.lastModified() > maxAgeMs) {
                    file.delete()
                }
            }
    }

    private fun appendLine(line: String) {
        synchronized(lock) {
            appendLineBlocking(line)
        }
    }

    private fun appendLineBlocking(line: String) {
        val activeId = preferences.getString(KEY_ACTIVE_SESSION_ID, null) ?: return
        val session = _sessions.value.firstOrNull { it.id == activeId } ?: return
        val file = File(logDir, session.fileName)
        file.appendText(line + "\n", Charsets.UTF_8)
    }

    private fun formatLine(
        level: String,
        category: String,
        message: String,
        throwable: Throwable?,
        metadata: Map<String, Any?>
    ): String {
        val values = linkedMapOf<String, Any?>(
            "timestamp" to ISO_FORMAT.format(Date()),
            "level" to level,
            "tag" to category,
            "process_session_id" to processSessionId,
            "build" to buildId(),
            "app_version" to "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            "user_id" to userId(),
            "pid" to android.os.Process.myPid(),
            "thread" to Thread.currentThread().name,
            "network" to networkType(),
            "memory_mb" to "%.1f".format(Locale.US, usedMemoryMb()),
            "app_state" to if (foreground) "foreground" else "background",
            "route" to currentRoute,
            "message" to message
        )
        metadata.forEach { (key, value) ->
            values[key] = value
        }
        if (throwable != null) {
            values["exception_class"] = throwable::class.java.name
            values["exception_message"] = throwable.message.orEmpty()
            values["stacktrace"] = throwable.stackTraceToString()
        }
        return values.entries.joinToString(" ") { (key, value) -> "$key=${encodeValue(value)}" }
    }

    private fun shouldWrite(level: String): Boolean {
        if (!_enabled.value) {
            return false
        }
        return when (level.uppercase(Locale.US)) {
            "VERBOSE" -> false
            "DEBUG" -> BuildConfig.DEBUG || preferences.getBoolean(KEY_DEBUG_ENABLED, false)
            else -> true
        }
    }

    private fun encodeValue(value: Any?): String {
        val raw = when (value) {
            null -> "null"
            is Boolean, is Number -> value.toString()
            else -> value.toString()
        }
        val escaped = raw
            .replace("\\", "\\\\")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\"", "\\\"")
        return if (escaped.matches(PLAIN_VALUE_REGEX)) escaped else "\"$escaped\""
    }

    private fun userId(): String {
        return runCatching {
            val user = userStorage.get()
            if (user.id > 0) "user_${user.id}" else "anonymous"
        }.getOrDefault("anonymous")
    }

    private fun buildId(): String {
        return "${BuildConfig.BUILD_TYPE}/${BuildConfig.VERSION_CODE}/${BuildConfig.APPLICATION_ID}"
    }

    private fun usedMemoryMb(): Double {
        val runtime = Runtime.getRuntime()
        return (runtime.totalMemory() - runtime.freeMemory()).toDouble() / 1024.0 / 1024.0
    }

    private fun networkType(): String {
        val connectivityManager = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return "UNKNOWN"
        val activeNetwork = connectivityManager.activeNetwork ?: return "NONE"
        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return "UNKNOWN"
        return when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WIFI"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> cellularNetworkType()
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ETHERNET"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
            else -> "UNKNOWN"
        }
    }

    private fun cellularNetworkType(): String {
        val telephonyManager = appContext.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            ?: return "CELLULAR"
        val type = runCatching { telephonyManager.dataNetworkType }.getOrDefault(TelephonyManager.NETWORK_TYPE_UNKNOWN)
        return when (type) {
            TelephonyManager.NETWORK_TYPE_NR -> "CELLULAR_5G"
            TelephonyManager.NETWORK_TYPE_LTE,
            TelephonyManager.NETWORK_TYPE_IWLAN -> "CELLULAR_4G"
            TelephonyManager.NETWORK_TYPE_UMTS,
            TelephonyManager.NETWORK_TYPE_HSDPA,
            TelephonyManager.NETWORK_TYPE_HSUPA,
            TelephonyManager.NETWORK_TYPE_HSPA,
            TelephonyManager.NETWORK_TYPE_HSPAP,
            TelephonyManager.NETWORK_TYPE_EVDO_0,
            TelephonyManager.NETWORK_TYPE_EVDO_A,
            TelephonyManager.NETWORK_TYPE_EVDO_B,
            TelephonyManager.NETWORK_TYPE_EHRPD -> "CELLULAR_3G"
            TelephonyManager.NETWORK_TYPE_GPRS,
            TelephonyManager.NETWORK_TYPE_EDGE,
            TelephonyManager.NETWORK_TYPE_CDMA,
            TelephonyManager.NETWORK_TYPE_1xRTT,
            TelephonyManager.NETWORK_TYPE_IDEN -> "CELLULAR_2G"
            else -> "CELLULAR"
        }
    }

    private fun recentActionsSnapshot(): List<String> {
        return synchronized(lock) { recentUserActions.toList() }
    }

    private fun ensureDirs() {
        logDir.mkdirs()
        sharedDir.mkdirs()
    }

    private fun loadSessions(): List<DiagnosticLogSession> {
        if (!sessionMetaFile.exists()) {
            return emptyList()
        }
        return runCatching {
            val type = object : TypeToken<List<DiagnosticLogSession>>() {}.type
            gson.fromJson<List<DiagnosticLogSession>>(sessionMetaFile.readText(Charsets.UTF_8), type)
        }.getOrDefault(emptyList())
    }

    private fun saveSessions(sessions: List<DiagnosticLogSession>) {
        ensureDirs()
        sessionMetaFile.writeText(gson.toJson(sessions), Charsets.UTF_8)
    }

    private companion object {
        const val KEY_ENABLED = "enabled"
        const val KEY_ACTIVE_SESSION_ID = "active_session_id"
        const val KEY_DEBUG_ENABLED = "debug_enabled"
        const val SHARED_ARCHIVE_TTL_MS = 10L * 60L * 1000L
        const val DELETE_ALL_SHARED_ARCHIVES = 0L
        const val MAIN_THREAD_WATCHDOG_INTERVAL_MS = 1_000L
        const val MAIN_THREAD_WARN_MS = 200L
        const val MAIN_THREAD_ERROR_MS = 1_000L
        const val RECENT_ACTION_LIMIT = 5
        val FILE_TIME_FORMAT = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US)
        val ISO_FORMAT = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US)
        val SESSION_TITLE_FORMAT = SimpleDateFormat("yyyy.M.d HH:mm", Locale.CHINA)
        val SESSION_TIME_FORMAT = SimpleDateFormat("HH:mm", Locale.CHINA)
        val PLAIN_VALUE_REGEX = Regex("[A-Za-z0-9_./:@+\\-]+")
    }
}

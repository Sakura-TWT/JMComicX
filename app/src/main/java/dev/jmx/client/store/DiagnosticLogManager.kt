package dev.jmx.client.store

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import androidx.core.content.edit
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dev.jmx.client.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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

    fun d(category: String, message: String) {
        manager?.write("DEBUG", category, message)
    }

    fun i(category: String, message: String) {
        manager?.write("INFO", category, message)
    }

    fun w(category: String, message: String, throwable: Throwable? = null) {
        manager?.write("WARN", category, message, throwable)
    }

    fun e(category: String, message: String, throwable: Throwable? = null) {
        manager?.write("ERROR", category, message, throwable)
    }
}

class DiagnosticLogManager(
    private val context: Context,
    private val gson: Gson,
    private val toastManager: ToastManager
) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences("jmx-diagnostic-log", Context.MODE_PRIVATE)
    private val logDir = File(appContext.filesDir, "diagnostic_logs")
    private val sharedDir = File(appContext.cacheDir, "shared_logs")
    private val sessionMetaFile = File(logDir, "sessions.json")
    private val lock = Any()
    private var previousExceptionHandler: Thread.UncaughtExceptionHandler? = null

    private val _enabled = MutableStateFlow(preferences.getBoolean(KEY_ENABLED, false))
    val enabled = _enabled.asStateFlow()

    private val _sessions = MutableStateFlow<List<DiagnosticLogSession>>(emptyList())
    val sessions = _sessions.asStateFlow()

    fun initialize() {
        ensureDirs()
        JmxDiagnostics.attach(this)
        _sessions.value = loadSessions()
        installCrashHandler()
        if (_enabled.value) {
            val activeId = preferences.getString(KEY_ACTIVE_SESSION_ID, null)
            val activeSession = _sessions.value.firstOrNull { it.id == activeId && it.endedAt == null }
            if (activeSession == null) {
                startSession()
            } else {
                write("INFO", "Diagnostics", "Process resumed with active diagnostic log session: ${activeSession.id}")
            }
        }
    }

    fun setEnabled(value: Boolean) {
        if (value == _enabled.value) {
            return
        }
        if (value) {
            startSession()
            write("INFO", "Diagnostics", "Diagnostic log output enabled by user")
        } else {
            write("INFO", "Diagnostics", "Diagnostic log output disabled by user")
            stopSession()
        }
    }

    fun write(level: String, category: String, message: String, throwable: Throwable? = null) {
        if (!_enabled.value) {
            return
        }
        appendLine(formatLine(level, category, message, throwable))
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
        val zipFile = createZip(label, files)
        write("INFO", "Diagnostics", "Share log archive label=$label files=${files.size}")
        shareZip(context, zipFile)
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
                write("INFO", "Diagnostics", "Delete log sessions count=${targetSessions.size}")
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

    private fun startSession() {
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
                |session_id=${session.id}
                |started_at=${ISO_FORMAT.format(Date(session.startedAt))}
                |app_version=${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})
                |build_type=${BuildConfig.BUILD_TYPE}
                |device=${Build.MANUFACTURER} ${Build.MODEL}
                |android=${Build.VERSION.RELEASE} api=${Build.VERSION.SDK_INT}
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
                |session_id=${activeId.orEmpty()}
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
                    startSession()
                }
                appendLineBlocking(formatLine("FATAL", "Crash", "Uncaught exception on ${thread.name}", throwable))
            }
            previousExceptionHandler?.uncaughtException(thread, throwable)
        }
    }

    private fun createZip(label: String, files: List<File>): File {
        ensureDirs()
        val zipFile = File(sharedDir, "$label-${FILE_TIME_FORMAT.format(Date())}.zip")
        ZipOutputStream(FileOutputStream(zipFile)).use { zipOut ->
            val manifest = buildString {
                appendLine("JMX diagnostic log archive")
                appendLine("created_at=${ISO_FORMAT.format(Date())}")
                appendLine("app_version=${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                appendLine("file_count=${files.size}")
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

    private fun formatLine(level: String, category: String, message: String, throwable: Throwable?): String {
        return buildString {
            append(ISO_FORMAT.format(Date()))
            append(" | ")
            append(level.padEnd(5))
            append(" | ")
            append(category.take(40).padEnd(40))
            append(" | ")
            append(message)
            if (throwable != null) {
                appendLine()
                append(throwable.stackTraceToString())
            }
        }
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
        val FILE_TIME_FORMAT = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US)
        val ISO_FORMAT = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.US)
        val SESSION_TITLE_FORMAT = SimpleDateFormat("yyyy.M.d HH:mm", Locale.CHINA)
        val SESSION_TIME_FORMAT = SimpleDateFormat("HH:mm", Locale.CHINA)
    }
}

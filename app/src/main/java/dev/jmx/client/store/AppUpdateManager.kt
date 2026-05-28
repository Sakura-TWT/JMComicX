package dev.jmx.client.store

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import dev.jmx.client.BuildConfig
import dev.jmx.client.storage.UpdatePreferenceStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Locale
import java.util.concurrent.TimeUnit

data class AppUpdateUiState(
    val checking: Boolean = false,
    val dialogInfo: UpdateInfo? = null
)

data class UpdateInfo(
    val versionName: String,
    val title: String,
    val body: String,
    val releaseUrl: String,
    val downloadUrl: String?,
    val forceUpdate: Boolean
)

class AppUpdateManager(
    private val preferenceStorage: UpdatePreferenceStorage,
    private val gson: Gson,
    private val toastManager: ToastManager
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.SECONDS)
        .build()

    private val _uiState = MutableStateFlow(AppUpdateUiState())
    val uiState = _uiState.asStateFlow()

    val autoCheckEnabled = preferenceStorage.autoCheckEnabled

    suspend fun checkForUpdate(showResultToast: Boolean, fromStartup: Boolean = false) {
        if (fromStartup && !autoCheckEnabled.value) {
            JmxDiagnostics.i(
                "Update",
                "Startup update check skipped because auto check is disabled",
                metadata = mapOf("from_startup" to fromStartup)
            )
            return
        }

        JmxDiagnostics.i(
            "Update",
            "Update check started",
            metadata = mapOf(
                "show_result_toast" to showResultToast,
                "from_startup" to fromStartup,
                "local_version" to BuildConfig.VERSION_NAME
            )
        )
        _uiState.value = _uiState.value.copy(checking = true)
        val latestResult = runCatching { fetchLatestRelease() }
        _uiState.value = _uiState.value.copy(checking = false)
        val latest = latestResult.getOrNull()

        when {
            latestResult.isFailure -> {
                JmxDiagnostics.e(
                    "Update",
                    "Update check failed with exception",
                    latestResult.exceptionOrNull(),
                    metadata = mapOf("from_startup" to fromStartup)
                )
                if (showResultToast) {
                    toastManager.showAsync("检查更新失败，可能需要稍后重试或开启网络代理")
                }
            }

            latest == null -> {
                JmxDiagnostics.w(
                    "Update",
                    "Update check finished without available release",
                    metadata = mapOf("from_startup" to fromStartup)
                )
                if (showResultToast) {
                    toastManager.showAsync("检查更新失败，可能需要稍后重试或开启网络代理")
                }
            }

            isNewerVersion(latest.versionName, BuildConfig.VERSION_NAME) -> {
                JmxDiagnostics.i(
                    "Update",
                    "New version found",
                    metadata = mapOf(
                        "remote_version" to latest.versionName,
                        "local_version" to BuildConfig.VERSION_NAME,
                        "force_update" to latest.forceUpdate,
                        "download_url" to latest.downloadUrl.orEmpty(),
                        "release_url" to latest.releaseUrl,
                        "release_body_length" to latest.body.length
                    )
                )
                _uiState.value = _uiState.value.copy(dialogInfo = latest)
            }

            showResultToast -> {
                JmxDiagnostics.i(
                    "Update",
                    "Already latest version",
                    metadata = mapOf("local_version" to BuildConfig.VERSION_NAME)
                )
                toastManager.showAsync("当前已是最新版本")
            }
        }
    }

    fun dismissDialog() {
        _uiState.value = _uiState.value.copy(dialogInfo = null)
    }

    fun setAutoCheckEnabled(enabled: Boolean) {
        JmxDiagnostics.userAction(
            screen = "About",
            action = "toggle",
            target = "auto_update_check",
            metadata = mapOf("enabled" to enabled)
        )
        preferenceStorage.setAutoCheckEnabled(enabled)
        toastManager.showAsync(if (enabled) "已开启自动更新检测" else "已关闭自动更新提示")
    }

    fun disableAutoPrompt() {
        setAutoCheckEnabled(false)
        dismissDialog()
    }

    private suspend fun fetchLatestRelease(): UpdateInfo? = withContext(Dispatchers.IO) {
        for ((index, endpoint) in RELEASE_ENDPOINTS.withIndex()) {
            JmxDiagnostics.i(
                "Update",
                "Fetch latest release endpoint started",
                metadata = mapOf("endpoint_index" to index, "endpoint" to endpoint)
            )
            val response = runCatching {
                client.newCall(
                    Request.Builder()
                        .url(endpoint)
                        .header("Accept", "application/vnd.github+json")
                        .header("User-Agent", "JMX/${BuildConfig.VERSION_NAME}")
                        .build()
                ).execute()
            }.onFailure {
                JmxDiagnostics.e(
                    "Update",
                    "Fetch latest release endpoint failed",
                    it,
                    metadata = mapOf("endpoint_index" to index, "endpoint" to endpoint)
                )
            }.getOrNull() ?: continue

            response.use {
                if (!it.isSuccessful) {
                    JmxDiagnostics.w(
                        "Update",
                        "Fetch latest release endpoint returned non-success",
                        metadata = mapOf(
                            "endpoint_index" to index,
                            "endpoint" to endpoint,
                            "status_code" to it.code
                        )
                    )
                    return@use
                }

                val payload = it.body?.string().orEmpty()
                val release = runCatching {
                    gson.fromJson(payload, GitHubReleaseResponse::class.java)
                }.onFailure { throwable ->
                    JmxDiagnostics.e(
                        "Update",
                        "Parse latest release response failed",
                        throwable,
                        metadata = mapOf("endpoint_index" to index, "payload_length" to payload.length)
                    )
                }.getOrNull() ?: return@use

                if (release.draft == true || release.prerelease == true) {
                    JmxDiagnostics.i(
                        "Update",
                        "Latest release ignored because it is draft or prerelease",
                        metadata = mapOf(
                            "draft" to release.draft,
                            "prerelease" to release.prerelease,
                            "tag_name" to release.tagName.orEmpty()
                        )
                    )
                    return@withContext null
                }

                val version = release.tagName?.normalizeVersion().orEmpty()
                if (version.isBlank()) {
                    return@use
                }

                val apkAsset = release.assets
                    .orEmpty()
                    .filter { it.name.orEmpty().endsWith(".apk", ignoreCase = true) }
                    .maxByOrNull { it.size ?: 0L }

                return@withContext UpdateInfo(
                    versionName = version,
                    title = release.name?.takeIf { title -> title.isNotBlank() } ?: "JMX v$version",
                    body = release.body.orEmpty().trim(),
                    releaseUrl = release.htmlUrl.orEmpty().ifBlank {
                        "https://github.com/Sakura-TWT/JMComicX/releases/tag/v$version"
                    },
                    downloadUrl = apkAsset?.browserDownloadUrl,
                    forceUpdate = release.body.orEmpty().isForceUpdateBody()
                )
            }
        }
        null
    }

    private fun isNewerVersion(remote: String, local: String): Boolean {
        val remoteParts = remote.normalizeVersion().split(".").map { it.toIntOrNull() ?: 0 }
        val localParts = local.normalizeVersion().split(".").map { it.toIntOrNull() ?: 0 }
        val maxSize = maxOf(remoteParts.size, localParts.size)

        for (index in 0 until maxSize) {
            val remoteValue = remoteParts.getOrElse(index) { 0 }
            val localValue = localParts.getOrElse(index) { 0 }
            if (remoteValue != localValue) {
                return remoteValue > localValue
            }
        }
        return false
    }

    private fun String.normalizeVersion(): String {
        return trim()
            .removePrefix("v")
            .removePrefix("V")
            .substringBefore("-")
            .substringBefore("+")
    }

    private fun String.isForceUpdateBody(): Boolean {
        val text = lowercase(Locale.ROOT)
        return "[force]" in text ||
            "critical" in text ||
            "强制更新" in this ||
            "严重" in this ||
            "重大" in this ||
            "必须更新" in this
    }

    private data class GitHubReleaseResponse(
        @SerializedName("tag_name") val tagName: String?,
        @SerializedName("name") val name: String?,
        @SerializedName("body") val body: String?,
        @SerializedName("html_url") val htmlUrl: String?,
        @SerializedName("draft") val draft: Boolean?,
        @SerializedName("prerelease") val prerelease: Boolean?,
        @SerializedName("assets") val assets: List<GitHubReleaseAsset>?
    )

    private data class GitHubReleaseAsset(
        @SerializedName("name") val name: String?,
        @SerializedName("browser_download_url") val browserDownloadUrl: String?,
        @SerializedName("size") val size: Long?
    )

    private companion object {
        val RELEASE_ENDPOINTS = listOf(
            "https://api.github.com/repos/Sakura-TWT/JMComicX/releases/latest",
            "https://gh.llkk.cc/https://api.github.com/repos/Sakura-TWT/JMComicX/releases/latest",
            "https://ghproxy.net/https://api.github.com/repos/Sakura-TWT/JMComicX/releases/latest"
        )
    }
}

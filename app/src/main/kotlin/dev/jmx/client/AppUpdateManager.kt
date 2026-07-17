package dev.jmx.client

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import dev.jmx.client.core.network.defaultOkHttpClient
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request

internal data class AppUpdateInfo(
    val versionName: String,
    val title: String,
    val body: String,
    val releaseUrl: String,
    val downloadUrls: List<String>,
)

internal data class AppUpdateState(
    val checking: Boolean = false,
    val launchingDownload: Boolean = false,
    val autoCheckEnabled: Boolean = true,
    val availableUpdate: AppUpdateInfo? = null,
    val resultMessage: String? = null,
)

internal class AppUpdateManager(
    context: Context,
    private val gson: Gson = Gson(),
    private val client: OkHttpClient = defaultOkHttpClient().newBuilder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.SECONDS)
        .build(),
) {
    private val applicationContext = context.applicationContext
    private val preferences = applicationContext.getSharedPreferences(
        UPDATE_PREFERENCES,
        Context.MODE_PRIVATE,
    )

    val localVersionName: String = runCatching {
        applicationContext.packageManager
            .getPackageInfo(applicationContext.packageName, 0)
            .versionName
    }.getOrNull().orEmpty().ifBlank { "0.0.0" }

    private val _state = kotlinx.coroutines.flow.MutableStateFlow(
        AppUpdateState(autoCheckEnabled = preferences.getBoolean(AUTO_CHECK_KEY, true)),
    )
    val state = _state.asStateFlow()

    fun setAutoCheckEnabled(enabled: Boolean) {
        preferences.edit { putBoolean(AUTO_CHECK_KEY, enabled) }
        _state.value = _state.value.copy(autoCheckEnabled = enabled)
    }

    suspend fun checkForUpdates(manual: Boolean, fromStartup: Boolean = false) {
        if (_state.value.checking || (fromStartup && !_state.value.autoCheckEnabled)) return
        Log.i(UPDATE_LOG_TAG, "check started; startup=$fromStartup local=$localVersionName")
        _state.value = _state.value.copy(checking = true, resultMessage = null)

        val latest = runCatching { fetchLatestRelease() }.getOrNull()
        Log.i(UPDATE_LOG_TAG, "check finished; remote=${latest?.versionName ?: "none"}")
        _state.value = when {
            latest == null -> _state.value.copy(
                checking = false,
                resultMessage = if (manual) "检查更新失败，请检查网络后重试。" else null,
            )
            isVersionNewer(latest.versionName, localVersionName) -> _state.value.copy(
                checking = false,
                availableUpdate = latest,
            )
            else -> _state.value.copy(
                checking = false,
                availableUpdate = null,
                resultMessage = if (manual) "当前已是最新版本。" else null,
            )
        }
    }

    fun dismissUpdate() {
        _state.value = _state.value.copy(availableUpdate = null, launchingDownload = false)
    }

    fun dismissResultMessage() {
        _state.value = _state.value.copy(resultMessage = null)
    }

    fun reportDownloadLaunchFailure() {
        _state.value = _state.value.copy(
            launchingDownload = false,
            resultMessage = "无法打开下载链接，请稍后重试或前往项目发布页下载。",
        )
    }

    suspend fun resolveDownloadUrl(info: AppUpdateInfo): String {
        if (info.downloadUrls.isEmpty()) return info.releaseUrl
        _state.value = _state.value.copy(launchingDownload = true)
        val resolved = fastestReachableUrl(info.downloadUrls) ?: info.downloadUrls.first()
        _state.value = _state.value.copy(launchingDownload = false)
        return resolved
    }

    private suspend fun fetchLatestRelease(): AppUpdateInfo? {
        val apiResult = firstSuccessful(RELEASE_API_ENDPOINTS) { fetchApiRelease(it) }
        return apiResult ?: fetchGitHubHtmlFallback()
    }

    private suspend fun fetchApiRelease(endpoint: String): AppUpdateInfo? = withContext(Dispatchers.IO) {
        val response = client.newCall(
            Request.Builder()
                .url(endpoint)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", userAgent())
                .build(),
        ).execute()
        response.use {
            if (!it.isSuccessful) {
                Log.w(UPDATE_LOG_TAG, "release endpoint failed: ${it.code} $endpoint")
                return@withContext null
            }
            val release = runCatching {
                gson.fromJson(it.body.string(), GitHubRelease::class.java)
            }.onFailure { error ->
                Log.w(UPDATE_LOG_TAG, "release payload parse failed: $endpoint", error)
            }.getOrNull() ?: return@withContext null
            if (release.draft == true || release.prerelease == true) return@withContext null
            val version = release.tagName.normalizeVersion().takeIf(String::isNotBlank)
                ?: return@withContext null
            val asset = release.assets.orEmpty()
                .filter { item -> item.name.orEmpty().endsWith(".apk", ignoreCase = true) }
                .maxByOrNull { item -> item.size ?: 0L }
            AppUpdateInfo(
                versionName = version,
                title = release.name?.takeIf(String::isNotBlank) ?: "JMComicX v$version",
                body = release.body.orEmpty().trim().ifBlank { DEFAULT_UPDATE_BODY },
                releaseUrl = release.htmlUrl.orEmpty().ifBlank { releaseUrl(release.tagName.orEmpty()) },
                downloadUrls = asset?.browserDownloadUrl.downloadCandidates(),
            )
        }
    }

    private suspend fun fetchGitHubHtmlFallback(): AppUpdateInfo? = withContext(Dispatchers.IO) {
        val latestResponse = client.newCall(
            Request.Builder().url(GITHUB_LATEST_RELEASE_URL).header("User-Agent", userAgent()).build(),
        ).execute()
        latestResponse.use { response ->
            if (!response.isSuccessful) {
                Log.w(UPDATE_LOG_TAG, "release HTML fallback failed: ${response.code}")
                return@withContext null
            }
            val tag = response.request.url.pathSegments.lastOrNull()?.takeIf(String::isNotBlank)
                ?: return@withContext null
            val version = tag.normalizeVersion().takeIf(String::isNotBlank) ?: return@withContext null
            val assetsResponse = client.newCall(
                Request.Builder()
                    .url("$GITHUB_REPOSITORY_URL/releases/expanded_assets/$tag")
                    .header("User-Agent", userAgent())
                    .build(),
            ).execute()
            val assetUrl = assetsResponse.use { assets ->
                if (!assets.isSuccessful) return@use null
                APK_LINK_PATTERN.find(assets.body.string())
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.replace("&amp;", "&")
                    ?.let { href -> if (href.startsWith("http")) href else "https://github.com$href" }
            }
            AppUpdateInfo(
                versionName = version,
                title = "JMComicX v$version",
                body = DEFAULT_UPDATE_BODY,
                releaseUrl = releaseUrl(tag),
                downloadUrls = assetUrl.downloadCandidates(),
            )
        }
    }

    private suspend fun fastestReachableUrl(urls: List<String>): String? {
        return firstSuccessful(urls) { url ->
            withContext(Dispatchers.IO) {
                val request = Request.Builder()
                    .url(url)
                    .head()
                    .header("User-Agent", userAgent())
                    .build()
                runCatching {
                    client.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            url
                        } else {
                            null
                        }
                    }
                }.getOrNull()
            }
        }
    }

    private suspend fun <T> firstSuccessful(
        inputs: List<String>,
        request: suspend (String) -> T?,
    ): T? = supervisorScope {
        val pending = inputs.map { input -> async { runCatching { request(input) }.getOrNull() } }.toMutableList()
        while (pending.isNotEmpty()) {
            val (completed, result) = pending.awaitNext()
            pending.remove(completed)
            if (result != null) {
                pending.forEach { it.cancel() }
                return@supervisorScope result
            }
        }
        null
    }

    private suspend fun <T> List<Deferred<T?>>.awaitNext(): Pair<Deferred<T?>, T?> = select {
        forEach { deferred -> deferred.onAwait { result -> deferred to result } }
    }

    private fun userAgent(): String = "JMComicX/$localVersionName (Android)"

    private data class GitHubRelease(
        @SerializedName("tag_name") val tagName: String?,
        @SerializedName("name") val name: String?,
        @SerializedName("body") val body: String?,
        @SerializedName("html_url") val htmlUrl: String?,
        @SerializedName("draft") val draft: Boolean?,
        @SerializedName("prerelease") val prerelease: Boolean?,
        @SerializedName("assets") val assets: List<GitHubAsset>?,
    )

    private data class GitHubAsset(
        @SerializedName("name") val name: String?,
        @SerializedName("browser_download_url") val browserDownloadUrl: String?,
        @SerializedName("size") val size: Long?,
    )
}

internal fun isVersionNewer(remote: String, local: String): Boolean {
    val remoteParts = remote.normalizeVersionParts()
    val localParts = local.normalizeVersionParts()
    repeat(maxOf(remoteParts.size, localParts.size)) { index ->
        val remoteValue = remoteParts.getOrElse(index) { 0 }
        val localValue = localParts.getOrElse(index) { 0 }
        if (remoteValue != localValue) return remoteValue > localValue
    }
    return false
}

private fun String?.downloadCandidates(): List<String> {
    val direct = this?.trim()?.takeIf(String::isNotEmpty) ?: return emptyList()
    return buildList {
        add(direct)
        DOWNLOAD_PROXY_PREFIXES.forEach { prefix -> add("$prefix$direct") }
    }.distinct()
}

private fun String?.normalizeVersion(): String = this.orEmpty()
    .trim()
    .removePrefix("v")
    .removePrefix("V")
    .substringBefore('-')
    .substringBefore('+')

private fun String.normalizeVersionParts(): List<Int> = normalizeVersion()
    .split('.')
    .map { part -> part.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }

private fun releaseUrl(tag: String): String = "$GITHUB_REPOSITORY_URL/releases/tag/$tag"

private const val UPDATE_PREFERENCES = "jmx-update-preferences"
private const val UPDATE_LOG_TAG = "JMComicX.Update"
private const val AUTO_CHECK_KEY = "auto_check_enabled"
private const val GITHUB_REPOSITORY_URL = "https://github.com/Sakura-TWT/JMComicX"
private const val GITHUB_LATEST_RELEASE_URL = "$GITHUB_REPOSITORY_URL/releases/latest"
private const val DEFAULT_UPDATE_BODY = "GitHub 已发布新版本，建议更新以获得最新功能和稳定性修复。"
private val APK_LINK_PATTERN = Regex("""href=[\"']([^\"']+/releases/download/[^\"']+\.apk(?:\?[^\"']*)?)[\"']""", RegexOption.IGNORE_CASE)
private val RELEASE_API_ENDPOINTS = listOf(
    "https://api.github.com/repos/Sakura-TWT/JMComicX/releases/latest",
    "https://gh.llkk.cc/https://api.github.com/repos/Sakura-TWT/JMComicX/releases/latest",
    "https://ghproxy.net/https://api.github.com/repos/Sakura-TWT/JMComicX/releases/latest",
)
private val DOWNLOAD_PROXY_PREFIXES = listOf(
    "https://gh.llkk.cc/",
    "https://ghproxy.net/",
)

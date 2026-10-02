package app.prismia.plus.core.cache

import app.prismia.plus.core.protocol.JmxProtocolConstants

class ProtocolStateStore(
    private val store: KeyValueStore
) {
    fun apiVersion(): String {
        val cached = store.getString(KEY_API_VERSION)?.takeIf { it.isNotBlank() }
            ?: return JmxProtocolConstants.DefaultApiVersion
        // 内置版本作为下限：升级引入更高协议版本时，旧的持久化值不再拖慢 Tokenparam
        return if (cached.isVersionOlderThan(JmxProtocolConstants.DefaultApiVersion)) {
            JmxProtocolConstants.DefaultApiVersion
        } else {
            cached
        }
    }

    fun updateApiVersion(version: String?) {
        store.putString(KEY_API_VERSION, version?.takeIf { it.isNotBlank() })
    }

    fun apiHosts(): List<String> {
        val cached = store.getString(KEY_API_HOSTS)
            ?.split(HOST_SEPARATOR)
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()
        if (cached.isEmpty()) return JmxProtocolConstants.DefaultApiHosts
        // 内置默认域名轮换（代际变更）后做一次性迁移：
        // 旧持久化列表并入新默认线路，避免升级用户困在已退役域名上；
        // 域名服务器刷新成功后会以远程列表整体覆盖
        if (store.getString(KEY_API_HOSTS_GENERATION) != API_HOSTS_GENERATION) {
            val migrated = (cached + JmxProtocolConstants.DefaultApiHosts).distinct()
            updateApiHosts(migrated)
            return migrated
        }
        return cached
    }

    fun updateApiHosts(hosts: List<String>) {
        val normalized = hosts.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        store.putString(KEY_API_HOSTS, normalized.takeIf { it.isNotEmpty() }?.joinToString(HOST_SEPARATOR))
        store.putString(KEY_API_HOSTS_GENERATION, API_HOSTS_GENERATION)
    }

    fun manualApiHost(): String? {
        return store.getString(KEY_MANUAL_API_HOST)?.takeIf { it.isNotBlank() }
    }

    fun updateManualApiHost(host: String?) {
        store.putString(KEY_MANUAL_API_HOST, host?.takeIf { it.isNotBlank() })
    }

    fun preferredAutoApiHost(): String? {
        return store.getString(KEY_PREFERRED_AUTO_API_HOST)?.takeIf { it.isNotBlank() }
    }

    fun updatePreferredAutoApiHost(host: String?) {
        store.putString(KEY_PREFERRED_AUTO_API_HOST, host?.takeIf { it.isNotBlank() })
    }

    /**
     * 签发当前登录态的 API 域名。
     *
     * 服务端把会话绑在签发它的那台机器上：同一个 AVS 拿到别的域名去请求 `/favorite`，
     * 回的是 401「請先登入會員」。所以这台机器要跟着登录态一起落盘，
     * 否则冷启动按健康度另选一台，所有需要登录的接口都会当场掉登录（见 [ApiEndpointManager]）。
     */
    fun sessionApiHost(): String? {
        return store.getString(KEY_SESSION_API_HOST)?.takeIf { it.isNotBlank() }
    }

    fun updateSessionApiHost(host: String?) {
        store.putString(KEY_SESSION_API_HOST, host?.takeIf { it.isNotBlank() })
    }

    /** 用户在设置里手动钉住的图片线路；null 表示按健康度自动选。 */
    fun manualImageHost(): String? {
        return store.getString(KEY_MANUAL_IMAGE_HOST)?.takeIf { it.isNotBlank() }
    }

    fun updateManualImageHost(host: String?) {
        store.putString(KEY_MANUAL_IMAGE_HOST, host?.takeIf { it.isNotBlank() })
    }

    /**
     * 上次自动选出的最优图片线路。
     *
     * 与 [preferredAutoApiHost] 同一个用途：冷启动时第一张封面就该打在上次通得最好的那台上，
     * 而不是从内置表头重新试探——图片请求的量远大于接口请求，试探成本也就更高。
     */
    fun preferredAutoImageHost(): String? {
        return store.getString(KEY_PREFERRED_AUTO_IMAGE_HOST)?.takeIf { it.isNotBlank() }
    }

    fun updatePreferredAutoImageHost(host: String?) {
        store.putString(KEY_PREFERRED_AUTO_IMAGE_HOST, host?.takeIf { it.isNotBlank() })
    }

    /** /setting 下发的 img_host，与内置表合并使用。 */
    fun remoteImageHost(): String? {
        return store.getString(KEY_REMOTE_IMAGE_HOST)?.takeIf { it.isNotBlank() }
    }

    fun updateRemoteImageHost(host: String?) {
        store.putString(KEY_REMOTE_IMAGE_HOST, host?.takeIf { it.isNotBlank() })
    }

    private companion object {
        const val KEY_API_VERSION = "protocol.api.version"
        const val KEY_API_HOSTS = "protocol.api.hosts"
        const val KEY_API_HOSTS_GENERATION = "protocol.api.hosts.generation"
        const val KEY_MANUAL_API_HOST = "protocol.api.manual_host"
        const val KEY_PREFERRED_AUTO_API_HOST = "protocol.api.preferred_auto_host"
        const val KEY_SESSION_API_HOST = "protocol.api.session_host"
        const val KEY_MANUAL_IMAGE_HOST = "protocol.image.manual_host"
        const val KEY_PREFERRED_AUTO_IMAGE_HOST = "protocol.image.preferred_auto_host"
        const val KEY_REMOTE_IMAGE_HOST = "protocol.image.remote_host"
        const val HOST_SEPARATOR = "\n"

        /** 默认 API 域名代际：内置域名组轮换时递增，触发一次性迁移 */
        const val API_HOSTS_GENERATION = "2026-08"
    }
}

private fun String.isVersionOlderThan(other: String): Boolean {
    val left = split('.').map { it.toIntOrNull() ?: 0 }
    val right = other.split('.').map { it.toIntOrNull() ?: 0 }
    val size = maxOf(left.size, right.size)
    for (index in 0 until size) {
        val l = left.getOrElse(index) { 0 }
        val r = right.getOrElse(index) { 0 }
        if (l != r) return l < r
    }
    return false
}

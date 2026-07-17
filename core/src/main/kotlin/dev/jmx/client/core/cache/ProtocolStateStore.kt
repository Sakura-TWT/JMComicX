package dev.jmx.client.core.cache

import dev.jmx.client.core.protocol.JmxProtocolConstants

class ProtocolStateStore(
    private val store: KeyValueStore
) {
    fun apiVersion(): String {
        return store.getString(KEY_API_VERSION)
            ?.takeIf { it.isNotBlank() }
            ?: JmxProtocolConstants.DefaultApiVersion
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
        return cached.ifEmpty { JmxProtocolConstants.DefaultApiHosts }
    }

    fun updateApiHosts(hosts: List<String>) {
        val normalized = hosts.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        store.putString(KEY_API_HOSTS, normalized.takeIf { it.isNotEmpty() }?.joinToString(HOST_SEPARATOR))
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

    private companion object {
        const val KEY_API_VERSION = "protocol.api.version"
        const val KEY_API_HOSTS = "protocol.api.hosts"
        const val KEY_MANUAL_API_HOST = "protocol.api.manual_host"
        const val KEY_PREFERRED_AUTO_API_HOST = "protocol.api.preferred_auto_host"
        const val HOST_SEPARATOR = "\n"
    }
}

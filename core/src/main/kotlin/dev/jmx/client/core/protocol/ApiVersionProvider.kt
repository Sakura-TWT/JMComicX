package dev.jmx.client.core.protocol

import dev.jmx.client.core.cache.ProtocolStateStore
import java.util.concurrent.atomic.AtomicReference

interface ApiVersionProvider {
    fun current(): String
    fun update(version: String): Boolean
}

class MutableApiVersionProvider(
    initialVersion: String = JmxProtocolConstants.DefaultApiVersion
) : ApiVersionProvider {
    private val version = AtomicReference(initialVersion.sanitizeVersionOrDefault())

    override fun current(): String = version.get()

    override fun update(version: String): Boolean {
        val sanitized = version.sanitizeVersionOrNull() ?: return false
        this.version.set(sanitized)
        return true
    }

    private fun String.sanitizeVersionOrDefault(): String {
        return sanitizeVersionOrNull() ?: JmxProtocolConstants.DefaultApiVersion
    }

    private fun String.sanitizeVersionOrNull(): String? {
        val value = trim()
        if (value.isEmpty()) return null
        if (!Regex("""\d+(?:\.\d+){1,3}""").matches(value)) return null
        return value
    }
}

class StoredApiVersionProvider(
    private val protocolStateStore: ProtocolStateStore
) : ApiVersionProvider {
    private val delegate = MutableApiVersionProvider(protocolStateStore.apiVersion())

    override fun current(): String = delegate.current()

    override fun update(version: String): Boolean {
        if (!delegate.update(version)) return false
        protocolStateStore.updateApiVersion(delegate.current())
        return true
    }
}

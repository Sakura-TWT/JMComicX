package dev.jmx.client.core.cache

import java.util.concurrent.ConcurrentHashMap

interface KeyValueStore {
    fun getString(key: String): String?
    fun putString(key: String, value: String?)
}

class InMemoryKeyValueStore(
    initialValues: Map<String, String> = emptyMap()
) : KeyValueStore {
    private val values = ConcurrentHashMap(initialValues)

    override fun getString(key: String): String? = values[key]

    override fun putString(key: String, value: String?) {
        if (value == null) {
            values.remove(key)
        } else {
            values[key] = value
        }
    }
}

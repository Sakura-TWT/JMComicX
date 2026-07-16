package dev.jmx.client

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import dev.jmx.client.core.cache.KeyValueStore
import dev.jmx.client.core.runtime.JmxCore
import dev.jmx.client.core.runtime.JmxCoreConfig
import dev.jmx.client.core.session.PersistentCookieStore

internal fun createAppJmxCore(context: Context): JmxCore {
    val stateStore = SharedPreferencesKeyValueStore(
        context.applicationContext.getSharedPreferences(JMX_CORE_PREFERENCES, Context.MODE_PRIVATE),
    )
    return JmxCore.create(
        JmxCoreConfig(
            keyValueStore = stateStore,
            cookieStore = PersistentCookieStore(stateStore),
        ),
    )
}

private class SharedPreferencesKeyValueStore(
    private val preferences: SharedPreferences,
) : KeyValueStore {
    override fun getString(key: String): String? = preferences.getString(key, null)

    override fun putString(key: String, value: String?) {
        preferences.edit {
            if (value == null) remove(key) else putString(key, value)
        }
    }
}

private const val JMX_CORE_PREFERENCES = "jmx_core_state"

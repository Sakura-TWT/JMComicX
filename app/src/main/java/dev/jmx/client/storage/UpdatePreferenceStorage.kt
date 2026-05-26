package dev.jmx.client.storage

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class UpdatePreferenceStorage(context: Context) {
    private val preferences = context.getSharedPreferences("jmx-update-preferences", Context.MODE_PRIVATE)
    private val _autoCheckEnabled = MutableStateFlow(
        preferences.getBoolean(KEY_AUTO_CHECK_ENABLED, true)
    )

    val autoCheckEnabled = _autoCheckEnabled.asStateFlow()

    fun setAutoCheckEnabled(enabled: Boolean) {
        preferences.edit {
            putBoolean(KEY_AUTO_CHECK_ENABLED, enabled)
        }
        _autoCheckEnabled.value = enabled
    }

    private companion object {
        const val KEY_AUTO_CHECK_ENABLED = "auto_check_enabled"
    }
}

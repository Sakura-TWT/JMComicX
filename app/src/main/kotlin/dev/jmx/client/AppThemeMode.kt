package dev.jmx.client

import android.content.Context
import androidx.core.content.edit
import top.yukonga.miuix.kmp.theme.ColorSchemeMode

internal enum class AppThemeMode(
    val label: String,
    val summary: String,
) {
    SYSTEM("跟随系统", "根据系统外观自动切换"),
    LIGHT("亮色模式", "始终使用亮色外观"),
    DARK("深色模式", "始终使用深色外观"),
    ;

    fun isDark(systemDark: Boolean): Boolean = when (this) {
        SYSTEM -> systemDark
        LIGHT -> false
        DARK -> true
    }

    fun toMiuixMode(): ColorSchemeMode = when (this) {
        SYSTEM -> ColorSchemeMode.System
        LIGHT -> ColorSchemeMode.Light
        DARK -> ColorSchemeMode.Dark
    }

    companion object {
        fun fromStored(value: String?): AppThemeMode = entries
            .firstOrNull { it.name == value }
            ?: SYSTEM
    }
}

internal class AppThemeModeStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        THEME_PREFERENCES,
        Context.MODE_PRIVATE,
    )

    fun load(): AppThemeMode = AppThemeMode.fromStored(
        preferences.getString(THEME_MODE_KEY, null),
    )

    fun save(mode: AppThemeMode) {
        preferences.edit { putString(THEME_MODE_KEY, mode.name) }
    }
}

private const val THEME_PREFERENCES = "jmx_theme"
private const val THEME_MODE_KEY = "theme_mode"

package dev.jmx.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppThemeModeTest {
    @Test
    fun unknownStoredModeFallsBackToSystem() {
        assertEquals(AppThemeMode.SYSTEM, AppThemeMode.fromStored("unknown"))
        assertEquals(AppThemeMode.SYSTEM, AppThemeMode.fromStored(null))
    }

    @Test
    fun themeModeResolvesAgainstSystemAppearance() {
        assertTrue(AppThemeMode.SYSTEM.isDark(systemDark = true))
        assertFalse(AppThemeMode.SYSTEM.isDark(systemDark = false))
        assertTrue(AppThemeMode.DARK.isDark(systemDark = false))
        assertFalse(AppThemeMode.LIGHT.isDark(systemDark = true))
    }
}

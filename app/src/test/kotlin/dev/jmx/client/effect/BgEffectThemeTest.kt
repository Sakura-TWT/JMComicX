package dev.jmx.client.effect

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BgEffectThemeTest {
    @Test
    fun effectPresetFollowsActualSurfaceBrightness() {
        assertTrue(Color.Black.usesDarkEffectPreset())
        assertTrue(Color(0xFF151515).usesDarkEffectPreset())
        assertFalse(Color.White.usesDarkEffectPreset())
        assertFalse(Color(0xFFF7F7F7).usesDarkEffectPreset())
    }
}

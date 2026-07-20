package dev.jmx.client

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jmx.client.effect.BgEffectBackground
import dev.jmx.client.effect.usesDarkEffectPreset
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurBlendMode
import top.yukonga.miuix.kmp.blur.BlurDefaults
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.textureBlur
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun MiuixAboutBackdrop(
    backdrop: LayerBackdrop,
    scrollProgress: () -> Float,
) {
    BgEffectBackground(
        dynamicBackground = true,
        isFullSize = true,
        isOs3Effect = true,
        modifier = Modifier.fillMaxSize(),
        bgModifier = Modifier.layerBackdrop(backdrop),
        alpha = { 1f - scrollProgress() * 0.18f },
    ) {}
}

@Composable
internal fun MiuixForegroundBlurHero(
    version: String,
    backdrop: LayerBackdrop,
    scrollProgress: () -> Float,
    modifier: Modifier = Modifier,
) {
    val darkTheme = MiuixTheme.colorScheme.surface.usesDarkEffectPreset()
    val titleBlend = remember(darkTheme) {
        if (darkTheme) {
            listOf(
                BlendColorEntry(Color(0xE6A1A1A1), BlurBlendMode.ColorDodge),
                BlendColorEntry(Color(0x4DE6E6E6), BlurBlendMode.LinearLight),
                BlendColorEntry(Color(0xFF1AF500), BlurBlendMode.Lab),
            )
        } else {
            listOf(
                BlendColorEntry(Color(0xCC4A4A4A), BlurBlendMode.ColorBurn),
                BlendColorEntry(Color(0xFF4F4F4F), BlurBlendMode.LinearLight),
                BlendColorEntry(Color(0xFF1AF200), BlurBlendMode.Lab),
            )
        }
    }
    val titleBlurColors = BlurDefaults.blurColors(blendColors = titleBlend)

    Column(
        modifier = modifier.graphicsLayer {
            val progress = scrollProgress()
            alpha = 1f - progress
            scaleX = 1f - progress * 0.05f
            scaleY = 1f - progress * 0.05f
        },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(88.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(Color.White.copy(alpha = 0.96f)),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = painterResource(R.drawable.jmx_logo),
                contentDescription = "JMComicX",
                modifier = Modifier.size(80.dp),
            )
        }
        Text(
            text = "JMComicX",
            fontSize = 35.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            color = MiuixTheme.colorScheme.onBackground,
            modifier = Modifier
                .fillMaxWidth()
                .textureBlur(
                    backdrop = backdrop,
                    shape = RoundedCornerShape(16.dp),
                    blurRadius = 150f,
                    colors = titleBlurColors,
                    contentBlendMode = BlendMode.DstIn,
                ),
        )
        Text(
            text = "v$version",
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.alpha(0.92f),
        )
    }
}

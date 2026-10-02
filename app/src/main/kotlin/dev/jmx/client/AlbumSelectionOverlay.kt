package dev.jmx.client

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Cover-sized decoration only; the actionable parent owns selection and accessibility. */
@Composable
internal fun AlbumSelectionOverlay(selected: Boolean, modifier: Modifier = Modifier) {
    val overlayColor by animateColorAsState(
        targetValue = if (selected) MiuixTheme.colorScheme.primary.copy(alpha = 0.14f) else Color.Transparent,
        animationSpec = tween(200),
        label = "AlbumSelectionOverlay",
    )
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(overlayColor)
            .clearAndSetSemantics {},
    ) {
        Surface(
            modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).size(28.dp),
            shape = CircleShape,
            color = MiuixTheme.colorScheme.surface.copy(alpha = 0.9f),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Checkbox(state = ToggleableState(selected), onClick = null)
            }
        }
    }
}

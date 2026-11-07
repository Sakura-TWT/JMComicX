package dev.jmx.client

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Toolbar/secondary actions are not filled submit buttons; keep native Miuix feedback and semantics. */
@Composable
internal fun QuietTextAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    emphasized: Boolean = false,
    destructive: Boolean = false,
) {
    val contentColor = when {
        destructive -> MiuixTheme.colorScheme.error
        emphasized -> MiuixTheme.colorScheme.primary
        else -> MiuixTheme.colorScheme.onSurfaceVariantSummary
    }
    TextButton(
        text = text,
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        minHeight = 44.dp,
        insideMargin = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
        colors = ButtonDefaults.textButtonColors(
            color = Color.Transparent,
            disabledColor = Color.Transparent,
            textColor = contentColor,
            disabledTextColor = MiuixTheme.colorScheme.disabledOnSecondaryVariant,
        ),
    )
}

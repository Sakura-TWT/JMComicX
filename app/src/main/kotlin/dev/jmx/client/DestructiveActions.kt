package dev.jmx.client

import androidx.compose.runtime.Composable
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.TextButtonColors
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** One semantic palette for destructive confirmations; acknowledgements remain primary. */
@Composable
internal fun destructiveTextButtonColors(): TextButtonColors = ButtonDefaults.textButtonColorsPrimary(
    color = MiuixTheme.colorScheme.error,
    textColor = MiuixTheme.colorScheme.onError,
    disabledColor = MiuixTheme.colorScheme.disabledSecondaryVariant,
    disabledTextColor = MiuixTheme.colorScheme.disabledOnSecondaryVariant,
)

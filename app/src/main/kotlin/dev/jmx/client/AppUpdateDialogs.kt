package dev.jmx.client

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

@Composable
internal fun AppUpdateDialog(
    info: AppUpdateInfo?,
    launchingDownload: Boolean,
    onLater: () -> Unit,
    onUpdate: () -> Unit,
) {
    WindowDialog(
        show = info != null,
        title = "发现新版本",
        summary = info?.let { "JMComicX v${it.versionName}" },
        onDismissRequest = { if (!launchingDownload) onLater() },
    ) {
        val update = info ?: return@WindowDialog
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(
                text = update.title,
                style = MiuixTheme.textStyles.title3,
                fontWeight = FontWeight.SemiBold,
                color = MiuixTheme.colorScheme.onSurface,
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 260.dp)
                    .background(
                        color = MiuixTheme.colorScheme.surfaceContainerHigh,
                        shape = RoundedCornerShape(8.dp),
                    )
                    .verticalScroll(rememberScrollState())
                    .padding(14.dp),
            ) {
                Text(
                    text = update.body,
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TextButton(
                    text = "稍后提醒",
                    enabled = !launchingDownload,
                    onClick = onLater,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    text = if (launchingDownload) "选择下载线路…" else "立即更新",
                    enabled = !launchingDownload,
                    onClick = onUpdate,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            }
        }
    }
}

@Composable
internal fun UpdateResultDialog(message: String?, onDismiss: () -> Unit) {
    WindowDialog(
        show = message != null,
        title = "获取更新",
        summary = message,
        onDismissRequest = onDismiss,
    ) {
        TextButton(
            text = "知道了",
            onClick = onDismiss,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.textButtonColorsPrimary(),
        )
    }
}

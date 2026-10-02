package dev.jmx.client

import android.content.Intent
import android.provider.DocumentsContract
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.preference.RadioButtonPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

private data class ExportProgress(val message: String, val completed: Int = 0, val total: Int = 0)
private data class ExportResultMessage(val title: String, val message: String)

@Composable
internal fun DownloadExportDialogs(manager: OfflineDownloadManager, selectedIds: Set<String>?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val library by manager.albums.collectAsState()
    val planResult = remember(library, selectedIds) { runCatching { downloadExportPlan(library, selectedIds.orEmpty()) } }
    val plan = planResult.getOrNull()
    var chosenFormat by rememberSaveable { mutableStateOf(DownloadExportFormat.ZIP.name) }
    val selectedFormat = DownloadExportFormat.valueOf(chosenFormat)
    var pendingFormat by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingChapters by rememberSaveable { mutableStateOf<List<String>>(arrayListOf()) }
    var pendingFileName by rememberSaveable { mutableStateOf("") }
    var progress by remember { mutableStateOf<ExportProgress?>(null) }
    var resultMessage by remember { mutableStateOf<ExportResultMessage?>(null) }
    var cancelling by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }
    val contentHeight = with(LocalDensity.current) {
        (LocalWindowInfo.current.containerSize.height.toDp() - 180.dp).coerceIn(100.dp, 560.dp)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data
        val format = pendingFormat?.let { name -> DownloadExportFormat.entries.firstOrNull { it.name == name } }
        val chapters = pendingChapters.groupBy({ it.substringBefore(':') }, { it.substringAfter(':') })
            .mapValues { (_, ids) -> ids.toSet() }
        val fileName = pendingFileName
        pendingFormat = null
        pendingChapters = arrayListOf()
        pendingFileName = ""
        if (result.resultCode == android.app.Activity.RESULT_OK && uri != null && format != null && chapters.isNotEmpty()) {
            progress = ExportProgress("正在准备所选章节…")
            cancelling = false
            job = scope.launch {
                var success = false
                var cancelled = false
                var failureMessage: String? = null
                try {
                    manager.withExportFiles(chapters) { albums ->
                        context.contentResolver.openOutputStream(uri, "wt")?.buffered(256 * 1024)?.use { output ->
                            exportOfflineDownloads(albums, format, output,
                                onPageProgress = { done, total ->
                                    progress = ExportProgress(if (done == total) "正在完成文件写入…" else progress?.message.orEmpty(), done, total)
                                },
                                onProgress = { message -> progress = (progress ?: ExportProgress(message)).copy(message = message) },
                            )
                        } ?: error("无法写入所选位置，请选择其他文件夹")
                    }
                    success = true
                } catch (_: CancellationException) {
                    cancelled = true
                } catch (failure: Exception) {
                    failureMessage = failure.message ?: "无法完成导出，请检查剩余空间和保存位置的访问权限"
                } finally {
                    val cleaned = if (success) true else withContext(NonCancellable + Dispatchers.IO) {
                        runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }.getOrDefault(false)
                    }
                    resultMessage = when {
                        success -> ExportResultMessage("导出完成", "$fileName\n已保存到你选择的位置。")
                        cancelled -> ExportResultMessage("已取消导出", if (cleaned) "未完成的导出文件已清理，本地漫画不受影响。" else "本地漫画不受影响。未能清理目标文件，请到保存位置删除不完整文件。")
                        else -> ExportResultMessage("导出未完成", failureMessage.orEmpty() + if (cleaned) "\n本地漫画不受影响，可以重新导出。" else "\n未能清理目标文件，请到保存位置删除不完整文件后重试。")
                    }
                    progress = null
                    cancelling = false
                    job = null
                }
            }
        }
    }
    WindowDialog(
        show = selectedIds != null && progress == null && resultMessage == null,
        title = "导出离线漫画",
        summary = plan?.let { "${it.albumCount} 部漫画 · ${it.chapterCount} 章 · ${it.pageCount} 页" },
        maxWidth = 480.dp,
        onDismissRequest = onDismiss,
    ) {
        Column(modifier = Modifier.heightIn(max = contentHeight), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(
                modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(text = "选择导出格式", style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                Card {
                    RadioButtonPreference(
                        title = "原图 ZIP",
                        summary = "保留离线图片，不降低画质。按漫画和章节分文件夹，适合备份与迁移。",
                        selected = selectedFormat == DownloadExportFormat.ZIP,
                        onClick = { chosenFormat = DownloadExportFormat.ZIP.name },
                    )
                    RadioButtonPreference(
                        title = "阅读版 PDF",
                        summary = "每章一份 PDF，适合阅读和分享。符合条件的图片直接封装，其余图片缩放压缩，不保证原图画质。",
                        selected = selectedFormat == DownloadExportFormat.PDF,
                        onClick = { chosenFormat = DownloadExportFormat.PDF.name },
                    )
                }
                if (plan != null) {
                    Text(
                        text = if (selectedFormat == DownloadExportFormat.PDF && plan.chapterCount > 1)
                            "本次生成 ${plan.chapterCount} 份 PDF，合并保存为一个 ZIP 文件。"
                        else if (selectedFormat == DownloadExportFormat.PDF) "本次保存为一个 PDF 文件。"
                        else "原图合计 ${Formatter.formatFileSize(context, plan.sourceBytes)}，导出的 ZIP 大小与原图总量接近。",
                        style = MiuixTheme.textStyles.footnote1,
                    )
                    Text(
                        text = if (plan.skippedChapters > 0) "跳过 ${plan.skippedChapters} 个未完成章节，仅导出完整章节。" else "仅导出本次确认时已完整下载的章节。",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    Text(text = "下一步选择保存位置。导出期间请保持应用运行。", style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                } else {
                    Text(text = planResult.exceptionOrNull()?.message ?: "暂无可导出的章节", color = MiuixTheme.colorScheme.error)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(text = "取消", onClick = onDismiss, modifier = Modifier.weight(1f))
                TextButton(
                    text = "选择位置",
                    enabled = plan != null && pendingFormat == null,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                    onClick = {
                        val chosen = plan ?: return@TextButton
                        // Freeze chapter IDs before SAF: subsequent downloads must not change the output type.
                        pendingChapters = ArrayList(chosen.chapters.flatMap { (album, ids) -> ids.map { "$album:$it" } })
                        pendingFormat = selectedFormat.name
                        pendingFileName = chosen.fileName(selectedFormat)
                        onDismiss()
                        try {
                            launcher.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                                addCategory(Intent.CATEGORY_OPENABLE)
                                type = chosen.mimeType(selectedFormat)
                                putExtra(Intent.EXTRA_TITLE, pendingFileName)
                            })
                        } catch (_: android.content.ActivityNotFoundException) {
                            pendingFormat = null
                            pendingChapters = arrayListOf()
                            pendingFileName = ""
                            resultMessage = ExportResultMessage("无法选择保存位置", "设备未提供文件选择器，请启用系统文件应用后重试。")
                        }
                    },
                )
            }
        }
    }
    WindowDialog(show = progress != null, title = if (cancelling) "正在取消导出" else "正在导出", maxWidth = 480.dp, onDismissRequest = {}) {
        Column(modifier = Modifier.heightIn(max = contentHeight), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val current = progress
            LinearProgressIndicator(progress = current?.takeIf { it.total > 0 }?.let { it.completed.toFloat() / it.total })
            Text(text = if (cancelling) "正在停止写入并清理文件…" else current?.message.orEmpty(), modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), style = MiuixTheme.textStyles.footnote1)
            if (current != null && current.total > 0) Text(text = "已处理 ${current.completed} / ${current.total} 页", style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            TextButton(text = if (cancelling) "正在取消…" else "取消导出", enabled = !cancelling, modifier = Modifier.fillMaxWidth(), onClick = { cancelling = true; job?.cancel() })
        }
    }
    WindowDialog(show = resultMessage != null, title = resultMessage?.title, maxWidth = 480.dp, onDismissRequest = { resultMessage = null }) {
        Column(modifier = Modifier.heightIn(max = contentHeight), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(text = resultMessage?.message.orEmpty(), modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), style = MiuixTheme.textStyles.body2)
            TextButton(text = "知道了", modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.textButtonColorsPrimary(), onClick = { resultMessage = null })
        }
    }
}

package dev.jmx.client

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

internal data class DownloadChapterUiModel(
    val id: String,
    val title: String,
    val summary: String? = null,
    val enabled: Boolean = true,
)

/**
 * Keep this composable mounted and change [show] to preserve WindowDialog's exit animation.
 * [onDownload] submits a snapshot of chapter IDs; the host owns enqueueing and dismissal.
 */
@Composable
internal fun DownloadChapterPicker(
    show: Boolean,
    chapters: List<DownloadChapterUiModel>,
    onDismiss: () -> Unit,
    onDownload: (Set<String>) -> Unit,
    modifier: Modifier = Modifier,
    albumTitle: String? = null,
    initialSelectedIds: Set<String> = emptySet(),
    loading: Boolean = false,
    error: String? = null,
    submitting: Boolean = false,
    onRetry: () -> Unit = {},
) {
    val rows = remember(chapters) { chapters.distinctBy(DownloadChapterUiModel::id) }
    val orderedIds = remember(rows) { rows.map(DownloadChapterUiModel::id) }
    val eligibleIds = remember(rows) { rows.filter(DownloadChapterUiModel::enabled).mapTo(linkedSetOf(), DownloadChapterUiModel::id) }
    // ArrayList instances stay Bundle-saveable; the state type is read-only so nothing mutates in place.
    var savedSelection by rememberSaveable(show, albumTitle) { mutableStateOf<List<String>>(ArrayList(initialSelectedIds)) }
    val selectedIds = savedSelection.toSet().intersect(eligibleIds)
    val listState = rememberLazyListState()
    val haptics = LocalHapticFeedback.current
    val interactive = show && !loading && !submitting
    val currentSelectedIds by rememberUpdatedState(selectedIds)
    val currentOrderedIds by rememberUpdatedState(orderedIds)
    val currentEligibleIds by rememberUpdatedState(eligibleIds)
    var dragging by remember { mutableStateOf(false) }
    var pointerY by remember { mutableFloatStateOf(0f) }
    var lastDragIndex by remember { mutableIntStateOf(-1) }
    var dragSelecting by remember { mutableStateOf(true) }
    val density = LocalDensity.current
    val edgeSize = with(density) { 56.dp.toPx() }
    val maxScrollSpeed = with(density) { 640.dp.toPx() }
    val screenHeight = with(density) { LocalWindowInfo.current.containerSize.height.toDp() }
    val compactHeight = screenHeight < 600.dp
    val contentMaxHeight = (screenHeight - 160.dp).coerceIn(140.dp, 560.dp)

    fun indexAt(y: Float): Int? = downloadSelectionIndexAt(
        pointerY = y,
        visibleRows = listState.layoutInfo.visibleItemsInfo.map {
            DownloadSelectionRow(index = it.index, offset = it.offset, size = it.size)
        },
    )

    fun paintAt(y: Float) {
        val index = indexAt(y) ?: return
        val from = lastDragIndex.takeIf { it >= 0 } ?: index
        savedSelection = ArrayList(
            paintDownloadSelectionRange(
                selectedIds = savedSelection.toSet(),
                orderedIds = currentOrderedIds,
                fromIndex = from,
                toIndex = index,
                selecting = dragSelecting,
                eligibleIds = currentEligibleIds,
            ),
        )
        lastDragIndex = index
    }

    fun finishDrag() {
        dragging = false
        lastDragIndex = -1
    }

    LaunchedEffect(show, eligibleIds, loading, submitting) {
        if (!loading) savedSelection = ArrayList(savedSelection.filter { it in eligibleIds })
        if (!interactive) finishDrag()
    }
    LaunchedEffect(show) {
        if (show) listState.scrollToItem(0)
    }

    // Independent frame loop is essential: a stationary finger at the edge emits no drag
    // events, but newly scrolled-in chapters must still be selected in that same gesture.
    LaunchedEffect(dragging) {
        if (!dragging) return@LaunchedEffect
        var previousFrame = withFrameNanos { it }
        while (dragging) {
            val frame = withFrameNanos { it }
            val elapsedSeconds = ((frame - previousFrame) / 1_000_000_000f).coerceIn(0f, 0.032f)
            previousFrame = frame
            val speed = downloadSelectionAutoScrollSpeed(
                pointerY = pointerY,
                viewportHeight = listState.layoutInfo.viewportSize.height.toFloat(),
                edgeSize = edgeSize,
                maxSpeed = maxScrollSpeed,
            )
            if (speed != 0f) listState.scrollBy(speed * elapsedSeconds)
            // Re-evaluate even when scrollBy hits the boundary or layout updates a frame later.
            paintAt(pointerY)
        }
    }

    WindowDialog(
        show = show,
        modifier = modifier,
        title = "下载章节",
        maxWidth = 480.dp,
        onDismissRequest = {
            finishDrag()
            onDismiss()
        },
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().heightIn(max = contentMaxHeight),
            verticalArrangement = Arrangement.spacedBy(if (compactHeight) 6.dp else 10.dp),
        ) {
            if (!compactHeight && !albumTitle.isNullOrBlank()) {
                Text(
                    text = albumTitle,
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 2,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
            Text(
                text = when {
                    dragging -> "拖动经过的章节会连续${if (dragSelecting) "选中" else "取消选择"}"
                    compactHeight -> "长按拖选 · 边缘自动滚动"
                    else -> "点选章节，或长按拖动批量选择。拖至列表边缘可继续滚动。"
                },
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "已选 ${selectedIds.size} 章 · 可选 ${eligibleIds.size} 章",
                    modifier = Modifier.weight(1f),
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.primary,
                )
                TextButton(
                    text = if (eligibleIds.isNotEmpty() && selectedIds.containsAll(eligibleIds)) "取消全选" else "全选",
                    onClick = {
                        savedSelection = ArrayList(toggleAllDownloadSelection(selectedIds, eligibleIds))
                    },
                    enabled = interactive && eligibleIds.isNotEmpty(),
                    minHeight = 40.dp,
                    insideMargin = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                )
            }
            if (submitting || (loading && rows.isNotEmpty())) LinearProgressIndicator()
            if (error != null && rows.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = error,
                        modifier = Modifier.weight(1f),
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.error,
                        maxLines = 3,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                    TextButton(text = "重试", onClick = onRetry, enabled = !loading && !submitting)
                }
            }
            if (rows.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxWidth().weight(1f, fill = false).padding(vertical = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (loading) CircularProgressIndicator()
                    Text(
                        text = when {
                            loading -> "正在读取章节…"
                            error != null -> error
                            else -> "暂无可下载的章节"
                        },
                        style = MiuixTheme.textStyles.body2,
                        color = if (error != null && !loading) MiuixTheme.colorScheme.error else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        textAlign = TextAlign.Center,
                    )
                    if (error != null && !loading) TextButton(text = "重试", onClick = onRetry, enabled = !submitting)
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxWidth().weight(1f, fill = false)
                        .pointerInput(show, orderedIds, eligibleIds, interactive) {
                            if (!interactive) return@pointerInput
                            try {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = { offset ->
                                        val index = indexAt(offset.y)
                                        val id = index?.let { currentOrderedIds.getOrNull(it) }
                                        if (index != null && id != null && id in currentEligibleIds) {
                                            pointerY = offset.y
                                            lastDragIndex = index
                                            dragSelecting = id !in currentSelectedIds
                                            dragging = true
                                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                            paintAt(offset.y)
                                        }
                                    },
                                    onDragEnd = { finishDrag() },
                                    onDragCancel = { finishDrag() },
                                    onDrag = { change, _ ->
                                        if (dragging) {
                                            change.consume()
                                            pointerY = change.position.y
                                            paintAt(pointerY)
                                        }
                                    },
                                )
                            } finally {
                                finishDrag()
                            }
                        },
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    contentPadding = PaddingValues(0.dp),
                ) {
                    itemsIndexed(rows, key = { _, chapter -> chapter.id }) { _, chapter ->
                        val checked = chapter.id in selectedIds
                        Surface(
                            onClick = {
                                savedSelection = ArrayList(toggleDownloadSelection(selectedIds, chapter.id, eligibleIds))
                            },
                            enabled = interactive && chapter.enabled,
                            modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {
                                role = Role.Checkbox
                                toggleableState = ToggleableState(checked)
                            },
                            shape = RoundedCornerShape(12.dp),
                            color = if (checked) MiuixTheme.colorScheme.primary.copy(alpha = 0.1f) else Color.Transparent,
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = chapter.title,
                                        style = MiuixTheme.textStyles.body2,
                                        color = if (chapter.enabled) MiuixTheme.colorScheme.onSurface else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    )
                                    chapter.summary?.let { summary ->
                                        Spacer(Modifier.height(4.dp))
                                        Text(text = summary, style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                                    }
                                }
                                // The whole row owns input. A passive real MIUIX Checkbox avoids
                                // a second gesture detector swallowing long-press range selection.
                                Checkbox(state = ToggleableState(checked), onClick = null, enabled = interactive && chapter.enabled)
                            }
                        }
                    }
                }
            }
            if (rows.isNotEmpty() && eligibleIds.isEmpty()) {
                Text(
                    text = "章节已下载或正在队列中，无需重复添加。",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(
                    text = "取消",
                    onClick = {
                        finishDrag()
                        onDismiss()
                    },
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    text = if (submitting) "正在加入队列…" else "开始下载",
                    onClick = {
                        finishDrag()
                        onDownload(selectedIds.toSet())
                    },
                    enabled = interactive && selectedIds.isNotEmpty(),
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            }
        }
    }
}

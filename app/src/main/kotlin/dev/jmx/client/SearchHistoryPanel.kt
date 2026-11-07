package dev.jmx.client

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun SearchHistoryPanel(
    history: List<String>,
    editing: Boolean,
    onEditingChange: (Boolean) -> Unit,
    onSelected: (String) -> Unit,
    onDelete: (String) -> Unit,
    onClearAll: () -> Unit,
    modifier: Modifier = Modifier,
    filterQuery: String = "",
) {
    val visibleHistory = remember(history, filterQuery, editing) {
        matchingSearchHistory(history, if (editing) "" else filterQuery)
    }
    val listState = rememberLazyListState()
    val haptic = LocalHapticFeedback.current
    val actionProgress by animateFloatAsState(if (editing) 1f else 0f, tween(180), label = "HistoryActions")
    Column(
        modifier.fillMaxSize().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("搜索历史", style = MiuixTheme.textStyles.title4)
                Text(
                    when {
                        editing -> "${history.size} 条记录 · 管理中"
                        filterQuery.isNotBlank() -> "${visibleHistory.size} / ${history.size} 条记录"
                        else -> "${history.size} 条记录"
                    },
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            QuietTextAction(
                if (editing) "完成" else "管理",
                onClick = { onEditingChange(!editing) },
                enabled = history.isNotEmpty() || editing,
                emphasized = editing,
            )
        }
        Box(Modifier.fillMaxWidth().weight(1f)) {
            if (visibleHistory.isNotEmpty()) {
                Card(Modifier.fillMaxWidth()) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(vertical = 4.dp),
                    ) {
                        items(visibleHistory, key = { it }) { query ->
                            BasicComponent(
                                title = query,
                                insideMargin = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                                modifier = if (!editing) Modifier.combinedClickable(
                                    role = Role.Button,
                                    onClick = { onSelected(query) },
                                    onLongClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        onEditingChange(true)
                                    },
                                    onLongClickLabel = "管理搜索历史",
                                ) else Modifier,
                                endActions = {
                                    Box(
                                        Modifier.size(44.dp).graphicsLayer {
                                            alpha = actionProgress
                                            translationX = 6.dp.toPx() * (1f - actionProgress)
                                        }.then(if (editing) Modifier else Modifier.clearAndSetSemantics {}),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        IconButton(onClick = { onDelete(query) }, enabled = editing, minWidth = 44.dp, minHeight = 44.dp) {
                                            Icon(MiuixIcons.Delete, contentDescription = "删除搜索记录：$query", tint = MiuixTheme.colorScheme.error, modifier = Modifier.size(20.dp))
                                        }
                                    }
                                },
                            )
                        }
                    }
                }
            } else {
                Column(Modifier.fillMaxWidth().padding(vertical = 40.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (history.isEmpty()) "暂无搜索历史" else "没有匹配的历史", style = MiuixTheme.textStyles.title4)
                    Text(if (history.isEmpty()) "输入标题、标签或 JM 车号开始搜索" else "可继续输入，或直接执行搜索", style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                }
            }
        }
        Row(Modifier.fillMaxWidth().height(44.dp).padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (editing) "仅影响此设备" else "长按记录可管理",
                modifier = Modifier.weight(1f),
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Box(Modifier.graphicsLayer { alpha = actionProgress }.then(if (editing) Modifier else Modifier.clearAndSetSemantics {})) {
                QuietTextAction("清空全部", onClearAll, enabled = editing && history.isNotEmpty(), destructive = true)
            }
        }
    }
}

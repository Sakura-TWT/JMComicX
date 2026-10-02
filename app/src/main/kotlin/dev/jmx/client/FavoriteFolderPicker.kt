package dev.jmx.client

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import dev.jmx.client.core.api.FavoriteFolder
import dev.jmx.client.core.result.JmxResult
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

/** Shared by adding a favorite and moving existing favorites; folder 0 is never a move target. */
@Composable
internal fun FavoriteFolderPicker(
    show: Boolean,
    repository: AccountDataRepository,
    sessionRevision: Int,
    onDismiss: () -> Unit,
    onConfirm: (FavoriteFolder) -> Unit,
    busy: Boolean = false,
    error: String? = null,
    onDefault: (() -> Unit)? = null,
) {
    var folders by remember(repository, sessionRevision) { mutableStateOf<List<FavoriteFolder>>(emptyList()) }
    var loading by remember(repository, sessionRevision) { mutableStateOf(true) }
    var loadError by remember(repository, sessionRevision) { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    var selectedId by rememberSaveable(show, sessionRevision) { mutableStateOf<String?>(null) }
    val selectedFolder = folders.firstOrNull { it.id == selectedId }
    val contentHeight = with(LocalDensity.current) {
        (LocalWindowInfo.current.containerSize.height.toDp() - 160.dp).coerceIn(140.dp, 520.dp)
    }

    LaunchedEffect(show, repository, sessionRevision, retry) {
        if (!show) return@LaunchedEffect
        loading = true
        loadError = null
        val requestedIdentity = repository.accountIdentity()
        val result = repository.loadFavoriteFolders()
        if (repository.accountIdentity() != requestedIdentity) {
            folders = emptyList()
            selectedId = null
            loadError = "账号已变化，请重新打开资料夹选择"
            loading = false
            return@LaunchedEffect
        }
        when (result) {
            is JmxResult.Success -> {
                folders = favoriteFolderChoices(result.value)
                if (selectedId != "0" && folders.none { it.id == selectedId }) selectedId = null
            }
            is JmxResult.Failure -> {
                folders = emptyList()
                loadError = result.error.toUiMessage()
            }
        }
        loading = false
    }

    WindowDialog(
        show = show,
        title = if (onDefault != null) "选择收藏资料夹" else "移动到资料夹",
        onDismissRequest = { if (!busy) onDismiss() },
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().heightIn(max = contentHeight),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (busy) {
                LinearProgressIndicator()
                Text(text = "正在处理，请稍候…", style = MiuixTheme.textStyles.footnote1)
            }
            if (error != null) {
                Text(text = error, color = MiuixTheme.colorScheme.error, style = MiuixTheme.textStyles.footnote1)
            }
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                contentPadding = PaddingValues(0.dp),
            ) {
                if (onDefault != null) {
                    item(key = "default") {
                        FavoriteFolderChoice(
                            title = "默认收藏",
                            summary = "仅收藏，不指定资料夹",
                            checked = selectedId == "0",
                            enabled = !busy,
                            onClick = { selectedId = "0" },
                        )
                    }
                }
                when {
                    loading -> item(key = "loading") {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            CircularProgressIndicator(size = 24.dp)
                            Text(text = "正在读取资料夹…", style = MiuixTheme.textStyles.body2)
                        }
                    }
                    loadError != null -> item(key = "error") {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(text = loadError.orEmpty(), color = MiuixTheme.colorScheme.error, style = MiuixTheme.textStyles.body2)
                            TextButton(text = "重试", onClick = { retry++ }, enabled = !busy)
                        }
                    }
                    folders.isEmpty() -> item(key = "empty") {
                        Text(
                            text = if (onDefault != null) "暂无自建资料夹，可使用默认收藏。" else "暂无可移动的资料夹。请先在账号中创建资料夹，然后重试。",
                            modifier = Modifier.padding(12.dp),
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                        TextButton(text = "刷新资料夹", onClick = { retry++ }, enabled = !busy)
                    }
                    else -> items(folders, key = FavoriteFolder::id) { folder ->
                        FavoriteFolderChoice(
                            title = folder.displayName(),
                            summary = null,
                            checked = selectedId == folder.id,
                            enabled = !busy,
                            onClick = { selectedId = folder.id },
                        )
                    }
                }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(text = "取消", onClick = onDismiss, enabled = !busy, modifier = Modifier.weight(1f))
                TextButton(
                    text = if (busy) "正在处理…" else "确认",
                    onClick = {
                        if (selectedId == "0" && onDefault != null) onDefault()
                        else selectedFolder?.let(onConfirm)
                    },
                    enabled = !busy && ((selectedId == "0" && onDefault != null) || (!loading && loadError == null && selectedFolder != null)),
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            }
        }
    }
}

@Composable
private fun FavoriteFolderChoice(
    title: String,
    summary: String?,
    checked: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {
            role = Role.RadioButton
            selected = checked
        },
        shape = RoundedCornerShape(12.dp),
        color = if (checked) MiuixTheme.colorScheme.primary.copy(alpha = 0.1f) else Color.Transparent,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(text = title, style = MiuixTheme.textStyles.body2)
                if (summary != null) Text(text = summary, style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
            Checkbox(state = ToggleableState(checked), onClick = null, enabled = enabled)
        }
    }
}

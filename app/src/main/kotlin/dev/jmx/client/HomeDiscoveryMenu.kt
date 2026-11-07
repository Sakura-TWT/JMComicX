package dev.jmx.client

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.MoreCircle
import top.yukonga.miuix.kmp.menu.WindowIconDropdownMenu
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Navigation belongs to the toolbar, not to the home content-category tab row. */
@Composable
internal fun HomeDiscoveryMenu(onWeekly: () -> Unit, onRanking: () -> Unit, enabled: Boolean = true) {
    WindowIconDropdownMenu(
        entry = DropdownEntry(items = listOf(
            DropdownItem(text = "每周必看", summary = "按期刊浏览精选作品", onClick = onWeekly),
            DropdownItem(text = "排行榜", summary = "日榜、周榜、月榜与总榜", onClick = onRanking),
        )),
        enabled = enabled,
        minHeight = 44.dp,
        minWidth = 68.dp,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(MiuixIcons.MoreCircle, contentDescription = "发现", tint = MiuixTheme.colorScheme.onSurface, modifier = Modifier.size(20.dp))
            Text("发现", style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurface)
        }
    }
}

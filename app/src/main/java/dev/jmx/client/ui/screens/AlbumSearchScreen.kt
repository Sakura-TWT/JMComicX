package dev.jmx.client.ui.screens

import android.net.Uri

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jmx.client.store.HistorySearchManager
import dev.jmx.client.store.JmxDiagnostics
import dev.jmx.client.ui.components.AlbumSearchHistoryTag
import dev.jmx.client.ui.glass.LocalJmxGlassPalette
import dev.jmx.client.ui.razor.AppleIconButton
import dev.jmx.client.ui.razor.RazorIcon
import dev.jmx.client.ui.razor.RazorText
import org.koin.compose.getKoin

@Composable
fun AlbumSearchScreen(
    historySearchManager: HistorySearchManager = getKoin().get()
) {
    val mainNavController = LocalMainNavController.current
    val palette = LocalJmxGlassPalette.current
    val focusRequester = remember { FocusRequester() }
    var searchText by remember { mutableStateOf("") }
    val historySearchState by historySearchManager.historySearchState.collectAsState()

    fun onSearch(text: String) {
        val value = text.trim()
        if (value.isBlank()) return
        JmxDiagnostics.userAction(
            screen = "AlbumSearch",
            action = "submit_search",
            target = "search_field",
            metadata = mapOf(
                "input_length" to value.length,
                "from_history" to (value in historySearchState)
            )
        )
        historySearchManager.addItem(value)
        mainNavController.navigate("albumSearchResult/${Uri.encode(value)}") {
            launchSingleTop = true
        }
    }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.page)
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = 16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp, bottom = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            AppleIconButton(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "返回",
                onClick = { mainNavController.popBackStack() }
            )
            Row(
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(palette.contentSurface.copy(alpha = 0.76f))
                    .padding(horizontal = 13.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(9.dp)
            ) {
                RazorIcon(
                    imageVector = Icons.Default.Search,
                    contentDescription = null,
                    modifier = Modifier.size(19.dp),
                    tint = palette.tertiaryText
                )
                Box(modifier = Modifier.weight(1f)) {
                    BasicTextField(
                        value = searchText,
                        onValueChange = { searchText = it },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester),
                        textStyle = TextStyle(
                            color = palette.primaryText,
                            fontSize = 16.sp,
                            lineHeight = 21.sp,
                            fontWeight = FontWeight.Normal
                        ),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(
                            onSearch = { onSearch(searchText) }
                        ),
                        interactionSource = remember { MutableInteractionSource() }
                    )
                    if (searchText.isEmpty()) {
                        RazorText(
                            text = "搜索漫画、作者或标签",
                            style = TextStyle(
                                color = palette.tertiaryText,
                                fontSize = 16.sp,
                                lineHeight = 21.sp
                            )
                        )
                    }
                }
                if (searchText.isNotEmpty()) {
                    AppleIconButton(
                        imageVector = Icons.Default.Close,
                        contentDescription = "清空",
                        modifier = Modifier.size(28.dp),
                        onClick = {
                            searchText = ""
                        },
                        tint = palette.secondaryText
                    )
                }
            }
            AppleIconButton(
                imageVector = Icons.Default.Search,
                contentDescription = "搜索",
                selected = true,
                onClick = { onSearch(searchText) }
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            RazorText(
                text = "搜索历史",
                style = TextStyle(
                    color = palette.primaryText,
                    fontSize = 22.sp,
                    lineHeight = 27.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.4).sp
                )
            )
            if (historySearchState.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(palette.contentSurface.copy(alpha = 0.72f))
                        .padding(horizontal = 12.dp, vertical = 7.dp)
                        .noRippleClickable { historySearchManager.clear() }
                ) {
                    RazorText(
                        text = "清空",
                        modifier = Modifier.align(Alignment.Center),
                        style = TextStyle(
                            color = palette.accent,
                            fontSize = 14.sp,
                            lineHeight = 18.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(14.dp))
        if (historySearchState.isNotEmpty()) {
            FlowRow(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                historySearchState.forEach {
                    key(it) {
                        AlbumSearchHistoryTag(
                            label = it,
                            onClick = { onSearch(it) }
                        )
                    }
                }
            }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    RazorIcon(
                        imageVector = Icons.Default.Search,
                        contentDescription = null,
                        modifier = Modifier.size(32.dp),
                        tint = palette.tertiaryText
                    )
                    RazorText(
                        text = "暂无搜索历史",
                        style = TextStyle(
                            color = palette.tertiaryText,
                            fontSize = 15.sp,
                            lineHeight = 20.sp
                        )
                    )
                }
            }
        }
    }
}

private fun Modifier.noRippleClickable(onClick: () -> Unit): Modifier = clickable(
    interactionSource = MutableInteractionSource(),
    indication = null,
    onClick = onClick
)

package dev.jmx.client.ui.screens.readScreen

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.jmx.client.data.models.AlbumChapter
import dev.jmx.client.ui.glass.LocalJmxGlassPalette
import dev.jmx.client.ui.razor.RazorText

@Composable
private fun ChapterRow(
    label: String,
    index: Int,
    selected: Boolean,
    showDivider: Boolean,
    onClick: () -> Unit,
) {
    val palette = LocalJmxGlassPalette.current
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val pressAlpha by animateFloatAsState(
        targetValue = if (pressed) 0.08f else 0f,
        animationSpec = spring(dampingRatio = 0.86f, stiffness = Spring.StiffnessMedium),
        label = "chapterRowPress"
    )

    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(palette.primaryText.copy(alpha = pressAlpha))
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onClick
                )
                .padding(horizontal = 20.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RazorText(
                text = (index + 1).toString().padStart(2, '0'),
                style = TextStyle(
                    color = if (selected) palette.accent else palette.tertiaryText,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    fontWeight = FontWeight.SemiBold
                )
            )
            RazorText(
                text = label,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(
                    color = palette.primaryText,
                    fontSize = 17.sp,
                    lineHeight = 22.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    letterSpacing = (-0.2).sp
                )
            )
            if (selected) {
                RazorText(
                    text = "当前",
                    style = TextStyle(
                        color = palette.accent,
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                )
            }
        }
        if (showDivider) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .fillMaxWidth()
                    .padding(start = 20.dp)
                    .background(palette.primaryText.copy(alpha = 0.08f))
                    .height(0.6.dp)
            )
        }
    }
}

@Composable
fun ChapterPickerSheet(
    currentAlbumId: Int,
    chapterList: List<AlbumChapter>,
    onDismissRequest: () -> Unit,
    onChapterClick: (AlbumChapter) -> Unit,
) {
    val palette = LocalJmxGlassPalette.current
    val density = LocalDensity.current
    val maxHeight = with(density) {
        (LocalWindowInfo.current.containerSize.height * 0.58f).toDp()
    }
    val currentIndex = chapterList.indexOfFirst { it.id == currentAlbumId }
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = (currentIndex - 3).coerceAtLeast(0)
    )
    val sheetColor = if (palette.page.luminance() > 0.5f) {
        Color.White.copy(alpha = 0.96f)
    } else {
        Color(0xFF1C1C1E).copy(alpha = 0.96f)
    }
    var appeared by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        appeared = true
    }
    val translate by animateFloatAsState(
        targetValue = if (appeared) 0f else 48f,
        animationSpec = spring(dampingRatio = 0.86f, stiffness = Spring.StiffnessMediumLow),
        label = "chapterSheetTranslate"
    )
    val alpha by animateFloatAsState(
        targetValue = if (appeared) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.90f, stiffness = Spring.StiffnessMediumLow),
        label = "chapterSheetAlpha"
    )

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.34f))
                .clickable(
                    interactionSource = null,
                    indication = null,
                    onClick = onDismissRequest
                )
                .padding(horizontal = 12.dp)
                .navigationBarsPadding(),
            contentAlignment = Alignment.BottomCenter
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 470.dp)
                    .padding(bottom = 10.dp)
                    .graphicsLayer {
                        translationY = translate
                        this.alpha = alpha
                    },
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(28.dp))
                        .background(sheetColor)
                        .clickable(
                            interactionSource = null,
                            indication = null,
                            onClick = {}
                        )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        RazorText(
                            text = "选择章节",
                            style = TextStyle(
                                color = palette.primaryText,
                                fontSize = 13.sp,
                                lineHeight = 17.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        )
                        RazorText(
                            text = if (currentIndex >= 0) {
                                "当前第 ${currentIndex + 1} 话，共 ${chapterList.size} 话"
                            } else {
                                "共 ${chapterList.size} 话"
                            },
                            style = TextStyle(
                                color = palette.tertiaryText,
                                fontSize = 12.sp,
                                lineHeight = 16.sp
                            )
                        )
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(palette.primaryText.copy(alpha = 0.08f))
                            .height(0.6.dp)
                    )
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = maxHeight)
                    ) {
                        itemsIndexed(chapterList, key = { _, item -> item.id }) { index, item ->
                            ChapterRow(
                                label = item.name.ifBlank { "第 ${index + 1} 话" },
                                index = index,
                                selected = item.id == currentAlbumId,
                                showDivider = index < chapterList.lastIndex,
                                onClick = { onChapterClick(item) }
                            )
                        }
                    }
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .clip(RoundedCornerShape(22.dp))
                        .background(sheetColor)
                        .clickable(
                            interactionSource = null,
                            indication = null,
                            onClick = onDismissRequest
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    RazorText(
                        text = "取消",
                        style = TextStyle(
                            color = palette.accent,
                            fontSize = 17.sp,
                            lineHeight = 22.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    )
                }
            }
        }
    }
}

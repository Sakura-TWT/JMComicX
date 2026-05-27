package dev.jmx.client.ui.screens.downloadScreen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import dev.jmx.client.ui.components.CommonScaffold
import dev.jmx.client.ui.glass.LocalJmxGlassPalette
import dev.jmx.client.ui.razor.RazorPullRefreshBox
import dev.jmx.client.ui.razor.RazorText
import dev.jmx.client.ui.screens.LocalMainNavController
import dev.jmx.client.ui.state.rememberTabIndexState
import dev.jmx.client.ui.viewModel.DownloadViewModel
import dev.jmx.client.store.ToastManager
import kotlinx.coroutines.launch
import org.koin.compose.getKoin
import org.koin.compose.viewmodel.koinActivityViewModel
import java.io.File

private val tabList = listOf("下载中" to "downloading", "已下载" to "complete")

@Composable
fun DownloadScreen(
    downloadViewModel: DownloadViewModel = koinActivityViewModel(),
    toastManager: ToastManager = getKoin().get()
) {
    val palette = LocalJmxGlassPalette.current
    val mainNavController = LocalMainNavController.current
    val coroutineScope = rememberCoroutineScope()
    val selectedTabIndexState = rememberTabIndexState()
    val scrollState = rememberScrollState()
    val pagerState = rememberPagerState(initialPage = 0) {
        tabList.size
    }
    val downloadLazyPagingItems = downloadViewModel.downloadPager.collectAsLazyPagingItems()
    val onTabClick: (index: Int) -> Unit = {
        selectedTabIndexState.value = it
        downloadViewModel.updateDownloadStatusFilter(tabList[it].second)
        coroutineScope.launch {
            pagerState.animateScrollToPage(selectedTabIndexState.value)
        }
    }
    CommonScaffold(title = "下载") {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(scrollState)
                    .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                tabList.forEachIndexed { index, item ->
                    key(item.second) {
                        val selected = selectedTabIndexState.value == index
                        RazorText(
                            modifier = Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .background(
                                    if (selected) palette.primaryText else palette.contentSurface.copy(alpha = 0.72f)
                                )
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    onClick = { onTabClick(index) }
                                )
                                .padding(horizontal = 16.dp, vertical = 9.dp),
                            text = item.first,
                            maxLines = 1,
                            style = TextStyle(
                                color = if (selected) palette.page else palette.primaryText,
                                fontSize = 14.sp,
                                lineHeight = 18.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        )
                    }
                }
            }
            val isRefreshing = downloadLazyPagingItems.loadState.refresh is LoadState.Loading
            RazorPullRefreshBox(
                isRefreshing = isRefreshing,
                onRefresh = {
                    downloadLazyPagingItems.refresh()
                }
            ) {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.Top),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(10.dp)
                ) {
                    items(
                        downloadLazyPagingItems.itemCount,
                        key = downloadLazyPagingItems.itemKey { it.id },
                    ) { index ->
                        val item = downloadLazyPagingItems[index]
                        if (item != null) {
                            DownloadListItem(
                                album = item,
                                onClick = { album ->
                                    when {
                                        album.status != "complete" -> {
                                            toastManager.showAsync("漫画仍在下载中，完成后可阅读")
                                        }

                                        album.zipPath.isBlank() || !File(album.zipPath).exists() -> {
                                            toastManager.showAsync("下载文件不存在，请重新下载")
                                        }

                                        else -> {
                                            mainNavController.navigate("albumRead/${album.id}") {
                                                launchSingleTop = true
                                            }
                                        }
                                    }
                                }
                            )
                        }
                    }
//                    items(count = 4, key = { it }) {
//                        DownloadListItem(
//                            album = DownloadAlbum(
//                                2,
//                                "test name",
//                                listOf("test author"),
//                                "none",
//                                listOf(),
//                                "",
//                                .5f,
//                                "pending"
//                            )
//                        )
//                    }
                }
            }

        }
    }
}

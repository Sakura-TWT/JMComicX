package dev.jmx.client.ui.screens.downloadScreen

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.ImageLoader
import coil.compose.AsyncImage
import dev.jmx.client.database.model.DownloadAlbum
import dev.jmx.client.ui.glass.GlassPanel
import dev.jmx.client.ui.glass.LocalJmxGlassPalette
import dev.jmx.client.ui.razor.RazorGlassButton
import dev.jmx.client.ui.razor.RazorLoadingIndicator
import dev.jmx.client.ui.razor.RazorText
import dev.jmx.client.utils.shimmer
import org.koin.compose.getKoin
import java.io.File

@Composable
private fun AlbumCoverImage(
    album: DownloadAlbum,
    imageLoader: ImageLoader = getKoin().get()
) {
    if (album.coverPath.isNotBlank()) {
        val file = File(album.coverPath)
        Box(modifier = Modifier.fillMaxWidth()) {
            AsyncImage(
                model = file,
                imageLoader = imageLoader,
                contentDescription = "${album.name}的封面",
                contentScale = ContentScale.FillBounds,
                modifier = Modifier
                    .aspectRatio(3f / 4f)
                    .fillMaxWidth(),
            )
        }
    } else {
        Box(modifier = Modifier
            .aspectRatio(3 / 4f)
            .shimmer()) {
        }
    }
}

@Composable
fun DownloadListItem(
    modifier: Modifier = Modifier,
    album: DownloadAlbum,
    onClick: (DownloadAlbum) -> Unit
) {
    val palette = LocalJmxGlassPalette.current
    GlassPanel(
        modifier = Modifier.clickable(
            interactionSource = null,
            indication = null,
            onClick = { onClick(album) }
        ),
        cornerRadius = 24.dp,
        useContentSurface = true
    ) {
        Box(modifier = modifier) {
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                AlbumCoverImage(album)
                RazorText(
                    modifier = Modifier
                        .padding(horizontal = 8.dp),
                    text = album.name,
                    overflow = TextOverflow.Ellipsis,
                    maxLines = 1,
                    style = TextStyle(
                        color = palette.primaryText,
                        fontSize = 13.sp,
                        lineHeight = 16.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                )
                RazorText(
                    modifier = Modifier
                        .padding(horizontal = 8.dp)
                        .padding(bottom = 8.dp),
                    text = album.authorList.joinToString(",").ifBlank { "暂无作者" },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(
                        color = palette.secondaryText,
                        fontSize = 12.sp,
                        lineHeight = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                )
            }
            when (album.status) {
                "pending", "downloading", "error" -> {
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .background(Color.Black.copy(alpha = 0.3f))
                    )
                }

                else -> {

                }
            }
            when (album.status) {
                "pending" -> {
                    RazorText(
                        modifier = Modifier.align(Alignment.Center),
                        text = "等待中",
                        style = TextStyle(
                            color = Color.White,
                            fontSize = 14.sp,
                            lineHeight = 18.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    )
                }

                "downloading" -> {
                    val animatedProgress by animateFloatAsState(
                        targetValue = album.progress,
                        label = "progressAnimation"
                    )
                    RazorLoadingIndicator(
                        progress = animatedProgress,
                        modifier = Modifier.align(Alignment.Center)
                    )
                }

                "error" -> {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        RazorText(
                            text = "出错了",
                            style = TextStyle(
                                color = Color.White,
                                fontSize = 14.sp,
                                lineHeight = 18.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        )
                        RazorGlassButton(onClick = {
                            // TODO
                        }) {
                            RazorText(
                                text = "重新下载",
                                style = TextStyle(
                                    color = palette.primaryText,
                                    fontSize = 13.sp,
                                    lineHeight = 16.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            )
                        }
                    }

                }

                else -> {

                }
            }
        }
    }
}

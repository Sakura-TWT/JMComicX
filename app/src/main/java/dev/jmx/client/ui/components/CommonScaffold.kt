package dev.jmx.client.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.jmx.client.ui.glass.LocalJmxBackdrop
import dev.jmx.client.ui.glass.LocalJmxGlassPalette
import dev.jmx.client.ui.glass.JmxGlassStage
import dev.jmx.client.ui.glass.jmxContentBackdrop
import dev.jmx.client.ui.razor.RazorBackTopBar
import dev.jmx.client.ui.screens.LocalMainNavController

@Composable
fun CommonScaffold(
    title: String,
    onTitleClick: (() -> Unit)? = null,
    actions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    content: @Composable (() -> Unit)? = null
) {
    val mainNavController = LocalMainNavController.current
    val palette = LocalJmxGlassPalette.current

    JmxGlassStage {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(palette.page)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .jmxContentBackdrop(it)
                    .padding(top = 102.dp)
            ) {
                content?.invoke()
            }
            CompositionLocalProvider(LocalJmxBackdrop provides it) {
                RazorBackTopBar(
                    title = title,
                    onTitleClick = onTitleClick,
                    onBack = { mainNavController.popBackStack() },
                    actions = actions
                )
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.BottomCenter
                ) {
                    bottomBar()
                }
            }
        }
    }
}

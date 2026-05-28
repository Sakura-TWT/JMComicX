package dev.jmx.client

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jmx.client.cache.trimAppCaches
import dev.jmx.client.store.AppUpdateManager
import dev.jmx.client.store.JmxDiagnostics
import dev.jmx.client.store.ToastManager
import dev.jmx.client.store.UpdateInfo
import dev.jmx.client.ui.glass.LocalJmxGlassPalette
import dev.jmx.client.ui.screens.AppScreen
import dev.jmx.client.ui.razor.RazorText
import dev.jmx.client.ui.viewModel.GlobalViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.koin.compose.getKoin
import org.koin.compose.viewmodel.koinActivityViewModel
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@Composable
private fun RazorToastHost(message: String?) {
    val palette = LocalJmxGlassPalette.current
    AnimatedVisibility(
        visible = message != null,
        modifier = Modifier
            .navigationBarsPadding()
            .imePadding()
            .padding(start = 22.dp, end = 22.dp, bottom = 90.dp),
        enter = slideInVertically(
            initialOffsetY = { it / 2 },
            animationSpec = tween(durationMillis = 220)
        ) + fadeIn(animationSpec = tween(durationMillis = 180)),
        exit = slideOutVertically(
            targetOffsetY = { it / 2 },
            animationSpec = tween(durationMillis = 180)
        ) + fadeOut(animationSpec = tween(durationMillis = 150))
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(18.dp))
                .background(palette.primaryText.copy(alpha = 0.90f))
                .padding(horizontal = 18.dp, vertical = 13.dp),
            contentAlignment = Alignment.Center
        ) {
            RazorText(
                text = message.orEmpty(),
                style = TextStyle(
                    color = palette.page,
                    fontSize = 14.sp,
                    lineHeight = 18.sp,
                    fontWeight = FontWeight.SemiBold
                )
            )
        }
    }
}

@Composable
private fun UpdateActionButton(
    text: String,
    emphasized: Boolean,
    onClick: () -> Unit
) {
    val palette = LocalJmxGlassPalette.current
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .background(
                if (emphasized) {
                    palette.accent
                } else {
                    palette.contentSurface.copy(alpha = 0.72f)
                }
            )
            .padding(horizontal = 16.dp, vertical = 13.dp),
        contentAlignment = Alignment.Center
    ) {
        RazorText(
            text = text,
            style = TextStyle(
                color = if (emphasized) palette.page else palette.primaryText,
                fontSize = 15.sp,
                lineHeight = 19.sp,
                fontWeight = FontWeight.SemiBold
            )
        )
    }
}

@Composable
private fun AppUpdateDialog(
    info: UpdateInfo,
    onUpdate: () -> Unit,
    onLater: () -> Unit,
    onDisableAutoPrompt: () -> Unit
) {
    val palette = LocalJmxGlassPalette.current
    Dialog(
        onDismissRequest = {
            if (!info.forceUpdate) {
                onLater()
            }
        },
        properties = DialogProperties(
            dismissOnBackPress = !info.forceUpdate,
            dismissOnClickOutside = !info.forceUpdate
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(34.dp))
                .background(palette.contentSurface.copy(alpha = 0.94f))
                .padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            RazorText(
                text = if (info.forceUpdate) "重要更新可用" else "发现新版本",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(
                    color = palette.primaryText,
                    fontSize = 24.sp,
                    lineHeight = 29.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = (-0.4).sp
                )
            )
            RazorText(
                text = "JMX v${info.versionName}",
                style = TextStyle(
                    color = palette.accent,
                    fontSize = 16.sp,
                    lineHeight = 20.sp,
                    fontWeight = FontWeight.SemiBold
                )
            )
            RazorText(
                text = info.body.ifBlank { "新版本已发布，建议更新以获得更稳定的体验。" }
                    .lineSequence()
                    .filter { it.isNotBlank() }
                    .take(5)
                    .joinToString("\n"),
                style = TextStyle(
                    color = palette.secondaryText,
                    fontSize = 14.sp,
                    lineHeight = 22.sp,
                    fontWeight = FontWeight.Medium
                )
            )
            Spacer(modifier = Modifier.height(2.dp))
            UpdateActionButton(
                text = "立即更新",
                emphasized = true,
                onClick = onUpdate
            )
            if (!info.forceUpdate) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(modifier = Modifier.weight(1f)) {
                        UpdateActionButton(
                            text = "稍后",
                            emphasized = false,
                            onClick = onLater
                        )
                    }
                    Box(modifier = Modifier.weight(1f)) {
                        UpdateActionButton(
                            text = "不再提示",
                            emphasized = false,
                            onClick = onDisableAutoPrompt
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun App(
    globalViewModel: GlobalViewModel = koinActivityViewModel(),
    toastManager: ToastManager = getKoin().get(),
    appUpdateManager: AppUpdateManager = getKoin().get()
) {
    var toastMessage by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val updateUiState by appUpdateManager.uiState.collectAsState()

    LaunchedEffect(Unit) {
        JmxDiagnostics.i(
            "App",
            "Global init requested",
            metadata = mapOf("stage" to "global_view_model_init")
        )
        globalViewModel.init()
    }
    LaunchedEffect(Unit) {
        JmxDiagnostics.i(
            "Update",
            "Startup update check requested",
            metadata = mapOf("stage" to "startup")
        )
        appUpdateManager.checkForUpdate(showResultToast = false, fromStartup = true)
    }
    LaunchedEffect(context) {
        withContext(Dispatchers.IO) {
            val start = System.nanoTime()
            JmxDiagnostics.i("Cache", "App cache trim started", metadata = mapOf("reason" to "startup"))
            trimAppCaches(context.applicationContext)
            JmxDiagnostics.i(
                "Cache",
                "App cache trim finished",
                metadata = mapOf(
                    "reason" to "startup",
                    "cost_ms" to ((System.nanoTime() - start) / 1_000_000)
                )
            )
        }
    }
    LaunchedEffect(Unit) {
        toastManager.message.collect { text ->
            toastMessage = text
            delay(2400)
            if (toastMessage == text) {
                toastMessage = null
            }
        }
    }
    Box(modifier = Modifier.fillMaxSize()) {
        AppScreen()
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.BottomCenter
        ) {
            RazorToastHost(message = toastMessage)
        }
        updateUiState.dialogInfo?.let { info ->
            AppUpdateDialog(
                info = info,
                onUpdate = {
                    uriHandler.openUri(info.downloadUrl ?: info.releaseUrl)
                    appUpdateManager.dismissDialog()
                },
                onLater = appUpdateManager::dismissDialog,
                onDisableAutoPrompt = appUpdateManager::disableAutoPrompt
            )
        }
    }
}

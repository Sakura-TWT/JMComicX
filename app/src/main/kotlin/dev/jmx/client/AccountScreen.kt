package dev.jmx.client

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.vector.ImageVector
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Contacts
import top.yukonga.miuix.kmp.icon.extended.Favorites
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.Recent
import top.yukonga.miuix.kmp.icon.extended.Show
import top.yukonga.miuix.kmp.icon.extended.Tasks
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog
import androidx.compose.foundation.text.KeyboardOptions

internal data class LoginUiFailure(
    val title: String,
    val message: String,
)

@Composable
internal fun AccountScreen(
    innerPadding: PaddingValues,
    profile: AccountProfile?,
    imageHost: String,
    onLoginRequested: () -> Unit,
    onLogout: () -> Unit,
    onFavorites: () -> Unit,
    onHistory: () -> Unit,
    onDaily: () -> Unit,
    onAbout: () -> Unit,
) {
    val avatarUrl = remember(profile?.avatar, imageHost) {
        resolveUserAvatarUrl(imageHost, profile?.avatar)
    }
    val levelName = remember(profile?.levelName) {
        profile?.levelName?.takeIf { it.isNotBlank() }
    }
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MiuixTheme.colorScheme.surface),
        contentPadding = PaddingValues(
            start = 12.dp,
            top = innerPadding.calculateTopPadding() + 18.dp,
            end = 12.dp,
            bottom = innerPadding.calculateBottomPadding() + 28.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item(key = "account-header") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = profile == null, onClick = onLoginRequested)
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AccountAvatar(url = avatarUrl, username = profile?.username ?: "未登录")
                Spacer(modifier = Modifier.width(18.dp))
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Text(
                        text = profile?.username ?: "未登录",
                        style = MiuixTheme.textStyles.title2,
                        fontWeight = FontWeight.SemiBold,
                        color = MiuixTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (levelName != null) {
                        Text(
                            text = levelName,
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (profile == null) {
                        Text(
                            text = "点击登录后查看账户资料",
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    } else {
                        profile.id?.let { uid ->
                            Text(
                                text = "UID $uid",
                                style = MiuixTheme.textStyles.footnote2,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                    }
                }
            }
        }
        item(key = "account-overview") {
            Card(
                modifier = Modifier.fillMaxWidth(),
                insideMargin = PaddingValues(18.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    AccountStat("等级", profile?.level?.let { "LV $it" } ?: "-")
                    AccountStat("J coins", profile?.coin?.toString() ?: "-")
                    AccountStat("收藏", profile?.currentFavoriteCount?.toString() ?: "-")
                }
                Spacer(modifier = Modifier.height(22.dp))
                AccountProgress(
                    title = "等级经验",
                    value = profile?.currentLevelExp,
                    maximum = profile?.nextLevelExp,
                    progress = accountProgress(
                        profile?.currentLevelExp,
                        profile?.nextLevelExp,
                        profile?.expPercent,
                    ),
                )
                Spacer(modifier = Modifier.height(18.dp))
                AccountProgress(
                    title = "收藏容量",
                    value = profile?.currentFavoriteCount,
                    maximum = profile?.maxFavoriteCount,
                    progress = accountProgress(
                        profile?.currentFavoriteCount,
                        profile?.maxFavoriteCount,
                    ),
                )
            }
        }
        item(key = "account-library") {
            Column {
                SmallTitle(text = "内容")
                Card(modifier = Modifier.fillMaxWidth()) {
                    ArrowPreference(
                        title = "漫画收藏",
                        startAction = { AccountPreferenceIcon(MiuixIcons.Favorites, "漫画收藏") },
                        onClick = onFavorites,
                    )
                    ArrowPreference(
                        title = "观看历史",
                        startAction = { AccountPreferenceIcon(MiuixIcons.Recent, "观看历史") },
                        onClick = onHistory,
                    )
                    ArrowPreference(
                        title = "每日签到",
                        startAction = { AccountPreferenceIcon(MiuixIcons.Tasks, "每日签到") },
                        onClick = onDaily,
                    )
                }
            }
        }
        item(key = "account-about") {
            Column {
                SmallTitle(text = "其他")
                Card(modifier = Modifier.fillMaxWidth()) {
                    ArrowPreference(
                        title = "关于 JMComicX",
                        startAction = { AccountPreferenceIcon(MiuixIcons.Info, "关于") },
                        onClick = onAbout,
                    )
                }
            }
        }
        if (profile != null) item(key = "account-logout") {
            TextButton(
                text = "退出登录",
                onClick = onLogout,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun AccountPreferenceIcon(imageVector: ImageVector, contentDescription: String) {
    Icon(
        imageVector = imageVector,
        contentDescription = contentDescription,
        modifier = Modifier
            .padding(end = 10.dp)
            .size(26.dp),
        tint = MiuixTheme.colorScheme.onSurface,
    )
}

@Composable
private fun AccountAvatar(url: String?, username: String) {
    Box(
        modifier = Modifier
            .size(82.dp)
            .clip(CircleShape)
            .background(MiuixTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) {
        if (url == null) {
            AccountAvatarFallback()
        } else {
            SubcomposeAsyncImage(
                model = url,
                contentDescription = "$username 的头像",
                modifier = Modifier.fillMaxSize(),
                loading = { CircularProgressIndicator(size = 24.dp, strokeWidth = 3.dp) },
                error = { AccountAvatarFallback() },
                success = { SubcomposeAsyncImageContent() },
            )
        }
    }
}

@Composable
private fun AccountAvatarFallback() {
    Icon(
        imageVector = MiuixIcons.Contacts,
        contentDescription = null,
        modifier = Modifier.size(36.dp),
        tint = MiuixTheme.colorScheme.onSurfaceContainerVariant,
    )
}

@Composable
private fun AccountStat(label: String, value: String) {
    Column(
        modifier = Modifier.width(98.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = value,
            style = MiuixTheme.textStyles.title3,
            fontWeight = FontWeight.SemiBold,
            color = MiuixTheme.colorScheme.onSurface,
            maxLines = 1,
        )
        Text(
            text = label,
            style = MiuixTheme.textStyles.footnote2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}

@Composable
private fun AccountProgress(
    title: String,
    value: Int?,
    maximum: Int?,
    progress: Float,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = title,
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = if (value != null && maximum != null) "$value/$maximum" else "-",
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
        LinearProgressIndicator(progress = progress, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
internal fun AccountLoginDialog(
    show: Boolean,
    initialUsername: String,
    submitting: Boolean,
    failure: LoginUiFailure?,
    onDismiss: () -> Unit,
    onSubmit: (username: String, password: String) -> Unit,
) {
    var username by remember(show, initialUsername) { mutableStateOf(initialUsername) }
    var password by remember(show) { mutableStateOf("") }
    var passwordVisible by remember(show) { mutableStateOf(false) }
    val canSubmit = username.isNotBlank() && password.isNotBlank() && !submitting

    WindowDialog(
        show = show,
        title = "账号登录",
        summary = failure?.let { "${it.title}：${it.message}" },
        onDismissRequest = { if (!submitting) onDismiss() },
    ) {
        TextField(
            value = username,
            onValueChange = { username = it },
            label = "账号",
            singleLine = true,
            enabled = !submitting,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(12.dp))
        TextField(
            value = password,
            onValueChange = { password = it },
            label = "密码",
            singleLine = true,
            enabled = !submitting,
            visualTransformation = if (passwordVisible) {
                VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            trailingIcon = {
                IconButton(onClick = { passwordVisible = !passwordVisible }) {
                    Icon(
                        imageVector = MiuixIcons.Show,
                        contentDescription = if (passwordVisible) "隐藏密码" else "显示密码",
                        tint = if (passwordVisible) {
                            MiuixTheme.colorScheme.primary
                        } else {
                            MiuixTheme.colorScheme.onSurfaceVariantActions
                        },
                    )
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(18.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TextButton(
                text = "取消",
                enabled = !submitting,
                onClick = onDismiss,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                text = if (submitting) "登录中" else "登录",
                enabled = canSubmit,
                onClick = { onSubmit(username.trim(), password) },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
        }
    }
}

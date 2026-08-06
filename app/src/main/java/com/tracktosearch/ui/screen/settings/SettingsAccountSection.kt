package com.tracktosearch.ui.screen.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.SubcomposeAsyncImage
import com.tracktosearch.R
import com.tracktosearch.ui.component.AdaptiveSingleLineText
import com.tracktosearch.ui.component.DoubanLogo
import com.tracktosearch.ui.component.TraktLogo
import com.tracktosearch.ui.util.HapticType
import com.tracktosearch.ui.util.performHaptic

/** 版本项：显示本地版本 + 最新版本状态 + 检查更新按钮（固定高度防跳动） */
@Composable
fun VersionItem(
    localVersion: String,
    latestVersion: String?,
    isChecking: Boolean,
    hasUpdate: Boolean,
    onCheckUpdate: () -> Unit
) {
    val context = LocalContext.current
    val view = LocalView.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Rounded.Info,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.settings_version),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(2.dp))
            // 版本副标题：有新版本用主题色显示"最新版本 xxx"，无新版本显示"已是最新版本xxx"
            val subtitle = if (latestVersion != null) {
                if (hasUpdate) {
                    stringResource(R.string.settings_latest_version, latestVersion)
                } else {
                    stringResource(R.string.settings_already_latest, latestVersion)
                }
            } else {
                "v$localVersion"
            }
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = if (hasUpdate) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        // 按钮区域固定宽度，防止 loading 态高度变化
        Box(modifier = Modifier.widthIn(min = 80.dp, max = 140.dp).height(36.dp), contentAlignment = Alignment.Center) {
            if (isChecking) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp
                )
            } else {
                OutlinedButton(
                    onClick = { view.performHaptic(HapticType.CLICK); onCheckUpdate() },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                ) {
                    AdaptiveSingleLineText(
                        text = stringResource(R.string.settings_check_update),
                        style = MaterialTheme.typography.labelLarge,
                        maxFontSize = 14.sp,
                        minFontSize = 10.sp
                    )
                }
            }
        }
    }
}

/**
 * 账户行：左 logo + 头像 + 主名称（含可选 ID 小字）+ 登出按钮。
 * 用于账户卡片中 Trakt / 豆瓣两行，统一行结构。
 *
 * - avatarUrl 为空且 showAvatar=true 时显示占位图标（未登录或加载失败）
 * - primaryName 为 null 时显示骨架占位（等待加载）
 * - secondaryName 非空时在主名称下方以小字显示（豆瓣 ID 行用）
 */
@Composable
internal fun AccountRow(
    brandLogo: @Composable () -> Unit,
    avatarUrl: String,
    primaryName: String?,
    secondaryName: String?,
    showAvatar: Boolean,
    isVip: Boolean,
    onLogout: () -> Unit
) {
    val view = LocalView.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 账户 logo：固定宽度让 Trakt/豆瓣两行的头像起点对齐
        Box(
            modifier = Modifier.width(40.dp),
            contentAlignment = Alignment.Center
        ) {
            brandLogo()
        }
        Spacer(modifier = Modifier.width(8.dp))
        // 头像：仅在 showAvatar=true 时展示（豆瓣未登录时无头像）
        if (showAvatar) {
            SubcomposeAsyncImage(
                model = avatarUrl,
                contentDescription = null,
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface),
                contentScale = ContentScale.Crop,
                loading = {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.surface),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                error = {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.surface),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Person,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            )
            Spacer(modifier = Modifier.width(12.dp))
        } else {
            // 未登录豆瓣：用占位图标保持视觉对齐
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.Movie,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
        }
        // 主名称 + 可选副名称（豆瓣 ID 小字）
        Column(modifier = Modifier.weight(1f)) {
            if (primaryName == null) {
                // 名称骨架
                Box(
                    modifier = Modifier
                        .width(80.dp)
                        .height(16.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colorScheme.surface)
                )
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = primaryName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (isVip) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        ) {
                            Text(
                                text = "VIP",
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                            )
                        }
                    }
                }
            }
            if (secondaryName != null) {
                Text(
                    text = secondaryName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        // 右侧登出按钮（固定高度确保两行按钮大小一致）
        Button(
            onClick = {
                view.performHaptic(HapticType.HEAVY_CLICK)
                onLogout()
            },
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError
            ),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
            modifier = Modifier.height(36.dp)
        ) {
            AdaptiveSingleLineText(
                text = stringResource(R.string.settings_logout_button),
                style = MaterialTheme.typography.labelLarge,
                maxFontSize = 14.sp,
                minFontSize = 10.sp
            )
        }
    }
}

/**
 * 豆瓣未登录行：标签 + 登录用途说明文字 + 登录按钮。
 * 用于账户卡片中豆瓣未登录或登出后的状态,与 AccountRow 保持行结构一致(标签宽度对齐)。
 */
@Composable
internal fun DoubanLoginPromptRow(
    onLogin: () -> Unit
) {
    val view = LocalView.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 账户 logo：与 AccountRow 固定 40dp 宽度对齐
        Box(
            modifier = Modifier.width(40.dp),
            contentAlignment = Alignment.Center
        ) {
            DoubanLogo(
                contentDescription = stringResource(R.string.settings_account_douban),
                modifier = Modifier.size(24.dp)
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        // 登录用途说明文字
        Text(
            text = stringResource(R.string.settings_douban_login_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        // 登录按钮(与 AccountRow 登出按钮同高,保持视觉对齐)
        Button(
            onClick = {
                view.performHaptic(HapticType.HEAVY_CLICK)
                onLogin()
            },
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
            modifier = Modifier.height(36.dp)
        ) {
            AdaptiveSingleLineText(
                text = stringResource(R.string.settings_account_douban_login),
                style = MaterialTheme.typography.labelLarge,
                maxFontSize = 14.sp,
                minFontSize = 10.sp
            )
        }
    }
}

/** Trakt 未连接时保留账户行，并提供登录入口。 */
@Composable
internal fun TraktLoginPromptRow(
    onLogin: () -> Unit
) {
    val view = LocalView.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.width(40.dp),
            contentAlignment = Alignment.Center
        ) {
            TraktLogo(
                contentDescription = stringResource(R.string.settings_account_trakt_label),
                modifier = Modifier.size(24.dp)
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = stringResource(R.string.settings_guest_login_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Button(
            onClick = {
                view.performHaptic(HapticType.HEAVY_CLICK)
                onLogin()
            },
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
            modifier = Modifier.height(36.dp)
        ) {
            AdaptiveSingleLineText(
                text = stringResource(R.string.settings_account_trakt_login),
                style = MaterialTheme.typography.labelLarge,
                maxFontSize = 14.sp,
                minFontSize = 10.sp
            )
        }
    }
}

/**
 * 访客模式下的账户区块：显示"登录 Trakt"按钮。
 * 豆瓣导入需先登录 Trakt，所以访客模式下只显示 Trakt 登录入口。
 */
@Composable
fun GuestLoginItem(
    onNavigateToLogin: () -> Unit,
    containerColor: Color = MaterialTheme.colorScheme.surfaceVariant
) {
    val view = LocalView.current
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(12.dp),
        color = containerColor,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.settings_guest_login_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            Button(
                onClick = {
                    view.performHaptic(HapticType.HEAVY_CLICK)
                    onNavigateToLogin()
                },
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                modifier = Modifier.height(36.dp)
            ) {
                AdaptiveSingleLineText(
                    text = stringResource(R.string.login_button),
                    style = MaterialTheme.typography.labelLarge,
                    maxFontSize = 14.sp,
                    minFontSize = 10.sp
                )
            }
        }
    }
}


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
import androidx.compose.material.icons.rounded.ErrorOutline
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.SubcomposeAsyncImage
import com.tracktosearch.R
import com.tracktosearch.ui.component.AdaptiveSingleLineText
import com.tracktosearch.ui.component.DoubanLogo
import com.tracktosearch.ui.component.TraktLogo
import com.tracktosearch.ui.haptic.rememberAppHaptics

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
    val haptics = rememberAppHaptics()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 账户 logo：固定宽度让 Trakt/豆瓣两行的头像起点对齐
        Box(
            modifier = Modifier.width(24.dp),
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
                haptics.tap()
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
 * 账号连接失效行：登录态还在，但对端已不认这份凭据（Trakt 连接检查失败 / 豆瓣 Cookie 过期）。
 *
 * 以前这种情况下账号卡与正常已登录完全一致，失效信息只出现在一致性检查弹窗里，用户在设置页
 * 看不出账号已经不能用了。这里保留身份信息，同时给出失效原因与「重新登录」CTA。
 */
@Composable
internal fun AccountExpiredRow(
    brandLogo: @Composable () -> Unit,
    primaryName: String?,
    onReconnect: () -> Unit,
    onLogout: () -> Unit
) {
    val haptics = rememberAppHaptics()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.width(24.dp),
            contentAlignment = Alignment.Center
        ) {
            brandLogo()
        }
        Spacer(modifier = Modifier.width(8.dp))
        // 失效态头像位用告警图标占位：与正常已登录一眼可分
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.errorContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Rounded.ErrorOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(22.dp)
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            if (primaryName != null) {
                Text(
                    text = primaryName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                text = stringResource(R.string.settings_account_connection_expired),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
            TextButton(
                onClick = {
                    haptics.tap()
                    onLogout()
                },
                contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp)
            ) {
                Text(
                    text = stringResource(R.string.settings_logout_button),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Button(
            onClick = {
                haptics.tap()
                onReconnect()
            },
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
            modifier = Modifier.height(36.dp)
        ) {
            AdaptiveSingleLineText(
                text = stringResource(R.string.settings_account_reconnect),
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
    val haptics = rememberAppHaptics()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 账户 logo：与 AccountRow 固定宽度对齐
        Box(
            modifier = Modifier.width(24.dp),
            contentAlignment = Alignment.Center
        ) {
            DoubanLogo(
                contentDescription = stringResource(R.string.settings_account_douban),
                modifier = Modifier.size(24.dp)
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        // 登录用途说明文字
        val loginHint = stringResource(R.string.settings_douban_login_hint)
        val myPageLabel = stringResource(R.string.settings_douban_my_page_label)
        val annotatedHint = buildAnnotatedString {
            val startIndex = loginHint.indexOf(myPageLabel)
            if (startIndex >= 0) {
                append(loginHint.substring(0, startIndex))
                withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary)) {
                    append(myPageLabel)
                }
                append(loginHint.substring(startIndex + myPageLabel.length))
            } else {
                append(loginHint)
            }
        }
        Text(
            text = annotatedHint,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        // 登录按钮(与 AccountRow 登出按钮同高,保持视觉对齐)
        Button(
            onClick = {
                haptics.tap()
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
    val haptics = rememberAppHaptics()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.width(24.dp),
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
                haptics.tap()
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


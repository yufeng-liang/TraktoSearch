package com.tracktosearch.ui.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import com.tracktosearch.R
import com.tracktosearch.ui.haptic.HapticSemantic
import com.tracktosearch.ui.haptic.hapticClickable
import com.tracktosearch.ui.haptic.rememberAppHaptics
import androidx.compose.ui.unit.dp

/**
 * 共享错误态的三种形态。
 *
 * 三种形态对应改造前散在各处的三套实现，视觉输出与原实现逐像素一致：
 * - [Inline]：分区级紧凑单行（原发现页 ErrorRetryRow），点击文案或 info 图标看原始错误详情。
 * - [Full]：整页居中（原 ui/component/ErrorStateView）。
 * - [Overlay]：带遮罩的浮层卡片（原 AI 功能页 FeatureError），挡住底层内容避免误触。
 */
enum class AppErrorVariant { Inline, Full, Overlay }

/**
 * 全 App 统一的错误态。
 *
 * @param message 错误详情文案。[AppErrorVariant.Inline] 下收进详情对话框，其余形态直接展示。
 * @param onRetry 重试回调，为 null 时不显示重试入口。
 * @param retryable 该错误重试是否有意义（如配额用完、授权失效重试无用）。false 时即便传了
 *        [onRetry] 也不显示重试按钮，免得用户白点还多烧一次请求。
 * @param inlineLabel [AppErrorVariant.Inline] 的可见短文案，默认 common_load_failed。
 * @param showDetail [AppErrorVariant.Inline] 是否提供「看错误详情」入口。失败原因只有一个
 *        布尔标志位、没有具体信息时传 false，免得点开对话框看到的还是同一句话。
 * @param retryLabel 重试按钮文案，默认 error_retry。
 */
@Composable
fun AppErrorState(
    message: String,
    onRetry: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    variant: AppErrorVariant = AppErrorVariant.Full,
    retryable: Boolean = true,
    icon: ImageVector = Icons.Rounded.CloudOff,
    inlineLabel: String? = null,
    showDetail: Boolean = true,
    retryLabel: String? = null
) {
    val retry = onRetry?.takeIf { retryable }
    val retryText = retryLabel ?: stringResource(R.string.error_retry)
    when (variant) {
        AppErrorVariant.Inline -> InlineErrorState(
            message = message,
            label = inlineLabel ?: stringResource(R.string.common_load_failed),
            retryText = retryText,
            onRetry = retry,
            showDetail = showDetail,
            modifier = modifier
        )
        AppErrorVariant.Full -> FullErrorState(
            message = message,
            retryText = retryText,
            onRetry = retry,
            icon = icon,
            modifier = modifier
        )
        AppErrorVariant.Overlay -> OverlayErrorState(
            message = message,
            retryText = retryText,
            onRetry = retry,
            modifier = modifier
        )
    }
}

@Composable
private fun InlineErrorState(
    message: String,
    label: String,
    retryText: String,
    onRetry: (() -> Unit)?,
    showDetail: Boolean,
    modifier: Modifier = Modifier
) {
    val haptics = rememberAppHaptics()
    var showError by remember { mutableStateOf(false) }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            modifier = if (showDetail) {
                Modifier.hapticClickable(semantic = HapticSemantic.LIGHT_TAP) { showError = true }
            } else {
                Modifier
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error
        )
        if (showDetail) {
            // 点击 info 图标弹出错误详情对话框
            IconButton(
                onClick = {
                    haptics.lightTap()
                    showError = true
                },
                modifier = Modifier.size(20.dp)
            ) {
                Icon(
                    Icons.Rounded.Info,
                    contentDescription = stringResource(R.string.error_detail_title),
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
        if (onRetry != null) {
            Spacer(Modifier.width(4.dp))
            TextButton(
                onClick = {
                    haptics.tap()
                    onRetry()
                },
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
            ) {
                Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text(retryText, style = MaterialTheme.typography.labelSmall)
            }
        }
    }

    if (showError) {
        AppAlertDialog(
            onDismissRequest = { showError = false },
            title = stringResource(R.string.error_detail_title),
            message = message,
            confirm = DialogAction(
                label = stringResource(R.string.error_detail_close),
                onClick = { showError = false }
            )
        )
    }
}

@Composable
private fun FullErrorState(
    message: String,
    retryText: String,
    onRetry: (() -> Unit)?,
    icon: ImageVector,
    modifier: Modifier = Modifier
) {
    val haptics = rememberAppHaptics()
    // 高度交给调用方：整页错误传 Modifier.fillMaxSize() 居中，卡片内错误按内容高度包裹。
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        if (onRetry != null) {
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = {
                    haptics.tap()
                    onRetry()
                }
            ) {
                Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(retryText)
            }
        }
    }
}

@Composable
private fun OverlayErrorState(
    message: String,
    retryText: String,
    onRetry: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    val haptics = rememberAppHaptics()
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f))
            // 纯挡板：只为吞掉点击不让触摸穿透，不是交互面，不要接触感
            .clickable(enabled = false, onClick = {}),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier.padding(24.dp),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.errorContainer,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = message,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    textAlign = TextAlign.Center
                )
                if (onRetry != null) {
                    OutlinedButton(
                        onClick = {
                            haptics.tap()
                            onRetry()
                        }
                    ) { Text(retryText) }
                }
            }
        }
    }
}

package com.tracktosearch.ui.component

import androidx.compose.foundation.layout.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.tracktosearch.R

/**
 * 共享加载态的三种形态：
 * - [Full]：整页居中转圈 + 文案。
 * - [Inline]：分区级紧凑转圈，占位高度与 [AppErrorVariant.Inline] 对齐，失败/加载切换时不跳动。
 * - [Skeleton]：骨架屏，内容委托给 ShimmerSkeleton.kt 里已有的骨架实现。
 */
enum class AppLoadingVariant { Full, Inline, Skeleton }

/**
 * 全 App 统一的加载态。
 *
 * @param message [AppLoadingVariant.Full] 下的文案，默认 loading_default。
 * @param skeleton [AppLoadingVariant.Skeleton] 的骨架内容，默认单张影视卡骨架。调用方按分区
 *        传入 ShimmerSkeleton.kt 里对应的骨架（如 DoubanHotCardSkeleton），不另造骨架实现。
 */
@Composable
fun AppLoadingState(
    variant: AppLoadingVariant = AppLoadingVariant.Full,
    message: String? = null,
    modifier: Modifier = Modifier,
    skeleton: (@Composable () -> Unit)? = null
) {
    when (variant) {
        AppLoadingVariant.Full -> LoadingView(
            message = message ?: stringResource(R.string.loading_default),
            modifier = modifier
        )
        AppLoadingVariant.Inline -> Box(
            modifier = modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary
            )
        }
        AppLoadingVariant.Skeleton -> if (skeleton != null) {
            skeleton()
        } else {
            MovieCardSkeleton(modifier = modifier)
        }
    }
}

@Composable
fun LoadingView(
    message: String = "",
    modifier: Modifier = Modifier
) {
    val displayMessage = message.ifBlank { stringResource(R.string.loading_default) }
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = displayMessage,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

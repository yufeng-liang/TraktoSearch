package com.tracktosearch.ui.screen.detail

import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tracktosearch.R
import com.tracktosearch.ui.component.ShimmerState
import com.tracktosearch.ui.component.rememberShimmer
import com.tracktosearch.ui.component.shimmer
import kotlinx.coroutines.delay

/**
 * 详情页骨架屏。
 *
 * 原先各处骨架都是静态的 `surfaceVariant.copy(alpha = 0.5f)` 灰块，跟「加载失败留下的
 * 空占位」长得一模一样，用户分不清是在等还是已经废了。项目里 [rememberShimmer] 早就
 * 存在（发现页、看单页都在用），这里把详情页的几处占位补上流光。
 *
 * 各骨架的尺寸都对齐加载完成后的真实卡片，避免数据到达时内容跳变。
 */

private val SkeletonShape = RoundedCornerShape(8.dp)
private val SkeletonLineShape = RoundedCornerShape(4.dp)
private val CastSkeletonShape = RoundedCornerShape(6.dp)

/** 动态省略号每步持续时长。只改字符，不做淡入、位移或缩放。 */
private const val LOADING_DOTS_STEP_MS = 600L

/**
 * 演职员加载态：保留一张 68×95 幽灵卡，下面用透明文字维持姓名和角色两行高度。
 *
 * 不再重复五张满尺寸 shimmer，也不显示转圈；等真实数据到达后原位替换。
 */
@Composable
internal fun CastRowSkeleton(
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .size(width = 68.dp, height = 95.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f), CastSkeletonShape),
                contentAlignment = Alignment.Center
            ) {
                LoadingDotsText()
            }
            Spacer(modifier = Modifier.height(4.dp))
            // 与真实 CastCard 的姓名/角色两行同高（labelSmall lineHeight = 16sp），
            // 数据到达时栏目高度不变。
            Box(modifier = Modifier.fillMaxWidth().height(16.dp))
            Box(modifier = Modifier.fillMaxWidth().height(16.dp))
        }
    }
}

/** 预告片/截图加载态：一张 240×135 幽灵横卡，不再铺满整排 shimmer。 */
@Composable
internal fun VideosRowSkeleton(
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .width(240.dp)
            .height(135.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f), SkeletonShape),
        contentAlignment = Alignment.Center
    ) {
        LoadingDotsText()
    }
}

/**
 * “加载中”加动态省略号。
 *
 * 省略号固定占一段宽度，只切换点数，因此“加载中”不会左右抖动。系统关闭动画时固定显示
 * 三个点，避免无效的定时重组。
 */
@Composable
private fun LoadingDotsText() {
    val context = LocalContext.current
    val animationsEnabled = remember(context) {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f
        ) != 0f
    }
    var dotCount by remember { mutableIntStateOf(if (animationsEnabled) 1 else 3) }
    LaunchedEffect(animationsEnabled) {
        if (!animationsEnabled) return@LaunchedEffect
        while (true) {
            delay(LOADING_DOTS_STEP_MS)
            dotCount = if (dotCount == 3) 1 else dotCount + 1
        }
    }
    val fullDescription = stringResource(R.string.loading_default)
    Row(
        modifier = Modifier.clearAndSetSemantics { contentDescription = fullDescription },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(R.string.detail_loading_base),
            // 9sp：日文“読み込み中”连省略号槽也放得进 68dp 幽灵卡，不裁切也不撑宽。
            fontSize = 9.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
        Text(
            text = ".".repeat(dotCount),
            fontSize = 9.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier.width(12.dp)
        )
    }
}

/** 简介骨架：3 行，末行短一截模拟自然段尾。 */
@Composable
internal fun TextBlockSkeleton(
    modifier: Modifier = Modifier,
    lineCount: Int = 3,
    shimmer: ShimmerState? = null
) {
    val state = shimmer ?: rememberShimmer()
    Column(modifier = modifier.fillMaxWidth()) {
        repeat(lineCount) { index ->
            val fraction = when {
                index == lineCount - 1 -> 0.7f
                index % 2 == 1 -> 0.95f
                else -> 1f
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction)
                    .height(14.dp)
                    .shimmer(state, SkeletonLineShape)
            )
            if (index < lineCount - 1) Spacer(modifier = Modifier.height(6.dp))
        }
    }
}

/** 短评骨架：头像 + 昵称 + 两行正文，对齐 CommentItem 的排布。 */
@Composable
internal fun CommentSkeleton(
    modifier: Modifier = Modifier,
    shimmer: ShimmerState? = null
) {
    val state = shimmer ?: rememberShimmer()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(modifier = Modifier.size(36.dp).shimmer(state, CircleShape))
        Column(modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .width(96.dp)
                    .height(12.dp)
                    .shimmer(state, SkeletonLineShape)
            )
            Spacer(modifier = Modifier.height(8.dp))
            TextBlockSkeleton(lineCount = 2, shimmer = state)
        }
    }
}

/**
 * 豆瓣详情页首屏骨架。
 *
 * 原先首屏是整页空白居中转一个圈，海报和标题的位置完全没有预告，数据到达时整页跳一下。
 * 这里按头部真实排布铺骨架：120×180 海报 + 标题条 + 子标题条 + 胶囊行。
 */
@Composable
internal fun DoubanHeaderSkeleton(
    modifier: Modifier = Modifier,
    shimmer: ShimmerState? = null
) {
    val state = shimmer ?: rememberShimmer()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(width = 120.dp, height = 180.dp)
                .shimmer(state, SkeletonShape)
        )
        Column(modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.8f)
                    .height(22.dp)
                    .shimmer(state, SkeletonLineShape)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(18.dp)
                    .shimmer(state, SkeletonLineShape)
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                repeat(3) {
                    Box(
                        modifier = Modifier
                            .width(48.dp)
                            .height(20.dp)
                            .shimmer(state, RoundedCornerShape(50))
                    )
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            TextBlockSkeleton(lineCount = 2, shimmer = state)
        }
    }
}

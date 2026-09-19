package com.tracktosearch.ui.screen.detail

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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tracktosearch.ui.component.ShimmerState
import com.tracktosearch.ui.component.rememberShimmer
import com.tracktosearch.ui.component.shimmer

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

/**
 * 演职员加载态：5 张 68×95 幽灵卡并排 shimmer，尺寸/间距对齐 CrewSection 真实卡
 * （卡宽 68dp、LazyRow spacedBy 10dp），数据到达时栏目不跳变。占位语言与预告片/截图
 * 及全页骨架统一为 shimmer 流光，不显示文字。
 */
@Composable
internal fun CastRowSkeleton(
    shimmer: ShimmerState,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        repeat(5) {
            // 列宽必须钉死 68dp：Row 子项里的 fillMaxWidth 会吃光整行宽度，
            // 把后面 4 张挤成 0 宽、卡片在满宽列里居中（真机踩过）
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.width(68.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(width = 68.dp, height = 95.dp)
                        .shimmer(shimmer, CastSkeletonShape)
                )
                Spacer(modifier = Modifier.height(4.dp))
                // 与真实 CastCard 的姓名/角色两行同高（labelSmall lineHeight = 16sp），
                // 数据到达时栏目高度不变。
                Box(modifier = Modifier.fillMaxWidth().height(16.dp))
                Box(modifier = Modifier.fillMaxWidth().height(16.dp))
            }
        }
    }
}

/** 预告片/截图加载态：一张 240×135 幽灵横卡 shimmer，对齐真实首卡尺寸。 */
@Composable
internal fun VideosRowSkeleton(
    shimmer: ShimmerState,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .width(240.dp)
            .height(135.dp)
            .shimmer(shimmer, SkeletonShape)
    )
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

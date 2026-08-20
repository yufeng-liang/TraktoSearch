package com.tracktosearch.ui.screen.person

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tracktosearch.R

@Composable
internal fun PersonSkeletonContent() {
    // 骨架背景色
    val skeletonColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    // 文字骨架圆角
    val textShape = RoundedCornerShape(4.dp)
    // 头像/卡片骨架圆角
    val cardShape = RoundedCornerShape(8.dp)

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
        contentPadding = PaddingValues(bottom = 16.dp)
    ) {
        // 顶部头部区域骨架
        item(key = "skeleton_header") {
            Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 48.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // 头像骨架（120x180 圆角矩形）
                    Box(
                        modifier = Modifier
                            .width(120.dp)
                            .height(180.dp)
                            .clip(cardShape)
                            .background(skeletonColor)
                    )
                    // 右侧文字骨架
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Spacer(modifier = Modifier.height(4.dp))
                        // 姓名骨架（宽一些）
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(0.7f)
                                .height(24.dp)
                                .clip(textShape)
                                .background(skeletonColor)
                        )
                        // 信息骨架（窄一些）
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(0.5f)
                                .height(16.dp)
                                .clip(textShape)
                                .background(skeletonColor)
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(0.6f)
                                .height(16.dp)
                                .clip(textShape)
                                .background(skeletonColor)
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(0.4f)
                                .height(16.dp)
                                .clip(textShape)
                                .background(skeletonColor)
                        )
                        // 社交媒体图标骨架
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(80.dp)
                                    .height(16.dp)
                                    .clip(textShape)
                                    .background(skeletonColor)
                            )
                            Box(
                                modifier = Modifier
                                    .width(90.dp)
                                    .height(16.dp)
                                    .clip(textShape)
                                    .background(skeletonColor)
                            )
                        }
                    }
                }

                // 简介区域骨架（「简介」标签为静态文字直接显示，正文 4 行保留骨架）
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.detail_overview_label),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(4.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(16.dp)
                        .clip(textShape)
                        .background(skeletonColor)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.95f)
                        .height(16.dp)
                        .clip(textShape)
                        .background(skeletonColor)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.9f)
                        .height(16.dp)
                        .clip(textShape)
                        .background(skeletonColor)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.7f)
                        .height(16.dp)
                        .clip(textShape)
                        .background(skeletonColor)
                )
            }
        }

        // 人物图片栏骨架（匹配实际的 110x165dp 图片 + 标题行）
        item(key = "skeleton_person_images") {
            Column(modifier = Modifier.padding(top = 16.dp)) {
                // 「人物图片」标题为静态文字直接显示，仅图片区保留骨架
                Text(
                    text = stringResource(R.string.person_images),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 2.dp)
                )
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp)
                ) {
                    items(4) {
                        Box(
                            modifier = Modifier
                                .width(110.dp)
                                .height(165.dp)
                                .clip(cardShape)
                                .background(skeletonColor)
                        )
                    }
                }
            }
        }

        // 电影作品区域骨架（「电影作品」标题为静态文字直接显示，仅卡片区保留骨架）
        item(key = "skeleton_movie_section") {
            Column(modifier = Modifier.padding(top = 32.dp)) {
                Text(
                    text = stringResource(R.string.person_movie_credits),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.padding(start = 16.dp)
                )
                Spacer(modifier = Modifier.height(8.dp))
                // 横向滚动的卡片骨架
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp)
                ) {
                    items(4) { _ ->
                        Box(
                            modifier = Modifier
                                .width(100.dp)
                                .height(150.dp)
                                .clip(cardShape)
                                .background(skeletonColor)
                        )
                    }
                }
            }
        }

        // 电视剧作品区域骨架（「电视剧作品」标题为静态文字直接显示，仅卡片区保留骨架）
        item(key = "skeleton_tv_section") {
            Column(modifier = Modifier.padding(top = 32.dp)) {
                Text(
                    text = stringResource(R.string.person_tv_credits),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.padding(start = 16.dp)
                )
                Spacer(modifier = Modifier.height(8.dp))
                // 横向滚动的卡片骨架
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp)
                ) {
                    items(4) { _ ->
                        Box(
                            modifier = Modifier
                                .width(100.dp)
                                .height(150.dp)
                                .clip(cardShape)
                                .background(skeletonColor)
                        )
                    }
                }
            }
        }
    }
}

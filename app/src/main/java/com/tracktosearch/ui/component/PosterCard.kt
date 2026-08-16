package com.tracktosearch.ui.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.graphics.drawable.BitmapDrawable
import androidx.core.graphics.drawable.toBitmap
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.tracktosearch.ui.theme.LocalVisualEffectMode

/**
 * 统一海报卡片
 *
 * 支持显示年份标签、类型标签、评分徽章和按压缩放动画。
 *
 * @param imageUrl 海报图片 URL
 * @param title 影视标题（用于内容描述）
 * @param year 年份，null 时不显示
 * @param rating 评分，null 时不显示
 * @param onClick 点击回调，null 时不附加点击手势（由外部容器统一处理）
 * @param modifier 外层修饰符
 * @param genres 类型字符串，null 或空时不显示
 * @param onLongClick 长按回调，null 时不启用长按
 * @param posterModifier 作用于海报容器的修饰符，用于共享元素转场等场景
 * @param imageSize 指定 Coil 解码尺寸，null 时直接使用 imageUrl 作为 model
 * @param onImageSuccess 海报加载成功回调，用于外部提取主色等场景
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PosterCard(
    imageUrl: String?,
    title: String,
    year: String? = null,
    rating: Double? = null,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    genres: String? = null,
    onLongClick: (() -> Unit)? = null,
    posterModifier: Modifier = Modifier,
    imageSize: Int? = null,
    onImageSuccess: ((android.graphics.Bitmap) -> Unit)? = null
) {
    val context = LocalContext.current
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.96f else 1f,
        label = "poster_scale"
    )
    // 用 rememberUpdatedState 持有最新 onImageSuccess：lambda 引用变化不会导致重组或
    // 触发 ImageRequest 重建，也不会在组合期写 snapshot state（原 var + mutableStateOf 写法）。
    val onImageSuccessRef by rememberUpdatedState(onImageSuccess)
    val model = remember(imageUrl, imageSize) {
        if (imageSize != null || onImageSuccess != null) {
            ImageRequest.Builder(context)
                .data(imageUrl)
                .apply { if (imageSize != null) size(imageSize) }
                .crossfade(false)
                .listener(
                    onSuccess = { _, result ->
                        val bitmap = (result.drawable as? BitmapDrawable)?.bitmap
                        if (bitmap != null) {
                            onImageSuccessRef?.invoke(bitmap) // 零拷贝复用 Coil 解码位图
                        } else {
                            onImageSuccessRef?.invoke(result.drawable.toBitmap())
                        }
                    }
                )
                .build()
        } else {
            imageUrl
        }
    }

    val isDark = isAppDarkTheme()
    val posterShape = RoundedCornerShape(12.dp)
    val useNeumorphicDecoration = usesNeumorphicDecoration(LocalVisualEffectMode.current)
    Box(modifier = modifier.scale(scale)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .then(
                    if (useNeumorphicDecoration) {
                        Modifier.neumorphicOuterShadow(
                            shape = posterShape,
                            isDark = isDark,
                            elevation = 4.dp,
                            darkAlpha = if (isDark) 0.4f else 0.12f
                        )
                    } else {
                        Modifier
                    }
                )
                .clip(posterShape)
                .then(posterModifier)
                .then(
                    if (useNeumorphicDecoration) {
                        Modifier.neumorphicInnerShadow(
                            shape = posterShape,
                            isDark = isDark,
                            elevation = 4.dp,
                            lightAlpha = if (isDark) 0.08f else 0.55f
                        )
                    } else {
                        Modifier
                    }
                )
                .then(
                    if (onClick != null) {
                        if (onLongClick != null) {
                            Modifier.combinedClickable(
                                interactionSource = interactionSource,
                                indication = null,
                                onClick = onClick,
                                onLongClick = onLongClick
                            )
                        } else {
                            Modifier.clickable(
                                interactionSource = interactionSource,
                                indication = null,
                                onClick = onClick
                            )
                        }
                    } else {
                        Modifier
                    }
                )
        ) {
            AsyncImage(
                model = model,
                contentDescription = title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            // 类型标签（左上角）
            if (!genres.isNullOrBlank()) {
                Text(
                    text = genres,
                    color = Color.White,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                        .background(
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                            RoundedCornerShape(8.dp)
                        )
                        .padding(horizontal = 5.dp, vertical = 2.dp)
                )
            }
            if (rating != null) {
                RatingBadge(
                    rating = rating,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(2.dp)
                )
            }
            val showYear = !year.isNullOrBlank() && year != "0"
            if (showYear) {
                YearBadge(
                    year = year,
                    fontSize = 10,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6.dp)
                )
            }
        }
    }
}

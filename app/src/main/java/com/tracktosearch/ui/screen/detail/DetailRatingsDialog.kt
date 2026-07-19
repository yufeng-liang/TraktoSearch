package com.tracktosearch.ui.screen.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.StarHalf
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tracktosearch.R
import com.tracktosearch.data.repository.MultiRatings
import com.tracktosearch.ui.util.HapticType
import com.tracktosearch.ui.util.performHaptic

// ==================== 评分行 ====================

@Composable
internal fun RatingsRow(ratings: MultiRatings) {
    // 第一行：IMDb, MTC
    val row1 = mutableListOf<Triple<String, Color, String>>()
    if (ratings.imdbRating.isNotEmpty()) {
        row1.add(Triple("IMDb", Color(0xFFF5C518), ratings.imdbRating))
    }
    if (ratings.metacritic.isNotEmpty()) {
        row1.add(Triple("MTC", Color(0xFFFF9500), ratings.metacritic))
    }

    // 第二行：TMDB, RT
    val row2 = mutableListOf<Triple<String, Color, String>>()
    if (ratings.tmdbRating > 0) {
        row2.add(Triple("TMDB", Color(0xFFF5C518), String.format("%.1f", ratings.tmdbRating)))
    }
    if (ratings.rottenTomatoes.isNotEmpty()) {
        row2.add(Triple("RT", Color(0xFFFF4444), ratings.rottenTomatoes))
    }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // 第一行：始终渲染，无数据时用透明占位保持高度
        Row(
            modifier = Modifier.fillMaxWidth().height(20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (row1.isNotEmpty()) {
                for ((label, color, value) in row1) {
                    RatingBadge(label = label, color = color, value = value, modifier = Modifier.weight(1f))
                }
                if (row1.size == 1) Spacer(modifier = Modifier.weight(1f))
            } else {
                Spacer(modifier = Modifier.weight(1f))
                Spacer(modifier = Modifier.weight(1f))
            }
        }
        // 第二行：始终渲染，无数据时用透明占位保持高度
        Row(
            modifier = Modifier.fillMaxWidth().height(20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (row2.isNotEmpty()) {
                for ((label, color, value) in row2) {
                    RatingBadge(label = label, color = color, value = value, modifier = Modifier.weight(1f))
                }
                if (row2.size == 1) Spacer(modifier = Modifier.weight(1f))
            } else {
                Spacer(modifier = Modifier.weight(1f))
                Spacer(modifier = Modifier.weight(1f))
            }
        }
    }
}

/** 单个评分项：无背景填充，各平台专属图标 */
@Composable
internal fun RatingBadge(label: String, color: Color, value: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        when (label) {
            "TMDB" -> Icon(
                Icons.Rounded.Star, contentDescription = null,
                modifier = Modifier.size(14.dp), tint = color
            )
            "IMDb" -> Surface(
                shape = RoundedCornerShape(2.dp),
                color = color
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontSize = 11.sp, fontWeight = FontWeight.Bold
                    ),
                    color = Color.Black,
                    modifier = Modifier.padding(horizontal = 3.dp, vertical = 0.dp)
                )
            }
            "RT" -> Text(
                text = "🍅",
                fontSize = 14.sp,
                modifier = Modifier.offset(y = -1.dp)
            )
            "MTC" -> Text(
                text = "🎯",
                fontSize = 14.sp,
                modifier = Modifier.offset(y = -1.dp)
            )
        }

        if (label != "IMDb") {
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall.copy(fontSize = 12.sp),
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall.copy(fontSize = 12.sp),
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

// ==================== 用户评分控件 ====================

@Composable
internal fun UserRatingBar(
    userRating: Int?,
    isRating: Boolean,
    isRatingLoading: Boolean,
    onClick: () -> Unit
) {
    val starColor = Color(0xFFFFC107)
    val emptyColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)

    Column(
        modifier = Modifier
            .padding(top = 8.dp, bottom = 8.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = !isRating && !isRatingLoading,
                onClick = onClick
            ),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // 第一行：标签 + 分数
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = stringResource(R.string.detail_your_rating),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (userRating != null) {
                Text(
                    text = "$userRating/10",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = starColor,
                    modifier = Modifier.width(38.dp)
                )
            }
        }
        // 第二行：星标
        if (isRatingLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                strokeWidth = 1.5.dp
            )
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                for (i in 1..5) {
                    val fullValue = i * 2
                    val halfValue = i * 2 - 1
                    val starType = when {
                        userRating != null && userRating >= fullValue -> "full"
                        userRating != null && userRating >= halfValue -> "half"
                        else -> "empty"
                    }
                    Icon(
                        imageVector = when (starType) {
                            "full" -> Icons.Rounded.Star
                            "half" -> Icons.AutoMirrored.Rounded.StarHalf
                            else -> Icons.Rounded.StarBorder
                        },
                        contentDescription = null,
                        modifier = Modifier.size(22.dp),
                        tint = when (starType) {
                            "full" -> starColor
                            "half" -> starColor
                            else -> emptyColor
                        }
                    )
                }
            }
        }
    }
}

@Composable
internal fun RatingDialog(
    initialRating: Int?,
    initialComment: String?,
    isSubmitting: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (Int?, String) -> Unit
) {
    var selectedRating by remember(initialRating) { mutableIntStateOf(initialRating ?: 0) }
    var commentText by remember(initialComment) {
        androidx.compose.runtime.mutableStateOf(initialComment ?: "")
    }
    val starColor = Color(0xFFFFC107)
    val emptyColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
    val ratingView = LocalView.current

    AlertDialog(
        onDismissRequest = { if (!isSubmitting) onDismiss() },
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(24.dp),
        title = null,
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp, bottom = 0.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(0.dp)
            ) {
                // 标题
                Text(
                    text = stringResource(R.string.detail_rating_dialog_title),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                // 大号评分数字
                Row(
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = if (selectedRating > 0) "$selectedRating" else "-",
                        style = MaterialTheme.typography.displaySmall.copy(
                            fontWeight = FontWeight.Bold
                        ),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "/10",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        modifier = Modifier.padding(bottom = 6.dp)
                    )
                }
                // 5 颗星，每颗分左右两半
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (i in 1..5) {
                        val fullValue = i * 2
                        val halfValue = i * 2 - 1
                        val starType = when {
                            selectedRating >= fullValue -> "full"
                            selectedRating >= halfValue -> "half"
                            else -> "empty"
                        }
                        Box(
                            modifier = Modifier.size(36.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = when (starType) {
                                    "full" -> Icons.Rounded.Star
                                    "half" -> Icons.AutoMirrored.Rounded.StarHalf
                                    else -> Icons.Rounded.StarBorder
                                },
                                contentDescription = null,
                                modifier = Modifier.size(36.dp),
                                tint = if (starType == "empty") emptyColor else starColor
                            )
                            Row(modifier = Modifier.fillMaxSize()) {
                                // 左半边：半星，再次点击已选的半星取消评分
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight()
                                        .clickable(
                                            interactionSource = remember { MutableInteractionSource() },
                                            indication = null,
                                            enabled = !isSubmitting,
                                            onClick = {
                                                ratingView.performHaptic(HapticType.TICK)
                                                selectedRating = if (selectedRating == halfValue) 0 else halfValue
                                            }
                                        )
                                )
                                // 右半边：整星，再次点击已选的整星取消评分
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight()
                                        .clickable(
                                            interactionSource = remember { MutableInteractionSource() },
                                            indication = null,
                                            enabled = !isSubmitting,
                                            onClick = {
                                                ratingView.performHaptic(HapticType.TICK)
                                                selectedRating = if (selectedRating == fullValue) 0 else fullValue
                                            }
                                        )
                                )
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
                // 评分说明
                Text(
                    text = stringResource(R.string.detail_rating_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(16.dp))
                // 短评输入框（可选）
                androidx.compose.material3.OutlinedTextField(
                    value = commentText,
                    onValueChange = { if (it.length <= 350) commentText = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = {
                        Text(
                            text = stringResource(R.string.detail_rating_comment_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isSystemInDarkTheme()) Color.White.copy(alpha = 0.4f) else Color(0xFF90A4AE)
                        )
                    },
                    textStyle = MaterialTheme.typography.bodySmall,
                    maxLines = 3,
                    enabled = !isSubmitting,
                    shape = RoundedCornerShape(12.dp)
                )
                Spacer(modifier = Modifier.height(20.dp))
                // 圆角按钮行
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // 取消按钮
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                            .clickable(enabled = !isSubmitting) { onDismiss() }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(R.string.detail_rating_cancel),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                    // 确定按钮
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.primary)
                            .clickable(enabled = !isSubmitting) { onConfirm(if (selectedRating > 0) selectedRating else null, commentText.trim()) }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        if (isSubmitting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        } else {
                            Text(
                                text = stringResource(R.string.detail_rating_confirm),
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {}
    )
}

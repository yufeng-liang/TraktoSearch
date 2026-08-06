package com.tracktosearch.ui.screen.detail
import com.tracktosearch.ui.theme.RatingGold

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tracktosearch.R
import com.tracktosearch.data.repository.MultiRatings
import com.tracktosearch.ui.util.HapticType
import com.tracktosearch.ui.util.performHaptic
import java.util.Locale

// ==================== 评分行 ====================

internal const val RATING_CARD_TEST_TAG = "detail_ratings_card"

private enum class RatingSource {
    IMDb,
    Douban,
    Metacritic,
    TMDB,
    RottenTomatoes
}

private data class RatingBadgeData(
    val source: RatingSource,
    val label: String,
    val brandColor: Color,
    val value: String,
    val normalizedScore: Double?
)

@Composable
internal fun RatingsRow(
    ratings: MultiRatings,
    isDoubanItem: Boolean = false,
    ratingSource: DetailRatingSource = if (isDoubanItem) {
        DetailRatingSource.DOUBAN
    } else {
        DetailRatingSource.NORMAL
    },
    immersionColor: Color? = null
) {
    if (ratingSource == DetailRatingSource.UNKNOWN) {
        RatingsLoadingPlaceholder(immersionColor = immersionColor)
        return
    }
    val missingValue = stringResource(R.string.detail_info_rating_missing)
    val normalizedDoubanRating = normalizeTenPointRating(ratings.doubanRating)
    val showDoubanRating = when (ratingSource) {
        DetailRatingSource.DOUBAN -> true
        DetailRatingSource.NORMAL -> normalizedDoubanRating != null
        DetailRatingSource.UNKNOWN -> false
    }
    val row1Second = if (showDoubanRating) {
        RatingBadgeData(
            source = RatingSource.Douban,
            label = stringResource(R.string.detail_info_douban_rating),
            brandColor = Color(0xFF2E963D),
            value = ratings.doubanRating?.takeIf { normalizedDoubanRating != null }?.let {
                String.format(Locale.getDefault(), "%.1f", it)
            } ?: missingValue,
            normalizedScore = normalizedDoubanRating
        )
    } else {
        RatingBadgeData(
            source = RatingSource.Metacritic,
            label = stringResource(R.string.detail_info_metacritic_rating),
            brandColor = Color(0xFFFF9500),
            value = displayRating(ratings.metacritic, missingValue),
            normalizedScore = normalizePercentRating(ratings.metacritic)
        )
    }

    val row1 = listOf(
        RatingBadgeData(
            source = RatingSource.IMDb,
            label = stringResource(R.string.detail_info_imdb_rating),
            brandColor = Color(0xFFF5C518),
            value = displayTenPointRating(ratings.imdbRating, missingValue),
            normalizedScore = parseTenPointRating(ratings.imdbRating)
        ),
        row1Second
    )
    val row2 = listOf(
        RatingBadgeData(
            source = RatingSource.TMDB,
            label = stringResource(R.string.detail_info_tmdb_rating),
            brandColor = Color(0xFFF5C518),
            value = displayTenPointRating(
                ratings.tmdbRating.takeIf { it > 0.0 },
                missingValue
            ),
            normalizedScore = ratings.tmdbRating.takeIf { it > 0.0 }
                ?.let(::normalizeTenPointRating)
        ),
        RatingBadgeData(
            source = RatingSource.RottenTomatoes,
            label = stringResource(R.string.detail_info_rotten_tomatoes_rating),
            brandColor = Color(0xFFFF4444),
            value = displayRating(ratings.rottenTomatoes, missingValue),
            normalizedScore = normalizePercentRating(ratings.rottenTomatoes)
        )
    )

    RatingCard(immersionColor = immersionColor) {
        RatingBadgeRow(row1)
        RatingBadgeRow(row2)
    }
}

@Composable
internal fun RatingsLoadingPlaceholder(immersionColor: Color? = null) {
    RatingCard(immersionColor = immersionColor) {
        repeat(2) {
            Row(
                modifier = Modifier.fillMaxWidth().height(20.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                repeat(2) {
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.weight(1f).height(20.dp)
                    ) {}
                }
            }
        }
    }
}

@Composable
private fun RatingCard(
    immersionColor: Color?,
    content: @Composable ColumnScope.() -> Unit
) {
    val darkTheme = isSystemInDarkTheme()
    val baseColor = immersionColor ?: MaterialTheme.colorScheme.surface
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(60.dp)
            .testTag(RATING_CARD_TEST_TAG),
        shape = RoundedCornerShape(12.dp),
        color = ratingCardColor(baseColor, darkTheme),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.35f)),
        shadowElevation = 0.dp,
        tonalElevation = 0.dp
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            content = content
        )
    }
}

@Composable
private fun RatingBadgeRow(badges: List<RatingBadgeData>) {
    Row(
        modifier = Modifier.fillMaxWidth().height(20.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        badges.forEach { badge ->
            RatingBadge(badge = badge, modifier = Modifier.weight(1f))
        }
    }
}

private fun displayRating(value: String, missingValue: String): String {
    return value.takeIf { normalizePercentRating(it) != null }?.trim() ?: missingValue
}

private fun parseTenPointRating(value: String): Double? {
    return value.trim()
        .replace(',', '.')
        .toDoubleOrNull()
        ?.let(::normalizeTenPointRating)
}

private fun displayTenPointRating(value: String, missingValue: String): String {
    return value.takeIf { parseTenPointRating(it) != null }?.trim() ?: missingValue
}

private fun displayTenPointRating(value: Double?, missingValue: String): String {
    return value
        ?.takeIf { normalizeTenPointRating(it) != null }
        ?.let { String.format(Locale.getDefault(), "%.1f", it) }
        ?: missingValue
}

/** 单个评分项：平台识别使用品牌色，评分数字使用分档色。 */
@Composable
private fun RatingBadge(badge: RatingBadgeData, modifier: Modifier = Modifier) {
    val source = badge.source
    val scoreColor = ratingBandColor(
        band = ratingBand(badge.normalizedScore),
        isDarkTheme = isSystemInDarkTheme(),
        unavailableColor = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        when (source) {
            RatingSource.TMDB -> Icon(
                Icons.Rounded.Star, contentDescription = null,
                modifier = Modifier.size(14.dp), tint = badge.brandColor
            )
            RatingSource.IMDb -> Surface(
                shape = RoundedCornerShape(2.dp),
                color = badge.brandColor
            ) {
                Text(
                    text = badge.label,
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontSize = 11.sp, fontWeight = FontWeight.Bold
                    ),
                    color = Color.Black,
                    modifier = Modifier.padding(horizontal = 3.dp, vertical = 0.dp)
                )
            }
            RatingSource.RottenTomatoes -> Text(
                text = "🍅",
                fontSize = 14.sp,
                modifier = Modifier.offset(y = -1.dp)
            )
            RatingSource.Douban -> androidx.compose.foundation.Image(
                painter = painterResource(com.tracktosearch.R.drawable.ic_douban_logo),
                contentDescription = stringResource(R.string.detail_info_douban_rating),
                modifier = Modifier.size(14.dp)
            )
            RatingSource.Metacritic -> Text(
                text = "🎯",
                fontSize = 14.sp,
                modifier = Modifier.offset(y = -1.dp)
            )
        }

        if (source != RatingSource.IMDb) {
            Text(
                text = badge.label,
                style = MaterialTheme.typography.titleSmall.copy(fontSize = 12.sp),
                fontWeight = FontWeight.Bold,
                color = badge.brandColor
            )
        }

        Text(
            text = badge.value,
            style = MaterialTheme.typography.titleSmall.copy(fontSize = 12.sp),
            fontWeight = FontWeight.Bold,
            color = scoreColor,
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
    val starColor = RatingGold
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
    val starColor = RatingGold
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
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
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

package com.tracktosearch.ui.screen.detail
import com.tracktosearch.ui.component.AppAlertDialog
import com.tracktosearch.ui.component.DialogAction
import com.tracktosearch.ui.component.GlassSurfaceRole
import com.tracktosearch.ui.component.glassBorderColor
import com.tracktosearch.ui.theme.LocalVisualEffectMode
import com.tracktosearch.ui.theme.BrandDouban
import com.tracktosearch.ui.theme.BrandImdb
import com.tracktosearch.ui.theme.BrandMetacritic
import com.tracktosearch.ui.theme.BrandRottenTomatoes
import com.tracktosearch.ui.theme.BrandTmdb
import com.tracktosearch.ui.theme.DesignToken
import com.tracktosearch.ui.theme.OnBrandImdb
import com.tracktosearch.ui.theme.OnBrandTmdb
import com.tracktosearch.ui.theme.RatingGold
import com.tracktosearch.ui.theme.readableOn
import com.tracktosearch.ui.theme.VisualEffectMode

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.StarHalf
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tracktosearch.R
import com.tracktosearch.data.repository.MultiRatings
import com.tracktosearch.ui.haptic.HapticSemantic
import com.tracktosearch.ui.haptic.hapticClickable
import java.util.Locale

// ==================== 四平台评分卡 ====================

internal const val RATING_CARD_TEST_TAG = "detail_ratings_card"

/**
 * 评分卡高度：平台标记(16) + 间距(3) + 分数(24) + 上下内边距(2×10) ≈ 66dp，
 * 加载占位与实际内容共用，数据到达时卡片不改高。
 *
 * 原先是 64dp 的 2×2 网格挤在海报右侧约 230dp 宽的列里：一行两个平台，
 * 「品牌标签 + 分数」横排，分数只有 15sp 且要靠一层文字阴影才勉强够对比度。
 * 现在整宽四等分，每格约 90dp，标记与分数上下排，分数放到 20sp。
 */
private val RATING_CARD_HEIGHT = 66.dp

/** 平台标记行高度：字标色块、豆瓣 logo、emoji 三种画法行高各不相同，锁死才能让四格分数对齐。 */
private val RATING_MARK_HEIGHT = 16.dp

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
    val onBrandColor: Color,
    val value: String,
    val normalizedScore: Double?
)

@Composable
internal fun RatingsRow(
    ratings: MultiRatings,
    ratingSource: DetailRatingSource = DetailRatingSource.NORMAL,
    immersionColor: Color? = null
) {
    if (ratingSource == DetailRatingSource.UNKNOWN) {
        RatingsLoadingPlaceholder(immersionColor = immersionColor)
        return
    }
    val missingValue = stringResource(R.string.detail_info_rating_missing)
    val normalizedDoubanRating = normalizeTenPointRating(ratings.doubanRating)
    // 与 TMDB 分支同守卫：0.0 视为无有效评分，不能渲染成 "0.0" 假分
    val validDoubanRating = ratings.doubanRating?.takeIf { it > 0.0 }
    val showDoubanRating = when (ratingSource) {
        DetailRatingSource.DOUBAN -> true
        DetailRatingSource.NORMAL -> normalizedDoubanRating != null
        DetailRatingSource.UNKNOWN -> false
    }
    val row1Second = if (showDoubanRating) {
        RatingBadgeData(
            source = RatingSource.Douban,
            label = stringResource(R.string.detail_info_douban_rating),
            brandColor = BrandDouban,
            onBrandColor = Color.White,
            value = validDoubanRating?.let {
                String.format(Locale.getDefault(), "%.1f", it)
            } ?: missingValue,
            normalizedScore = validDoubanRating?.let(::normalizeTenPointRating)
        )
    } else {
        RatingBadgeData(
            source = RatingSource.Metacritic,
            label = stringResource(R.string.detail_info_metacritic_rating),
            brandColor = BrandMetacritic,
            onBrandColor = Color.White,
            value = displayRating(ratings.metacritic, missingValue),
            normalizedScore = normalizePercentRating(ratings.metacritic)
        )
    }

    // 固定四格：IMDb / 豆瓣或 MTC / TMDB / 烂番茄。有豆瓣评分时豆瓣顶掉 MTC 槽，
    // 格数恒定，切换数据源不会让卡片宽度重排。
    val badges = listOf(
        RatingBadgeData(
            source = RatingSource.IMDb,
            label = stringResource(R.string.detail_info_imdb_rating),
            brandColor = BrandImdb,
            onBrandColor = OnBrandImdb,
            value = displayTenPointRating(ratings.imdbRating, missingValue),
            normalizedScore = parseTenPointRating(ratings.imdbRating)
        ),
        row1Second,
        RatingBadgeData(
            source = RatingSource.TMDB,
            label = stringResource(R.string.detail_info_tmdb_rating),
            brandColor = BrandTmdb,
            onBrandColor = OnBrandTmdb,
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
            brandColor = BrandRottenTomatoes,
            onBrandColor = Color.White,
            value = displayRating(ratings.rottenTomatoes, missingValue),
            normalizedScore = normalizePercentRating(ratings.rottenTomatoes)
        )
    )

    RatingCard(immersionColor = immersionColor) { cardColor ->
        val dividerColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
        badges.forEachIndexed { index, badge ->
            if (index > 0) {
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(26.dp)
                        .background(dividerColor)
                )
            }
            RatingCell(badge = badge, cardColor = cardColor, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
internal fun RatingsLoadingPlaceholder(immersionColor: Color? = null) {
    val placeholderColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    RatingCard(immersionColor = immersionColor) {
        repeat(4) {
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(3.dp),
                    color = placeholderColor,
                    modifier = Modifier.width(30.dp).height(16.dp)
                ) {}
                Surface(
                    shape = RoundedCornerShape(3.dp),
                    color = placeholderColor,
                    modifier = Modifier.width(34.dp).height(20.dp)
                ) {}
            }
        }
    }
}

@Composable
private fun RatingCard(
    immersionColor: Color?,
    content: @Composable RowScope.(Color) -> Unit
) {
    val darkTheme = isSystemInDarkTheme()
    val baseColor = immersionColor ?: MaterialTheme.colorScheme.surface
    val cardColor = ratingCardColor(baseColor, darkTheme)
    // 玻璃边框：与搜索框/卡片角色统一；GLASS 模式按 Card token 调制透明度，Blur 分支保持普通边框
    val cardBorderColor = if (LocalVisualEffectMode.current == VisualEffectMode.GLASS) {
        glassBorderColor(
            role = GlassSurfaceRole.Card,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
        )
    } else {
        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(RATING_CARD_HEIGHT)
            .testTag(RATING_CARD_TEST_TAG),
        shape = RoundedCornerShape(14.dp),
        color = cardColor,
        border = BorderStroke(1.dp, cardBorderColor),
        shadowElevation = 0.dp,
        tonalElevation = 0.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = { content(cardColor) }
        )
    }
}

/** 单格：平台标记在上、分数在下。分数按分档取色（见 RatingPresentation.ratingBandColor）。 */
@Composable
private fun RatingCell(
    badge: RatingBadgeData,
    cardColor: Color,
    modifier: Modifier = Modifier
) {
    val scoreColor = ratingBandColor(
        band = ratingBand(badge.normalizedScore),
        surfaceColor = cardColor,
        unavailableColor = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        RatingMark(badge)
        Text(
            text = badge.value,
            style = MaterialTheme.typography.titleMedium.copy(
                fontSize = 20.sp,
                lineHeight = 24.sp
            ),
            fontWeight = FontWeight.Bold,
            color = scoreColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * 单格顶部的平台标记：各平台沿用自己的识别物 —— IMDb/TMDB 是品牌色底的字标，
 * 豆瓣是 logo 图，烂番茄与 Metacritic 是 🍅 / 🎯。
 *
 * 三种画法统一压在 [RATING_MARK_HEIGHT] 高的行里（emoji 的行高比字标高一截，
 * 不锁高度四格的分数会错开）。图标与 emoji 都对读屏屏蔽：紧邻的平台名已经念过一遍。
 */
@Composable
private fun RatingMark(badge: RatingBadgeData) {
    val labelStyle = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp)
    Row(
        modifier = Modifier.height(RATING_MARK_HEIGHT),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        when (badge.source) {
            // 字标平台：品牌色底 + 反色字，这本身就是它们 logo 的样子
            RatingSource.IMDb, RatingSource.TMDB -> Surface(
                shape = RoundedCornerShape(4.dp),
                color = badge.brandColor
            ) {
                Text(
                    text = badge.label,
                    style = labelStyle,
                    fontWeight = FontWeight.Bold,
                    color = badge.onBrandColor,
                    maxLines = 1,
                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                )
            }
            RatingSource.Douban -> {
                Image(
                    painter = painterResource(R.drawable.ic_douban_logo),
                    contentDescription = null,
                    modifier = Modifier
                        .size(14.dp)
                        .clearAndSetSemantics {}
                )
                RatingMarkLabel(badge = badge, style = labelStyle)
            }
            RatingSource.RottenTomatoes, RatingSource.Metacritic -> {
                Text(
                    text = if (badge.source == RatingSource.RottenTomatoes) "🍅" else "🎯",
                    fontSize = 12.sp,
                    lineHeight = 14.sp,
                    modifier = Modifier.clearAndSetSemantics {}
                )
                RatingMarkLabel(badge = badge, style = labelStyle)
            }
        }
    }
}

/**
 * 图标右侧的平台名，用品牌色 —— 但压在卡片上先过 [readableOn]。
 *
 * 原色直接当 10sp 文字全部不到 AA：豆瓣绿 2.96:1、烂番茄红 2.97:1、
 * Metacritic 橙在浅卡片上只有 1.72:1。保色相调明度，识别性由左边的图标/emoji 兜住。
 * IMDb / TMDB 的字标不走这里，它们的名字在色块里，配的是 On* 前景色。
 */
@Composable
private fun RatingMarkLabel(badge: RatingBadgeData, style: TextStyle) {
    val surface = MaterialTheme.colorScheme.surfaceVariant
    val labelColor = remember(badge.brandColor, surface) {
        readableOn(badge.brandColor, surface)
    }
    Text(
        text = badge.label,
        style = style,
        fontWeight = FontWeight.Bold,
        color = labelColor,
        maxLines = 1
    )
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

    AppAlertDialog(
        onDismissRequest = { if (!isSubmitting) onDismiss() },
        dismiss = DialogAction(
            label = stringResource(R.string.detail_rating_cancel),
            enabled = !isSubmitting,
            onClick = onDismiss,
        ),
        content = {
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
                                        .hapticClickable(
                                            interactionSource = remember { MutableInteractionSource() },
                                            indication = null,
                                            semantic = HapticSemantic.SEGMENT_TICK,
                                            enabled = !isSubmitting,
                                            onClick = {
                                                selectedRating = if (selectedRating == halfValue) 0 else halfValue
                                            }
                                        )
                                )
                                // 右半边：整星，再次点击已选的整星取消评分
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight()
                                        .hapticClickable(
                                            interactionSource = remember { MutableInteractionSource() },
                                            indication = null,
                                            semantic = HapticSemantic.SEGMENT_TICK,
                                            enabled = !isSubmitting,
                                            onClick = {
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
                // 全仓唯一保留自绘的主按钮：提交中要把文字换成转圈，AppDialogActionRow
                // 的按钮不承载按钮内 loading，所以「确定」不走 confirm 槽，规格向按钮行看齐。
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                        // 这一记就是评分弹窗「确定」的全部触感。DetailScreen 的
                        // onConfirm 回调里原先也有一记，已删 —— 按钮才是手势的主人，
                        // 且那一记捕获的是屏幕宿主 View 而不是弹窗自己的。
                        .hapticClickable(
                            semantic = HapticSemantic.TAP,
                            enabled = !isSubmitting
                        ) { onConfirm(if (selectedRating > 0) selectedRating else null, commentText.trim()) }
                        .heightIn(min = DesignToken.DialogActionMinHeight),
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
    )
}

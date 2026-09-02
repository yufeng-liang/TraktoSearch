package com.tracktosearch.ui.component

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tracktosearch.data.remote.dto.DiskType
import com.tracktosearch.data.remote.dto.ResourceItem
import com.tracktosearch.R
import com.tracktosearch.ui.haptic.HapticSemantic
import com.tracktosearch.ui.haptic.hapticCombinedClickable
import com.tracktosearch.ui.theme.*
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

data class DiskStyle(
    val shortNameResId: Int,
    val backgroundColor: Color,
    @DrawableRes val iconRes: Int = 0
)

internal data class SourceTagColors(
    val background: Color,
    val content: Color
)

private val lightCustomSourceTagColors = listOf(
    SourceTagColors(Color(0xFFD8C7A3), Color(0xFF5A4B2E)),
    SourceTagColors(Color(0xFFD2C4D0), Color(0xFF534352)),
    SourceTagColors(Color(0xFFB9D0CF), Color(0xFF2F4D4C)),
    SourceTagColors(Color(0xFFD8BFC5), Color(0xFF5A3942))
)

private val darkCustomSourceTagColors = listOf(
    SourceTagColors(Color(0xFF695A3D), Color(0xFFF0E2C1)),
    SourceTagColors(Color(0xFF604B5F), Color(0xFFEBDCE9)),
    SourceTagColors(Color(0xFF3F605F), Color(0xFFD9ECEB)),
    SourceTagColors(Color(0xFF6E4650), Color(0xFFF1D9DF))
)

internal fun sourceTagColors(source: String, isDark: Boolean): SourceTagColors {
    return when (source) {
        "pansou" -> SourceTagColors(
            background = if (isDark) Color(0xFF6F453A) else Color(0xFFE8C8B8),
            content = if (isDark) Color(0xFFFFE5D9) else Color(0xFF5D4036)
        )
        "panhub" -> SourceTagColors(
            background = if (isDark) Color(0xFF405747) else Color(0xFFC9D4C5),
            content = if (isDark) Color(0xFFDEEDDF) else Color(0xFF314738)
        )
        "zreso" -> SourceTagColors(
            background = if (isDark) Color(0xFF43566B) else Color(0xFFC7D2DF),
            content = if (isDark) Color(0xFFDDE8F4) else Color(0xFF33465A)
        )
        else -> {
            val paletteIndex = Math.floorMod(source.hashCode(), lightCustomSourceTagColors.size)
            if (isDark) darkCustomSourceTagColors[paletteIndex] else lightCustomSourceTagColors[paletteIndex]
        }
    }
}

internal fun sourceTagLabel(source: String, customName: String?): String {
    return when (source) {
        "pansou" -> "PanSou"
        "panhub" -> "PanHub"
        "zreso" -> "Zreso"
        else -> customName ?: source
    }
}

internal fun resourceCardGradientColors(
    diskColor: Color,
    sourceColors: SourceTagColors,
    isDark: Boolean
): List<Color> {
    return listOf(
        diskColor.copy(alpha = if (isDark) 0.22f else 0.14f),
        diskColor.copy(alpha = if (isDark) 0.08f else 0.05f),
        sourceColors.background.copy(alpha = if (isDark) 0.26f else 0.18f)
    )
}

fun diskStyleOf(type: DiskType): DiskStyle = when (type) {
    DiskType.QUARK -> DiskStyle(R.string.disk_quark, QuarkBlue, R.drawable.ic_disk_quark)
    DiskType.BAIDU -> DiskStyle(R.string.disk_baidu, BaiduBlue, R.drawable.ic_disk_baidu)
    DiskType.ALI -> DiskStyle(R.string.disk_ali, AliIndigo, R.drawable.ic_disk_ali)
    DiskType.XUNLEI -> DiskStyle(R.string.disk_xunlei, XunleiBlue, R.drawable.ic_disk_xunlei)
    DiskType.UC -> DiskStyle(R.string.disk_uc, UcOrange, R.drawable.ic_disk_uc)
    DiskType.ONEONEFIVE -> DiskStyle(R.string.disk_115, Blue115, R.drawable.ic_disk_115)
    DiskType.MAGNET -> DiskStyle(R.string.disk_magnet, Color(0xFFD44000))
    DiskType.OTHER -> DiskStyle(R.string.disk_other, Color(0xFF8E8E93))
}

private val DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-M-d HH:mm")

private fun formatFileDate(raw: String): String {
    return try {
        // 尝试解析 ISO 格式 (如 2026-06-13T15:15:09+08:00)
        LocalDateTime.parse(raw.take(19)).format(DATE_FORMATTER)
    } catch (_: Exception) {
        raw
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ResourceItemCard(
    item: ResourceItem,
    sourceName: String? = null,
    isViewed: Boolean = false,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    index: Int = 0,
    modifier: Modifier = Modifier
) {
    val style = diskStyleOf(item.diskType)
    val contentAlpha = if (isViewed) 0.5f else 1f
    val isLight = MaterialTheme.colorScheme.background.luminance() > 0.5f
    val sourceColors = sourceTagColors(item.source, isDark = !isLight)
    val gradientColors = resourceCardGradientColors(style.backgroundColor, sourceColors, isDark = !isLight)
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.96f else 1f,
        label = "resource_card_scale"
    )

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .scale(scale)
            .hapticCombinedClickable(
                interactionSource = interactionSource,
                indication = null,
                semantic = HapticSemantic.LIGHT_TAP,
                onClick = onClick,
                onLongClick = onLongClick
            ),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.horizontalGradient(
                        colors = gradientColors
                    )
                )
                .padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 8.dp)
                .alpha(contentAlpha)
        ) {
            // 左侧品牌色强调条
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .width(3.dp)
                    .height(40.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(style.backgroundColor.copy(alpha = 0.9f))
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 序号ID
                    Text(
                        text = "#${index + 1}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.width(24.dp)
                    )

                    // 网盘类型标签（品牌色背景 + 图标 + 文字，胶囊形）
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = style.backgroundColor,
                        modifier = Modifier
                            .height(28.dp)
                            .widthIn(max = 88.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .padding(horizontal = 8.dp)
                                .fillMaxHeight(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            if (style.iconRes != 0) {
                                Image(
                                    painter = painterResource(id = style.iconRes),
                                    contentDescription = null,
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                            AdaptiveSingleLineText(
                                text = stringResource(style.shortNameResId),
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 11.sp,
                                    color = Color.White
                                ),
                                maxFontSize = 11.sp,
                                minFontSize = 9.sp,
                                modifier = Modifier.widthIn(max = 68.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    // 来源标签
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = sourceColors.background,
                        modifier = Modifier.widthIn(max = 120.dp)
                    ) {
                        AdaptiveSingleLineText(
                            text = sourceTagLabel(item.source, sourceName),
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Medium,
                                color = sourceColors.content
                            ),
                            maxFontSize = 11.sp,
                            minFontSize = 8.5.sp,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }

                    // 已查看标签
                    if (isViewed) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                            modifier = Modifier.widthIn(max = 80.dp)
                        ) {
                            AdaptiveSingleLineText(
                                text = stringResource(R.string.resource_viewed),
                                style = MaterialTheme.typography.labelSmall.copy(
                                    color = MaterialTheme.colorScheme.outline
                                ),
                                maxFontSize = 10.sp,
                                minFontSize = 8.sp,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                    }

                    Spacer(modifier = Modifier.weight(1f))

                    // 日期（右对齐）
                    if (item.fileDate.isNotEmpty()) {
                        val formattedDate = remember(item.fileDate) { formatFileDate(item.fileDate) }
                        Text(
                            text = formattedDate,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                        )
                    }
                }

                // 资源名称
                AdaptiveTwoLineTitle(
                    text = item.name,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                    maxFontSize = 14.sp,
                    minFontSize = 12.sp,
                    modifier = Modifier.padding(top = 6.dp)
                )

                // 文件数
                if (item.fileCount > 1) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = style.backgroundColor.copy(alpha = 0.12f)
                    ) {
                        Text(
                            text = stringResource(R.string.resource_file_count, item.fileCount),
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                            color = style.backgroundColor.copy(alpha = 0.9f),
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                        )
                    }
                }
            }
        }
    }
}

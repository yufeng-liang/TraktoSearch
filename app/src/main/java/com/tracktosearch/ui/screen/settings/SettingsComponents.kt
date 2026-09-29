package com.tracktosearch.ui.screen.settings

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tracktosearch.R
import com.tracktosearch.ui.component.AdaptiveSingleLineText
import com.tracktosearch.ui.component.GlassSurfaceRole
import com.tracktosearch.ui.component.NeumorphicFrostedSurface
import com.tracktosearch.ui.component.isAppDarkTheme
import com.tracktosearch.ui.component.SettingsEntryCardCorner
import androidx.compose.foundation.LocalIndication
import com.tracktosearch.ui.haptic.HapticSemantic
import com.tracktosearch.ui.haptic.hapticClickable
import com.tracktosearch.ui.haptic.hapticCombinedClickable
import com.tracktosearch.ui.theme.GlassBorderDarkSubtle
import com.tracktosearch.ui.theme.GlassFillDarkSubtle
import com.tracktosearch.ui.theme.LocalVisualEffectMode
import com.tracktosearch.ui.theme.VisualEffectMode
import com.tracktosearch.ui.theme.appSwitchColors
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.materials.HazeMaterials

/**
 * 列表卡片是否走「真模糊」。
 *
 * 设置页同屏有 6-10 个分组卡片，每个都作为独立 hazeEffect 消费者时，每张卡片都要额外
 * 一层离屏图层 + 一次模糊卷积。Simpleperf 实测 settings_scroll 的 renderLayersImpl
 * 占应用采样 38.8%，正是这些小离屏层堆出来的。
 *
 * 卡片自身填充本就盖住了大部分区域，背后内容的模糊折射几乎看不见，因此 BLUR 模式下
 * 列表卡片改用不透明度更高的纯色填充，把真模糊留给顶栏吸顶与底部导航——那两处是大面积
 * 半透明，模糊是观感重点。GLASS 模式走 GlassSurfaceImpl，不受影响。
 */
@Composable
private fun listCardUsesRealBlur(): Boolean =
    LocalVisualEffectMode.current == VisualEffectMode.GLASS

/** 取消真模糊后用于替代的卡片填充：把原本靠模糊撑起来的层次改由更实的填充承担。 */
@Composable
private fun solidCardFill(isDark: Boolean, blurFill: Color): Color = if (isDark) {
    // 深色主题原填充只有 white 8%，完全依赖模糊才立得住；改为主题 surface 高不透明度打底。
    MaterialTheme.colorScheme.surface.copy(alpha = 0.72f)
} else {
    // 浅色主题原填充已接近实色，仅再抬一档补足失去的模糊层次。
    blurFill.copy(alpha = (blurFill.alpha + 0.12f).coerceAtMost(0.92f))
}

/**
 * 设置分组卡片：玻璃拟态圆角卡片，顶部显示 13sp 分组标题。
 * 内部内容由调用方自行组织，通常配合 [GroupDivider] 在项之间添加细分隔线。
 */
@Composable
fun SettingsGroupCard(
    title: String,
    modifier: Modifier = Modifier,
    hazeState: HazeState? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val isDark = isAppDarkTheme()
    val realBlur = listCardUsesRealBlur()
    val blurFill = if (isDark) GlassFillDarkSubtle
                   else Color.White.copy(alpha = 0.70f)
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            text = title,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.primary,
            letterSpacing = 0.3.sp,
            modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 6.dp)
        )
        NeumorphicFrostedSurface(
            modifier = Modifier.fillMaxWidth(),
            isDark = isDark,
            shape = RoundedCornerShape(20.dp),
             backgroundColor = if (realBlur) blurFill else solidCardFill(isDark, blurFill),
             borderColor = if (isDark) GlassBorderDarkSubtle
                           else Color(0xFFE0E5EC).copy(alpha = 0.9f),
            elevation = 6.dp,
            blurRadius = 18.dp,
            hazeState = if (realBlur) hazeState else null,
            hazeStyle = HazeMaterials.thin(),
            glassRole = GlassSurfaceRole.Card
        ) {
            Column(
                modifier = Modifier.padding(vertical = 4.dp),
                content = content
            )
        }
    }
}

/** 分组卡片内设置项之间的细 divider。 */
@Composable
fun ColumnScope.GroupDivider(modifier: Modifier = Modifier) {
    val isDark = isAppDarkTheme()
    HorizontalDivider(
        modifier = modifier,
        thickness = 0.5.dp,
        color = if (isDark) Color.White.copy(alpha = 0.06f)
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f)
    )
}

@Composable
internal fun settingsIconContainerColor(
    isDark: Boolean,
    alphaMultiplier: Float = 1f
): Color {
    val alpha = if (isDark) 0.2f else 0.12f
    return MaterialTheme.colorScheme.primary.copy(alpha = alpha * alphaMultiplier)
}

/**
 * 设置项：玻璃卡片内的列表项（彩色图标块 + 标题 + 小字 + 右箭头/开关）。
 */
@Composable
internal fun SettingsItemCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    trailing: @Composable (() -> Unit)? = null,
    containerColor: Color = Color.Transparent,
    iconTint: Color = MaterialTheme.colorScheme.primary
) {
    val isDark = isAppDarkTheme()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // 行区域是本组件唯一拥有的可点面，触感只在这里发一记：本组件既做纯跳转卡片
            // 也做开关行（trailing 挂 Switch），两者都取轻一档的 LIGHT_TAP。
            // 调用方不要再在自己的 onClick 里补一记 —— 那样点行区域就是同一语义背靠背两下。
            .hapticClickable(semantic = HapticSemantic.LIGHT_TAP) { onClick() }
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(
                    color = settingsIconContainerColor(isDark),
                    shape = RoundedCornerShape(12.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(22.dp)
            )
        }
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 15.sp
            )
            if (subtitle.isNotEmpty()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp
                )
            }
        }
        if (trailing != null) {
            Spacer(modifier = Modifier.width(8.dp))
            trailing()
        } else {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

/**
 * 数据流通卡片：圆角居中布局（图标 + 标题），用于 2x2 网格入口
 *
 * @param enabled 是否启用。禁用时图标与文字 alpha 降至 0.38,点击无响应
 */
@Composable
internal fun DataFlowCard(
    modifier: Modifier = Modifier,
    icon: ImageVector,
    title: String,
    onClick: () -> Unit,
    containerColor: Color = Color.Transparent,
    iconTintColor: Color = MaterialTheme.colorScheme.primary,
    enabled: Boolean = true
) {
    val isDark = isAppDarkTheme()
    // 禁用时图标/文字统一降透明度,提供视觉反馈
    val disabledAlpha = if (enabled) 1f else 0.38f
    val effectiveIconTint = iconTintColor.copy(alpha = disabledAlpha)
    Column(
        modifier = modifier
            .hapticClickable(semantic = HapticSemantic.LIGHT_TAP, enabled = enabled) {
                onClick()
            }
            .padding(vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .background(
                    color = settingsIconContainerColor(isDark, disabledAlpha),
                    shape = RoundedCornerShape(14.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = effectiveIconTint,
                modifier = Modifier.size(26.dp)
            )
        }
        Text(
            title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = disabledAlpha),
            textAlign = TextAlign.Center
        )
    }
}

/**
 * 设置卡片：圆角居中布局（图标 + 标题 + 可选小字），用于 2x2 / 2x1 网格入口。
 * iconTintColor 用于根据状态（如启用/禁用）变化以提供视觉反馈。
 *
 * - mergeTitleAndSubtitle：true 时合并显示「标题 - 小字」为一行；无小字则只显示标题
 * - loadingIcon：true 时图标位置用 CircularProgressIndicator 替代（用于版本检查等待态）
 * - subtitleColor：小字颜色（merge 模式下整个合并文本使用此颜色）
 */
@Composable
@OptIn(ExperimentalFoundationApi::class)
internal fun SettingsCard(
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    title: String,
    subtitle: String? = null,
    mergeTitleAndSubtitle: Boolean = false,
    iconTintColor: Color = MaterialTheme.colorScheme.primary,
    loadingIcon: Boolean = false,
    subtitleColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
    containerColor: Color = Color.Transparent
) {
    val isDark = isAppDarkTheme()
    Column(
        modifier = modifier
            .fillMaxWidth()
            .hapticCombinedClickable(
                // interactionSource 传 null 让 Compose 自己建一份：这颗方块原先走的是
                // combinedClickable 的短形式，涟漪来自 LocalIndication，换成长形式后要显式带上
                interactionSource = null,
                indication = LocalIndication.current,
                semantic = HapticSemantic.LIGHT_TAP,
                onClick = onClick,
                onLongClick = onLongClick
            )
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .background(
                    color = settingsIconContainerColor(isDark),
                    shape = RoundedCornerShape(14.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            if (loadingIcon) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp,
                    color = iconTintColor
                )
            } else if (icon != null) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = iconTintColor,
                    modifier = Modifier.size(26.dp)
                )
            }
        }
        if (mergeTitleAndSubtitle) {
            val displayText = if (!subtitle.isNullOrEmpty()) {
                "$title - $subtitle"
            } else {
                title
            }
            Text(
                text = displayText,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = subtitleColor,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        } else {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (!subtitle.isNullOrEmpty()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = subtitleColor,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * 观看统计大卡片：玻璃拟态风格，左侧彩色图标块 + 右侧标题副标题 + 箭头。
 * 作为设置页第一位置，无类目 Header。
 */
@Composable
internal fun StatisticsCard(
    modifier: Modifier = Modifier,
    hazeState: HazeState? = null,
    onClick: () -> Unit
) {
    val isDark = isAppDarkTheme()
    val realBlur = listCardUsesRealBlur()
    val blurFill = if (isDark) GlassFillDarkSubtle
                   else Color.White.copy(alpha = 0.70f)
    NeumorphicFrostedSurface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            // 调用方的修饰符挂在外边距之内：容器变形要量的是卡片可见的那块圆角面，
            // 挂在 padding 之外量到的是整行宽度，转场起始矩形会比用户看到的卡片宽出两侧留白。
            .then(modifier)
            .clip(RoundedCornerShape(SettingsEntryCardCorner))
            .hapticClickable(semantic = HapticSemantic.LIGHT_TAP) { onClick() }
            .testTag("settings_statistics_card"),
        isDark = isDark,
        shape = RoundedCornerShape(SettingsEntryCardCorner),
        backgroundColor = if (realBlur) blurFill else solidCardFill(isDark, blurFill),
        borderColor = if (isDark) GlassBorderDarkSubtle
                      else Color(0xFFE0E5EC).copy(alpha = 0.9f),
        elevation = 6.dp,
        blurRadius = 18.dp,
        hazeState = if (realBlur) hazeState else null,
        hazeStyle = HazeMaterials.thin(),
        glassRole = GlassSurfaceRole.Card
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(
                        color = settingsIconContainerColor(isDark),
                        shape = RoundedCornerShape(14.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.BarChart,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(26.dp)
                )
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.settings_view_statistics),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = stringResource(R.string.settings_view_statistics_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.size(22.dp)
            )
        }
    }
}


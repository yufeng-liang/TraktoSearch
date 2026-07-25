package com.tracktosearch.ui.screen.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tracktosearch.R
import com.tracktosearch.data.local.CustomSearchSource
import com.tracktosearch.ui.component.NeumorphicFrostedSurface
import com.tracktosearch.ui.component.isAppDarkTheme
import com.tracktosearch.ui.theme.appSwitchColors
import com.tracktosearch.ui.util.HapticType
import com.tracktosearch.ui.util.performHaptic
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.materials.HazeMaterials

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
            backgroundColor = if (isDark) Color.White.copy(alpha = 0.08f)
                              else Color.White.copy(alpha = 0.70f),
            borderColor = if (isDark) Color.White.copy(alpha = 0.10f)
                          else Color(0xFFE0E5EC).copy(alpha = 0.9f),
            elevation = 6.dp,
            blurRadius = 18.dp,
            hazeState = hazeState,
            hazeStyle = HazeMaterials.thin()
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

/**
 * 设置页卡片背景色。
 * 浅色模式下 surfaceVariant 接近白色，叠加 alpha 后与白色背景几乎无对比，
 * 因此浅色模式用完整 surfaceVariant 提升对比度，深色模式保持 0.3 半透明效果。
 */
@Composable
internal fun cardSurfaceColor(): Color {
    val isLight = MaterialTheme.colorScheme.surface.luminance() > 0.5f
    return MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (isLight) 1.0f else 0.3f)
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
    val view = LocalView.current
    val isDark = isAppDarkTheme()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { view.performHaptic(HapticType.CLICK); onClick() }
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(
                    color = iconTint.copy(alpha = if (isDark) 0.2f else 0.12f),
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



/** 自定义搜索源列表项 */
@Composable
fun CustomSearchSourceItem(
    source: CustomSearchSource,
    testResult: SettingsViewModel.TestResultState?,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onTest: () -> Unit
) {
    val view = LocalView.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = source.name.ifBlank { stringResource(R.string.settings_source_unnamed) },
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = source.baseUrl,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onTest, enabled = source.enabled) {
                if (testResult?.isTesting == true) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Text(
                        stringResource(R.string.settings_source_test),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
            IconButton(onClick = onEdit) {
                Icon(Icons.Rounded.Edit, contentDescription = stringResource(R.string.cd_edit), modifier = Modifier.size(20.dp))
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Rounded.Delete, contentDescription = stringResource(R.string.cd_delete), modifier = Modifier.size(20.dp))
            }
            Switch(
                checked = source.enabled,
                onCheckedChange = { view.performHaptic(HapticType.CLICK); onToggle(it) },
                colors = appSwitchColors()
            )
        }
        // 测试结果
        testResult?.message?.let { msg ->
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = msg,
                style = MaterialTheme.typography.bodySmall,
                color = when {
                    testResult.success == true -> MaterialTheme.colorScheme.primary
                    testResult.success == false -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
    }
}

/**
 * 数据流通卡片：圆角居中布局（图标 + 标题），用于 2x2 网格入口
 */
@Composable
internal fun DataFlowCard(
    modifier: Modifier = Modifier,
    icon: ImageVector,
    title: String,
    onClick: () -> Unit,
    containerColor: Color = Color.Transparent,
    iconTintColor: Color = MaterialTheme.colorScheme.primary
) {
    val view = LocalView.current
    val isDark = isAppDarkTheme()
    Column(
        modifier = modifier
            .clickable { view.performHaptic(HapticType.CLICK); onClick() }
            .padding(vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .background(
                    color = iconTintColor.copy(alpha = if (isDark) 0.2f else 0.12f),
                    shape = RoundedCornerShape(14.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = iconTintColor,
                modifier = Modifier.size(26.dp)
            )
        }
        Text(
            title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
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
internal fun SettingsCard(
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    title: String,
    subtitle: String? = null,
    mergeTitleAndSubtitle: Boolean = false,
    iconTintColor: Color = MaterialTheme.colorScheme.primary,
    loadingIcon: Boolean = false,
    subtitleColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    onClick: () -> Unit,
    containerColor: Color = Color.Transparent
) {
    val view = LocalView.current
    val isDark = isAppDarkTheme()
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clickable { view.performHaptic(HapticType.CLICK); onClick() }
            .padding(vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .background(
                    color = iconTintColor.copy(alpha = if (isDark) 0.2f else 0.12f),
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
 * 搜索源卡片：用于 2x2 网格（PanSou/Panhub/Zreso）。
 * 左侧搜索源名字 + 右侧开关；名字颜色随开关状态变化（开启=主题色，关闭=原色）。
 * 可选配置按钮（Panhub 用齿轮图标）位于中间。
 */
@Composable
internal fun SearchSourceCard(
    modifier: Modifier = Modifier,
    name: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    onConfigClick: (() -> Unit)? = null,
    configContentDescription: String? = null,
    containerColor: Color = MaterialTheme.colorScheme.surfaceVariant
) {
    val view = LocalView.current
    Surface(
        modifier = modifier.fillMaxSize(),
        shape = RoundedCornerShape(12.dp),
        color = containerColor,
    ) {
        Row(
            modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = if (checked) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (onConfigClick != null) {
                IconButton(
                    onClick = { view.performHaptic(HapticType.CLICK); onConfigClick() },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        Icons.Rounded.Tune,
                        contentDescription = configContentDescription,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.width(4.dp))
            } else {
                Spacer(modifier = Modifier.width(8.dp))
            }
            Switch(
                checked = checked,
                onCheckedChange = { view.performHaptic(HapticType.CLICK); onCheckedChange(it) },
                colors = appSwitchColors()
            )
        }
    }
}

/**
 * 搜索源「添加」卡片：与 [SearchSourceCard] 同高度对齐,内部 Column 居中布局。
 * 左侧 Add 图标 + 标题垂直堆叠,与其他卡片有开关按钮撑大的视觉感保持一致。
 */
@Composable
internal fun SearchSourceAddCard(
    modifier: Modifier = Modifier,
    title: String,
    onClick: () -> Unit,
    containerColor: Color = MaterialTheme.colorScheme.surfaceVariant
) {
    val view = LocalView.current
    Surface(
        modifier = modifier
            .fillMaxSize()
            .clickable { view.performHaptic(HapticType.CLICK); onClick() },
        shape = RoundedCornerShape(12.dp),
        color = containerColor,
    ) {
        Column(
            modifier = Modifier
                .padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 8.dp)
                .fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                Icons.Rounded.Add,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
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
    val view = LocalView.current
    val isDark = isAppDarkTheme()
    NeumorphicFrostedSurface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clickable { view.performHaptic(HapticType.CLICK); onClick() },
        isDark = isDark,
        shape = RoundedCornerShape(20.dp),
        backgroundColor = if (isDark) Color.White.copy(alpha = 0.08f)
                          else Color.White.copy(alpha = 0.70f),
        borderColor = if (isDark) Color.White.copy(alpha = 0.10f)
                      else Color(0xFFE0E5EC).copy(alpha = 0.9f),
        elevation = 6.dp,
        blurRadius = 18.dp,
        hazeState = hazeState,
        hazeStyle = HazeMaterials.thin()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
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

/**
 * 设置页 Section 容器：标题（13sp Medium 主色）+ 玻璃卡片
 * 统一 6 个分组的标题+卡片样式，避免重复代码
 */
@Composable
fun SettingsSectionCard(
    title: String,
    modifier: Modifier = Modifier,
    hazeState: HazeState? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val isDark = isAppDarkTheme()
    Column(modifier = modifier.fillMaxWidth()) {
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
            backgroundColor = if (isDark) Color.White.copy(alpha = 0.08f)
                              else Color.White.copy(alpha = 0.55f),
            borderColor = if (isDark) Color.White.copy(alpha = 0.10f)
                          else Color.White.copy(alpha = 0.75f),
            elevation = 4.dp,
            blurRadius = 16.dp,
            hazeState = hazeState,
            hazeStyle = HazeMaterials.thin()
        ) {
            Column(
                modifier = Modifier.padding(vertical = 4.dp),
                content = content
            )
        }
    }
}

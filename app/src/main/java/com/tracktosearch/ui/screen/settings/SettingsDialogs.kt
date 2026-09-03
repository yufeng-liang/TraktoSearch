package com.tracktosearch.ui.screen.settings

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.DragIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.tracktosearch.ui.theme.MonetAccent
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tracktosearch.R
import com.tracktosearch.data.local.LanguageStorage
import com.tracktosearch.data.local.ThemeStorage
import com.tracktosearch.ui.haptic.HapticMode
import com.tracktosearch.ui.haptic.HapticModeSummary
import com.tracktosearch.ui.haptic.HapticSemantic
import com.tracktosearch.ui.haptic.HapticSystemState
import com.tracktosearch.ui.haptic.hapticClickable
import com.tracktosearch.ui.haptic.hapticModeSummary
import com.tracktosearch.ui.haptic.rememberAppHaptics
import com.tracktosearch.ui.theme.GlassVariant
import com.tracktosearch.ui.theme.MeshPreset
import com.tracktosearch.ui.theme.VisualEffectMode
import com.tracktosearch.ui.component.AdaptiveSingleLineText
import com.tracktosearch.ui.component.DropdownAnchorMenu
import com.tracktosearch.ui.component.StickyHeaderChangelogContent
import com.tracktosearch.ui.theme.appSwitchColors
import com.github.skydoves.colorpicker.compose.HsvColorPicker
import com.github.skydoves.colorpicker.compose.rememberColorPickerController
import com.github.skydoves.colorpicker.compose.BrightnessSlider
import com.github.skydoves.colorpicker.compose.SaturationSlider
import com.github.skydoves.colorpicker.compose.ColorEnvelope
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Surface
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.foundation.layout.height
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

/** 主题选择对话框 */
@Composable
internal fun ThemeSelectionDialog(
    currentTheme: String,
    onThemeSelected: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
        title = { Text(stringResource(R.string.settings_theme)) },
        text = {
            Column {
                ThemeOptionRow(
                    label = stringResource(R.string.theme_system),
                    selected = currentTheme == ThemeStorage.MODE_SYSTEM,
                    onClick = { onThemeSelected(ThemeStorage.MODE_SYSTEM) }
                )
                ThemeOptionRow(
                    label = stringResource(R.string.theme_dark),
                    selected = currentTheme == ThemeStorage.MODE_DARK,
                    onClick = { onThemeSelected(ThemeStorage.MODE_DARK) }
                )
                ThemeOptionRow(
                    label = stringResource(R.string.theme_light),
                    selected = currentTheme == ThemeStorage.MODE_LIGHT,
                    onClick = { onThemeSelected(ThemeStorage.MODE_LIGHT) }
                )
            }
        },
        confirmButton = {}
    )
}

@Composable
private fun ThemeOptionRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    // 本行位于 AlertDialog 的 text 槽内，这里取到的就是对话框自己的宿主 View
    val haptics = rememberAppHaptics()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .hapticClickable(semantic = HapticSemantic.SEGMENT_TICK) { onClick() }
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = selected,
            // RadioButton 是另一个手势面，行点与它一次只命中一个
            onClick = { haptics.segmentTick(); onClick() }
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(label)
    }
}

/** 触感三档选择对话框：跟随系统 / 关闭 / 增强 */
@Composable
internal fun HapticModeSelectionDialog(
    currentMode: HapticMode,
    systemState: HapticSystemState,
    onModeSelected: (HapticMode) -> Unit,
    onOpenSystemSettings: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
        title = { Text(stringResource(R.string.settings_haptic)) },
        text = {
            // 三行都带一句说明，加上底部可能出现的限制提示，小屏放不下，给一条竖向滚动
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                HapticModeOptionRow(
                    label = stringResource(R.string.settings_haptic_follow_system),
                    description = stringResource(R.string.settings_haptic_follow_system_desc),
                    mode = HapticMode.FOLLOW_SYSTEM,
                    selected = currentMode == HapticMode.FOLLOW_SYSTEM,
                    onClick = { onModeSelected(HapticMode.FOLLOW_SYSTEM) }
                )
                HapticModeOptionRow(
                    label = stringResource(R.string.settings_haptic_off),
                    description = stringResource(R.string.settings_haptic_off_desc),
                    mode = HapticMode.OFF,
                    selected = currentMode == HapticMode.OFF,
                    onClick = { onModeSelected(HapticMode.OFF) }
                )
                HapticModeOptionRow(
                    label = stringResource(R.string.settings_haptic_boost),
                    description = stringResource(R.string.settings_haptic_boost_desc),
                    mode = HapticMode.BOOST,
                    selected = currentMode == HapticMode.BOOST,
                    onClick = { onModeSelected(HapticMode.BOOST) }
                )
                // 限制提示复用 hapticModeSummary 的判定，不在这里重写一遍优先级：
                // 「没马达」压过「用户自己关了」压过「系统总开关关了」那三条顺序有单测钉着
                when (hapticModeSummary(currentMode, systemState)) {
                    HapticModeSummary.NO_VIBRATOR -> HapticLimitNotice(
                        text = stringResource(R.string.settings_haptic_no_vibrator)
                    )
                    HapticModeSummary.SYSTEM_DISABLED -> HapticLimitNotice(
                        text = stringResource(R.string.settings_haptic_system_disabled),
                        actionLabel = stringResource(R.string.settings_haptic_open_system_settings),
                        onAction = onOpenSystemSettings
                    )
                    else -> Unit
                }
            }
        },
        confirmButton = {}
    )
}

/**
 * 触感档位单选行。
 *
 * 与 [ThemeOptionRow] 唯一的区别是**「关闭」那一行不发触感**：选「关闭」还震一记，
 * 用户会以为设置没生效。另两档照常发 `segmentTick()`，等于「选完立刻试听一下」——
 * 选「增强」当场就能感到比原来重，这是这个开关最需要的即时反馈。
 *
 * 为什么靠 [mode] 显式判而不是让引擎自己静默：`AppHaptics` 确实遵守档位，但档位是经 DataStore
 * 异步落盘的，点下去那一刻新值还没到引擎，仍会按旧档位响一记。显式判掉是确定的。
 */
@Composable
private fun HapticModeOptionRow(
    label: String,
    description: String,
    mode: HapticMode,
    selected: Boolean,
    onClick: () -> Unit
) {
    val haptics = rememberAppHaptics()
    val onClickWithHaptic = {
        if (mode != HapticMode.OFF) haptics.segmentTick()
        onClick()
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClickWithHaptic)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onClickWithHaptic)
        Spacer(modifier = Modifier.width(8.dp))
        Column {
            Text(label)
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * 档位之外的环境限制提示：没马达、或系统总开关关着。
 *
 * 摆出来是因为这两种情况下用户选的档位一点效果都没有 —— 不说，就只能看成应用坏了。
 * [onAction] 只在能引导用户去解决时给（系统开关关着可以去系统设置打开；没马达无解）。
 */
@Composable
private fun HapticLimitNotice(
    text: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    Column(modifier = Modifier.padding(top = 8.dp)) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error
        )
        if (actionLabel != null && onAction != null) {
            TextButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}

/** 主题色选择对话框 */
@Composable
internal fun AccentColorDialog(
    currentAccent: com.tracktosearch.ui.theme.MonetAccent?,
    onAccentSelected: (com.tracktosearch.ui.theme.MonetAccent?) -> Unit,
    customAccentArgb: Long?,
    onCustomAccentSelected: (Long?) -> Unit,
    currentMode: VisualEffectMode,
    currentVariant: GlassVariant,
    onVisualEffectSelected: (VisualEffectMode, GlassVariant) -> Unit,
    currentMeshPreset: MeshPreset,
    currentMeshEnabled: Boolean,
    onMeshSelected: (MeshPreset?) -> Unit,
    /** 未解锁霉粉彩蛋时隐藏「星云」选项 */
    swiftieUnlocked: Boolean,
    onDismiss: () -> Unit,
    dialogTitle: String? = null
) {
    var materialMenuExpanded by remember { mutableStateOf(false) }
    var meshMenuExpanded by remember { mutableStateOf(false) }
    var showCustomPicker by remember { mutableStateOf(false) }

    // 壁纸取色选项的渐变色板与勾选图标对比色
    val dynamicColors = listOf(
        Color(0xFF7B68AE), Color(0xFFE8915A), Color(0xFF5A8F6B),
        Color(0xFF6B7FA0), Color(0xFFC4A94D), Color(0xFFD4748A),
        Color(0xFF4A7FB5), Color(0xFF7B68AE)
    )
    val dynamicCheckTint = if (dynamicColors.map { it.luminance() }.average() > 0.5) Color.Black else Color.White

    // 壁纸取色后的真实主色预览（Android 12+），否则回退彩虹渐变
    val context = LocalContext.current
    val darkTheme = isSystemInDarkTheme()
    val dynamicPrimaryColor = remember(darkTheme) {
        if (Build.VERSION.SDK_INT >= 31) {
            if (darkTheme) dynamicDarkColorScheme(context).primary
            else dynamicLightColorScheme(context).primary
        } else null
    }

    data class MaterialOption(
        val mode: VisualEffectMode,
        val variant: GlassVariant,
        val title: String,
        val description: String
    )

    // 材质选项：成熟 Haze Blur（默认）/ Backdrop Glass；Glass 的场景深度由组件自行适配。
    val materialOptions = listOf(
        MaterialOption(
            mode = VisualEffectMode.BLUR,
            variant = GlassVariant.CLEAR,
            title = stringResource(R.string.settings_visual_effect_blur),
            description = stringResource(R.string.settings_visual_effect_blur_desc)
        ),
        MaterialOption(
            mode = VisualEffectMode.GLASS,
            variant = GlassVariant.CLEAR,
            title = stringResource(R.string.settings_visual_effect_glass),
            description = stringResource(R.string.settings_visual_effect_glass_desc)
        )
    )

    // 描述文案比标题长很多，菜单宽度按窗口可用空间计算，避免只按标题测量导致窄列截断描述。
    val density = LocalDensity.current
    val windowWidth = with(density) { LocalWindowInfo.current.containerSize.width.toDp() }
    val menuWidth = (windowWidth - 32.dp).coerceAtLeast(0.dp).coerceAtMost(320.dp)

    // 背景光晕选项：关闭 / Paper Shaders 原配色的星云·水墨·海滩 / 主题色驱动的极光·熔岩灯·弥散绽放，null 表示关闭
    data class MeshOption(
        val preset: MeshPreset?,
        val title: String,
        val description: String,
        // 主题色驱动动效：标题旁显「跟随主题色」胶囊标签
        val isThemeDriven: Boolean = false,
        // 霉粉彩蛋解锁项：标题旁显「灵感源于 Lover」胶囊，填充色固定不跟主题
        val loverBadge: Boolean = false
    )
    val meshOptions = buildList {
        add(
            MeshOption(
                preset = null,
                title = stringResource(R.string.bg_glow_off),
                description = stringResource(R.string.bg_glow_off_desc)
            )
        )
        // 星云是霉粉彩蛋解锁内容，未解锁时整项不出现在列表里
        if (swiftieUnlocked) {
            add(
                MeshOption(
                    preset = MeshPreset.NEBULA,
                    title = stringResource(R.string.bg_glow_nebula),
                    description = stringResource(R.string.bg_glow_nebula_desc),
                    loverBadge = true
                )
            )
        }
        add(
            MeshOption(
                preset = MeshPreset.INK,
                title = stringResource(R.string.bg_glow_ink),
                description = stringResource(R.string.bg_glow_ink_desc)
            )
        )
        add(
            MeshOption(
                preset = MeshPreset.BEACH,
                title = stringResource(R.string.bg_glow_beach),
                description = stringResource(R.string.bg_glow_beach_desc)
            )
        )
        add(
            MeshOption(
                preset = MeshPreset.AURORA,
                title = stringResource(R.string.bg_glow_aurora),
                description = stringResource(R.string.bg_glow_aurora_desc),
                isThemeDriven = true
            )
        )
        add(
            MeshOption(
                preset = MeshPreset.LAVA_LAMP,
                title = stringResource(R.string.bg_glow_lava),
                description = stringResource(R.string.bg_glow_lava_desc),
                isThemeDriven = true
            )
        )
        add(
            MeshOption(
                preset = MeshPreset.BLOOM,
                title = stringResource(R.string.bg_glow_bloom),
                description = stringResource(R.string.bg_glow_bloom_desc),
                isThemeDriven = true
            )
        )
    }
    val currentMeshLabel = meshOptions.firstOrNull {
        it.preset == (if (currentMeshEnabled) currentMeshPreset else null)
    }?.title ?: stringResource(R.string.bg_glow_off)

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
        title = { Text(dialogTitle ?: stringResource(R.string.settings_accent_color)) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // 材质区：标题在左，当前值 + 下拉箭头在右；点击弹出 DropdownAnchorMenu
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        // 这个面只可能「展开」—— 收起走 onDismissRequest 或选中某项，
                        // 所以 toggle 的方向感在这里永远出不来，按「次级入口」给 LIGHT_TAP
                        .hapticClickable(semantic = HapticSemantic.LIGHT_TAP) {
                            materialMenuExpanded = true
                        }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.settings_material),
                        style = MaterialTheme.typography.labelMedium.copy(fontSize = 14.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    DropdownAnchorMenu(
                        expanded = materialMenuExpanded,
                        onDismissRequest = { materialMenuExpanded = false },
                        menuWidth = menuWidth,
                        anchor = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                val currentLabel = materialOptions.firstOrNull {
                                    it.mode == currentMode
                                }?.title ?: stringResource(R.string.settings_visual_effect_blur)
                                Text(
                                    text = currentLabel,
                                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Icon(
                                    imageVector = Icons.Rounded.ArrowDropDown,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    ) {
                        materialOptions.forEach { option ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    // 材质是一组里选一个，走刻度感
                                    .hapticClickable(semantic = HapticSemantic.SEGMENT_TICK) {
                                        onVisualEffectSelected(option.mode, option.variant)
                                        materialMenuExpanded = false
                                    }
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = option.title,
                                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp)
                                    )
                                    Text(
                                        text = option.description,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2
                                    )
                                }
                                if (option.mode == currentMode) {
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Icon(
                                        imageVector = Icons.Rounded.Check,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    }
                }
                // 背景光晕区：关闭 / Paper Shaders 固定配色 / 主题色驱动动效
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                modifier = Modifier
                    .fillMaxWidth()
                    // 同上，只可能「展开」，给 LIGHT_TAP
                    .hapticClickable(semantic = HapticSemantic.LIGHT_TAP) {
                        meshMenuExpanded = true
                    }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.bg_glow_title),
                        style = MaterialTheme.typography.labelMedium.copy(fontSize = 14.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    DropdownAnchorMenu(
                        expanded = meshMenuExpanded,
                        onDismissRequest = { meshMenuExpanded = false },
                        menuWidth = menuWidth,
                        anchor = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = currentMeshLabel,
                                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Icon(
                                    imageVector = Icons.Rounded.ArrowDropDown,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    ) {
                        meshOptions.forEach { option ->
                            val selected = option.preset == (if (currentMeshEnabled) currentMeshPreset else null)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                        // 背景光晕是一组里选一个，走刻度感
                                        .hapticClickable(semantic = HapticSemantic.SEGMENT_TICK) {
                                            onMeshSelected(option.preset)
                                            meshMenuExpanded = false
                                        }
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                text = option.title,
                                                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp)
                                            )
                                            // 主题色驱动动效：标题后胶囊标签，填充主题色、白字
                                            if (option.isThemeDriven) {
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Surface(
                                                    shape = RoundedCornerShape(50),
                                                    color = MaterialTheme.colorScheme.primary
                                                ) {
                                                    Text(
                                                        text = stringResource(R.string.bg_glow_theme_driven),
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = Color.White,
                                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                                    )
                                                }
                                            }
                                            if (option.loverBadge) {
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Surface(
                                                    shape = RoundedCornerShape(50),
                                                    // 固定 #D0295F：白字对比 5.04:1 合规。
                                                    // 不能用闪粉玫红 #E83A72，对白字只有 3.98:1。
                                                    color = Color(0xFFD0295F)
                                                ) {
                                                    Text(
                                                        text = stringResource(R.string.bg_glow_lover_inspired),
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = Color.White,
                                                        modifier = Modifier.padding(
                                                            horizontal = 8.dp,
                                                            vertical = 2.dp
                                                        )
                                                    )
                                                }
                                            }
                                        }
                                        Text(
                                            text = option.description,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 2
                                        )
                                    }
                                if (selected) {
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Icon(
                                        imageVector = Icons.Rounded.Check,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                // 色调区：壁纸取色 + 莫奈/印象派色块网格 + 自由调色（自由调色紧跟船上午餐蓝）
                val customMarker = Any()
                val swatches: List<Any?> = buildList {
                    add(null) // 壁纸取色
                    MonetAccent.entries.forEach { accent ->
                        add(accent)
                        if (accent == MonetAccent.BOAT_BREAKFAST) add(customMarker) // 自由调色排在船上午餐蓝后
                    }
                }
                val rows = swatches.chunked(4)
                rows.forEach { row ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        row.forEach { swatch ->
                            val isCustom = swatch === customMarker
                            val accent = swatch as? MonetAccent // null 表示壁纸取色
                            val labelResId = when {
                                isCustom -> R.string.settings_accent_custom
                                accent != null -> accent.labelResId
                                else -> R.string.settings_accent_dynamic
                            }
                            // 勾选态：壁纸取色仅在未启用自由调色时勾选，自定义色独立勾选
                            val selected = when {
                                isCustom -> customAccentArgb != null
                                accent != null -> currentAccent == accent
                                else -> currentAccent == null && customAccentArgb == null
                            }
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    // 色板是一组里选一个（自由调色那格转开子弹窗），走刻度感
                                    .hapticClickable(semantic = HapticSemantic.SEGMENT_TICK) {
                                        if (isCustom) {
                                            showCustomPicker = true
                                        } else {
                                            // 选择预设/壁纸取色时清除自定义色，避免 Theme 优先走 customAccent
                                            onCustomAccentSelected(null)
                                            onAccentSelected(accent)
                                        }
                                    }
                                    .padding(vertical = 4.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(44.dp)
                                        .clip(CircleShape)
                                        .background(
                                            brush = when {
                                                isCustom -> androidx.compose.ui.graphics.Brush.sweepGradient(colors = dynamicColors)
                                                accent != null -> androidx.compose.ui.graphics.SolidColor(accent.light)
                                                dynamicPrimaryColor != null -> androidx.compose.ui.graphics.SolidColor(dynamicPrimaryColor)
                                                else -> androidx.compose.ui.graphics.Brush.sweepGradient(colors = dynamicColors)
                                            }
                                        )
                                        .then(
                                            if (selected)
                                                Modifier.border(3.dp, MaterialTheme.colorScheme.primary, CircleShape)
                                            else Modifier
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (selected) {
                                        Icon(
                                            imageVector = Icons.Rounded.Check,
                                            contentDescription = null,
                                            tint = when {
                                                accent != null -> if (accent.light.luminance() > 0.5f) Color.Black else Color.White
                                                else -> dynamicCheckTint
                                            },
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Box(
                                    modifier = Modifier.width(72.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    AdaptiveSingleLineText(
                                        text = stringResource(labelResId),
                                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                                        maxFontSize = 11.sp,
                                        minFontSize = 8.5.sp,
                                        modifier = Modifier.width(72.dp),
                                        textAlign = TextAlign.Center,
                                        fillMaxWidth = true
                                    )
                                }
                            }
                        }
                        // 补齐空位
                        repeat(4 - row.size) { Spacer(modifier = Modifier.weight(1f)) }
                    }
                }
            }
        },
        confirmButton = {
            // confirmButton 槽是独立 subcomposition（自带宿主 View），不能复用 text 槽里那些
            val haptics = rememberAppHaptics()
            TextButton(onClick = { haptics.tap(); onDismiss() }) {
                Text(stringResource(R.string.common_done))
            }
        }
    )

    // 自由调色弹窗
    if (showCustomPicker) {
        CustomAccentDialog(
            initialArgb = customAccentArgb,
            onColorSelected = { argb ->
                onCustomAccentSelected(argb)
                // 选择自定义色后同时清除预设色（互斥）
                onAccentSelected(null)
            },
            onResetDefault = {
                // 恢复默认：清除自定义色并回到壁纸取色
                onCustomAccentSelected(null)
                onAccentSelected(null)
            },
            onDismiss = { showCustomPicker = false }
        )
    }
}

/** 自由调色弹窗：HSV 圆形色轮 + 亮度滑杆 + HEX 显示 + 预览 */
@Composable
internal fun CustomAccentDialog(
    initialArgb: Long?,
    onColorSelected: (Long) -> Unit,
    onDismiss: () -> Unit,
    onResetDefault: () -> Unit
) {
    val initialColor = remember(initialArgb) {
        // 未设置自定义色时默认白色起步，使亮度条渐变与圆盘指针一致（白→黑）
        initialArgb?.let { Color(it.toInt()) } ?: Color.White
    }
    val controller = rememberColorPickerController()
    var selectedColor by remember { mutableStateOf(initialColor) }

    // 控制器需与弹窗初始颜色同步，否则圆盘指针与亮度条渐变使用不一致的内部状态
    LaunchedEffect(initialColor) {
        controller.selectByColor(initialColor, fromUser = false)
    }
    val hexText = remember(selectedColor) {
        String.format("#%06X", selectedColor.toArgb() and 0xFFFFFF)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
        title = {
            Text(
                text = stringResource(R.string.settings_accent_custom),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            // text 槽自带宿主 View，「恢复默认」那一记要在槽内取实例
            val haptics = rememberAppHaptics()
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                HsvColorPicker(
                    modifier = Modifier.fillMaxWidth().height(280.dp),
                    controller = controller,
                    onColorChanged = { envelope -> selectedColor = envelope.color }
                )
                // 饱和度滑轨：附加后亮度条渐变才按当前饱和度取色（圆心 sat=0 时渐变正确显示黑→白，
                // 而非库默认固定的「当前 hue 满饱和→黑」）
                SaturationSlider(
                    modifier = Modifier.fillMaxWidth().height(36.dp),
                    controller = controller
                )
                BrightnessSlider(
                    modifier = Modifier.fillMaxWidth().height(36.dp),
                    controller = controller
                )
                // HEX
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("HEX", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(40.dp))
                    Text(hexText, style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace), color = MaterialTheme.colorScheme.onSurface)
                }
                // 预览
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).background(selectedColor))
                        Spacer(Modifier.height(4.dp))
                        Text("Primary", style = MaterialTheme.typography.labelSmall, fontSize = 10.sp)
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        val containerColor = deriveContainerColor(selectedColor)
                        Box(Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).background(containerColor))
                        Spacer(Modifier.height(4.dp))
                        Text("Container", style = MaterialTheme.typography.labelSmall, fontSize = 10.sp)
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Surface(shape = RoundedCornerShape(20.dp), color = selectedColor, modifier = Modifier.height(36.dp)) {
                            Text("示例按钮", color = Color.White, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.labelMedium)
                        }
                        Spacer(Modifier.height(4.dp))
                        Text("Button", style = MaterialTheme.typography.labelSmall, fontSize = 10.sp)
                    }
                }
                TextButton(onClick = { haptics.tap(); onResetDefault(); onDismiss() }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.settings_accent_reset_default), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = {
            // 每个槽各取一份，理由同 text 槽
            val confirmHaptics = rememberAppHaptics()
            Button(onClick = {
                confirmHaptics.tap()
                onColorSelected(selectedColor.toArgb().toLong())
                onDismiss()
            }) { Text("确定") }
        },
        dismissButton = {
            val dismissHaptics = rememberAppHaptics()
            TextButton(onClick = { dismissHaptics.lightTap(); onDismiss() }) { Text("取消") }
        }
    )
}

private fun deriveContainerColor(seed: Color): Color {
    val hct = com.tracktosearch.data.util.mcu.hct.Hct.fromInt(seed.toArgb())
    val c = com.tracktosearch.data.util.mcu.hct.Hct.from(hct.hue, hct.chroma * 0.3, 90.0)
    return Color(c.toInt())
}

/** 语言选择对话框 */
@Composable
internal fun LanguageSelectionDialog(
    currentLanguage: String,
    onLanguageSelected: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
        title = { Text(stringResource(R.string.settings_language)) },
        text = {
            Column {
                LanguageOptionRow(
                    label = stringResource(R.string.language_system),
                    selected = currentLanguage == LanguageStorage.LANGUAGE_SYSTEM,
                    onClick = {
                        onLanguageSelected(LanguageStorage.LANGUAGE_SYSTEM)
                        onDismiss()
                    }
                )
                LanguageOptionRow(
                    label = stringResource(R.string.language_chinese),
                    selected = currentLanguage == LanguageStorage.LANGUAGE_CHINESE,
                    onClick = {
                        onLanguageSelected(LanguageStorage.LANGUAGE_CHINESE)
                        onDismiss()
                    }
                )
                LanguageOptionRow(
                    label = stringResource(R.string.language_english),
                    selected = currentLanguage == LanguageStorage.LANGUAGE_ENGLISH,
                    onClick = {
                        onLanguageSelected(LanguageStorage.LANGUAGE_ENGLISH)
                        onDismiss()
                    }
                )
                LanguageOptionRow(
                    label = stringResource(R.string.language_japanese),
                    selected = currentLanguage == LanguageStorage.LANGUAGE_JAPANESE,
                    onClick = {
                        onLanguageSelected(LanguageStorage.LANGUAGE_JAPANESE)
                        onDismiss()
                    }
                )
                LanguageOptionRow(
                    label = stringResource(R.string.language_korean),
                    selected = currentLanguage == LanguageStorage.LANGUAGE_KOREAN,
                    onClick = {
                        onLanguageSelected(LanguageStorage.LANGUAGE_KOREAN)
                        onDismiss()
                    }
                )
            }
        },
        confirmButton = {}
    )
}

@Composable
private fun LanguageOptionRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    // 本行位于 AlertDialog 的 text 槽内，这里取到的就是对话框自己的宿主 View
    val haptics = rememberAppHaptics()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .hapticClickable(semantic = HapticSemantic.SEGMENT_TICK) { onClick() }
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = selected,
            // RadioButton 是另一个手势面，行点与它一次只命中一个
            onClick = { haptics.segmentTick(); onClick() }
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(label)
    }
}

/** 更新日志对话框：从仓库获取 md 渲染显示 */
@Composable
fun ChangelogDialog(
    viewModel: SettingsViewModel,
    onDismiss: () -> Unit
) {
    val changelog by viewModel.changelog.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoadingChangelog.collectAsStateWithLifecycle()

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
        title = null,
        text = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp)
            ) {
                when {
                    isLoading && changelog == null -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        }
                    }
                    changelog != null && changelog!!.isNotBlank() -> {
                        StickyHeaderChangelogContent(text = changelog!!)
                    }
                    else -> {
                        Text(
                            text = stringResource(R.string.settings_no_changelog),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        },
        confirmButton = {
            // confirmButton 槽自带宿主 View
            val haptics = rememberAppHaptics()
            TextButton(onClick = { haptics.tap(); onDismiss() }) {
                Text(stringResource(android.R.string.ok))
            }
        }
    )
}

/** 发现页栏目设置对话框：显示/隐藏开关 + 拖动排序 */
@Composable
fun DiscoverSectionsDialog(
    viewModel: SettingsViewModel,
    onDismiss: () -> Unit
) {
    val sections by viewModel.discoverSections.collectAsStateWithLifecycle()
    var reorderedSections by remember { mutableStateOf(sections) }

    LaunchedEffect(sections) {
        reorderedSections = sections
    }

    val lazyListState = rememberLazyListState()
    val reorderableLazyListState = rememberReorderableLazyListState(lazyListState) { from, to ->
        reorderedSections = reorderedSections.toMutableList().apply {
            add(to.index, removeAt(from.index))
        }
        viewModel.setSectionOrder(reorderedSections.map { it.id })
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
        title = {
            Column {
                Text(stringResource(R.string.settings_discover_sections))
                Text(
                    text = stringResource(R.string.settings_discover_sections_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        },
        text = {
            // text 槽自带宿主 View；把手那一记在槽内取实例（DiscoverSectionRow 里那份是它自己的）
            val haptics = rememberAppHaptics()
            LazyColumn(
                state = lazyListState,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 500.dp)
            ) {
                items(reorderedSections, key = { it.id }) { section ->
                    ReorderableItem(
                        state = reorderableLazyListState,
                        key = section.id
                    ) { isDragging ->
                        DiscoverSectionRow(
                            name = getSectionDisplayName(section.id),
                            visible = section.visible,
                            onToggle = { viewModel.setSectionVisible(section.id, it) },
                            // sh.calvin.reorderable 3.1.0 自己不发任何触感（整个 aar 里没有
                            // performHapticFeedback / HapticFeedbackType 的引用，LongPress 探测器
                            // 走的是 Compose 的 detectDragGesturesAfterLongPress —— 那条路没有内建
                            // 长按触感，与 combinedClickable 不同），所以起手与落定两记都要自己补。
                            //
                            // 拖动过程中每换一格**不发**：一趟拖过十几个栏目会连出十几记，
                            // 起手与放手这一对才是「抓住了 / 放稳了」。
                            dragHandleModifier = Modifier.draggableHandle(
                                onDragStarted = { haptics.dragStart() },
                                onDragStopped = { haptics.gestureEnd() }
                            ),
                            isDragging = isDragging
                        )
                    }
                }
            }
        },
        confirmButton = {
            val confirmHaptics = rememberAppHaptics()
            TextButton(onClick = { confirmHaptics.tap(); onDismiss() }) {
                Text(stringResource(android.R.string.ok))
            }
        }
    )
}

@Composable
private fun DiscoverSectionRow(
    name: String,
    visible: Boolean,
    onToggle: (Boolean) -> Unit,
    dragHandleModifier: Modifier,
    isDragging: Boolean
) {
    // 本行位于 AlertDialog 的 text 槽内，这里取到的就是对话框自己的宿主 View
    val haptics = rememberAppHaptics()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .background(
                color = if (isDragging) MaterialTheme.colorScheme.surfaceVariant
                else MaterialTheme.colorScheme.surface,
                shape = MaterialTheme.shapes.medium
            )
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = name,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f)
        )
        Icon(
            imageVector = Icons.Rounded.DragIndicator,
            contentDescription = stringResource(R.string.settings_drag_to_reorder),
            modifier = dragHandleModifier.padding(8.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Switch(
            checked = visible,
            onCheckedChange = { haptics.toggle(it); onToggle(it) },
            colors = appSwitchColors()
        )
    }
}

/** 栏目 ID 转为显示名称 */
@Composable
private fun getSectionDisplayName(id: String): String {
    return when (id) {
        "douban-movie" -> stringResource(R.string.discover_douban_new_movies)
        "douban-weekly" -> stringResource(R.string.discover_douban_weekly)
        "douban-top250" -> stringResource(R.string.discover_douban_top250)
        "douban-nowplaying" -> stringResource(R.string.discover_douban_nowplaying)
        "tmdb-popular" -> stringResource(R.string.discover_trending)
        "tmdb-upcoming" -> stringResource(R.string.discover_upcoming)
        "trakt-trending-movies" -> stringResource(R.string.discover_trakt_trending_movies)
        "trakt-trending-shows" -> stringResource(R.string.discover_trakt_trending_shows)
        "trakt-anticipated" -> stringResource(R.string.discover_trakt_anticipated)
        "trakt-recommendations" -> stringResource(R.string.discover_recommended)
        "trakt-show-recommendations" -> stringResource(R.string.discover_trakt_recommendations_shows)
        "trakt-lists" -> stringResource(R.string.discover_trending_lists)
        "douban-recommend" -> stringResource(R.string.discover_douban_recommend)
        else -> id
    }
}

/** 详情页模块设置对话框：仅显示/隐藏开关（无拖动排序） */
@Composable
fun DetailSectionsDialog(
    viewModel: SettingsViewModel,
    onDismiss: () -> Unit
) {
    val sections by viewModel.detailSections.collectAsStateWithLifecycle()

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
        title = {
            Text(stringResource(R.string.settings_detail_sections))
        },
        text = {
            // text 槽是独立 subcomposition（有自己的宿主 View），触感实例必须在槽内取
            val haptics = rememberAppHaptics()
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp)
            ) {
                sections.forEach { section ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = getDetailSectionDisplayName(section.id),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f)
                        )
                        Switch(
                            checked = section.visible,
                            onCheckedChange = {
                                haptics.toggle(it)
                                viewModel.setDetailSectionVisible(section.id, it)
                            },
                colors = appSwitchColors()
                        )
                    }
                }
            }
        },
        confirmButton = {
            // 与 text 槽里那份分开取：两个槽各有自己的宿主 View
            val confirmHaptics = rememberAppHaptics()
            TextButton(onClick = { confirmHaptics.tap(); onDismiss() }) {
                Text(stringResource(android.R.string.ok))
            }
        }
    )
}

/** 详情页模块 ID 转为显示名称 */
@Composable
private fun getDetailSectionDisplayName(id: String): String {
    return when (id) {
        "cast" -> stringResource(R.string.detail_cast_crew)
        "videos-images" -> stringResource(R.string.detail_videos_section)
        "overview" -> stringResource(R.string.detail_overview_label)
        "my-rating" -> stringResource(R.string.detail_your_rating)
        "comments" -> stringResource(R.string.detail_comments)
        "recommendations" -> stringResource(R.string.detail_recommendations_title)
        else -> id
    }
}

@Composable
internal fun DefaultTabSelectionDialog(
    currentTab: Int,
    onTabSelected: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
        title = { Text(stringResource(R.string.settings_default_tab)) },
        text = {
            // text 槽是独立 subcomposition（有自己的宿主 View），触感实例必须在槽内取
            val haptics = rememberAppHaptics()
            Column {
                listOf(
                    0 to stringResource(R.string.tab_search),
                    1 to stringResource(R.string.tab_discover),
                    2 to stringResource(R.string.tab_me)
                ).forEach { (tabIndex, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .hapticClickable(semantic = HapticSemantic.SEGMENT_TICK) {
                                onTabSelected(tabIndex)
                            }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = currentTab == tabIndex,
                            // RadioButton 是另一个手势面，行点与它一次只命中一个
                            onClick = { haptics.segmentTick(); onTabSelected(tabIndex) }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(label)
                    }
                }
            }
        },
        confirmButton = {}
    )
}

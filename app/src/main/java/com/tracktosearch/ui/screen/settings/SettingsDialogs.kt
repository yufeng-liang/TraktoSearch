package com.tracktosearch.ui.screen.settings

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DragIndicator
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.tracktosearch.ui.theme.MonetAccent
import com.tracktosearch.ui.theme.HctChromaSlider
import com.tracktosearch.ui.theme.HctColorSchemePreviewGrid
import com.tracktosearch.ui.theme.HctHueSlider
import com.tracktosearch.ui.theme.HctToneGrid
import com.tracktosearch.ui.theme.monetColorScheme
import com.tracktosearch.data.util.mcu.hct.Hct
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
import com.tracktosearch.ui.haptic.strength
import com.tracktosearch.ui.theme.GlassVariant
import com.tracktosearch.ui.theme.MeshPreset
import com.tracktosearch.ui.theme.VisualEffectMode
import com.tracktosearch.ui.component.AdaptiveSingleLineText
import com.tracktosearch.ui.component.AppAlertDialog
import com.tracktosearch.ui.component.DialogAction
import com.tracktosearch.ui.component.DropdownAnchorMenu
import com.tracktosearch.ui.component.StickyHeaderChangelogContent
import com.tracktosearch.ui.theme.appSwitchColors
import com.tracktosearch.ui.theme.isDarkScheme
import com.tracktosearch.ui.theme.floatingDialogColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

/** 主题选择对话框 */
@Composable
internal fun ThemeSelectionDialog(
    currentTheme: String,
    onThemeSelected: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AppAlertDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.settings_theme),
        // 选项行留在 content 槽自绘；本弹窗只靠点选行生效，无按钮
        content = {
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
        }
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

/** 触感四档选择对话框：关闭 / 轻 / 跟随系统 / 强 */
@Composable
internal fun HapticModeSelectionDialog(
    currentMode: HapticMode,
    systemState: HapticSystemState,
    onModeSelected: (HapticMode) -> Unit,
    onOpenSystemSettings: () -> Unit,
    onDismiss: () -> Unit
) {
    AppAlertDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.settings_haptic),
        // 限制提示 + 四行选项留在 content 槽自绘；滚动与限高由组件内置（原手写 verticalScroll 删除），
        // 本弹窗只靠点选行生效，无按钮
        content = {
            // 四行都带一句说明，加上底部可能出现的限制提示，小屏放不下，组件内容槽自带竖向滚动
            Column {
                HapticModeOptionRow(
                    label = stringResource(R.string.settings_haptic_off),
                    description = stringResource(R.string.settings_haptic_off_desc),
                    mode = HapticMode.OFF,
                    selected = currentMode == HapticMode.OFF,
                    onClick = { onModeSelected(HapticMode.OFF) }
                )
                HapticModeOptionRow(
                    label = stringResource(R.string.settings_haptic_light),
                    description = stringResource(R.string.settings_haptic_light_desc),
                    mode = HapticMode.LIGHT,
                    selected = currentMode == HapticMode.LIGHT,
                    onClick = { onModeSelected(HapticMode.LIGHT) }
                )
                HapticModeOptionRow(
                    label = stringResource(R.string.settings_haptic_follow_system),
                    description = stringResource(R.string.settings_haptic_follow_system_desc),
                    mode = HapticMode.FOLLOW_SYSTEM,
                    selected = currentMode == HapticMode.FOLLOW_SYSTEM,
                    onClick = { onModeSelected(HapticMode.FOLLOW_SYSTEM) }
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
        }
    )
}

/**
 * 触感档位单选行。
 *
 * 与 [ThemeOptionRow] 的区别有两处：
 *
 * 1. **「关闭」那一行不发触感**：选「关闭」还震一记，用户会以为设置没生效。
 * 2. **其余三档的试听用同一效果按该档强度放**（[HapticMode.strength] 显式传下去）：
 *    轻档放轻的、强档放重的，当场对比出三档差异 —— 这是这套档位最直观的说明书。
 *
 * 为什么显式传强度而不是让引擎自己按档位放：档位经 DataStore 异步落盘，
 * 点下去那一刻新值还没到引擎，仍会按旧档位响。显式传是确定的。
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
        mode.strength?.let { strength -> haptics.perform(HapticSemantic.SEGMENT_TICK, strength) }
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
    customAccentColors: List<Long>,
    selectedCustomAccentArgb: Long?,
    /** 选中/取消选中自定义色调：非 null=选中，null=取消选中（与预设/壁纸互斥） */
    onCustomAccentSelected: (Long?) -> Unit,
    /** 自定义色调弹窗确认（添加模式，storage 自动去重+选中） */
    onCustomAccentAdd: (Long) -> Unit,
    /** 自定义色调弹窗确认（编辑模式，原位更新） */
    onCustomAccentUpdate: (Long, Long) -> Unit,
    /** 长按确认删除已收藏自定义色调 */
    onCustomAccentRemove: (Long) -> Unit,
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
    /** 编辑模式的旧色（非 null = 该色原位更新；null = 添加新色） */
    var editingArgb by remember { mutableStateOf<Long?>(null) }
    /** 长按进入待删态的色；再点该盘确认删除，点别的盘退出待删 */
    var pendingDeleteArgb by remember { mutableStateOf<Long?>(null) }

    // 色块要显示当前深浅色档实际会用到的那一个种子色 ——
    // 以前一律取 .light，深色模式下点进去和看到的不是一个颜色。
    val swatchIsDark = MaterialTheme.colorScheme.isDarkScheme
    // 壁纸取色选项的渐变色板：直接从色板取样，别再抄一份字面量 ——
    // 原先手写的 8 个值里 #6B7FA0 早就跟枚举对不上了（教堂蓝灰现在是 #6A7180）。
    // 首尾同色让 sweepGradient 接缝处不出现硬边。
    val dynamicColors = remember(swatchIsDark) {
        val ring = MonetAccent.entries.map { if (swatchIsDark) it.dark else it.light }
        ring + ring.first()
    }

    // 壁纸取色后的真实主色预览（Android 12+），否则回退彩虹渐变
    val context = LocalContext.current
    // 用应用实际配色判断深浅，而不是系统主题：本应用的明暗由 ThemeStorage 控制，
    // 系统深色 + 应用浅色时 isSystemInDarkTheme() 会把预览算成深色，与弹窗实际底不一致。
    val darkTheme = MaterialTheme.colorScheme.isDarkScheme
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

    AppAlertDialog(
        onDismissRequest = onDismiss,
        title = dialogTitle ?: stringResource(R.string.settings_accent_color),
        content = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                // 整块内容必须落进 AppAlertDialog 的 DialogContentMaxHeight，
                // 超出即被裁成可滚动、末行色盘看不全：这里所有垂直间距都按这个预算给。
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
                        .padding(vertical = 4.dp),
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
                Row(
                modifier = Modifier
                    .fillMaxWidth()
                    // 同上，只可能「展开」，给 LIGHT_TAP
                    .hapticClickable(semantic = HapticSemantic.LIGHT_TAP) {
                        meshMenuExpanded = true
                    }
                    .padding(top = 4.dp, bottom = 9.dp),
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
                // 色调区：壁纸取色 → 印象派预设 → 已收藏自定义纯色盘 → 彩虹＋盘
                val addMarker = Any()
                val addDisabled = customAccentColors.size >= ThemeStorage.MAX_CUSTOM_ACCENTS
                val swatches: List<Any?> = buildList {
                    add(null) // 壁纸取色
                    addAll(MonetAccent.entries)
                    addAll(customAccentColors.map { it as Any }) // 自定义收藏（Long 装箱）
                    if (!addDisabled) add(addMarker) // 满 8 则收掉添加入口
                }
                val rows = swatches.chunked(4)
                rows.forEach { row ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        row.forEach { swatch ->
                            val isAdd = swatch === addMarker
                            val customSwatch = swatch as? Long
                            val isCustom = customSwatch != null
                            val accent = swatch as? MonetAccent // 非 null 表示预设，null 表示壁纸取色
                            val labelResId = when {
                                isAdd -> R.string.settings_accent_custom
                                isCustom -> null
                                accent != null -> accent.labelResId
                                else -> R.string.settings_accent_dynamic
                            }
                            val swatchColor = when {
                                isCustom -> Color(customSwatch.toInt())
                                accent != null -> if (swatchIsDark) accent.dark else accent.light
                                else -> null
                            }
                            // 勾选态：壁纸仅在未选中预设/自定义时勾选；自定义与预设互斥勾选
                            val selected = when {
                                isAdd -> false
                                isCustom -> selectedCustomAccentArgb == customSwatch
                                accent != null -> currentAccent == accent
                                else -> currentAccent == null && selectedCustomAccentArgb == null
                            }
                            // 长按进入待删的盘；再点确认删除，点别的盘自然退出待删
                            val isPendingDelete = isCustom && pendingDeleteArgb == customSwatch
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(vertical = 2.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(44.dp)
                                        .clip(CircleShape)
                                        .combinedClickable(
                                            onClick = {
                                                if (isCustom && pendingDeleteArgb == customSwatch) {
                                                    // 待删态再点 = 确认删除
                                                    onCustomAccentRemove(customSwatch!!)
                                                    pendingDeleteArgb = null
                                                } else {
                                                    pendingDeleteArgb = null
                                                    when {
                                                        isAdd -> {
                                                            editingArgb = null
                                                            showCustomPicker = true
                                                        }
                                                        isCustom -> if (selected) {
                                                            // 再按已选色盘 = 打开弹窗原位编辑
                                                            editingArgb = customSwatch
                                                            showCustomPicker = true
                                                        } else {
                                                            // 选中自定义色，并与预设/壁纸互斥
                                                            onAccentSelected(null)
                                                            onCustomAccentSelected(customSwatch)
                                                        }
                                                        else -> {
                                                            // 预设/壁纸：清除自定义选中，避免 Theme 优先走 customAccent
                                                            onCustomAccentSelected(null)
                                                            onAccentSelected(accent)
                                                        }
                                                    }
                                                }
                                            },
                                            onLongClick = { if (isCustom) pendingDeleteArgb = customSwatch }
                                        )
                                        .background(
                                            brush = when {
                                                isAdd -> androidx.compose.ui.graphics.Brush.sweepGradient(colors = dynamicColors)
                                                swatchColor != null -> androidx.compose.ui.graphics.SolidColor(swatchColor)
                                                dynamicPrimaryColor != null -> androidx.compose.ui.graphics.SolidColor(dynamicPrimaryColor)
                                                else -> androidx.compose.ui.graphics.Brush.sweepGradient(colors = dynamicColors)
                                            }
                                        )
                                        .then(
                                            when {
                                                isPendingDelete -> Modifier.border(3.dp, MaterialTheme.colorScheme.error, CircleShape)
                                                selected -> Modifier.border(3.dp, MaterialTheme.colorScheme.primary, CircleShape)
                                                else -> Modifier
                                            }
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (isPendingDelete) {
                                        // 待删确认按钮：红底圆 + 删除图标，点盘即删
                                        Box(
                                            modifier = Modifier
                                                .size(24.dp)
                                                .clip(CircleShape)
                                                .background(MaterialTheme.colorScheme.error),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = Icons.Rounded.Delete,
                                                contentDescription = stringResource(R.string.settings_accent_delete),
                                                tint = MaterialTheme.colorScheme.onError,
                                                modifier = Modifier.size(14.dp)
                                            )
                                        }
                                    } else if (selected) {
                                        // 勾统一白色（含彩虹渐变盘），在深色预设上靠深色描边兜底可辨
                                        Icon(
                                            imageVector = Icons.Rounded.Check,
                                            contentDescription = null,
                                            tint = Color.White,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    } else if (isAdd) {
                                        Icon(
                                            imageVector = Icons.Rounded.Add,
                                            contentDescription = stringResource(R.string.settings_accent_add),
                                            tint = Color.White,
                                            modifier = Modifier.size(24.dp)
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(2.dp))
                                Box(
                                    modifier = Modifier.width(72.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (labelResId != null) {
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
                        }
                        // 补齐空位
                        repeat(4 - row.size) { Spacer(modifier = Modifier.weight(1f)) }
                    }
                }
            }
        },
        confirm = DialogAction(
            label = stringResource(R.string.common_done),
            onClick = { onDismiss() }
        )
    )

    // 自由调色弹窗：添加（editingArgb=null）或编辑（回填原色）共用同一面板
    if (showCustomPicker) {
        CustomAccentDialog(
            initialArgb = editingArgb,
            onColorConfirmed = { argb ->
                val old = editingArgb
                if (old != null) onCustomAccentUpdate(old, argb)
                else onCustomAccentAdd(argb)
                // 自定义色调激活后与预设/壁纸互斥
                onAccentSelected(null)
            },
            onDismiss = { showCustomPicker = false }
        )
    }
}

/**
 * 自由调色弹窗：HCT 三轴选色（FlClash 同款 Hue/Chroma 渐变滑杆 + Tone 网格）
 * + 8 role 全 scheme 实时预览。只留取消/确定，确定按添加或编辑语义由调用方处理。
 *
 * @param initialArgb 编辑模式时回填该色 HCT；null = 添加模式，以 Hct(0,0,60) 中性灰起步
 * @param onColorConfirmed 用户选中并确认的最终色（ARGB Long）
 */
@Composable
internal fun CustomAccentDialog(
    initialArgb: Long?,
    onColorConfirmed: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    // 用应用实际配色判断深浅，而不是系统主题：本应用的明暗由 ThemeStorage 控制，
    // 系统深色 + 应用浅色时 isSystemInDarkTheme() 会把预览算成深色，与弹窗实际底不一致。
    val darkTheme = MaterialTheme.colorScheme.isDarkScheme
    // 三轴状态：编辑模式回填原色，添加模式 FlClash 同款中性灰起步
    val seedHct = remember(initialArgb) {
        if (initialArgb != null) Hct.fromInt(initialArgb.toInt())
        else Hct.from(0.0, 0.0, 60.0)
    }
    var hue by remember(seedHct) { mutableFloatStateOf(seedHct.hue.toFloat()) }
    var chroma by remember(seedHct) { mutableFloatStateOf(seedHct.chroma.toFloat()) }
    var tone by remember(seedHct) { mutableStateOf(seedHct.tone.toInt()) }

    val currentColor = remember(hue, chroma, tone) {
        Color(Hct.from(hue.toDouble(), chroma.toDouble(), tone.toDouble()).toInt())
    }
    // 预览直接吃生产同款 scheme 生成器，保证所见即所得
    val previewScheme = remember(currentColor, darkTheme) {
        monetColorScheme(seed = currentColor, dark = darkTheme)
    }

    AppAlertDialog(
        onDismissRequest = onDismiss,
        // titleLarge + Bold 由组件默认提供，调用点不再手写
        title = stringResource(R.string.settings_accent_custom),
        content = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                HctHueSlider(hue = hue, onHueChanged = { hue = it })
                HctChromaSlider(hue = hue, chroma = chroma, onChromaChanged = { chroma = it })
                HctToneGrid(
                    hue = hue,
                    chroma = chroma,
                    selectedTone = tone,
                    onToneSelected = { tone = it }
                )
                Text(
                    text = stringResource(R.string.settings_accent_preview),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                HctColorSchemePreviewGrid(scheme = previewScheme)
            }
        },
        confirm = DialogAction(
            label = stringResource(R.string.common_confirm),
            onClick = {
                onColorConfirmed(currentColor.toArgb().toLong())
                onDismiss()
            }
        ),
        dismiss = DialogAction(
            label = stringResource(R.string.common_cancel),
            onClick = { onDismiss() }
        )
    )
}

/** 语言选择对话框 */
@Composable
internal fun LanguageSelectionDialog(
    currentLanguage: String,
    onLanguageSelected: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AppAlertDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.settings_language),
        // 选项行留在 content 槽自绘；点选即生效并关闭，无按钮
        content = {
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
        }
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

    AppAlertDialog(
        onDismissRequest = onDismiss,
        // 无标题弹窗：title 槽整省略；内容限高由组件内置（原手写 heightIn 删除）
        content = {
            Box(modifier = Modifier.fillMaxWidth()) {
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
                        StickyHeaderChangelogContent(
                            text = changelog!!,
                            // 标题吸顶后的填充色跟弹窗容器同色，静态看不出色块
                            headerColor = floatingDialogColor()
                        )
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
        confirm = DialogAction(
            label = stringResource(android.R.string.ok),
            onClick = { onDismiss() }
        )
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

    AppAlertDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.settings_discover_sections),
        // 副提示走 supportMessage：它就是「标题下方一行小字」，与组件里 message+supportMessage
        // 的配方同构，不必再自绘两行标题把字号交回调用点。
        supportMessage = stringResource(R.string.settings_discover_sections_hint),
        content = {
            // text 槽自带宿主 View；把手那一记在槽内取实例（DiscoverSectionRow 里那份是它自己的）；
            // 限高与滚动由组件内容槽负责（原手写 heightIn(max=500.dp) 删除）
            val haptics = rememberAppHaptics()
            LazyColumn(
                state = lazyListState,
                modifier = Modifier.fillMaxWidth()
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
        confirm = DialogAction(
            label = stringResource(android.R.string.ok),
            onClick = { onDismiss() }
        )
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

    AppAlertDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.settings_detail_sections),
        content = {
            // text 槽是独立 subcomposition（有自己的宿主 View），触感实例必须在槽内取；
            // 限高与滚动由组件内容槽负责（原手写 heightIn(max=400.dp) 删除）
            val haptics = rememberAppHaptics()
            Column(modifier = Modifier.fillMaxWidth()) {
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
        confirm = DialogAction(
            label = stringResource(android.R.string.ok),
            onClick = { onDismiss() }
        )
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
    AppAlertDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.settings_default_tab),
        // 选项行留在 content 槽自绘；点选即生效并关闭，无按钮
        content = {
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
        }
    )
}

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
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.tracktosearch.ui.theme.MonetAccent
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tracktosearch.R
import com.tracktosearch.data.local.LanguageStorage
import com.tracktosearch.data.local.ThemeStorage
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
import com.github.skydoves.colorpicker.compose.ColorEnvelope
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Surface
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.foundation.layout.height
import com.tracktosearch.ui.util.HapticType
import com.tracktosearch.ui.util.performHaptic
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
    val view = LocalView.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { view.performHaptic(HapticType.TICK); onClick() }
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = selected,
            onClick = { view.performHaptic(HapticType.TICK); onClick() }
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(label)
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
    onDismiss: () -> Unit,
    dialogTitle: String? = null
) {
    val view = LocalView.current
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

    // 材质选项：Backdrop Glass / 成熟 Haze Blur；Glass 的场景深度由组件自行适配。
    val materialOptions = listOf(
        MaterialOption(
            mode = VisualEffectMode.GLASS,
            variant = GlassVariant.CLEAR,
            title = stringResource(R.string.settings_visual_effect_glass),
            description = stringResource(R.string.settings_visual_effect_glass_desc)
        ),
        MaterialOption(
            mode = VisualEffectMode.BLUR,
            variant = GlassVariant.CLEAR,
            title = stringResource(R.string.settings_visual_effect_blur),
            description = stringResource(R.string.settings_visual_effect_blur_desc)
        )
    )

    // 描述文案比标题长很多，菜单宽度按窗口可用空间计算，避免只按标题测量导致窄列截断描述。
    val density = LocalDensity.current
    val windowWidth = with(density) { LocalWindowInfo.current.containerSize.width.toDp() }
    val menuWidth = (windowWidth - 32.dp).coerceAtLeast(0.dp).coerceAtMost(320.dp)

    // 背景光晕选项：关闭 / 极光 / 熔岩灯 / 弥散绽放，null 表示关闭
    data class MeshOption(
        val preset: MeshPreset?,
        val title: String,
        val description: String
    )
    val meshOptions = listOf(
        MeshOption(
            preset = null,
            title = stringResource(R.string.bg_glow_off),
            description = stringResource(R.string.bg_glow_off_desc)
        ),
        MeshOption(
            preset = MeshPreset.AURORA,
            title = stringResource(R.string.bg_glow_aurora),
            description = stringResource(R.string.bg_glow_aurora_desc)
        ),
        MeshOption(
            preset = MeshPreset.LAVA_LAMP,
            title = stringResource(R.string.bg_glow_lava),
            description = stringResource(R.string.bg_glow_lava_desc)
        ),
        MeshOption(
            preset = MeshPreset.BLOOM,
            title = stringResource(R.string.bg_glow_bloom),
            description = stringResource(R.string.bg_glow_bloom_desc)
        )
    )
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
                        .clickable {
                            view.performHaptic(HapticType.TICK)
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
                                }?.title ?: stringResource(R.string.settings_visual_effect_glass)
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
                                    .clickable {
                                        view.performHaptic(HapticType.TICK)
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
                Spacer(modifier = Modifier.height(8.dp))
                // 背景光晕区：与材质区同构，点击弹出 DropdownAnchorMenu，展示各预设标题 + 说明
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            view.performHaptic(HapticType.TICK)
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
                                    .clickable {
                                        view.performHaptic(HapticType.TICK)
                                        onMeshSelected(option.preset)
                                        meshMenuExpanded = false
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
                                    .clickable {
                                        view.performHaptic(HapticType.TICK)
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
                                                isCustom -> if (customAccentArgb != null) {
                                                    androidx.compose.ui.graphics.SolidColor(Color(customAccentArgb.toInt()))
                                                } else {
                                                    androidx.compose.ui.graphics.Brush.sweepGradient(
                                                        colors = listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red)
                                                    )
                                                }
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
                                                isCustom -> if (Color(customAccentArgb!!.toInt()).luminance() > 0.5f) Color.Black else Color.White
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
            TextButton(onClick = onDismiss) {
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
        initialArgb?.let { Color(it.toInt()) } ?: Color(0xFF9A6242)
    }
    val controller = rememberColorPickerController()
    var selectedColor by remember { mutableStateOf(initialColor) }
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
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                HsvColorPicker(
                    modifier = Modifier.fillMaxWidth().height(280.dp),
                    controller = controller,
                    onColorChanged = { envelope -> selectedColor = envelope.color }
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
                TextButton(onClick = { onResetDefault(); onDismiss() }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.settings_accent_reset_default), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = { Button(onClick = { onColorSelected(selectedColor.toArgb().toLong()); onDismiss() }) { Text("确定") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
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
    val view = LocalView.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { view.performHaptic(HapticType.TICK); onClick() }
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = selected,
            onClick = { view.performHaptic(HapticType.TICK); onClick() }
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
            TextButton(onClick = onDismiss) {
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
                            dragHandleModifier = Modifier.draggableHandle(),
                            isDragging = isDragging
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
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
    val view = LocalView.current
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
            onCheckedChange = { view.performHaptic(HapticType.CLICK); onToggle(it) },
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
    val view = LocalView.current

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
        title = {
            Text(stringResource(R.string.settings_detail_sections))
        },
        text = {
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
                                view.performHaptic(HapticType.CLICK)
                                viewModel.setDetailSectionVisible(section.id, it)
                            },
                colors = appSwitchColors()
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
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
    val view = LocalView.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
        title = { Text(stringResource(R.string.settings_default_tab)) },
        text = {
            Column {
                listOf(
                    0 to stringResource(R.string.tab_search),
                    1 to stringResource(R.string.tab_discover),
                    2 to stringResource(R.string.tab_me)
                ).forEach { (tabIndex, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { view.performHaptic(HapticType.TICK); onTabSelected(tabIndex) }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = currentTab == tabIndex,
                            onClick = { view.performHaptic(HapticType.TICK); onTabSelected(tabIndex) }
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

package com.tracktosearch.ui.screen.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tracktosearch.R
import com.tracktosearch.data.local.LanguageStorage
import com.tracktosearch.data.local.ThemeStorage
import com.tracktosearch.ui.theme.GlassVariant
import com.tracktosearch.ui.theme.VisualEffectMode
import com.tracktosearch.ui.component.AdaptiveSingleLineText
import com.tracktosearch.ui.component.DropdownAnchorMenu
import com.tracktosearch.ui.component.StickyHeaderChangelogContent
import com.tracktosearch.ui.theme.appSwitchColors
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
    currentMode: VisualEffectMode,
    currentVariant: GlassVariant,
    onVisualEffectSelected: (VisualEffectMode, GlassVariant) -> Unit,
    onDismiss: () -> Unit,
    dialogTitle: String? = null
) {
    val view = LocalView.current
    var materialMenuExpanded by remember { mutableStateOf(false) }

    // 壁纸取色选项的渐变色板与勾选图标对比色
    val dynamicColors = listOf(
        Color(0xFF7B68AE), Color(0xFFE8915A), Color(0xFF5A8F6B),
        Color(0xFF6B7FA0), Color(0xFFC4A94D), Color(0xFFD4748A),
        Color(0xFF4A7FB5), Color(0xFF7B68AE)
    )
    val dynamicCheckTint = if (dynamicColors.map { it.luminance() }.average() > 0.5) Color.Black else Color.White

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
                // 色调区：壁纸取色 + 莫奈/印象派色块网格
                val swatches = listOf(null) + com.tracktosearch.ui.theme.MonetAccent.entries
                val rows = swatches.chunked(4)
                rows.forEach { row ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        row.forEach { accent ->
                            val labelResId = if (accent != null) accent.labelResId else R.string.settings_accent_dynamic
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable {
                                        view.performHaptic(HapticType.TICK)
                                        onAccentSelected(accent)
                                    }
                                    .padding(vertical = 4.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(44.dp)
                                        .clip(CircleShape)
                                        .background(
                                            brush = if (accent != null) {
                                                androidx.compose.ui.graphics.SolidColor(accent.light)
                                            } else {
                                                androidx.compose.ui.graphics.Brush.sweepGradient(colors = dynamicColors)
                                            }
                                        )
                                        .then(
                                            if (currentAccent == accent)
                                                Modifier.border(3.dp, MaterialTheme.colorScheme.primary, CircleShape)
                                            else Modifier
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (currentAccent == accent) {
                                        Icon(
                                            imageVector = Icons.Rounded.Check,
                                            contentDescription = null,
                                            tint = if (accent != null) {
                                                if (accent.light.luminance() > 0.5f) Color.Black else Color.White
                                            } else {
                                                dynamicCheckTint
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
                                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
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

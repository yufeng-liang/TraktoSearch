package com.tracktosearch.ui.screen.searchsource

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tracktosearch.R
import com.tracktosearch.data.local.CustomSearchSource
import com.tracktosearch.ui.component.NeumorphicIconButton
import com.tracktosearch.ui.component.hazeTopBar
import com.tracktosearch.ui.component.isAppDarkTheme
import com.tracktosearch.ui.screen.settings.PanHubConfigDialog
import com.tracktosearch.ui.theme.appSwitchColors
import com.tracktosearch.ui.util.HapticType
import com.tracktosearch.ui.util.performHaptic
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.blur.materials.HazeMaterials

/**
 * 搜索源管理页：内置搜索源启停、PanHub 配置入口、自定义搜索源列表与增删改测试。
 * 替换原设置页内嵌的 SearchSourcesItem，成为唯一入口。
 */
@Composable
fun SearchSourcesScreen(
    onBack: () -> Unit,
    onEditSource: (String) -> Unit,
    onAddFromTemplate: (String) -> Unit,
    /** 跳到帮助页「自定义搜索源」那一段：配置字段的说明都在那里 */
    onHelpClick: () -> Unit = {},
    viewModel: SearchSourcesViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val hazeState = remember { HazeState() }
    val listState = rememberLazyListState()
    val statusBarHeight = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val pansouEnabled by viewModel.pansouEnabled.collectAsStateWithLifecycle()
    val panhubEnabled by viewModel.panhubEnabled.collectAsStateWithLifecycle()
    val zresoEnabled by viewModel.zresoEnabled.collectAsStateWithLifecycle()
    val customSources by viewModel.customSources.collectAsStateWithLifecycle()
    val testResults by viewModel.testResults.collectAsStateWithLifecycle()
    val panHubConfig by viewModel.panHubConfig.collectAsStateWithLifecycle()
    var showPanHubConfig by remember { mutableStateOf(false) }
    var showShareSource by remember { mutableStateOf<CustomSearchSource?>(null) }
    var showImportDialog by remember { mutableStateOf(false) }
    var pendingImport by remember { mutableStateOf<CustomSearchSource?>(null) }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = MaterialTheme.colorScheme.background
    ) { _ ->
        Box(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .hazeSource(hazeState),
                contentPadding = PaddingValues(
                    start = 0.dp,
                    end = 0.dp,
                    top = 65.dp + statusBarHeight,
                    bottom = 80.dp
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item(key = "builtin") {
                    BuiltinSourcesSection(
                        pansouEnabled = pansouEnabled,
                        panhubEnabled = panhubEnabled,
                        zresoEnabled = zresoEnabled,
                        onPansouChange = viewModel::setPansouEnabled,
                        onPanhubChange = viewModel::setPanhubEnabled,
                        onZresoChange = viewModel::setZresoEnabled,
                        onOpenPanHubConfig = { showPanHubConfig = true }
                    )
                }

                item(key = "custom_label") {
                    Column(
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 2.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.search_sources_custom),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }

                if (customSources.isEmpty()) {
                    item(key = "custom_empty") {
                        EmptyCustomSourceCard(
                            onAddFromTemplate = onAddFromTemplate,
                            onImport = { showImportDialog = true }
                        )
                    }
                } else {
                    items(
                        count = customSources.size,
                        key = { index -> "custom_${customSources[index].id}" }
                    ) { index ->
                        val source = customSources[index]
                        val testState = testResults[source.id]
                        CustomSourceRow(
                            source = source,
                            testState = testState,
                            onEnabledChange = { viewModel.setCustomSourceEnabled(source.id, it) },
                            onTest = { viewModel.testCustomSource(source) },
                            onEdit = { onEditSource(source.id) },
                            onDelete = { viewModel.deleteCustomSource(source.id) },
                            onShare = { showShareSource = source }
                        )
                    }
                }

                if (customSources.isNotEmpty()) {
                    item(key = "add_card") {
                        AddSourceCard(
                            onAddFromTemplate = onAddFromTemplate,
                            onImport = { showImportDialog = true }
                        )
                    }
                }
            }

            HeaderBar(
                hazeState = hazeState,
                onBack = onBack,
                onImport = { showImportDialog = true },
                onHelpClick = onHelpClick
            )
        }
    }

    // ---- PanHub 配置弹窗 ----
    if (showPanHubConfig) {
        PanHubConfigDialog(
            config = panHubConfig,
            enabled = panhubEnabled,
            onEnabledChange = { viewModel.setPanhubEnabled(it) },
            onConcurrencyChange = { viewModel.setPanHubConcurrency(it) },
            onTimeoutMsChange = { viewModel.setPanHubTimeoutMs(it) },
            onEnabledPluginsChange = { viewModel.setPanHubEnabledPlugins(it) },
            onEnabledChannelsChange = { viewModel.setPanHubEnabledChannels(it) },
            onDismiss = { showPanHubConfig = false }
        )
    }

    // ---- 分享弹层 ----
    showShareSource?.let { source ->
        ShareSourceDialog(
            source = source,
            onDismiss = { showShareSource = null }
        )
    }

    // ---- 导入弹层：打开时自动识别剪贴板中的分享配置 ----
    if (showImportDialog) {
        ImportSourceDialog(
            onConfirm = { source ->
                showImportDialog = false
                val conflict = viewModel.findImportConflict(source)
                if (conflict != null) {
                    pendingImport = source
                } else {
                    viewModel.importSource(source)
                    Toast.makeText(context, R.string.import_success, Toast.LENGTH_SHORT).show()
                }
            },
            onDismiss = { showImportDialog = false }
        )
    }

    // ---- 冲突确认：同名/同地址源已存在 ----
    pendingImport?.let { source ->
        AlertDialog(
            onDismissRequest = { pendingImport = null },
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            title = { Text(stringResource(R.string.search_sources_title)) },
            text = { Text(stringResource(R.string.import_duplicate_warning)) },
            confirmButton = {
                TextButton(onClick = {
                    pendingImport = null
                    viewModel.importSource(source, overwrite = true)
                    Toast.makeText(context, R.string.import_success, Toast.LENGTH_SHORT).show()
                }) { Text(stringResource(R.string.import_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingImport = null }) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        )
    }
}

/** 毛玻璃吸顶标题栏：返回 + 标题 + 帮助 + 导入入口（间距与按钮样式对齐标记记录页） */
@Composable
private fun HeaderBar(
    hazeState: HazeState,
    onBack: () -> Unit,
    onImport: () -> Unit,
    onHelpClick: () -> Unit = {}
) {
    val isDark = isAppDarkTheme()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .hazeTopBar(
                state = hazeState,
                style = HazeMaterials.thin(MaterialTheme.colorScheme.surface.copy(alpha = 0.55f)),
                blurRadius = 24.dp,
                isContentUnderTopBar = true
            )
            // 拦截点击：顶栏覆盖可滚动列表，不消费会让点击穿透到下方列表项
            .clickable(enabled = false, onClick = {})
    ) {
        Spacer(modifier = Modifier.statusBarsPadding())
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 1.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = stringResource(R.string.detail_back),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            Text(
                text = stringResource(R.string.search_sources_title),
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            // 帮助入口：自定义源的字段含义写在帮助页「自定义搜索源」段，之前只能自己去设置里翻
            NeumorphicIconButton(
                onClick = onHelpClick,
                isDark = isDark,
                lightBorderAlpha = 0.35f,
                hazeState = hazeState
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.HelpOutline,
                    contentDescription = stringResource(R.string.help_title),
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            NeumorphicIconButton(
                onClick = onImport,
                isDark = isDark,
                lightBorderAlpha = 0.35f,
                hazeState = hazeState
            ) {
                Icon(
                    imageVector = Icons.Rounded.FileDownload,
                    contentDescription = stringResource(R.string.search_sources_import),
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

/** 内置搜索源分组：PanSou / PanHub / ZReso */
@Composable
private fun BuiltinSourcesSection(
    pansouEnabled: Boolean,
    panhubEnabled: Boolean,
    zresoEnabled: Boolean,
    onPansouChange: (Boolean) -> Unit,
    onPanhubChange: (Boolean) -> Unit,
    onZresoChange: (Boolean) -> Unit,
    onOpenPanHubConfig: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
    ) {
        SectionLabel(text = stringResource(R.string.search_sources_builtin))
        SourceCardRow(
            title = stringResource(R.string.search_sources_pansou),
            subtitle = stringResource(R.string.search_sources_pansou_desc),
            checked = pansouEnabled,
            onCheckedChange = onPansouChange
        )
        SourceCardRow(
            title = stringResource(R.string.search_sources_panhub),
            subtitle = stringResource(R.string.search_sources_panhub_desc),
            checked = panhubEnabled,
            onCheckedChange = onPanhubChange,
            onClick = onOpenPanHubConfig,
            leadingAction = {
                IconButton(onClick = onOpenPanHubConfig) {
                    Icon(
                        imageVector = Icons.Rounded.Settings,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        )
        SourceCardRow(
            title = stringResource(R.string.search_sources_zreso),
            subtitle = stringResource(R.string.search_sources_zreso_desc),
            checked = zresoEnabled,
            onCheckedChange = onZresoChange
        )
    }
}

/** 分组小标题（与自定义源分组标题字号、左边距一致） */
@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(top = 12.dp, bottom = 2.dp)
    )
}

/** 内置源单行卡片：名称描述 + 开关（可附带点击区域与开关左侧操作） */
@Composable
private fun SourceCardRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    onClick: (() -> Unit)? = null,
    leadingAction: (@Composable () -> Unit)? = null
) {
    val view = LocalView.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (leadingAction != null) {
            leadingAction()
            Spacer(modifier = Modifier.width(4.dp))
        }
        Switch(
            checked = checked,
            onCheckedChange = {
                view.performHaptic(HapticType.CLICK)
                onCheckedChange(it)
            },
            colors = appSwitchColors()
        )
    }
}

/** 空状态卡片：引导新建或导入 */
@Composable
private fun EmptyCustomSourceCard(
    onAddFromTemplate: (String) -> Unit,
    onImport: () -> Unit
) {
    var showTemplateSheet by remember { mutableStateOf(false) }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = stringResource(R.string.search_sources_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { showTemplateSheet = true }) {
                    Icon(
                        imageVector = Icons.Rounded.Add,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(stringResource(R.string.settings_add_source))
                }
                TextButton(onClick = onImport) {
                    Icon(
                        imageVector = Icons.Rounded.FileDownload,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(stringResource(R.string.search_sources_import))
                }
            }
        }
    }

    if (showTemplateSheet) {
        SourceTemplateSheet(
            onTemplateClick = { template ->
                showTemplateSheet = false
                onAddFromTemplate(template.id)
            },
            onImport = {
                showTemplateSheet = false
                onImport()
            },
            onDismiss = { showTemplateSheet = false }
        )
    }
}

/** 自定义源行卡片：名称 + 解析模式徽标 + 测试/编辑/分享/删除 + 开关 */
@Composable
private fun CustomSourceRow(
    source: CustomSearchSource,
    testState: SearchSourcesViewModel.TestResultState?,
    onEnabledChange: (Boolean) -> Unit,
    onTest: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onShare: () -> Unit
) {
    var showDeleteConfirm by remember { mutableStateOf(false) }
    val view = LocalView.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(MaterialTheme.shapes.large)
            .clickable(onClick = { view.performHaptic(HapticType.CLICK); onEdit() })
            .padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = source.name.ifBlank { stringResource(R.string.settings_source_unnamed) },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    SourceBadge(parseMode = source.parseMode)
                }
                Text(
                    text = "${source.baseUrl.ifBlank { "-" }}${source.apiPath}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Switch(
                checked = source.enabled,
                onCheckedChange = onEnabledChange,
                colors = appSwitchColors()
            )
        }
        TestResultLine(testState = testState)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            SourceActionIcon(
                icon = Icons.Rounded.PlayArrow,
                contentDescription = stringResource(R.string.settings_source_test),
                enabled = testState?.isTesting != true,
                onClick = onTest
            )
            if (testState?.isTesting == true) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(4.dp))
            }
            SourceActionIcon(
                icon = Icons.Rounded.Edit,
                contentDescription = stringResource(R.string.cd_edit),
                onClick = onEdit
            )
            SourceActionIcon(
                icon = Icons.Rounded.Share,
                contentDescription = stringResource(R.string.cd_share),
                onClick = onShare
            )
            SourceActionIcon(
                icon = Icons.Rounded.Delete,
                contentDescription = stringResource(R.string.settings_delete_source),
                tint = MaterialTheme.colorScheme.error,
                onClick = { showDeleteConfirm = true }
            )
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            title = { Text(stringResource(R.string.settings_delete_source)) },
            text = {
                Text(
                    stringResource(
                        R.string.settings_delete_source_confirm,
                        source.name.ifBlank { stringResource(R.string.settings_source_unnamed) }
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    onDelete()
                }) {
                    Text(stringResource(R.string.cd_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        )
    }
}

/** 测试结果行：成功/失败 提示文字 */
@Composable
private fun TestResultLine(testState: SearchSourcesViewModel.TestResultState?) {
    val message = testState?.message
    if (message == null) return
    val color = if (testState.success == true) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.error
    }
    Text(
        text = message,
        style = MaterialTheme.typography.bodySmall,
        color = color,
        modifier = Modifier.padding(top = 2.dp)
    )
}

/** 解析模式徽标：pansou / zreso / 自定义 */
@Composable
private fun SourceBadge(parseMode: String) {
    val (labelRes, color) = when (parseMode) {
        "pansou_template", "pansou" -> {
            stringResource(R.string.settings_source_parse_mode_pansou) to Color(0xFF1E88E5)
        }
        "zreso" -> {
            stringResource(R.string.settings_source_parse_mode_zreso) to Color(0xFF43A047)
        }
        else -> {
            stringResource(R.string.settings_source_parse_mode_custom) to MaterialTheme.colorScheme.tertiary
        }
    }
    Text(
        text = labelRes,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        modifier = Modifier
            .clip(MaterialTheme.shapes.small)
            .padding(horizontal = 6.dp, vertical = 2.dp)
    )
}

/** 行内小图标按钮 */
@Composable
private fun SourceActionIcon(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(32.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(20.dp)
        )
    }
}

/** 底部新建卡片：点击打开模板库弹层 */
@Composable
private fun AddSourceCard(
    onAddFromTemplate: (String) -> Unit,
    onImport: () -> Unit
) {
    var showTemplateSheet by remember { mutableStateOf(false) }
    val view = LocalView.current

    // 与反馈页「写新反馈」按钮统一：主色卡片 + 圆角 20 + 图标文字水平居中
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clickable(onClick = { view.performHaptic(HapticType.CLICK); showTemplateSheet = true }),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally)
        ) {
            Icon(
                imageVector = Icons.Rounded.Add,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary
            )
            Text(
                text = stringResource(R.string.settings_add_source),
                color = MaterialTheme.colorScheme.onPrimary,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp
            )
        }
    }

    if (showTemplateSheet) {
        SourceTemplateSheet(
            onTemplateClick = { template ->
                showTemplateSheet = false
                onAddFromTemplate(template.id)
            },
            onImport = {
                showTemplateSheet = false
                onImport()
            },
            onDismiss = { showTemplateSheet = false }
        )
    }
}

package com.tracktosearch.ui.screen.searchsource

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.navigationBars
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
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tracktosearch.R
import com.tracktosearch.data.local.CustomSearchSource
import com.tracktosearch.ui.component.NeumorphicIconButton
import com.tracktosearch.ui.component.EmptyStateCard
import com.tracktosearch.ui.component.LocalAnimatedVisibilityScope
import com.tracktosearch.ui.component.SearchSourcesEntryKey
import com.tracktosearch.ui.component.SettingsEntryCardCorner
import com.tracktosearch.ui.component.SharedCorner
import com.tracktosearch.ui.component.appSharedBounds
import com.tracktosearch.ui.component.appSkipToLookaheadSize
import com.tracktosearch.ui.component.hazeTopBar
import com.tracktosearch.ui.component.hasListScrolled
import com.tracktosearch.ui.component.isAppDarkTheme
import com.tracktosearch.ui.component.isAppSharedTransitionActive
import com.tracktosearch.ui.haptic.HapticOutcomeEffect
import com.tracktosearch.ui.haptic.HapticSemantic
import com.tracktosearch.ui.haptic.hapticClickable
import com.tracktosearch.ui.haptic.rememberAppHaptics
import com.tracktosearch.ui.screen.settings.PanHubConfigDialog
import com.tracktosearch.ui.theme.appSwitchColors
import com.tracktosearch.ui.theme.floatingDialogColor
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.blur.materials.HazeMaterials
import kotlinx.coroutines.launch

/**
 * 搜索源管理页：内置搜索源启停、PanHub 配置入口、自定义搜索源列表与增删改测试。
 * 替换原设置页内嵌的 SearchSourcesItem，成为唯一入口。
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun SearchSourcesScreen(
    onBack: () -> Unit,
    onEditSource: (String) -> Unit,
    onAddFromTemplate: (String) -> Unit,
    /** 跳到帮助页「自定义搜索源」那一段：配置字段的说明都在那里 */
    onHelpClick: () -> Unit = {},
    viewModel: SearchSourcesViewModel = hiltViewModel()
) {
    val hazeState = remember { HazeState() }
    val listState = rememberLazyListState()
    // 共享元素转场 scope（与设置页搜索源入口卡片配对）
    val animatedVisibilityScope = LocalAnimatedVisibilityScope.current
    val hasContentUnderTopBar by remember {
        derivedStateOf {
            hasListScrolled(
                firstVisibleItemIndex = listState.firstVisibleItemIndex,
                firstVisibleItemScrollOffsetPx = listState.firstVisibleItemScrollOffset
            )
        }
    }
    val statusBarHeight = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val pansouEnabled by viewModel.pansouEnabled.collectAsStateWithLifecycle()
    val panhubEnabled by viewModel.panhubEnabled.collectAsStateWithLifecycle()
    val zresoEnabled by viewModel.zresoEnabled.collectAsStateWithLifecycle()
    val customSources by viewModel.customSources.collectAsStateWithLifecycle()
    val testResults by viewModel.testResults.collectAsStateWithLifecycle()
    // 源测试结果、导入撞冲突时的 reject 都从这一行出
    HapticOutcomeEffect(viewModel.hapticOutcomes)
    val panHubConfig by viewModel.panHubConfig.collectAsStateWithLifecycle()
    var showPanHubConfig by remember { mutableStateOf(false) }
    var showShareSource by remember { mutableStateOf<CustomSearchSource?>(null) }
    var showImportDialog by remember { mutableStateOf(false) }
    var pendingImport by remember { mutableStateOf<CustomSearchSource?>(null) }
    // 导入弹层的确认按钮在自己的 subcomposition 里，但导入成功那一记发生在页面作用域
    val importHaptics = rememberAppHaptics()
    // 导入成功反馈改走 Snackbar：与详情页标记、设置页清缓存的反馈风格一致，也不受系统「关闭通知/Toast」影响
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val importSuccessMessage = stringResource(R.string.import_success)

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        // 容器色置透明、底色改由下面那个共享节点自己画，理由见该处注释。
        // contentColor 显式写成 onBackground：Scaffold 默认取 contentColorFor(containerColor)，
        // 而 contentColorFor(Transparent) 是 Unspecified，会让整页文字颜色退回外层 LocalContentColor。
        containerColor = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onBackground,
        snackbarHost = {
            SnackbarHost(
                hostState = snackbarHostState,
                // 本页 contentWindowInsets 置零，Snackbar 得自己避开系统导航栏
                modifier = Modifier.padding(
                    bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 12.dp
                )
            )
        }
    ) { _ ->
        // 与设置页搜索源入口卡片配对的是整页，而不是顶栏：卡片放大成页面、返回时收回成卡片。
        // 卡片侧圆角 SettingsEntryCardCorner，页面侧是 0，转场期间在两者之间插值。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .appSharedBounds(
                    key = SearchSourcesEntryKey,
                    animatedVisibilityScope = animatedVisibilityScope,
                    corner = SharedCorner.flattenFrom(SettingsEntryCardCorner),
                    // 容器变形要的是「内容不变形、被裁剪逐渐露出」，默认的 scaleToBounds 会把内容
                    // 跟着容器一起缩放绘制。逐帧重测的代价由内容侧的 appSkipToLookaheadSize 挡掉。
                    resizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds,
                )
                // 页面底色挪进共享节点内侧，并把 Scaffold 的容器色置透明。
                // 否则 Scaffold 会在共享节点之外先铺满一整屏不透明底色，转场第一帧整屏就已经是本页的背景，
                // 「卡片长成页面」退化成「页面已经在了，只是内容从一个小矩形里长出来」。
                // 挪进来之后底色跟着动画边界一起长大，且被上面那层圆角动画裁剪，落定后与原来逐像素相同。
                .background(MaterialTheme.colorScheme.background)
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    // 整页参与容器变形时按落定尺寸布局：否则列表会跟着容器逐帧变宽，
                    // 一次转场里重复决定「哪些项可见、每项多宽」几十遍
                    .appSkipToLookaheadSize()
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
                isContentUnderTopBar = hasContentUnderTopBar,
                onBack = onBack,
                onImport = { showImportDialog = true },
                // 顶栏不参与配对：来源侧那张卡片上没有对应的标题栏，硬配对会把一行标题从卡片尺寸拉过来。
                // 但它必须从第一帧就在，与内容一样按落定尺寸布局，跟着容器裁剪逐渐露出；
                // 延迟入场会让容器长大的那段时间顶栏位置空着，落位时再整片闪出来。
                modifier = Modifier.appSkipToLookaheadSize(),
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
                    importHaptics.confirm()
                    scope.launch { snackbarHostState.showSnackbar(importSuccessMessage) }
                }
            },
            onDismiss = { showImportDialog = false }
        )
    }

    // ---- 冲突确认：同名/同地址源已存在 ----
    pendingImport?.let { source ->
        AlertDialog(
            onDismissRequest = { pendingImport = null },
            containerColor = floatingDialogColor(),
            title = { Text(stringResource(R.string.search_sources_title)) },
            text = { Text(stringResource(R.string.import_duplicate_warning)) },
            confirmButton = {
                // 每个槽是独立 subcomposition（自己的宿主 View），单独取一份
                val confirmHaptics = rememberAppHaptics()
                TextButton(onClick = {
                    confirmHaptics.tap()
                    pendingImport = null
                    viewModel.importSource(source, overwrite = true)
                    confirmHaptics.confirm()
                    scope.launch { snackbarHostState.showSnackbar(importSuccessMessage) }
                }) { Text(stringResource(R.string.import_confirm)) }
            },
            dismissButton = {
                val dismissHaptics = rememberAppHaptics()
                TextButton(onClick = {
                    dismissHaptics.lightTap()
                    pendingImport = null
                }) {
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
    isContentUnderTopBar: Boolean,
    onBack: () -> Unit,
    onImport: () -> Unit,
    modifier: Modifier = Modifier,
    onHelpClick: () -> Unit = {}
) {
    val isDark = isAppDarkTheme()
    // 转场期间让 haze 停采样：容器变形恰好是每帧背景都在变的时刻，再叠实时模糊最容易掉帧。
    val transitionActive = isAppSharedTransitionActive()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(modifier)
            .hazeTopBar(
                state = hazeState,
                style = HazeMaterials.thin(MaterialTheme.colorScheme.surface.copy(alpha = 0.55f)),
                blurRadius = 24.dp,
                isContentUnderTopBar = if (transitionActive) false else isContentUnderTopBar
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
    val haptics = rememberAppHaptics()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
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
                // 齿轮和整行点的是同一个动作，行那侧已给 LIGHT_TAP，这里对齐；
                // 内层 IconButton 自己消费点击，不会连带发行那一记
                IconButton(
                    onClick = {
                        haptics.lightTap()
                        onOpenPanHubConfig()
                    }
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Settings,
                        contentDescription = stringResource(R.string.settings_panhub_config),
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

// 列表页所有卡片共用一套规格：20dp 圆角、无描边、极轻投影，与统计页卡片对齐
private val SOURCE_CARD_SHAPE = RoundedCornerShape(20.dp)

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

/**
 * 内置源单行卡片：名称描述 + 开关（可附带点击区域与开关左侧操作）。
 *
 * 之前只 clip 不填色，行是透明的，整页看起来是一串浮在背景上的文字；
 * 补上 surfaceVariant 卡面 + 20dp 圆角 + 极轻投影，和统计页卡片规格对齐。
 */
@Composable
private fun SourceCardRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    onClick: (() -> Unit)? = null,
    leadingAction: (@Composable () -> Unit)? = null
) {
    val haptics = rememberAppHaptics()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(1.dp, SOURCE_CARD_SHAPE)
            .clip(SOURCE_CARD_SHAPE)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .then(
                if (onClick != null) {
                    Modifier.hapticClickable(semantic = HapticSemantic.LIGHT_TAP, onClick = onClick)
                } else {
                    Modifier
                }
            )
            .padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
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
                haptics.toggle(it)
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
    EmptyStateCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        isDark = isAppDarkTheme(),
        title = stringResource(R.string.search_sources_empty),
        // 空状态只写「暂无自定义源」等于没说；补一句「自定义源能做什么」才有点开的理由
        description = stringResource(R.string.search_sources_empty_hint),
        icon = Icons.Rounded.Language,
        actions = {
            // EmptyStateCard 是纯容器，actions 槽里的按钮要自己发；两个都是带文字的 CTA，同给 TAP
            val actionHaptics = rememberAppHaptics()
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    onClick = {
                        actionHaptics.tap()
                        showTemplateSheet = true
                    }
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Add,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(stringResource(R.string.settings_add_source))
                }
                TextButton(
                    onClick = {
                        actionHaptics.tap()
                        onImport()
                    }
                ) {
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
    )

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
    val haptics = rememberAppHaptics()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .shadow(1.dp, SOURCE_CARD_SHAPE)
            .clip(SOURCE_CARD_SHAPE)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .hapticClickable(semantic = HapticSemantic.LIGHT_TAP, onClick = onEdit)
            .padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 8.dp)
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
                // 与紧挨在上方的内置源开关（SourceCardRow）同一种控件、同一屏，手感必须一致
                onCheckedChange = {
                    haptics.toggle(it)
                    onEnabledChange(it)
                },
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
            containerColor = floatingDialogColor(),
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
                // 每个槽是独立 subcomposition（自己的宿主 View），单独取一份
                val confirmHaptics = rememberAppHaptics()
                TextButton(onClick = {
                    confirmHaptics.tap()
                    showDeleteConfirm = false
                    onDelete()
                }) {
                    Text(stringResource(R.string.cd_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                val dismissHaptics = rememberAppHaptics()
                TextButton(onClick = {
                    dismissHaptics.lightTap()
                    showDeleteConfirm = false
                }) {
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
    // 测试/编辑/分享/删除四个都走这里，一处给完保证同一行四个图标手感一致。
    // 删除那个点下去会弹确认框，但按钮身份就是图标按钮，弹窗那记归另一个任务
    val haptics = rememberAppHaptics()
    IconButton(
        onClick = {
            haptics.lightTap()
            onClick()
        },
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

    // 与反馈页「写新反馈」按钮统一：主色卡片 + 圆角 20 + 图标文字水平居中
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .hapticClickable(semantic = HapticSemantic.TAP) { showTemplateSheet = true },
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

package com.tracktosearch.ui.screen.searchsource

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tracktosearch.R
import com.tracktosearch.ui.component.hazeTopBar
import com.tracktosearch.ui.haptic.HapticSemantic
import com.tracktosearch.ui.haptic.hapticClickable
import com.tracktosearch.ui.haptic.rememberAppHaptics
import com.tracktosearch.ui.theme.floatingDialogColor
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.blur.materials.HazeMaterials
import kotlinx.coroutines.launch

/**
 * 分步向导编辑页：
 * 步骤 1 基本信息（名称+地址）→ 步骤 2 自动解析 → 步骤 3 确认参数。
 * 模板/编辑/导入模式直接进步骤 3。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchSourceEditorScreen(
    onBack: () -> Unit,
    onSaved: () -> Unit,
    viewModel: SearchSourceEditorViewModel = hiltViewModel()
) {
    val hazeState = remember { HazeState() }
    val name by viewModel.name.collectAsStateWithLifecycle()
    val baseUrl by viewModel.baseUrl.collectAsStateWithLifecycle()
    val apiPath by viewModel.apiPath.collectAsStateWithLifecycle()
    val keywordParam by viewModel.keywordParam.collectAsStateWithLifecycle()
    val cloudTypesParam by viewModel.cloudTypesParam.collectAsStateWithLifecycle()
    val cloudTypesValue by viewModel.cloudTypesValue.collectAsStateWithLifecycle()
    val srcParam by viewModel.srcParam.collectAsStateWithLifecycle()
    val srcValue by viewModel.srcValue.collectAsStateWithLifecycle()
    val parseMode by viewModel.parseMode.collectAsStateWithLifecycle()
    val listPath by viewModel.listPath.collectAsStateWithLifecycle()
    val namePath by viewModel.namePath.collectAsStateWithLifecycle()
    val urlPath by viewModel.urlPath.collectAsStateWithLifecycle()
    val diskTypePath by viewModel.diskTypePath.collectAsStateWithLifecycle()
    val datePath by viewModel.datePath.collectAsStateWithLifecycle()
    val step by viewModel.step.collectAsStateWithLifecycle()
    val probeState by viewModel.probeState.collectAsStateWithLifecycle()
    val importState by viewModel.importState.collectAsStateWithLifecycle()
    val appliedTemplateName by viewModel.appliedTemplateName.collectAsStateWithLifecycle()
    var showConflictDialog by remember { mutableStateOf(false) }
    var testOutcome by remember {
        mutableStateOf<SearchSourceEditorViewModel.TestOutcome?>(null)
    }
    var isTesting by remember { mutableStateOf(false) }
    var showTemplateSheet by remember { mutableStateOf(false) }
    // 只展开第一组：13 个字段一次铺开会把人吓退，其余两组按需打开
    var expandedGroup by remember { mutableStateOf<EditorGroup?>(EditorGroup.BASIC) }

    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val pasteEmptyMessage = stringResource(R.string.editor_paste_empty)
    // 测试结果那一记触感挂在回调上而不是挂在 testOutcome 状态上：回调一次测试只走一遍，
    // 而 LaunchedEffect(testOutcome) 会在旋屏后拿着旧结果再震一次
    val outcomeHaptics = rememberAppHaptics()

    val values = SourceFormValues(
        name = name,
        baseUrl = baseUrl,
        apiPath = apiPath,
        keywordParam = keywordParam,
        cloudTypesParam = cloudTypesParam,
        cloudTypesValue = cloudTypesValue,
        srcParam = srcParam,
        srcValue = srcValue,
        parseMode = parseMode,
        listPath = listPath.orEmpty(),
        namePath = namePath.orEmpty(),
        urlPath = urlPath.orEmpty(),
        diskTypePath = diskTypePath.orEmpty(),
        datePath = datePath.orEmpty(),
    )
    val callbacks = remember(viewModel) {
        SourceFormCallbacks(
            onName = viewModel::setName,
            onBaseUrl = viewModel::setBaseUrl,
            onApiPath = viewModel::setApiPath,
            onKeywordParam = viewModel::setKeywordParam,
            onCloudTypesParam = viewModel::setCloudTypesParam,
            onCloudTypesValue = viewModel::setCloudTypesValue,
            onSrcParam = viewModel::setSrcParam,
            onSrcValue = viewModel::setSrcValue,
            onParseMode = viewModel::setParseMode,
            onListPath = viewModel::setListPath,
            onNamePath = viewModel::setNamePath,
            onUrlPath = viewModel::setUrlPath,
            onDiskTypePath = viewModel::setDiskTypePath,
            onDatePath = viewModel::setDatePath,
        )
    }
    // 只在用户按下按钮时读剪贴板：进页面自动读会让系统弹「已读取剪贴板」提示，观感像偷看
    val onPasteBaseUrl: () -> Unit = {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = clipboard.primaryClip
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.text
            ?.toString()
            ?.trim()
        if (text.isNullOrBlank()) {
            scope.launch { snackbarHostState.showSnackbar(pasteEmptyMessage) }
        } else {
            viewModel.setBaseUrl(text)
        }
    }

    val title = stringResource(
        when {
            importState !is SearchSourceEditorViewModel.ImportUiState.Empty -> R.string.editor_title_import
            else -> R.string.editor_title_add
        }
    )

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = {
            SnackbarHost(
                hostState = snackbarHostState,
                // 本页 contentWindowInsets 置零，Snackbar 得自己避开底部操作栏与导航栏
                modifier = Modifier.padding(
                    bottom = WindowInsets.navigationBars.asPaddingValues()
                        .calculateBottomPadding() + 84.dp
                )
            )
        }
    ) { _ ->
        Box(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                EditorHeader(
                    title = title,
                    hazeState = hazeState,
                    onBack = onBack
                )
                StepIndicator(currentStep = step, onStepClick = viewModel::goToStep)

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .hazeSource(hazeState)
                        .verticalScroll(rememberScrollState())
                ) {
                    when (step) {
                        1 -> StepBasic(
                            name = name, onNameChange = viewModel::setName,
                            baseUrl = baseUrl, onBaseUrlChange = viewModel::setBaseUrl,
                            onPickTemplate = { showTemplateSheet = true },
                            onPasteBaseUrl = onPasteBaseUrl
                        )
                        2 -> StepAutoProbe(
                            probeState = probeState,
                            onStartProbe = viewModel::startProbe,
                            // 手动调参必然从 JSONPath 开始，直接把解析规则组展开，省一次点击
                            onManual = {
                                expandedGroup = EditorGroup.PARSE
                                viewModel.switchToManual()
                            }
                        )
                        3 -> StepConfirm(
                            values = values,
                            callbacks = callbacks,
                            expandedGroup = expandedGroup,
                            onGroupToggle = { group ->
                                expandedGroup = if (expandedGroup == group) null else group
                            },
                            appliedTemplateName = appliedTemplateName,
                            importState = importState,
                            onImportRename = viewModel::renameForImport,
                            onPasteBaseUrl = onPasteBaseUrl
                        )
                    }
                    // 底部操作栏悬浮在内容之上，留出它的高度免得最后一个字段被压住
                    Spacer(
                        modifier = Modifier.height(
                            96.dp + WindowInsets.navigationBars.asPaddingValues()
                                .calculateBottomPadding()
                        )
                    )
                }
            }

            EditorBottomBar(
                modifier = Modifier.align(Alignment.BottomCenter),
                hazeState = hazeState,
                step = step,
                canNext = when (step) {
                    1 -> name.isNotBlank() && baseUrl.isNotBlank()
                    2 -> probeState is SearchSourceEditorViewModel.ProbeUiState.Found ||
                        probeState is SearchSourceEditorViewModel.ProbeUiState.Failed ||
                        probeState is SearchSourceEditorViewModel.ProbeUiState.Idle
                    else -> false
                },
                onPrev = viewModel::prevStep,
                onNext = viewModel::nextStep,
                onTest = {
                    isTesting = true
                    testOutcome = null
                    viewModel.testCurrent { outcome ->
                        if (outcome.success) outcomeHaptics.confirm() else outcomeHaptics.reject()
                        testOutcome = outcome
                        isTesting = false
                    }
                },
                isTesting = isTesting,
                testOutcome = testOutcome,
                onSave = {
                    val conflict = viewModel.findConflict()
                    if (conflict != null && conflict.id != viewModel.currentEditingId()) {
                        showConflictDialog = true
                    } else {
                        // save() 返回 null 只有名称/地址为空一种可能，而 canSave 已经拦在按钮上；
                        // 仍按返回值判，免得哪天 save() 多一条失败路径而这里还在盲报成功
                        if (viewModel.save() != null) outcomeHaptics.confirm() else outcomeHaptics.reject()
                        onSaved()
                    }
                },
                canSave = name.isNotBlank() && baseUrl.isNotBlank()
            )
        }
    }

    if (showTemplateSheet) {
        SourceTemplateSheet(
            onTemplateClick = { template ->
                showTemplateSheet = false
                viewModel.applyTemplate(template.id)
            },
            onImport = null,
            onDismiss = { showTemplateSheet = false }
        )
    }

    if (showConflictDialog) {
        AlertDialog(
            onDismissRequest = { showConflictDialog = false },
            containerColor = floatingDialogColor(),
            title = { Text(stringResource(R.string.search_sources_title)) },
            text = { Text(stringResource(R.string.import_duplicate_warning)) },
            confirmButton = {
                // 每个槽是独立 subcomposition（自己的宿主 View），单独取一份
                val confirmHaptics = rememberAppHaptics()
                TextButton(onClick = {
                    confirmHaptics.tap()
                    showConflictDialog = false
                    if (viewModel.save() != null) confirmHaptics.confirm() else confirmHaptics.reject()
                    onSaved()
                }) {
                    Text(stringResource(R.string.import_confirm))
                }
            },
            dismissButton = {
                val dismissHaptics = rememberAppHaptics()
                TextButton(onClick = {
                    dismissHaptics.lightTap()
                    showConflictDialog = false
                }) { Text(stringResource(android.R.string.cancel)) }
            }
        )
    }
}

/** 毛玻璃标题栏 */
@Composable
private fun EditorHeader(
    title: String,
    hazeState: HazeState,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .hazeTopBar(
                state = hazeState,
                style = HazeMaterials.thin(MaterialTheme.colorScheme.surface.copy(alpha = 0.55f)),
                blurRadius = 24.dp,
                isContentUnderTopBar = true
            )
    ) {
        Spacer(modifier = Modifier.statusBarsPadding())
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 8.dp),
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
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** 三组字段折叠分组，一次只展开一组 */
internal enum class EditorGroup { BASIC, REQUEST, PARSE }

/**
 * 胶囊步骤条：已完成的步骤显示对勾，当前步骤填充主色，未到的步骤淡显。
 *
 * 原先三格等宽方块只有数字和文字，看不出「哪几步已经过了」；
 * 换成对勾胶囊 + 连接线后，进度一眼可读，点击仍可直接跳步。
 */
@Composable
private fun StepIndicator(
    currentStep: Int,
    onStepClick: (Int) -> Unit
) {
    val labels = listOf(
        stringResource(R.string.editor_step_basic),
        stringResource(R.string.editor_step_auto),
        stringResource(R.string.editor_step_confirm)
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        labels.forEachIndexed { index, label ->
            val stepNumber = index + 1
            val isActive = stepNumber == currentStep
            val isDone = stepNumber < currentStep
            if (index > 0) {
                // 连接线跟随左侧步骤的完成度着色，形成一条走过的轨迹
                Box(
                    modifier = Modifier
                        .width(14.dp)
                        .height(2.dp)
                        .clip(RoundedCornerShape(1.dp))
                        .background(
                            if (stepNumber <= currentStep) {
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f)
                            }
                        )
                )
            }
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(50))
                    .background(
                        when {
                            isActive -> MaterialTheme.colorScheme.primary
                            isDone -> MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                            else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                        }
                    )
                    // 三格步骤条是分段控件，一组里只能停在一格上，走刻度感
                    .hapticClickable(semantic = HapticSemantic.SEGMENT_TICK) {
                        onStepClick(stepNumber)
                    }
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                val contentColor = when {
                    isActive -> MaterialTheme.colorScheme.onPrimary
                    isDone -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
                if (isDone) {
                    Icon(
                        imageVector = Icons.Rounded.Check,
                        contentDescription = null,
                        modifier = Modifier.size(15.dp),
                        tint = contentColor
                    )
                } else {
                    Text(
                        text = "$stepNumber",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = contentColor
                    )
                }
                Spacer(modifier = Modifier.width(5.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                    color = contentColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/** 步骤 1：基本信息 + 模板入口 + 剪贴板粘贴 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StepBasic(
    name: String,
    onNameChange: (String) -> Unit,
    baseUrl: String,
    onBaseUrlChange: (String) -> Unit,
    onPickTemplate: () -> Unit,
    onPasteBaseUrl: () -> Unit
) {
    val haptics = rememberAppHaptics()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 模板入口放在最上面：多数人要加的源就在模板里，先给这条捷径再谈手填
        Card(
            // 带右箭头的跳转入口，不是本页的提交动作，按次级入口给 LIGHT_TAP
            onClick = {
                haptics.lightTap()
                onPickTemplate()
            },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.editor_template_entry_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = stringResource(R.string.editor_template_entry_desc),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Icon(
                    imageVector = Icons.Rounded.ChevronRight,
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(1.dp)
                    .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f))
            )
            Text(
                text = stringResource(R.string.editor_manual_divider),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(1.dp)
                    .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f))
            )
        }

        SourceTextField(
            label = stringResource(R.string.settings_source_name),
            value = name,
            onValueChange = onNameChange,
            required = true,
            supporting = stringResource(R.string.editor_name_hint)
        )
        SourceTextField(
            label = stringResource(R.string.editor_label_base_url),
            value = baseUrl,
            onValueChange = onBaseUrlChange,
            placeholder = EXAMPLE_BASE_URL,
            required = true,
            supporting = stringResource(R.string.editor_base_url_hint),
            monospace = true
        )
        OutlinedButton(
            onClick = {
                haptics.tap()
                onPasteBaseUrl()
            },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.ContentPaste,
                contentDescription = null,
                modifier = Modifier.size(17.dp)
            )
            Spacer(modifier = Modifier.width(7.dp))
            Text(stringResource(R.string.editor_paste_url))
        }
    }
}

/** 步骤 2：自动解析 */
@Composable
private fun StepAutoProbe(
    probeState: SearchSourceEditorViewModel.ProbeUiState,
    onStartProbe: () -> Unit,
    onManual: () -> Unit
) {
    // 本步四个按钮都是带文字的操作按钮，同给 tap，共用一份实例
    val haptics = rememberAppHaptics()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = stringResource(R.string.editor_probe_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        when (probeState) {
            is SearchSourceEditorViewModel.ProbeUiState.Idle -> {
                Button(
                    onClick = {
                        haptics.tap()
                        onStartProbe()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.size(6.dp))
                    Text(stringResource(R.string.editor_probe_start))
                }
            }
            is SearchSourceEditorViewModel.ProbeUiState.Probing -> {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.size(10.dp))
                    Text(
                        text = stringResource(R.string.editor_probe_progress),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            is SearchSourceEditorViewModel.ProbeUiState.Found -> {
                ProbeResultCard(state = probeState)
                OutlinedButton(
                    onClick = {
                        haptics.tap()
                        onManual()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp)
                ) { Text(stringResource(R.string.editor_probe_manual)) }
            }
            is SearchSourceEditorViewModel.ProbeUiState.Failed -> {
                ProbeFailureCard(reason = probeState.reason)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            haptics.tap()
                            onStartProbe()
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(17.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.editor_probe_retry))
                    }
                    Button(
                        onClick = {
                            haptics.tap()
                            onManual()
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp)
                    ) { Text(stringResource(R.string.editor_probe_manual)) }
                }
            }
        }
    }
}

/**
 * 探测失败卡片：一句原因 + 一句建议。
 *
 * 只说「自动解析失败」没法行动——地址打不开、返回不是 JSON、字段认不出，
 * 三种情况该改的东西完全不同，所以分开讲。
 */
@Composable
private fun ProbeFailureCard(reason: SearchSourceEditorViewModel.ProbeFailureReason) {
    val (reasonRes, adviceRes) = when (reason) {
        SearchSourceEditorViewModel.ProbeFailureReason.UNREACHABLE ->
            R.string.editor_probe_failed_unreachable to R.string.editor_probe_advice_unreachable
        SearchSourceEditorViewModel.ProbeFailureReason.NOT_JSON ->
            R.string.editor_probe_failed_not_json to R.string.editor_probe_advice_not_json
        SearchSourceEditorViewModel.ProbeFailureReason.UNRECOGNIZED ->
            R.string.editor_probe_failed_unrecognized to R.string.editor_probe_advice_unrecognized
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.error.copy(alpha = 0.08f))
            .padding(14.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            imageVector = Icons.Rounded.ErrorOutline,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.error
        )
        Spacer(modifier = Modifier.width(10.dp))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = stringResource(reasonRes),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.error
            )
            Text(
                text = stringResource(adviceRes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 探测结果卡片：识别出的路径 + 关键词参数 */
@Composable
private fun ProbeResultCard(state: SearchSourceEditorViewModel.ProbeUiState.Found) {
    val result = state.result
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = stringResource(R.string.editor_probe_result_title),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold
        )
        ProbePathRow(stringResource(R.string.editor_probe_result_list), result.listPath)
        ProbePathRow(stringResource(R.string.editor_probe_result_url), result.urlPath)
        result.diskTypePath?.let { ProbePathRow(stringResource(R.string.editor_probe_result_disk), it) }
        result.datePath?.let { ProbePathRow(stringResource(R.string.editor_probe_result_date), it) }
        ProbePathRow(stringResource(R.string.editor_probe_keyword_param), state.keywordParam)
        ProbePathRow(stringResource(R.string.settings_source_api_path), state.apiPath)
    }
}

@Composable
private fun ProbePathRow(label: String, path: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = path,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** 步骤 3：确认参数，13 个字段收进三张折叠分组卡 */
@Composable
private fun StepConfirm(
    values: SourceFormValues,
    callbacks: SourceFormCallbacks,
    expandedGroup: EditorGroup?,
    onGroupToggle: (EditorGroup) -> Unit,
    appliedTemplateName: String?,
    importState: SearchSourceEditorViewModel.ImportUiState,
    onImportRename: (String) -> Unit,
    onPasteBaseUrl: () -> Unit
) {
    val haptics = rememberAppHaptics()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 导入预览态
        if (importState is SearchSourceEditorViewModel.ImportUiState.Preview) {
            SourceTextField(
                label = stringResource(R.string.import_preview_name),
                value = importState.source.name,
                onValueChange = onImportRename,
                required = true,
                supporting = stringResource(R.string.import_valid)
            )
        } else if (importState is SearchSourceEditorViewModel.ImportUiState.Invalid) {
            Text(
                text = stringResource(R.string.import_invalid),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }

        // 模板提示
        appliedTemplateName?.let { templateName ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = null,
                    modifier = Modifier.size(17.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.editor_applied_template, templateName),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        // 请求 URL 预览：三组字段填完长什么样，比逐个字段核对直观
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = stringResource(R.string.editor_preview_url),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            val previewUrl = buildString {
                append(values.baseUrl.trimEnd('/'))
                append('/')
                append(values.apiPath.trimStart('/'))
                if (values.keywordParam.isNotBlank()) {
                    append("?")
                    append(values.keywordParam)
                    append("=…")
                }
            }
            Text(
                text = previewUrl,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.primary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth()
            )
        }

        BasicFieldsGroup(
            values = values,
            callbacks = callbacks,
            expanded = expandedGroup == EditorGroup.BASIC,
            onToggle = { onGroupToggle(EditorGroup.BASIC) },
            baseUrlTrailingIcon = {
                // 输入框尾部的图标按钮，比步骤 1 那个整宽文字按钮轻一档
                IconButton(
                    onClick = {
                        haptics.lightTap()
                        onPasteBaseUrl()
                    }
                ) {
                    Icon(
                        imageVector = Icons.Rounded.ContentPaste,
                        contentDescription = stringResource(R.string.editor_paste_url),
                        modifier = Modifier.size(19.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        )
        RequestFieldsGroup(
            values = values,
            callbacks = callbacks,
            expanded = expandedGroup == EditorGroup.REQUEST,
            onToggle = { onGroupToggle(EditorGroup.REQUEST) }
        )
        ParseFieldsGroup(
            values = values,
            callbacks = callbacks,
            expanded = expandedGroup == EditorGroup.PARSE,
            onToggle = { onGroupToggle(EditorGroup.PARSE) }
        )
    }
}

/**
 * 底部操作栏：上一步/下一步/测试/保存。
 *
 * 原先是不透明背景硬贴在内容上，滚动时和卡片撞色；改成毛玻璃后能透出下面的内容，
 * 同时补上导航栏 inset——之前全屏手势条会压住按钮。
 */
@Composable
private fun EditorBottomBar(
    modifier: Modifier = Modifier,
    hazeState: HazeState,
    step: Int,
    canNext: Boolean,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onTest: () -> Unit,
    isTesting: Boolean,
    testOutcome: SearchSourceEditorViewModel.TestOutcome?,
    onSave: () -> Unit,
    canSave: Boolean
) {
    // 上一步/下一步/测试/保存都是带文字的操作按钮，同给 tap；
    // 「保存成功」那一记 confirm 由调用方在 onSave 回调里按 save() 的返回值发，这里只发点击
    val haptics = rememberAppHaptics()
    Column(
        modifier = modifier
            .fillMaxWidth()
            .hazeTopBar(
                state = hazeState,
                style = HazeMaterials.thin(MaterialTheme.colorScheme.surface.copy(alpha = 0.55f)),
                blurRadius = 24.dp,
                isContentUnderTopBar = true
            )
            .padding(
                start = 16.dp,
                end = 16.dp,
                top = 12.dp,
                // 悬浮底栏自己吃掉导航栏内边距：Scaffold 为了让内容能滑到屏幕底沿没有留 inset
                bottom = 12.dp + WindowInsets.navigationBars.asPaddingValues()
                    .calculateBottomPadding()
            )
    ) {
        testOutcome?.let { outcome ->
            Text(
                text = outcome.message,
                style = MaterialTheme.typography.bodySmall,
                // 成功与失败原来同画 primary 色，「测试失败」和「测试通过」长得一模一样
                color = if (outcome.success) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                },
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (step > 1) {
                OutlinedButton(
                    onClick = {
                        haptics.tap()
                        onPrev()
                    },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(14.dp)
                ) { Text(stringResource(R.string.editor_prev)) }
            }
            when (step) {
                1 -> Button(
                    onClick = {
                        haptics.tap()
                        onNext()
                    },
                    enabled = canNext,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(14.dp)
                ) { Text(stringResource(R.string.editor_next)) }
                2 -> Button(
                    onClick = {
                        haptics.tap()
                        onNext()
                    },
                    enabled = canNext,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(14.dp)
                ) { Text(stringResource(R.string.editor_next_confirm)) }
                else -> {
                    OutlinedButton(
                        onClick = {
                            haptics.tap()
                            onTest()
                        },
                        enabled = !isTesting,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        if (isTesting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text(stringResource(R.string.settings_source_test))
                        }
                    }
                    Button(
                        onClick = {
                            haptics.tap()
                            onSave()
                        },
                        enabled = canSave,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp)
                    ) { Text(stringResource(R.string.settings_source_save)) }
                }
            }
        }
    }
}
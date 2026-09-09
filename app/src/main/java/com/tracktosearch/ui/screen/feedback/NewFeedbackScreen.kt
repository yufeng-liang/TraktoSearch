package com.tracktosearch.ui.screen.feedback

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.tracktosearch.R
import com.tracktosearch.ui.component.LocalFullscreenSharedElement
import com.tracktosearch.ui.component.fullscreenSharedElementKey
import com.tracktosearch.ui.component.ZoomableImageOverlay
import com.tracktosearch.ui.component.hazeTopBar
import com.tracktosearch.ui.component.zoomSharedSource
import com.tracktosearch.ui.haptic.HapticSemantic
import com.tracktosearch.ui.haptic.hapticClickable
import com.tracktosearch.ui.haptic.rememberAppHaptics
import com.tracktosearch.ui.theme.floatingDialogColor
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.materials.HazeMaterials
import dev.chrisbanes.haze.hazeSource
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

private const val MAX_SCREENSHOTS = 5
private const val MAX_CONTENT_LENGTH = 2000
private const val MIN_CONTENT_LENGTH = 5

/** 字数计数器只在快到上限时才提示颜色，平时是普通弱化色。 */
private const val CONTENT_COUNTER_WARN_FROM = 1800

/** 四个反馈类型的固定顺序，与 [feedbackTypeLabel] 的映射对应。 */
private val FEEDBACK_TYPES = listOf("FEATURE", "BUG", "UX", "OTHER")

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun NewFeedbackScreen(
    onBack: () -> Unit,
    onSuccess: () -> Unit,
    viewModel: FeedbackViewModel = hiltViewModel()
) {
    val submitState by viewModel.submitState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scrollState = rememberScrollState()
    val hazeState = remember { HazeState() }
    val hazeStyle = HazeMaterials.thin()
    val hasContentUnderTopBar by remember {
        derivedStateOf { scrollState.value > 0 }
    }

    var selectedType by remember { mutableStateOf<String?>(null) }
    var content by remember { mutableStateOf("") }
    var screenshots by remember { mutableStateOf<List<Pair<ByteArray, String>>>(emptyList()) }

    // 截图全屏查看
    var fullscreenIndex by remember { mutableStateOf<Int?>(null) }
    var showDiscardDialog by remember { mutableStateOf(false) }

    // 图片选择器
    val pickImageLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris: List<Uri> ->
        val newScreenshots = uris.take(MAX_SCREENSHOTS - screenshots.size).mapNotNull { uri ->
            try {
                val mimeType = context.contentResolver.getType(uri) ?: "image/jpeg"
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                if (bytes != null) bytes to mimeType else null
            } catch (e: Exception) {
                null
            }
        }
        screenshots = (screenshots + newScreenshots).take(MAX_SCREENSHOTS)
    }

    // 提交成功后返回
    val outcomeHaptics = rememberAppHaptics()
    LaunchedEffect(submitState) {
        when (submitState) {
            is FeedbackViewModel.SubmitState.Success -> {
                outcomeHaptics.confirm()
                onSuccess()
                viewModel.resetSubmitState()
            }
            // 失败态在下面渲染成一张错误卡片，触感是对那张卡片的补充而不是唯一反馈
            is FeedbackViewModel.SubmitState.Error -> outcomeHaptics.reject()
            // 上传中/提交中/空闲都不是结果
            FeedbackViewModel.SubmitState.Idle,
            is FeedbackViewModel.SubmitState.Uploading,
            FeedbackViewModel.SubmitState.Submitting,
            -> Unit
        }
    }

    val isSubmitting = submitState is FeedbackViewModel.SubmitState.Uploading ||
        submitState is FeedbackViewModel.SubmitState.Submitting
    val hasDraft = selectedType != null || content.isNotBlank() || screenshots.isNotEmpty()
    val canSubmit = selectedType != null &&
        content.length >= MIN_CONTENT_LENGTH &&
        !isSubmitting

    // 打了半页字被返回键清空是这页最容易犯的错，有草稿就先问一句
    val requestBack: () -> Unit = {
        if (hasDraft && !isSubmitting) showDiscardDialog = true else onBack()
    }
    BackHandler(enabled = hasDraft && !isSubmitting) { showDiscardDialog = true }

    // 全屏查看器打开时把该 key 广播给缩略图源侧，让源侧置不可见，
    // 保证同一 key 同时只有一侧是 target（否则缩放转场方向会反）
    val fullscreenSharedKey = fullscreenIndex
        ?.takeIf { screenshots.isNotEmpty() }
        ?.let { "fb-new-${it.coerceIn(0, screenshots.size - 1)}" }
    CompositionLocalProvider(
        LocalFullscreenSharedElement provides fullscreenSharedElementKey(fullscreenSharedKey)
    ) {

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            val topBarHeight = 64.dp +
                WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .hazeSource(state = hazeState)
                    // 原先是固定高度的 Column，键盘弹起后提交按钮被顶出屏幕、
                    // 大字号下类型 chip 也会被挤掉
                    .verticalScroll(scrollState)
                    .padding(
                        start = 16.dp,
                        end = 16.dp,
                        top = topBarHeight + 8.dp,
                        bottom = 24.dp +
                            WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                    ),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                NewFeedbackSectionLabel(text = stringResource(R.string.feedback_select_type))
                // 单行 Row 在窄屏或大字号下会把第四个 chip 挤出去，改成自动换行
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FEEDBACK_TYPES.forEach { type ->
                        FeedbackTypeChip(
                            type = type,
                            selected = selectedType == type,
                            enabled = !isSubmitting,
                            onClick = {
                                selectedType = if (selectedType == type) null else type
                            }
                        )
                    }
                }
                // 正文写够了但没选类型时，提交键是灰的却看不出为什么
                if (selectedType == null && content.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.feedback_type_required),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                OutlinedTextField(
                    value = content,
                    onValueChange = { if (it.length <= MAX_CONTENT_LENGTH) content = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 140.dp),
                    placeholder = { Text(stringResource(R.string.feedback_content_placeholder)) },
                    label = { Text(stringResource(R.string.feedback_content_label)) },
                    isError = content.isNotEmpty() && content.length < MIN_CONTENT_LENGTH,
                    shape = RoundedCornerShape(16.dp),
                    supportingText = {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            if (content.isNotEmpty() && content.length < MIN_CONTENT_LENGTH) {
                                Text(
                                    text = stringResource(R.string.feedback_content_too_short),
                                    color = MaterialTheme.colorScheme.error
                                )
                            } else {
                                Spacer(Modifier.width(0.dp))
                            }
                            Text(
                                text = "${content.length}/$MAX_CONTENT_LENGTH",
                                fontSize = 11.sp,
                                // 只在快到上限时变色，平时不要一直红着催人
                                color = if (content.length >= CONTENT_COUNTER_WARN_FROM) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        }
                    },
                    enabled = !isSubmitting
                )

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    NewFeedbackSectionLabel(text = stringResource(R.string.feedback_screenshots))
                    // 上限和长按排序原先只有代码知道
                    Text(
                        text = stringResource(R.string.feedback_screenshots_hint),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                ScreenshotRow(
                    screenshots = screenshots,
                    enabled = !isSubmitting,
                    sharedKeyPrefix = "fb-new",
                    onAddClick = { pickImageLauncher.launch("image/*") },
                    onRemoveClick = { index ->
                        screenshots = screenshots.toMutableList().apply { removeAt(index) }
                    },
                    onImageClick = { index -> fullscreenIndex = index },
                    onReorder = { from, to ->
                        screenshots = screenshots.toMutableList().apply {
                            add(to, removeAt(from))
                        }
                    }
                )

                (submitState as? FeedbackViewModel.SubmitState.Error)?.let {
                    FeedbackNoticeBanner(
                        icon = Icons.Rounded.ErrorOutline,
                        text = it.message,
                        accent = MaterialTheme.colorScheme.error
                    )
                }

                SubmitFeedbackButton(
                    submitState = submitState,
                    isSubmitting = isSubmitting,
                    enabled = canSubmit,
                    onSubmit = {
                        selectedType?.let { type ->
                            viewModel.submit(
                                type = type,
                                content = content.trim(),
                                screenshotBytes = screenshots.map { it.first },
                                screenshotMimeTypes = screenshots.map { it.second }
                            )
                        }
                    }
                )
            }

            NewFeedbackTopBar(
                hazeState = hazeState,
                hazeStyle = hazeStyle,
                isContentUnderTopBar = hasContentUnderTopBar,
                backEnabled = !isSubmitting,
                onBack = requestBack
            )
        }
    }

    if (showDiscardDialog) {
        AlertDialog(
            onDismissRequest = { showDiscardDialog = false },
            title = { Text(stringResource(R.string.feedback_discard_title)) },
            text = { Text(stringResource(R.string.feedback_discard_message)) },
            confirmButton = {
                // 弹窗的两个槽各是独立 subcomposition，各取一份 facade
                val confirmHaptics = rememberAppHaptics()
                TextButton(
                    onClick = {
                        confirmHaptics.tap()
                        showDiscardDialog = false
                        onBack()
                    }
                ) {
                    Text(
                        text = stringResource(R.string.feedback_discard_confirm),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                val dismissHaptics = rememberAppHaptics()
                TextButton(onClick = {
                    dismissHaptics.lightTap()
                    showDiscardDialog = false
                }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
            containerColor = floatingDialogColor()
        )
    }

    // 截图全屏查看
    ZoomableImageOverlay(
        visible = fullscreenIndex != null && screenshots.isNotEmpty(),
        images = screenshots.map { it.first },
        initialIndex = fullscreenIndex?.coerceIn(0, screenshots.size - 1) ?: 0,
        sharedKeyPrefix = "fb-new",
        onDismiss = { fullscreenIndex = null }
    )
    } // CompositionLocalProvider(LocalFullscreenSharedElement)
}

/** 小标题：类型 / 截图两段共用。 */
@Composable
private fun NewFeedbackSectionLabel(text: String) {
    Text(
        text = text,
        fontSize = 14.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = FontWeight.SemiBold
    )
}

/** 写新反馈顶栏：与列表 / 详情 / 消息页同一套 haze 贴顶写法。 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun NewFeedbackTopBar(
    hazeState: HazeState,
    hazeStyle: HazeBlurStyle,
    isContentUnderTopBar: Boolean,
    backEnabled: Boolean,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .hazeTopBar(
                state = hazeState,
                style = hazeStyle,
                blurRadius = 24.dp,
                isContentUnderTopBar = isContentUnderTopBar
            )
            // 顶栏覆盖内容，拦截空白区域点击，避免穿透到下面的输入框
            .clickable(enabled = false, onClick = {})
    ) {
        Spacer(modifier = Modifier.statusBarsPadding())
        TopAppBar(
            title = {
                Text(
                    text = stringResource(R.string.feedback_new),
                    fontWeight = FontWeight.ExtraBold
                )
            },
            navigationIcon = {
                IconButton(onClick = onBack, enabled = backEnabled) {
                    Icon(
                        Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = stringResource(R.string.content_desc_back)
                    )
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            windowInsets = WindowInsets(0, 0, 0, 0)
        )
    }
}

/**
 * 提交按钮。
 *
 * 上传是逐张走的，原先只有一个转圈 + 「上传截图 (2/5)」文字；进度条能一眼看出还剩多少。
 */
@Composable
private fun SubmitFeedbackButton(
    submitState: FeedbackViewModel.SubmitState,
    isSubmitting: Boolean,
    enabled: Boolean,
    onSubmit: () -> Unit
) {
    val haptics = rememberAppHaptics()
    val uploading = submitState as? FeedbackViewModel.SubmitState.Uploading
    Button(
        onClick = {
            // 本页的主操作；提交成功/失败那一记归输出侧的任务在 VM 接
            haptics.tap()
            onSubmit()
        },
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp),
        shape = RoundedCornerShape(16.dp),
        enabled = enabled
    ) {
        if (isSubmitting) {
            if (uploading != null && uploading.total > 0) {
                CircularProgressIndicator(
                    progress = { uploading.current.toFloat() / uploading.total },
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary
                )
            } else {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = if (uploading != null) {
                    stringResource(
                        R.string.feedback_uploading,
                        (uploading.current + 1).coerceAtMost(uploading.total),
                        uploading.total
                    )
                } else {
                    stringResource(R.string.feedback_submitting)
                },
                fontWeight = FontWeight.Bold
            )
        } else {
            Text(stringResource(R.string.feedback_submit), fontWeight = FontWeight.Bold)
        }
    }
}

/**
 * 截图行：支持添加、删除、点击查看大图、长按拖动排序（跟随手指 + 插入动画）。
 * 使用 sh.calvin.reorderable 库实现：被拖项跟随手指平移，其他项通过 animateItem 平滑插入。
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun ScreenshotRow(
    screenshots: List<Pair<ByteArray, String>>,
    enabled: Boolean,
    sharedKeyPrefix: String? = null,
    onAddClick: () -> Unit,
    onRemoveClick: (Int) -> Unit,
    onImageClick: (Int) -> Unit,
    onReorder: (Int, Int) -> Unit
) {
    val context = LocalContext.current
    val haptics = rememberAppHaptics()
    val lazyListState = rememberLazyListState()
    val reorderableState = rememberReorderableLazyListState(lazyListState) { from, to ->
        onReorder(from.index, to.index)
    }

    LazyRow(
        state = lazyListState,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        itemsIndexed(screenshots, key = { _, pair -> pair.first }) { index, (bytes, _) ->
            ReorderableItem(
                state = reorderableState,
                key = bytes,
                enabled = enabled && screenshots.size >= 2
            ) { isDragging ->
                Box(
                    // 长按触发拖动；库会自动通过 graphicsLayer 平移被拖项跟随手指。
                    // longPressDraggableHandle 是 ReorderableCollectionItemScope 内 Modifier 的扩展。
                    // 起手与落定成对发：库本身一记触感都不发（3.1.0 里没有 performHapticFeedback），
                    // 只发起手会让「抓起来有感、放下去没感」；换格中间不发，一趟拖过五张会连成一串。
                    Modifier
                        .size(80.dp)
                        .longPressDraggableHandle(
                            enabled = enabled && screenshots.size >= 2,
                            onDragStarted = { haptics.dragStart() },
                            onDragStopped = { haptics.gestureEnd() }
                        )
                        // 拖动时抬起阴影 + 轻微放大，强化"被抓住"反馈
                        .graphicsLayer {
                            scaleX = if (isDragging) 1.08f else 1f
                            scaleY = if (isDragging) 1.08f else 1f
                            shadowElevation = if (isDragging) 12f else 0f
                        }
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp))
                        .clip(RoundedCornerShape(10.dp))
                        // 点缩略图是看图预览，不震（用户规则：点击图片无触感）；
                        // 角标的删除 × 与末尾的加号是操作，各自保留触感
                        .hapticClickable(
                            semantic = null,
                            enabled = enabled
                        ) { onImageClick(index) },
                    contentAlignment = Alignment.Center
                ) {
                    AsyncImage(
                        model = remember(bytes) {
                            ImageRequest.Builder(context)
                                .data(bytes)
                                .crossfade(true)
                                .build()
                        },
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(80.dp)
                            // 与全屏端 "$sharedKeyPrefix-$page" 配对；caller-managed visibility
                            // 保证同一 key 同时只有一侧是 target
                            .zoomSharedSource(key = sharedKeyPrefix?.let { "$it-$index" })
                    )
                    // 右上角删除按钮：原先 20dp 且没有 contentDescription，
                    // 手指点不准、读屏也念不出。放到 36dp——再大就会盖住缩略图中心，
                    // 变成想点开预览反而删了图
                    if (enabled) {
                        Box(
                            Modifier
                                .align(Alignment.TopEnd)
                                .size(36.dp)
                                .hapticClickable(semantic = HapticSemantic.LIGHT_TAP) {
                                    onRemoveClick(index)
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Rounded.Close,
                                contentDescription = stringResource(R.string.cd_delete),
                                tint = Color.White,
                                modifier = Modifier
                                    .size(20.dp)
                                    .background(Color.Black.copy(alpha = 0.6f), CircleShape)
                                    .padding(3.dp)
                            )
                        }
                    }
                }
            }
        }
        if (screenshots.size < MAX_SCREENSHOTS && enabled) {
            item(key = "add") {
                val addLabel = stringResource(R.string.feedback_screenshots)
                Box(
                    Modifier
                        .size(80.dp)
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant,
                            RoundedCornerShape(10.dp)
                        )
                        .hapticClickable(semantic = HapticSemantic.LIGHT_TAP) { onAddClick() },
                    contentAlignment = Alignment.Center
                ) {
                    // 原先是个 "+" 字符，读屏念成加号、字形还跟着系统字体走
                    Icon(
                        imageVector = Icons.Rounded.AddPhotoAlternate,
                        contentDescription = addLabel,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }
    }
}

/** 类型胶囊：换成 M3 FilterChip，选中语义与触控尺寸由组件给，配色走共用的类型强调色。 */
@Composable
private fun FeedbackTypeChip(
    type: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val accent = feedbackTypeColor(type)
    val haptics = rememberAppHaptics()
    FilterChip(
        selected = selected,
        enabled = enabled,
        onClick = {
            // 四个类型互斥（selectedType 是单值赋值，不是集合加减），按单选给 SEGMENT_TICK
            haptics.segmentTick()
            onClick()
        },
        label = { Text(text = feedbackTypeLabel(type), fontSize = 13.sp, maxLines = 1) },
        leadingIcon = {
            Icon(
                imageVector = feedbackTypeIcon(type),
                contentDescription = null,
                modifier = Modifier.size(16.dp)
            )
        },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = accent.copy(alpha = 0.16f),
            selectedLabelColor = accent,
            selectedLeadingIconColor = accent
        ),
        shape = RoundedCornerShape(12.dp)
    )
}











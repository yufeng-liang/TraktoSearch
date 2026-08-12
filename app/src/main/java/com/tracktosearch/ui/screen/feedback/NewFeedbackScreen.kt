package com.tracktosearch.ui.screen.feedback

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.SentimentDissatisfied
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
import com.tracktosearch.ui.component.LocalAnimatedVisibilityScope
import com.tracktosearch.ui.component.LocalSharedTransitionEnabled
import com.tracktosearch.ui.component.LocalSharedTransitionScope
import com.tracktosearch.ui.component.ZoomableImageOverlay
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

private const val MAX_SCREENSHOTS = 5

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewFeedbackScreen(
    onBack: () -> Unit,
    onSuccess: () -> Unit,
    viewModel: FeedbackViewModel = hiltViewModel()
) {
    val submitState by viewModel.submitState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var selectedType by remember { mutableStateOf<String?>(null) }
    var content by remember { mutableStateOf("") }
    var screenshots by remember { mutableStateOf<List<Pair<ByteArray, String>>>(emptyList()) }

    // 截图全屏查看
    var fullscreenIndex by remember { mutableStateOf<Int?>(null) }

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
    LaunchedEffect(submitState) {
        if (submitState is FeedbackViewModel.SubmitState.Success) {
            onSuccess()
            viewModel.resetSubmitState()
        }
    }

    val isSubmitting = submitState is FeedbackViewModel.SubmitState.Uploading || submitState is FeedbackViewModel.SubmitState.Submitting

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.feedback_new), fontWeight = FontWeight.ExtraBold) },
                navigationIcon = {
                    IconButton(onClick = onBack, enabled = !isSubmitting) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = null)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 类型选择 — 四个 chip 宽度符合文字宽度，左对齐排列
            Text(stringResource(R.string.feedback_select_type), fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.SemiBold)
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.Start)
            ) {
                FeedbackTypeChip(
                    labelRes = R.string.feedback_type_feature,
                    color = Color(0xFF34D399),
                    icon = Icons.Rounded.Lightbulb,
                    selected = selectedType == "FEATURE"
                ) {
                    selectedType = if (selectedType == "FEATURE") null else "FEATURE"
                }
                FeedbackTypeChip(
                    labelRes = R.string.feedback_type_bug,
                    color = Color(0xFFFB7185),
                    icon = Icons.Rounded.BugReport,
                    selected = selectedType == "BUG"
                ) {
                    selectedType = if (selectedType == "BUG") null else "BUG"
                }
                FeedbackTypeChip(
                    labelRes = R.string.feedback_type_ux,
                    color = Color(0xFFFBBF24),
                    icon = Icons.Rounded.SentimentDissatisfied,
                    selected = selectedType == "UX"
                ) {
                    selectedType = if (selectedType == "UX") null else "UX"
                }
                FeedbackTypeChip(
                    labelRes = R.string.feedback_type_other,
                    color = Color(0xFF9CA3AF),
                    icon = Icons.Rounded.MoreHoriz,
                    selected = selectedType == "OTHER"
                ) {
                    selectedType = if (selectedType == "OTHER") null else "OTHER"
                }
            }

            // 正文
            OutlinedTextField(
                value = content,
                onValueChange = { if (it.length <= 2000) content = it },
                modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
                placeholder = { Text(stringResource(R.string.feedback_content_placeholder)) },
                label = { Text(stringResource(R.string.feedback_content_label)) },
                isError = content.isNotEmpty() && content.length < 5,
                supportingText = {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        if (content.isNotEmpty() && content.length < 5) {
                            Text(stringResource(R.string.feedback_content_too_short), color = MaterialTheme.colorScheme.error)
                        }
                        Text("${content.length}/2000", fontSize = 11.sp)
                    }
                },
                enabled = !isSubmitting
            )

            // 截图
            Text(stringResource(R.string.feedback_screenshots), fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            ScreenshotRow(
                screenshots = screenshots,
                enabled = !isSubmitting,
                sharedKeyPrefix = "fb-new",
                onAddClick = { pickImageLauncher.launch("image/*") },
                onRemoveClick = { index -> screenshots = screenshots.toMutableList().apply { removeAt(index) } },
                onImageClick = { index -> fullscreenIndex = index },
                onReorder = { from, to ->
                    screenshots = screenshots.toMutableList().apply {
                        add(to, removeAt(from))
                    }
                }
            )

            // 错误信息
            (submitState as? FeedbackViewModel.SubmitState.Error)?.let {
                Text(it.message, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
            }

            // 提交按钮
            Button(
                onClick = {
                    if (selectedType != null && content.length >= 5) {
                        viewModel.submit(
                            type = selectedType!!,
                            content = content.trim(),
                            screenshotBytes = screenshots.map { it.first },
                            screenshotMimeTypes = screenshots.map { it.second }
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = selectedType != null && content.length >= 5 && !isSubmitting
            ) {
                if (isSubmitting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(Modifier.width(8.dp))
                    val progressText = when (val s = submitState) {
                        is FeedbackViewModel.SubmitState.Uploading ->
                            stringResource(R.string.feedback_uploading, s.current + 1, s.total)
                        is FeedbackViewModel.SubmitState.Submitting ->
                            stringResource(R.string.feedback_submitting)
                        else -> stringResource(R.string.feedback_submitting)
                    }
                    Text(text = progressText, fontWeight = FontWeight.Bold)
                } else {
                    Text(
                        text = stringResource(R.string.feedback_submit),
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }

    // 截图全屏查看
    ZoomableImageOverlay(
        visible = fullscreenIndex != null && screenshots.isNotEmpty(),
        images = screenshots.map { it.first },
        initialIndex = fullscreenIndex?.coerceIn(0, screenshots.size - 1) ?: 0,
        sharedKeyPrefix = "fb-new",
        onDismiss = { fullscreenIndex = null }
    )
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
                    Modifier
                        .size(80.dp)
                        .longPressDraggableHandle(
                            enabled = enabled && screenshots.size >= 2
                        )
                        // 拖动时抬起阴影 + 轻微放大，强化"被抓住"反馈
                        .graphicsLayer {
                            scaleX = if (isDragging) 1.08f else 1f
                            scaleY = if (isDragging) 1.08f else 1f
                            shadowElevation = if (isDragging) 12f else 0f
                        }
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(enabled = enabled) { onImageClick(index) },
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
                            .then(
                                if (sharedKeyPrefix != null && LocalSharedTransitionScope.current != null && LocalAnimatedVisibilityScope.current != null && LocalSharedTransitionEnabled.current) {
                                    val scope = LocalSharedTransitionScope.current
                                    with(scope!!) {
                                        Modifier.sharedElement(
                                            rememberSharedContentState(key = "$sharedKeyPrefix-$index"),
                                            animatedVisibilityScope = LocalAnimatedVisibilityScope.current!!
                                        )
                                    }
                                } else Modifier
                            )
                    )
                    // 右上角删除按钮
                    if (enabled) {
                        Box(
                            Modifier
                                .align(Alignment.TopEnd)
                                .padding(2.dp)
                                .size(20.dp)
                                .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(10.dp))
                                .clickable { onRemoveClick(index) },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Rounded.Close,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                }
            }
        }
        if (screenshots.size < MAX_SCREENSHOTS && enabled) {
            item(key = "add") {
                Box(
                    Modifier
                        .size(80.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                        .clickable { onAddClick() },
                    contentAlignment = Alignment.Center
                ) {
                    Text("+", fontSize = 24.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun FeedbackTypeChip(
    labelRes: Int,
    color: Color,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        modifier = modifier.clickable { onClick() },
        shape = RoundedCornerShape(8.dp),
        color = if (selected) color.copy(alpha = 0.2f) else MaterialTheme.colorScheme.surface,
        tonalElevation = if (selected) 0.dp else 1.dp,
        shadowElevation = if (selected) 0.dp else 1.dp
    ) {
        Row(modifier = Modifier.padding(horizontal = 6.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(14.dp), tint = if (selected) color else MaterialTheme.colorScheme.onSurface)
            Text(text = stringResource(labelRes), fontSize = 12.sp, maxLines = 1, color = if (selected) color else MaterialTheme.colorScheme.onSurface)
        }
    }
}

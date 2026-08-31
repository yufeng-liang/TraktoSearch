package com.tracktosearch.ui.screen.detail

import androidx.compose.foundation.clickable
import com.tracktosearch.ui.theme.RatingGold
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tracktosearch.R
import com.tracktosearch.data.remote.trakt.dto.TraktComment
import com.tracktosearch.ui.component.NeumorphicFrostedSurface
import com.tracktosearch.ui.component.GlassScene
import com.tracktosearch.ui.component.GlassSurfaceRole
import com.tracktosearch.ui.component.isAppDarkTheme
import com.tracktosearch.ui.theme.NeumorphicBorderLight

// ==================== 单条评论 ====================

@Composable
private fun ownCommentSyncDescription(targets: Set<OwnCommentTarget>): String = when {
    OwnCommentTarget.TRAKT in targets && OwnCommentTarget.DOUBAN in targets ->
        stringResource(R.string.detail_own_comment_sync_trakt_douban)
    OwnCommentTarget.TRAKT in targets -> stringResource(R.string.detail_own_comment_sync_trakt)
    OwnCommentTarget.DOUBAN in targets -> stringResource(R.string.detail_own_comment_sync_douban)
    else -> stringResource(R.string.detail_own_comment_sync_unavailable)
}

/** 输入框提示词：这里写下的短评将同步至 xx */
@Composable
private fun ownCommentPlaceholder(targets: Set<OwnCommentTarget>): String = when {
    OwnCommentTarget.TRAKT in targets && OwnCommentTarget.DOUBAN in targets ->
        stringResource(R.string.detail_own_comment_placeholder_trakt_douban)
    OwnCommentTarget.TRAKT in targets -> stringResource(R.string.detail_own_comment_placeholder_trakt)
    OwnCommentTarget.DOUBAN in targets -> stringResource(R.string.detail_own_comment_placeholder_douban)
    else -> stringResource(R.string.detail_own_comment_placeholder_unavailable)
}

@Composable
internal fun OwnCommentComposer(
    initialComment: String,
    targets: Set<OwnCommentTarget>,
    isSaving: Boolean,
    isEditing: Boolean,
    onSubmit: (String) -> Unit,
    onCancelEdit: () -> Unit,
    scene: GlassScene = GlassScene()
) {
    var comment by remember(initialComment) { mutableStateOf(initialComment) }
    // 收起态：原先一进评论 Tab 就是一张带 3 行空输入框的大卡，占掉小半屏，
    // 而多数人是来看别人短评的。默认收成一行入口，点开才展开输入框；
    // 编辑既有短评（isEditing）或已经写了草稿时直接展开。
    var expanded by remember(initialComment, isEditing) {
        mutableStateOf(isEditing || initialComment.isNotBlank())
    }
    val isDark = isAppDarkTheme()
    CommentCardSurface(isDark = isDark, scene = scene) {
        if (!expanded) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { expanded = true }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Rounded.Edit,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = ownCommentPlaceholder(targets),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            return@CommentCardSurface
        }
        Column(modifier = Modifier.padding(16.dp)) {
            // 标题行：标题在左，发布按钮右对齐
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.detail_own_comment_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                if (isEditing) {
                    TextButton(onClick = onCancelEdit, enabled = !isSaving) {
                        Text(stringResource(R.string.detail_own_comment_cancel))
                    }
                }
                Button(
                    onClick = { onSubmit(comment) },
                    enabled = comment.isNotBlank() && !isSaving
                ) {
                    if (isSaving) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    } else {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.Send,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(R.string.detail_own_comment_publish))
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
            OutlinedTextField(
                value = comment,
                onValueChange = { if (it.length <= 350) comment = it },
                modifier = Modifier.fillMaxWidth(),
                // 同步说明作为输入框提示词
                placeholder = { Text(ownCommentPlaceholder(targets)) },
                textStyle = MaterialTheme.typography.bodyMedium,
                minLines = 3,
                maxLines = 5,
                enabled = !isSaving,
                shape = RoundedCornerShape(12.dp)
            )
        }
    }
}

/** 评论区三张卡（我的短评收起态/展开态、他人短评）共用的拟态玻璃底。
 * 原先这 14 行配置在三处逐字重复。 */
@Composable
private fun CommentCardSurface(
    isDark: Boolean,
    scene: GlassScene,
    content: @Composable () -> Unit
) {
    NeumorphicFrostedSurface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        isDark = isDark,
        shape = RoundedCornerShape(18.dp),
        elevation = 6.dp,
        blurRadius = 16.dp,
        shadowOffset = 5.dp,
        backgroundColor = if (isDark) Color.White.copy(alpha = 0.08f) else Color.White.copy(alpha = 0.65f),
        borderColor = if (isDark) Color.White.copy(alpha = 0.15f) else NeumorphicBorderLight.copy(alpha = 0.9f),
        darkShadowAlpha = if (isDark) 0.25f else 0.16f,
        lightShadowAlpha = if (isDark) 0.08f else 0.65f,
        hazeState = null,
        glassRole = GlassSurfaceRole.Card,
        scene = scene,
        content = { content() }
    )
}

@Composable
internal fun OwnCommentCard(
    comment: String,
    targets: Set<OwnCommentTarget>,
    retryTargets: Set<OwnCommentTarget>,
    isSaving: Boolean,
    onEdit: () -> Unit,
    onRetry: () -> Unit,
    scene: GlassScene = GlassScene()
) {
    val isDark = isAppDarkTheme()
    val retryTargetName = when {
        OwnCommentTarget.TRAKT in retryTargets && OwnCommentTarget.DOUBAN in retryTargets ->
            stringResource(R.string.detail_own_comment_targets_trakt_douban)
        OwnCommentTarget.TRAKT in retryTargets -> stringResource(R.string.detail_own_comment_target_trakt)
        else -> stringResource(R.string.detail_own_comment_target_douban)
    }
    CommentCardSurface(isDark = isDark, scene = scene) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.detail_own_comment_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = ownCommentSyncDescription(targets),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onEdit, enabled = !isSaving) {
                    Icon(
                        imageVector = Icons.Rounded.Edit,
                        contentDescription = stringResource(R.string.detail_own_comment_edit),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            ExpandableText(text = comment)
            if (retryTargets.isNotEmpty()) {
                TextButton(
                    onClick = onRetry,
                    enabled = !isSaving,
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(stringResource(R.string.detail_own_comment_retry, retryTargetName))
                }
            }
        }
    }
}

/** 评论卡片底部的文字动作（翻译 / 原文·译文切换）：无涟漪、labelSmall、主色。 */
@Composable
private fun CommentActionText(text: String, onClick: () -> Unit) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = onClick
        )
    )
}

@Composable
internal fun CommentItem(
    comment: TraktComment,
    translatedText: String?,
    onTranslate: (Int) -> Unit,
    isTranslating: Boolean,
    isThisTranslating: Boolean,
    scene: GlassScene = GlassScene()
) {
    var showOriginal by remember(comment.id) { mutableStateOf(false) }
    var spoilerRevealed by remember { mutableStateOf(false) }

    val displayText = if (showOriginal) comment.comment else (translatedText ?: comment.comment)
    val isDark = isAppDarkTheme()
    val sourceLabel = if (comment.source == DOUBAN_COMMENT_SOURCE) {
        stringResource(R.string.detail_comment_source_douban)
    } else {
        comment.source
    }
    val sourceDescription = stringResource(R.string.detail_comment_source, sourceLabel)

    CommentCardSurface(isDark = isDark, scene = scene) {
        Column(modifier = Modifier.padding(16.dp)) {
            // 身份行：用户名 —— 来源 · ★评分。
            // 原先这一行塞了四类东西（12sp 用户名、来源、评分、翻译按钮），用 Spacer
            // 手动拉开，用户名一长就把右端的翻译按钮挤出可视区。现在这行只留身份信息，
            // 用户名放大到 bodyMedium 并允许自己截断，来源与评分成组贴右。
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = comment.user.username,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                // 来源只显示平台名，读屏走完整的「（来源：xx）」，否则念出来是个孤立词
                Text(
                    text = sourceLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier.clearAndSetSemantics {
                        contentDescription = sourceDescription
                    }
                )
                if (comment.user_rating != null) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Icon(
                        Icons.Rounded.Star,
                        contentDescription = null,
                        modifier = Modifier.size(12.dp),
                        tint = RatingGold
                    )
                    Spacer(modifier = Modifier.width(2.dp))
                    Text(
                        text = String.format("%.0f", comment.user_rating),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 剧透遮罩
            if (comment.spoiler && !spoilerRevealed) {
                Surface(
                    onClick = { spoilerRevealed = true },
                    modifier = Modifier
                        .fillMaxWidth(),
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
                ) {
                    Text(
                        text = stringResource(R.string.detail_spoiler),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(8.dp),
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                // 正文与底部动作行。「翻译」「原文/译文」交给 ExpandableText 的 leadingAction，
                // 与右侧的「展开」同处一行：原先两者各占一行（翻译挂在身份行右端、原文切换
                // 又在正文下方另起一行右对齐），一条两行短评能排成四行高。
                val bottomAction: (@Composable () -> Unit)? = when {
                    translatedText != null -> {
                        {
                            CommentActionText(
                                text = if (showOriginal) {
                                    stringResource(R.string.detail_translated)
                                } else {
                                    stringResource(R.string.detail_original)
                                },
                                onClick = { showOriginal = !showOriginal }
                            )
                        }
                    }
                    isThisTranslating -> {
                        {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(12.dp),
                                    strokeWidth = 1.5.dp
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = stringResource(R.string.detail_translating),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    // 豆瓣短评本身是中文，不提供翻译入口
                    comment.source != DOUBAN_COMMENT_SOURCE -> {
                        {
                            CommentActionText(
                                text = stringResource(R.string.detail_translate),
                                onClick = { onTranslate(comment.id) }
                            )
                        }
                    }
                    else -> null
                }
                ExpandableText(text = displayText, leadingAction = bottomAction)
            }
        }
    }
}

package com.tracktosearch.ui.screen.detail

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import com.tracktosearch.R
import com.tracktosearch.data.remote.tmdb.TmdbImageUrls
import com.tracktosearch.data.remote.tmdb.dto.TmdbCast
import com.tracktosearch.data.remote.tmdb.dto.TmdbCrew
import com.tracktosearch.data.util.PersonAvatarColorStore
import com.tracktosearch.ui.component.LocalAnimatedVisibilityScope
import com.tracktosearch.ui.component.PosterColorExtractorProvider
import com.tracktosearch.ui.component.appSharedBounds
import com.tracktosearch.ui.component.personAvatarSharedKey
import com.tracktosearch.ui.haptic.HapticSemantic
import com.tracktosearch.ui.haptic.hapticClickable
import com.tracktosearch.ui.haptic.rememberAppHaptics
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.launch

// ==================== 演职员 ====================

@Composable
internal fun CrewSection(
    cast: List<TmdbCast>,
    crew: List<TmdbCrew>,
    onShowAll: () -> Unit = {},
    onPersonClick: (personId: Int, personName: String, profileUrl: String?, avatarColor: Color?) -> Unit = { _, _, _, _ -> }
) {
    val directors = crew.filter { it.job == "Director" }
    val writers = crew.filter { it.job == "Writer" || it.job == "Screenplay" }
    val producers = crew.filter { it.job == "Producer" }

    // 已提取的 avatarColor 缓存（personId -> Color），onSuccess 回调只触发一次
    val avatarColors = remember { mutableMapOf<Int, Color>() }

    fun onAvatarColorExtracted(personId: Int, color: Color) {
        if (avatarColors[personId] != color) {
            avatarColors[personId] = color
        }
    }

    Column {
        // 标题行：与详情页其他栏目共用同一种画法（原先这里是自制 Row + 裸「全部」文字，
        // 没有 › 指示符，也和 SectionHeader 的字号/间距对不上）。
        // 「全部」那一记 LIGHT_TAP 现在由共享的 SectionHeader 自己发，这里不用再挂
        DetailSectionHeader(
            title = stringResource(R.string.detail_cast_crew),
            actionText = stringResource(R.string.detail_cast_all),
            onActionClick = onShowAll
        )

        // 横向滚动：导演→演员→编剧→制片人
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(end = 16.dp)
        ) {
            // 导演
            itemsIndexed(directors, key = { index, person -> "director_${person.id}_$index" }) { _, person ->
                val profileUrl = person.profile_path?.let { TmdbImageUrls.build(it) }
                CastCard(
                    name = person.name,
                    role = stringResource(R.string.detail_director_tag),
                    profileUrl = profileUrl,
                    personId = person.id,
                    onClick = { onPersonClick(person.id, person.name, profileUrl, avatarColors[person.id]) },
                    onAvatarColorExtracted = { color: Color -> onAvatarColorExtracted(person.id, color) }
                )
            }
            // 演员
            itemsIndexed(cast, key = { index, person -> "cast_${person.id}_${person.character}_$index" }) { _, person ->
                val profileUrl = person.profile_path?.let { TmdbImageUrls.build(it) }
                CastCard(
                    name = person.name,
                    role = if (person.character.isNotEmpty()) stringResource(R.string.detail_cast_as, person.character) else stringResource(R.string.detail_actor),
                    profileUrl = profileUrl,
                    personId = person.id,
                    onClick = { onPersonClick(person.id, person.name, profileUrl, avatarColors[person.id]) },
                    onAvatarColorExtracted = { color: Color -> onAvatarColorExtracted(person.id, color) }
                )
            }
            // 编剧
            itemsIndexed(writers, key = { index, person -> "writer_${person.id}_$index" }) { _, person ->
                val profileUrl = person.profile_path?.let { TmdbImageUrls.build(it) }
                CastCard(
                    name = person.name,
                    role = stringResource(R.string.detail_writer_tag),
                    profileUrl = profileUrl,
                    personId = person.id,
                    onClick = { onPersonClick(person.id, person.name, profileUrl, avatarColors[person.id]) },
                    onAvatarColorExtracted = { color: Color -> onAvatarColorExtracted(person.id, color) }
                )
            }
            // 制片人
            itemsIndexed(producers, key = { index, person -> "producer_${person.id}_$index" }) { _, person ->
                val profileUrl = person.profile_path?.let { TmdbImageUrls.build(it) }
                CastCard(
                    name = person.name,
                    role = stringResource(R.string.detail_producer_tag),
                    profileUrl = profileUrl,
                    personId = person.id,
                    onClick = { onPersonClick(person.id, person.name, profileUrl, avatarColors[person.id]) },
                    onAvatarColorExtracted = { color: Color -> onAvatarColorExtracted(person.id, color) }
                )
            }
        }
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun CastCard(
    name: String,
    role: String,
    profileUrl: String?,
    personId: Int,
    onClick: () -> Unit = {},
    onAvatarColorExtracted: ((Color) -> Unit)? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val animatedVisibilityScope = LocalAnimatedVisibilityScope.current
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.96f else 1f,
        label = "cast_card_scale"
    )
    val posterColorExtractor = remember {
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            PosterColorExtractorProvider::class.java
        ).posterColorExtractor()
    }
    // 已提取的主色缓存，避免重复写
    var extractedColor by remember { mutableStateOf<Color?>(null) }
    Column(
        modifier = Modifier
            .width(68.dp)
            .scale(scale)
            .hapticClickable(
                interactionSource = interactionSource,
                indication = null,
                semantic = HapticSemantic.LIGHT_TAP,
                onClick = onClick
            ),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Surface(
            shape = RoundedCornerShape(6.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier
                .width(68.dp)
                .height(95.dp)
        ) {
            if (profileUrl != null) {
                val imageModifier = Modifier
                    .appSharedBounds(
                        key = personAvatarSharedKey(personId),
                        animatedVisibilityScope = animatedVisibilityScope,
                    )
                    .fillMaxSize()
                SubcomposeAsyncImage(
                    model = remember(profileUrl) {
                        ImageRequest.Builder(context)
                            .data(profileUrl)
                            .size(200)
                            .crossfade(false)
                            .listener(
                                onSuccess = { _, result ->
                                    val bitmap = result.drawable.toBitmap()
                                    scope.launch {
                                        posterColorExtractor.extractDominantColor(profileUrl, bitmap)
                                            .takeIf { it != 0L }
                                            ?.let { argb ->
                                                // 写入进程内缓存，供 PersonScreen 首帧读取避免白色闪烁
                                                PersonAvatarColorStore.put(personId, argb)
                                                val color = Color(argb)
                                                if (extractedColor != color) {
                                                    extractedColor = color
                                                    onAvatarColorExtracted?.invoke(color)
                                                }
                                            }
                                    }
                                }
                            )
                            .build()
                    },
                    contentDescription = name,
                    contentScale = ContentScale.Crop,
                    modifier = imageModifier,
                    loading = {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp
                            )
                        }
                    },
                    error = {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                            Icon(
                                Icons.Rounded.Person,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                )
            } else {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Icon(
                        Icons.Rounded.Person,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = name,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, textAlign = TextAlign.Center),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            text = role,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, textAlign = TextAlign.Center),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

// ==================== 全部演职员弹窗 ====================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FullCastCrewSheet(
    cast: List<TmdbCast>,
    crew: List<TmdbCrew>,
    onDismiss: () -> Unit,
    onPersonClick: (personId: Int, personName: String, profileUrl: String?, avatarColor: Color?) -> Unit = { _, _, _, _ -> }
) {
    val directors = crew.filter { it.job == "Director" }
    val writers = crew.filter { it.job == "Writer" || it.job == "Screenplay" }
    val producers = crew.filter { it.job == "Producer" }

    val avatarColors = remember { mutableMapOf<Int, Color>() }
    val listState = rememberLazyListState()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceVariant
    ) {
        // ModalBottomSheet 的内容是独立 subcomposition（有自己的宿主 View），单独取一份
        val sheetHaptics = rememberAppHaptics()
        Column(modifier = Modifier.fillMaxWidth()) {
            // 标题栏
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.detail_cast_all_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                TextButton(onClick = { sheetHaptics.lightTap(); onDismiss() }) {
                    Text(stringResource(R.string.detail_cast_close))
                }
            }

            // 分组列表
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxHeight(0.8f),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // 导演
                if (directors.isNotEmpty()) {
                    item { FullCastGroupHeader("${stringResource(R.string.detail_director_tag)} (${directors.size})") }
                    itemsIndexed(directors, key = { index, person -> "director_${person.id}_$index" }) { _, person ->
                        val profileUrl = person.profile_path?.let { TmdbImageUrls.build(it) }
                        FullCastItem(
                            name = person.name,
                            originalName = person.original_name,
                            role = person.job,
                            profileUrl = profileUrl,
                            personId = person.id,
                            onClick = { onPersonClick(person.id, person.name, profileUrl, avatarColors[person.id]) },
                            onAvatarColorExtracted = { color: Color -> avatarColors[person.id] = color }
                        )
                    }
                }
                // 演员
                if (cast.isNotEmpty()) {
                    item { FullCastGroupHeader("${stringResource(R.string.detail_actor)} (${cast.size})") }
                    itemsIndexed(cast, key = { index, person -> "cast_${person.id}_${person.character}_$index" }) { _, person ->
                        val profileUrl = person.profile_path?.let { TmdbImageUrls.build(it) }
                        FullCastItem(
                            name = person.name,
                            originalName = person.original_name,
                            role = if (person.character.isNotEmpty()) stringResource(R.string.detail_cast_as, person.character) else "",
                            profileUrl = profileUrl,
                            personId = person.id,
                            onClick = { onPersonClick(person.id, person.name, profileUrl, avatarColors[person.id]) },
                            onAvatarColorExtracted = { color: Color -> avatarColors[person.id] = color }
                        )
                    }
                }
                // 编剧
                if (writers.isNotEmpty()) {
                    item { FullCastGroupHeader("${stringResource(R.string.detail_writer_tag)} (${writers.size})") }
                    itemsIndexed(writers, key = { index, person -> "writer_${person.id}_$index" }) { _, person ->
                        val profileUrl = person.profile_path?.let { TmdbImageUrls.build(it) }
                        FullCastItem(
                            name = person.name,
                            originalName = person.original_name,
                            role = person.job,
                            profileUrl = profileUrl,
                            personId = person.id,
                            onClick = { onPersonClick(person.id, person.name, profileUrl, avatarColors[person.id]) },
                            onAvatarColorExtracted = { color: Color -> avatarColors[person.id] = color }
                        )
                    }
                }
                // 制片人
                if (producers.isNotEmpty()) {
                    item { FullCastGroupHeader("${stringResource(R.string.detail_producer_tag)} (${producers.size})") }
                    itemsIndexed(producers, key = { index, person -> "producer_${person.id}_$index" }) { _, person ->
                        val profileUrl = person.profile_path?.let { TmdbImageUrls.build(it) }
                        FullCastItem(
                            name = person.name,
                            originalName = person.original_name,
                            role = person.job,
                            profileUrl = profileUrl,
                            personId = person.id,
                            onClick = { onPersonClick(person.id, person.name, profileUrl, avatarColors[person.id]) },
                            onAvatarColorExtracted = { color: Color -> avatarColors[person.id] = color }
                        )
                    }
                }
            }
        }
    }
}

/**
 * 全部演职员 Sheet 内的分组标题（导演 / 演员 / 编剧 / 制片人）。
 *
 * 原名就叫 SectionHeader，与 ui/component 里的共用组件同名，在本包内把后者遮蔽掉了；
 * 这里在 Sheet 的纯色底上按分组用主题主色，与详情页栏目标题不是一回事，故改名区分。
 */
@Composable
internal fun FullCastGroupHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
    )
}

@Composable
internal fun FullCastItem(
    name: String,
    originalName: String,
    role: String,
    profileUrl: String?,
    personId: Int,
    onClick: () -> Unit = {},
    onAvatarColorExtracted: ((Color) -> Unit)? = null
) {
    val context = LocalContext.current
    var extractedColor by remember { mutableStateOf<Color?>(null) }
    val scope = rememberCoroutineScope()
    // 头像 Surface 与整行都可点、且都走同一个 onClick：内层 Surface 会消费点击，两者不会同时发
    val haptics = rememberAppHaptics()
    val posterColorExtractor = remember {
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            PosterColorExtractorProvider::class.java
        ).posterColorExtractor()
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .hapticClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                semantic = HapticSemantic.LIGHT_TAP,
                onClick = onClick
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            onClick = { haptics.lightTap(); onClick() },
            shape = RoundedCornerShape(6.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.size(width = 72.dp, height = 100.dp)
        ) {
            if (profileUrl != null) {
                SubcomposeAsyncImage(
                    model = remember(profileUrl) {
                        ImageRequest.Builder(context)
                            .data(profileUrl)
                            .size(200)
                            .crossfade(false)
                            .listener(
                                onSuccess = { _, result ->
                                    val bitmap = result.drawable.toBitmap()
                                    scope.launch {
                                        posterColorExtractor.extractDominantColor(profileUrl, bitmap)
                                            .takeIf { it != 0L }
                                            ?.let { argb ->
                                                // 写入进程内缓存，供 PersonScreen 首帧读取避免白色闪烁
                                                PersonAvatarColorStore.put(personId, argb)
                                                val color = Color(argb)
                                                if (extractedColor != color) {
                                                    extractedColor = color
                                                    onAvatarColorExtracted?.invoke(color)
                                                }
                                            }
                                    }
                                }
                            )
                            .build()
                    },
                    contentDescription = name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                    loading = {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        }
                    },
                    error = {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                            Icon(Icons.Rounded.Person, contentDescription = null, modifier = Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                )
            } else {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Icon(Icons.Rounded.Person, contentDescription = null, modifier = Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
            if (originalName.isNotEmpty() && originalName != name) {
                Text(
                    text = originalName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (role.isNotEmpty()) {
                Text(
                    text = role,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

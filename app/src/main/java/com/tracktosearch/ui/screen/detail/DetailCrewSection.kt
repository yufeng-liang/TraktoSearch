package com.tracktosearch.ui.screen.detail

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import com.tracktosearch.R
import com.tracktosearch.data.remote.tmdb.dto.TmdbCast
import com.tracktosearch.data.remote.tmdb.dto.TmdbCrew
import com.tracktosearch.ui.component.LocalAnimatedVisibilityScope
import com.tracktosearch.ui.component.LocalSharedTransitionScope

// ==================== 演职员 ====================

@Composable
internal fun CrewSection(
    cast: List<TmdbCast>,
    crew: List<TmdbCrew>,
    onShowAll: () -> Unit = {},
    onPersonClick: (personId: Int, personName: String, profileUrl: String?) -> Unit = { _, _, _ -> }
) {
    val directors = crew.filter { it.job == "Director" }
    val writers = crew.filter { it.job == "Writer" || it.job == "Screenplay" }
    val producers = crew.filter { it.job == "Producer" }

    Column {
        // 标题行
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.detail_cast_crew),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = stringResource(R.string.detail_cast_all),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable(onClick = onShowAll)
            )
        }

        // 横向滚动：导演→演员→编剧→制片人
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(end = 16.dp)
        ) {
            // 导演
            itemsIndexed(directors, key = { index, person -> "director_${person.id}_$index" }) { _, person ->
                val profileUrl = person.profile_path?.let { "https://image.tmdb.org/t/p/w500$it" }
                CastCard(
                    name = person.name,
                    role = stringResource(R.string.detail_director_tag),
                    profileUrl = profileUrl,
                    personId = person.id,
                    onClick = { onPersonClick(person.id, person.name, profileUrl) }
                )
            }
            // 演员
            itemsIndexed(cast, key = { index, person -> "cast_${person.id}_${person.character}_$index" }) { _, person ->
                val profileUrl = person.profile_path?.let { "https://image.tmdb.org/t/p/w500$it" }
                CastCard(
                    name = person.name,
                    role = if (person.character.isNotEmpty()) stringResource(R.string.detail_cast_as, person.character) else stringResource(R.string.detail_actor),
                    profileUrl = profileUrl,
                    personId = person.id,
                    onClick = { onPersonClick(person.id, person.name, profileUrl) }
                )
            }
            // 编剧
            itemsIndexed(writers, key = { index, person -> "writer_${person.id}_$index" }) { _, person ->
                val profileUrl = person.profile_path?.let { "https://image.tmdb.org/t/p/w500$it" }
                CastCard(
                    name = person.name,
                    role = stringResource(R.string.detail_writer_tag),
                    profileUrl = profileUrl,
                    personId = person.id,
                    onClick = { onPersonClick(person.id, person.name, profileUrl) }
                )
            }
            // 制片人
            itemsIndexed(producers, key = { index, person -> "producer_${person.id}_$index" }) { _, person ->
                val profileUrl = person.profile_path?.let { "https://image.tmdb.org/t/p/w500$it" }
                CastCard(
                    name = person.name,
                    role = stringResource(R.string.detail_producer_tag),
                    profileUrl = profileUrl,
                    personId = person.id,
                    onClick = { onPersonClick(person.id, person.name, profileUrl) }
                )
            }
        }
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun CastCard(name: String, role: String, profileUrl: String?, personId: Int, onClick: () -> Unit = {}) {
    val context = LocalContext.current
    val sharedTransitionScope = LocalSharedTransitionScope.current
    val animatedVisibilityScope = LocalAnimatedVisibilityScope.current
    Column(
        modifier = Modifier
            .width(68.dp)
            .clickable(onClick = onClick),
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
                val imageModifier = if (sharedTransitionScope != null && animatedVisibilityScope != null) {
                    with(sharedTransitionScope) {
                        Modifier
                            .sharedElement(
                                rememberSharedContentState(key = "person-avatar-$personId"),
                                animatedVisibilityScope = animatedVisibilityScope
                            )
                            .fillMaxSize()
                    }
                } else {
                    Modifier.fillMaxSize()
                }
                SubcomposeAsyncImage(
                    model = remember(profileUrl) {
                        ImageRequest.Builder(context)
                            .data(profileUrl)
                            .size(200)
                            .crossfade(false)
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
                                Icons.Filled.Person,
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
                        Icons.Filled.Person,
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
    onPersonClick: (personId: Int, personName: String, profileUrl: String?) -> Unit = { _, _, _ -> }
) {
    val directors = crew.filter { it.job == "Director" }
    val writers = crew.filter { it.job == "Writer" || it.job == "Screenplay" }
    val producers = crew.filter { it.job == "Producer" }

    // 延迟导航：先关闭弹窗，再延迟导航
    var pendingPersonClick by remember { mutableStateOf<Triple<Int, String, String?>?>(null) }
    LaunchedEffect(pendingPersonClick) {
        pendingPersonClick?.let { (id, name, url) ->
            kotlinx.coroutines.delay(300)
            onPersonClick(id, name, url)
            pendingPersonClick = null
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceVariant
    ) {
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
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.detail_cast_close))
                }
            }

            // 分组列表
            LazyColumn(
                modifier = Modifier.fillMaxHeight(0.8f),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // 导演
                if (directors.isNotEmpty()) {
                    item { SectionHeader("${stringResource(R.string.detail_director_tag)} (${directors.size})") }
                    itemsIndexed(directors, key = { index, person -> "director_${person.id}_$index" }) { _, person ->
                        val profileUrl = person.profile_path?.let { "https://image.tmdb.org/t/p/w500$it" }
                        FullCastItem(
                            name = person.name,
                            originalName = person.original_name,
                            role = person.job,
                            profileUrl = profileUrl,
                            personId = person.id,
                            onClick = { onDismiss(); pendingPersonClick = Triple(person.id, person.name, profileUrl) }
                        )
                    }
                }
                // 演员
                if (cast.isNotEmpty()) {
                    item { SectionHeader("${stringResource(R.string.detail_actor)} (${cast.size})") }
                    itemsIndexed(cast, key = { index, person -> "cast_${person.id}_${person.character}_$index" }) { _, person ->
                        val profileUrl = person.profile_path?.let { "https://image.tmdb.org/t/p/w500$it" }
                        FullCastItem(
                            name = person.name,
                            originalName = person.original_name,
                            role = if (person.character.isNotEmpty()) stringResource(R.string.detail_cast_as, person.character) else "",
                            profileUrl = profileUrl,
                            personId = person.id,
                            onClick = { onDismiss(); pendingPersonClick = Triple(person.id, person.name, profileUrl) }
                        )
                    }
                }
                // 编剧
                if (writers.isNotEmpty()) {
                    item { SectionHeader("${stringResource(R.string.detail_writer_tag)} (${writers.size})") }
                    itemsIndexed(writers, key = { index, person -> "writer_${person.id}_$index" }) { _, person ->
                        val profileUrl = person.profile_path?.let { "https://image.tmdb.org/t/p/w500$it" }
                        FullCastItem(
                            name = person.name,
                            originalName = person.original_name,
                            role = person.job,
                            profileUrl = profileUrl,
                            personId = person.id,
                            onClick = { onDismiss(); pendingPersonClick = Triple(person.id, person.name, profileUrl) }
                        )
                    }
                }
                // 制片人
                if (producers.isNotEmpty()) {
                    item { SectionHeader("${stringResource(R.string.detail_producer_tag)} (${producers.size})") }
                    itemsIndexed(producers, key = { index, person -> "producer_${person.id}_$index" }) { _, person ->
                        val profileUrl = person.profile_path?.let { "https://image.tmdb.org/t/p/w500$it" }
                        FullCastItem(
                            name = person.name,
                            originalName = person.original_name,
                            role = person.job,
                            profileUrl = profileUrl,
                            personId = person.id,
                            onClick = { onDismiss(); pendingPersonClick = Triple(person.id, person.name, profileUrl) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
    )
}

@Composable
internal fun FullCastItem(name: String, originalName: String, role: String, profileUrl: String?, personId: Int, onClick: () -> Unit = {}) {
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
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
                            Icon(Icons.Filled.Person, contentDescription = null, modifier = Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                )
            } else {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Icon(Icons.Filled.Person, contentDescription = null, modifier = Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
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

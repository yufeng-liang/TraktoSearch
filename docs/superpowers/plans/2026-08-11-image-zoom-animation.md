# 全 App 图片缩放动画实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** App 内全部 5 处「点击缩略图看大图」场景获得 Telegram 风格缩放过渡动画（小图→大图缩放进入，大图→小图缩放退出）。

**Architecture:** 新建通用 `ZoomableImageOverlay` 组件（AnimatedVisibility 包裹全屏图片查看，内部用 `sharedElement` 做缩放转场）。各场景缩略图源头加 `sharedElement`/`sharedBounds`，与全屏端按唯一 key 配对。沿用项目已有 `SharedTransitionLayout`（AppNavigation 顶层）与 `LocalSharedTransitionScope`/`LocalAnimatedVisibilityScope`/`LocalSharedTransitionEnabled` 三个 CompositionLocal。所有依赖 Dialog 的 overlay（Dialog 独立 window 丢失 composition locals，无法配对 sharedBounds）改为内联在宿主组合内。

**Tech Stack:** Jetpack Compose SharedTransitionScope（`sharedElement`/`sharedBounds`）、AnimatedVisibility、HorizontalPager、net.engawapg zoomable、Coil AsyncImage。

## Global Constraints

- 所有 sharedBounds/sharedElement 使用点必须判空 `LocalSharedTransitionEnabled.current`，关闭时退化为无动画弹窗（沿用 [DetailHeaderContent.kt](app/src/main/java/com/tracktosearch/ui/screen/detail/DetailHeaderContent.kt) 现有模式）
- 每场景共享 key 必须唯一，禁止跨场景/跨源重复（详见各任务）
- 代码注释用中文；Git commit 用 Conventional Commits 中文格式
- 依赖已存在，无需改 `libs.versions.toml`
- 按项目记忆：不每次改动都构建；本计划在 Task 3 与 Task 7 各构建一次 `assembleDebug`
- Dialog 内元素无法参与共享转场：详情截图、人物图全屏 overlay 必须去 Dialog 化

---

## 文件结构

**新建：**
- `app/src/main/java/com/tracktosearch/ui/component/ZoomableImageOverlay.kt` — 通用全屏图片查看组件（黑底 + 横滑翻页 + 双击缩放 + 可选保存 + 共享转场）

**修改：**
- `app/src/main/java/com/tracktosearch/ui/screen/detail/DetailPosterOverlay.kt` — `PosterFullscreenOverlay` 加 `visible`/`sharedKeyPrefix` 参数，内部改 AnimatedVisibility + sharedBounds
- `app/src/main/java/com/tracktosearch/ui/screen/detail/DetailHeaderContent.kt` — 海报 AsyncImage 外包一层 sharedBounds Box
- `app/src/main/java/com/tracktosearch/ui/screen/detail/DetailVideosImages.kt` — `BackdropPagerOverlay` 改委托 `ZoomableImageOverlay`；`BackdropCard`/`VideosAndImagesSection` 加 sharedElement
- `app/src/main/java/com/tracktosearch/ui/screen/detail/DetailScreen.kt` — 截图 overlay 去 Dialog；海报/截图 overlay 传 key
- `app/src/main/java/com/tracktosearch/ui/screen/person/PersonImageOverlay.kt` — `PersonImagePagerOverlay` 改委托；`AllPersonImagesSheet`(ModalBottomSheet) 改内联 `AllPersonImagesPanel`
- `app/src/main/java/com/tracktosearch/ui/screen/person/PersonScreen.kt` — 行/网格缩略图加 sharedElement；overlay 传 key
- `app/src/main/java/com/tracktosearch/ui/screen/feedback/NewFeedbackScreen.kt` — overlay 换组件；`ScreenshotRow` 加 sharedElement
- `app/src/main/java/com/tracktosearch/ui/screen/feedback/FeedbackDetailScreen.kt` — 两处 overlay 换组件；`OriginalFeedbackCard`/`ConversationBubble`/`ReplyBar` 加 sharedElement
- `app/src/main/java/com/tracktosearch/ui/screen/douban/DoubanItemDetailScreen.kt` — 海报源加 sharedBounds；overlay 传 key

**删除：**
- `app/src/main/java/com/tracktosearch/ui/screen/feedback/ScreenshotFullscreenOverlay.kt` — 被 ZoomableImageOverlay 取代

---

### Task 1: 通用组件 ZoomableImageOverlay

**Files:**
- Create: `app/src/main/java/com/tracktosearch/ui/component/ZoomableImageOverlay.kt`

**Interfaces:**
- Produces: `ZoomableImageOverlay(visible: Boolean, images: List<Any>, initialIndex: Int, sharedKeyPrefix: String?, onDismiss: () -> Unit, onSave: ((Int) -> Unit)? = null, isSavedAt: (Int) -> Boolean = { false })`
  - `visible`：AnimatedVisibility 开关；`false` 时内容在 exit 动画后移出组合
  - `images`：URL 字符串或 ByteArray 混合列表
  - `sharedKeyPrefix`：非 null 且共享转场开启时，每页图片用 `"$sharedKeyPrefix-$page"` 作 sharedElement key
  - `onSave`/`isSavedAt`：可选保存按钮

- [ ] **Step 1: 创建组件文件**

```kotlin
package com.tracktosearch.ui.component

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.tracktosearch.R
import kotlinx.coroutines.launch
import net.engawapg.lib.zoomable.rememberZoomState
import net.engawapg.lib.zoomable.zoomable

/**
 * 通用全屏图片查看 overlay：黑底 + 横滑翻页 + 双击/双指缩放 + 可选保存。
 * 用 AnimatedVisibility 包裹，`sharedKeyPrefix` 非空且共享转场开启时，
 * 每页图片以 `$sharedKeyPrefix-$page` 作为 sharedElement key，
 * 与缩略图端相同 key 配对，实现 Telegram 风格的小图→大图缩放过渡。
 * 注意：本组件必须一直处于组合中（用 `visible` 控制显隐），不能包在 `if` 里。
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun ZoomableImageOverlay(
    visible: Boolean,
    images: List<Any>,
    initialIndex: Int,
    sharedKeyPrefix: String?,
    onDismiss: () -> Unit,
    onSave: ((Int) -> Unit)? = null,
    isSavedAt: (Int) -> Boolean = { false },
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val safeInitial = initialIndex.coerceIn(0, (images.size - 1).coerceAtLeast(0))
    val pagerState = rememberPagerState(initialPage = safeInitial, pageCount = { images.size })
    val zoomState = rememberZoomState()
    val closeDesc = stringResource(R.string.detail_close)

    // 切页时重置缩放
    LaunchedEffect(pagerState.currentPage) { zoomState.reset() }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(animationSpec = tween(200)),
        exit = fadeOut(animationSpec = tween(200))
    ) {
        val sharedTransitionScope = LocalSharedTransitionScope.current
        val sharedEnabled = LocalSharedTransitionEnabled.current
        val animatedVisibilityScope = this

        BackHandler(enabled = true) {
            if (zoomState.scale > 1f) scope.launch { zoomState.changeScale(1f, Offset.Zero) }
            else onDismiss()
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.92f))
                .statusBarsPadding()
        ) {
            // 图片区：点背景退出
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {
                            if (zoomState.scale > 1f) scope.launch { zoomState.changeScale(1f, Offset.Zero) }
                            else onDismiss()
                        }
                    ),
                contentAlignment = Alignment.Center
            ) {
                HorizontalPager(
                    state = pagerState,
                    userScrollEnabled = zoomState.scale <= 1f,
                    modifier = Modifier.fillMaxSize()
                ) { page ->
                    val url = images.getOrNull(page) ?: return@HorizontalPager
                    val sharedModifier = if (sharedTransitionScope != null && sharedEnabled && sharedKeyPrefix != null) {
                        with(sharedTransitionScope) {
                            Modifier.sharedElement(
                                rememberSharedContentState(key = "$sharedKeyPrefix-$page"),
                                animatedVisibilityScope = animatedVisibilityScope
                            )
                        }
                    } else Modifier
                    AsyncImage(
                        model = remember(url) {
                            ImageRequest.Builder(context)
                                .data(url)
                                .crossfade(false)
                                .size(1080)
                                .build()
                        },
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .then(sharedModifier)
                            .pointerInput(zoomState) {
                                detectTapGestures(
                                    onDoubleTap = { tapOffset ->
                                        if (zoomState.scale > 1f) {
                                            scope.launch { zoomState.changeScale(1f, Offset.Zero) }
                                        } else {
                                            scope.launch { zoomState.changeScale(2.5f, tapOffset) }
                                        }
                                    }
                                )
                            }
                            .zoomable(zoomState)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = {} // 拦截点击，不触发外层 dismiss
                            )
                    )
                }
            }

            // 顶部操作栏
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .padding(horizontal = 8.dp, vertical = 8.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {}
                    ),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onDismiss) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .background(Color.Black.copy(alpha = 0.4f), RoundedCornerShape(20.dp))
                            .border(1.dp, Color.White.copy(alpha = 0.35f), RoundedCornerShape(20.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Rounded.Close,
                            contentDescription = closeDesc,
                            tint = Color.White,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }

                // 页码（多图时显示）
                if (images.size > 1) {
                    Text(
                        text = "${pagerState.currentPage + 1} / ${images.size}",
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier
                            .background(Color.Black.copy(alpha = 0.4f), RoundedCornerShape(16.dp))
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }

                // 保存按钮（可选）
                if (onSave != null) {
                    val idx = pagerState.currentPage
                    val saved = isSavedAt(idx)
                    IconButton(onClick = { onSave(idx) }) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .background(Color.Black.copy(alpha = 0.4f), RoundedCornerShape(20.dp))
                                .border(1.dp, Color.White.copy(alpha = 0.35f), RoundedCornerShape(20.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                if (saved) Icons.Rounded.Check else Icons.Rounded.Download,
                                contentDescription = null,
                                tint = if (saved) Color(0xFF4CAF50) else Color.White,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
```

- [ ] **Step 2: 构建验证组件本身可编译**

Run: `./gradlew :app:compileDebugKotlin`
Expected: 编译通过（组件未接线，无运行时验证）

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/tracktosearch/ui/component/ZoomableImageOverlay.kt
git commit -m "feat(ui): 新增通用全屏图片缩放组件"
```

---

### Task 2: 详情页海报缩放动画

**Files:**
- Modify: `app/src/main/java/com/tracktosearch/ui/screen/detail/DetailPosterOverlay.kt`
- Modify: `app/src/main/java/com/tracktosearch/ui/screen/detail/DetailHeaderContent.kt:144-215`
- Modify: `app/src/main/java/com/tracktosearch/ui/screen/detail/DetailScreen.kt:874-882`

**Interfaces:**
- Consumes: `LocalSharedTransitionScope`/`LocalSharedTransitionEnabled`（Task 1 组件同款）
- Produces: `PosterFullscreenOverlay(visible: Boolean, posterUrl: String, title: String, sharedKeyPrefix: String?, onDismiss: () -> Unit)` — 新签名，内部 AnimatedVisibility + sharedBounds
- Produces: `DetailHeaderContent` 海报源：`Box` 包 `AsyncImage`，Box 加 `sharedBounds(key = "poster-zoom-bounds-$tmdbId")`；AsyncImage 上**保留现有** `sharedElement("poster-$tmdbId")`（导航转场用，双 key 嵌套是文档支持模式）
- Produces: DetailScreen 调用点：`sharedKeyPrefix = "poster-zoom-bounds-$tmdbId"`

**key 约定:** 全屏端与源头同用 `"poster-zoom-bounds-$tmdbId"`，与导航转场 key `"poster-$tmdbId"` 区分开。

- [ ] **Step 1: 重写 PosterFullscreenOverlay**

`DetailPosterOverlay.kt` 整文件重写（保留保存逻辑）。关键差异：签名加 `visible`/`sharedKeyPrefix`；内容包进 `AnimatedVisibility`；图片加 `sharedBounds`。结构：

```kotlin
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun PosterFullscreenOverlay(
    visible: Boolean,
    posterUrl: String,
    title: String,
    sharedKeyPrefix: String?,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val savedToAlbumToast = stringResource(R.string.gallery_saved_to_album)
    val scope = rememberCoroutineScope()
    var isSaved by remember { mutableStateOf<Boolean?>(null) } // null=未检查, true=已保存, false=未保存

    // 进入时检查是否已保存（保留原逻辑）
    LaunchedEffect(posterUrl, title) {
        val safeName = title.replace(Regex("[^a-zA-Z0-9\\u4e00-\\u9fa5]"), "_")
        val filename = "TrackToSearch_${safeName}.jpg"
        val relativePath = Environment.DIRECTORY_PICTURES + "/TrackToSearch"
        isSaved = queryExistingFile(context, filename, relativePath) != null
    }

    val zoomState = rememberZoomState()

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(animationSpec = tween(200)),
        exit = fadeOut(animationSpec = tween(200))
    ) {
        val sharedTransitionScope = LocalSharedTransitionScope.current
        val sharedEnabled = LocalSharedTransitionEnabled.current
        val animatedVisibilityScope = this
        val boundsModifier = if (sharedTransitionScope != null && sharedEnabled && sharedKeyPrefix != null) {
            with(sharedTransitionScope) {
                Modifier.sharedBounds(
                    rememberSharedContentState(key = sharedKeyPrefix),
                    animatedVisibilityScope = animatedVisibilityScope,
                    clipInOverlayDuringTransition = OverlayClip(RoundedCornerShape(12.dp))
                )
            }
        } else Modifier

        BackHandler(enabled = true) {
            if (zoomState.scale > 1f) scope.launch { zoomState.changeScale(1f, Offset.Zero) }
            else onDismiss()
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.92f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {
                        if (zoomState.scale > 1f) scope.launch { zoomState.changeScale(1f, Offset.Zero) }
                        else onDismiss()
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            // 海报图片（放大显示，拦截点击事件不触发外层dismiss）
            AsyncImage(
                model = remember(posterUrl) {
                    ImageRequest.Builder(context)
                        .data(posterUrl)
                        .crossfade(false)
                        .size(1080)
                        .build()
                },
                contentDescription = title,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth(0.85f)
                    .aspectRatio(2f / 3f)
                    .then(boundsModifier)
                    .pointerInput(zoomState) {
                        detectTapGestures(
                            onDoubleTap = { tapOffset ->
                                if (zoomState.scale > 1f) {
                                    scope.launch { zoomState.changeScale(1f, Offset.Zero) }
                                } else {
                                    scope.launch { zoomState.changeScale(2.5f, tapOffset) }
                                }
                            }
                        )
                    }
                    .zoomable(zoomState)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {}
                    )
            )

            // 顶部操作栏（保留原有关闭/保存 Row，原样拷贝）
            Row(/* ...原有内容... */)
        }
    }
}
```

新增 import：
```kotlin
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope.OverlayClip
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import com.tracktosearch.ui.component.LocalSharedTransitionEnabled
import com.tracktosearch.ui.component.LocalSharedTransitionScope
```

- [ ] **Step 2: DetailHeaderContent 海报源加 sharedBounds**

`DetailHeaderContent.kt` 第 156-205 行，把 `AsyncImage` 包进一个带 `sharedBounds` 的 Box（AsyncImage 自身保留原 `sharedElement("poster-$tmdbId")`）：

```kotlin
// 用 Box 承载全屏查看转场的 sharedBounds；AsyncImage 上保留导航用 sharedElement
val boundsModifier = if (sharedTransitionScope != null && animatedVisibilityScope != null && LocalSharedTransitionEnabled.current) {
    with(sharedTransitionScope) {
        Modifier.sharedBounds(
            rememberSharedContentState(key = "poster-zoom-bounds-$tmdbId"),
            animatedVisibilityScope = animatedVisibilityScope,
            clipInOverlayDuringTransition = OverlayClip(RoundedCornerShape(8.dp))
        )
    }
} else Modifier
Box(modifier = Modifier.fillMaxSize().then(boundsModifier)) {
    AsyncImage(/* ...原有内容... modifier = posterModifier ... */)
}
```

新增 import：`androidx.compose.animation.SharedTransitionScope.OverlayClip`（`sharedBounds` 与 `rememberSharedContentState` 已在 `with(sharedTransitionScope)` 扩展里可用，`sharedBounds` 是 scope 成员，无需额外 import scope 类）。

- [ ] **Step 3: DetailScreen 调用点改签名**

`DetailScreen.kt:874-882`：

```kotlin
// 海报大图查看
val posterUrl = uiState.posterUrl
if (posterUrl != null) {
    PosterFullscreenOverlay(
        visible = showPosterFullscreen,
        posterUrl = posterUrl,
        title = uiState.displayTitle,
        sharedKeyPrefix = "poster-zoom-bounds-$tmdbId",
        onDismiss = { showPosterFullscreen = false }
    )
}
```

- [ ] **Step 4: 构建验证**

Run: `./gradlew :app:compileDebugKotlin`
Expected: 编译通过

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/tracktosearch/ui/screen/detail/DetailPosterOverlay.kt app/src/main/java/com/tracktosearch/ui/screen/detail/DetailHeaderContent.kt app/src/main/java/com/tracktosearch/ui/screen/detail/DetailScreen.kt
git commit -m "feat(detail): 详情页海报缩放动画"
```

---

### Task 3: 详情页截图缩放动画（去 Dialog）

**Files:**
- Modify: `app/src/main/java/com/tracktosearch/ui/screen/detail/DetailVideosImages.kt`
- Modify: `app/src/main/java/com/tracktosearch/ui/screen/detail/DetailScreen.kt:901-913`

**Interfaces:**
- Consumes: `ZoomableImageOverlay`（Task 1）
- Produces: `BackdropPagerOverlay(visible: Boolean, backdrops: List<String>, initialIndex: Int, sharedKeyPrefix: String?, onDismiss: () -> Unit)` — 改为薄委托，内部持有保存逻辑后调 `ZoomableImageOverlay`
- Produces: `VideosAndImagesSection` 与 `BackdropCard` 加 `sharedKeyPrefix: String?` 参数；`BackdropCard` 内 AsyncImage 加 `sharedElement("$sharedKeyPrefix-$index")`
- key: 源与全屏端 `"backdrop-zoom-$tmdbId-$index"`

注意：`FullVideosImagesSheet`（ModalBottomSheet）是独立 window，其内元素**不**加 sharedElement（无效果）；sheet 内点击只关闭 sheet + 打开全屏（淡入，无缩放），行为退化可接受。

- [ ] **Step 1: BackdropPagerOverlay 改为委托**

`DetailVideosImages.kt` 中 `BackdropPagerOverlay` 整段替换。保留已保存状态检查与保存逻辑，图片展示委托给 `ZoomableImageOverlay`：

```kotlin
@Composable
internal fun BackdropPagerOverlay(
    visible: Boolean,
    backdrops: List<String>,
    initialIndex: Int,
    sharedKeyPrefix: String?,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val alreadySavedToast = stringResource(R.string.poster_already_saved)
    val scope = rememberCoroutineScope()
    val savedBackdrops = remember { mutableStateOf<Set<Int>>(emptySet()) }
    // 大图用 original 清晰度
    val originalUrls = remember(backdrops) { backdrops.map { it.replace("/w780/", "/original/") } }

    // 检查当前截图是否已保存（保留原逻辑）
    LaunchedEffect(pagerStateOrCurrentPage) { /* ...原有查询逻辑... */ }

    ZoomableImageOverlay(
        visible = visible,
        images = originalUrls,
        initialIndex = initialIndex,
        sharedKeyPrefix = sharedKeyPrefix,
        onDismiss = onDismiss,
        onSave = { idx ->
            val url = originalUrls.getOrNull(idx) ?: return@ZoomableImageOverlay
            if (idx in savedBackdrops.value) {
                context.showToast(alreadySavedToast)
            } else {
                savePosterToGallery(context, scope, url, "TrackToSearch_backdrop_$idx.jpg") {
                    savedBackdrops.value += idx
                }
            }
        },
        isSavedAt = { idx -> idx in savedBackdrops.value }
    )
}
```

问题：保存状态检查依赖当前页索引，但 `ZoomableImageOverlay` 内部持有 pagerState。把检查逻辑改为基于 `initialIndex` 的一次性检查即可（与原有 `LaunchedEffect(pagerState.currentPage)` 语义近似），或把 `isSavedAt`/`onSave` 改为接收 idx 的简单回调（上面已如此）。移除对 `HorizontalPager`/`zoomable`/`AsyncImage` 的 import（若不再使用），`mutableStateOf`/`queryExistingFile`/`savePosterToGallery` 保留。

- [ ] **Step 2: BackdropCard 加 sharedElement**

`DetailVideosImages.kt`：

```kotlin
@Composable
internal fun BackdropCard(
    backdropUrl: String,
    onClick: () -> Unit = {},
    sharedKeyPrefix: String? = null,
    index: Int = 0
) {
    Box(
        modifier = Modifier
            .width(240.dp)
            .height(135.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
    ) {
        val sharedModifier = if (sharedKeyPrefix != null && LocalSharedTransitionScope.current != null && LocalAnimatedVisibilityScope.current != null && LocalSharedTransitionEnabled.current) {
            val scope = LocalSharedTransitionScope.current
            with(scope!!) {
                Modifier.sharedElement(
                    rememberSharedContentState(key = "$sharedKeyPrefix-$index"),
                    animatedVisibilityScope = LocalAnimatedVisibilityScope.current!!
                )
            }
        } else Modifier
        AsyncImage(
            model = backdropUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().then(sharedModifier)
        )
    }
}
```

新增 import：`com.tracktosearch.ui.component.LocalAnimatedVisibilityScope`、`LocalSharedTransitionEnabled`、`LocalSharedTransitionScope`。

`VideosAndImagesSection` 加参数 `sharedKeyPrefix: String? = null`，`itemsIndexed(backdrops...)` 处传给 `BackdropCard(sharedKeyPrefix = sharedKeyPrefix, index = index)`。

- [ ] **Step 3: DetailScreen 去 Dialog、传 key**

`DetailScreen.kt:901-913` 原 Dialog 包裹改为直接调用：

```kotlin
// 截图滑动查看（内联，不再用 Dialog，共享转场需同 window）
BackdropPagerOverlay(
    visible = selectedBackdropIndex >= 0 && uiState.backdrops.isNotEmpty(),
    backdrops = uiState.backdrops,
    initialIndex = selectedBackdropIndex.coerceAtLeast(0),
    sharedKeyPrefix = "backdrop-zoom-$tmdbId",
    onDismiss = { selectedBackdropIndex = -1 }
)
```

同时：`VideosAndImagesSection` 调用点传 `sharedKeyPrefix = "backdrop-zoom-$tmdbId"`。找到 `FullVideosImagesSheet` 的 `onBackdropClick` 调用点（DetailScreen 约 928 行），改为同时关闭 sheet：

```kotlin
onBackdropClick = { index ->
    selectedBackdropIndex = index
    showAllVideos = false // 关闭 sheet，全屏淡入（sheet 内元素无法参与共享转场）
}
```

- [ ] **Step 4: 构建验证**

Run: `./gradlew :app:compileDebugKotlin`
Expected: 编译通过

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/tracktosearch/ui/screen/detail/DetailVideosImages.kt app/src/main/java/com/tracktosearch/ui/screen/detail/DetailScreen.kt
git commit -m "feat(detail): 详情截图缩放动画并去除 Dialog"
```

---

### Task 4: 人物页图片缩放动画（含内联网格面板）

**Files:**
- Modify: `app/src/main/java/com/tracktosearch/ui/screen/person/PersonImageOverlay.kt`
- Modify: `app/src/main/java/com/tracktosearch/ui/screen/person/PersonScreen.kt:171-223, 437-463`

**Interfaces:**
- Consumes: `ZoomableImageOverlay`（Task 1）
- Produces: `PersonImagePagerOverlay(visible: Boolean, images: List<String>, initialIndex: Int, sharedKeyPrefix: String?, onDismiss: () -> Unit)` — 薄委托（保留保存逻辑）
- Produces: `AllPersonImagesPanel(visible: Boolean, images: List<String>, personId: Int, onImageClick: (Int) -> Unit, onDismiss: () -> Unit)` — 内联网格面板（原 ModalBottomSheet），内用 AnimatedVisibility + sharedElement
- key: 行源 `"person-row-$personId-$index"`；网格源 `"person-grid-$personId-$index"`（同一图片两个源，key 必须按源区分防冲突）

- [ ] **Step 1: PersonImagePagerOverlay 改为委托**

`PersonImageOverlay.kt`：`PersonImagePagerOverlay` 整段替换为薄委托，图片部分交给 `ZoomableImageOverlay`，保存逻辑保留：

```kotlin
@Composable
internal fun PersonImagePagerOverlay(
    visible: Boolean,
    images: List<String>,
    initialIndex: Int,
    sharedKeyPrefix: String?,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val alreadySavedToast = stringResource(R.string.poster_already_saved)
    val scope = rememberCoroutineScope()
    val savedImages = remember { mutableStateOf<Set<Int>>(emptySet()) }

    // 已保存检查（保留原逻辑，按 initialIndex 一次性检查）
    LaunchedEffect(Unit) {
        val fileName = "TrackToSearch_person_${initialIndex}.webp"
        val relativePath = Environment.DIRECTORY_PICTURES + "/TrackToSearch"
        if (queryExistingFile(context, fileName, relativePath) != null) {
            savedImages.value += initialIndex
        }
    }

    ZoomableImageOverlay(
        visible = visible,
        images = images,
        initialIndex = initialIndex,
        sharedKeyPrefix = sharedKeyPrefix,
        onDismiss = onDismiss,
        onSave = { idx ->
            if (idx in savedImages.value) {
                context.showToast(alreadySavedToast)
            } else {
                savePosterToGallery(context, scope, images[idx], "TrackToSearch_person_$idx.webp") {
                    savedImages.value += idx
                }
            }
        },
        isSavedAt = { idx -> idx in savedImages.value }
    )
}
```

- [ ] **Step 2: AllPersonImagesSheet 改内联 AllPersonImagesPanel**

`PersonImageOverlay.kt`：把 `AllPersonImagesSheet`（ModalBottomSheet）替换为内联面板。删掉 `ModalBottomSheet`/`ExperimentalMaterial3Api` 相关，改为 `AnimatedVisibility` 包全屏面板，网格单元加 sharedElement（用面板自身 AnimatedVisibility 的 scope）：

```kotlin
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun AllPersonImagesPanel(
    visible: Boolean,
    images: List<String>,
    personId: Int,
    onImageClick: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(animationSpec = tween(200)),
        exit = fadeOut(animationSpec = tween(200))
    ) {
        val sharedTransitionScope = LocalSharedTransitionScope.current
        val sharedEnabled = LocalSharedTransitionEnabled.current
        val animatedVisibilityScope = this
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .statusBarsPadding()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.person_images),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.common_close))
                }
            }
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier.fillMaxHeight(0.8f),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                itemsIndexed(images, key = { index, _ -> "person_img_all_$index" }, contentType = { _, _ -> "image" }) { index, url ->
                    val sharedModifier = if (sharedTransitionScope != null && sharedEnabled) {
                        with(sharedTransitionScope) {
                            Modifier.sharedElement(
                                rememberSharedContentState(key = "person-grid-$personId-$index"),
                                animatedVisibilityScope = animatedVisibilityScope
                            )
                        }
                    } else Modifier
                    SubcomposeAsyncImage(
                        model = remember(url) {
                            ImageRequest.Builder(context)
                                .data(url)
                                .size(300)
                                .crossfade(false)
                                .build()
                        },
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(2f / 3f)
                            .clip(RoundedCornerShape(6.dp))
                            .then(sharedModifier)
                            .clickable { onImageClick(index) }
                    )
                }
            }
        }
    }
}
```

- [ ] **Step 3: PersonScreen 接线**

`PersonScreen.kt`：

新增状态与 key（在现有 state 声明区，约 100-105 行）：
```kotlin
// 当前全屏图片来源前缀：决定 sharedElement 与哪端缩略图配对
var personImageFullscreenKey by remember { mutableStateOf<String?>(null) }
```

头部横向栏（约 206-219 行）`PosterCard` 加 `posterModifier`（共享转场用）与点击设置 key：
```kotlin
itemsIndexed(uiState.personImages, key = { index, url -> "person_img_$index" }, contentType = { _, _ -> "image" }) { index, url ->
    val sharedModifier = if (LocalSharedTransitionScope.current != null && LocalAnimatedVisibilityScope.current != null && LocalSharedTransitionEnabled.current) {
        val scope = LocalSharedTransitionScope.current
        with(scope!!) {
            Modifier.sharedElement(
                rememberSharedContentState(key = "person-row-$personId-$index"),
                animatedVisibilityScope = LocalAnimatedVisibilityScope.current!!
            )
        }
    } else Modifier
    PosterCard(
        imageUrl = url,
        title = uiState.person?.name ?: personName,
        onClick = {
            personImageFullscreenKey = "person-row-$personId"
            selectedPersonImageIndex = index
        },
        modifier = Modifier
            .width(110.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(14.dp)),
        posterModifier = sharedModifier,
        imageSize = 200
    )
}
```

全屏 overlay（约 437-452 行）去 Dialog、改调用：
```kotlin
// 人物图片大图查看（内联，共享转场需同 window）
PersonImagePagerOverlay(
    visible = selectedPersonImageIndex >= 0 && uiState.personImages.isNotEmpty() && personImageFullscreenKey != null,
    images = uiState.personImages,
    initialIndex = selectedPersonImageIndex.coerceAtLeast(0),
    sharedKeyPrefix = personImageFullscreenKey,
    onDismiss = { selectedPersonImageIndex = -1 }
)
```

全部图片面板（约 454-463 行）：
```kotlin
// 全部人物图片面板（内联，替代原 ModalBottomSheet）
AllPersonImagesPanel(
    visible = showAllPersonImages && uiState.personImages.isNotEmpty(),
    images = uiState.personImages,
    personId = personId,
    onImageClick = { index ->
        personImageFullscreenKey = "person-grid-$personId"
        selectedPersonImageIndex = index
        showAllPersonImages = false // 关闭面板，全屏从网格原位缩放飞出
    },
    onDismiss = { showAllPersonImages = false }
)
```

删除 `Dialog`/`DialogProperties` 相关 import（若不再使用）；`personId` 参数已存在。

- [ ] **Step 4: 构建验证**

Run: `./gradlew :app:compileDebugKotlin`
Expected: 编译通过

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/tracktosearch/ui/screen/person/PersonImageOverlay.kt app/src/main/java/com/tracktosearch/ui/screen/person/PersonScreen.kt
git commit -m "feat(person): 人物图片缩放动画与全图内联面板"
```

---

### Task 5: 反馈页截图缩放动画

**Files:**
- Modify: `app/src/main/java/com/tracktosearch/ui/screen/feedback/NewFeedbackScreen.kt`
- Modify: `app/src/main/java/com/tracktosearch/ui/screen/feedback/FeedbackDetailScreen.kt`
- Delete: `app/src/main/java/com/tracktosearch/ui/screen/feedback/ScreenshotFullscreenOverlay.kt`

**Interfaces:**
- Consumes: `ZoomableImageOverlay`（Task 1）
- key 约定：
  - 新反馈截图（ScreenshotRow）: `"fb-new-$index"`，全屏 `sharedKeyPrefix = "fb-new"`
  - 反馈详情·原帖截图（OriginalFeedbackCard）: `"fb-conv-$feedbackId-$index"`，全屏 `sharedKeyPrefix = "fb-conv-$feedbackId"`
  - 反馈详情·回复气泡截图（ConversationBubble，reply.id 唯一）: `"fb-reply-${reply.id}-$index"`，全屏 `sharedKeyPrefix = "fb-reply-${reply.id}"`
  - 反馈详情·回复框预览（ReplyBar）: `"fb-compose-$index"`，全屏 `sharedKeyPrefix = "fb-compose"`

- [ ] **Step 1: 删除 ScreenshotFullscreenOverlay**

```bash
git rm app/src/main/java/com/tracktosearch/ui/screen/feedback/ScreenshotFullscreenOverlay.kt
```

- [ ] **Step 2: NewFeedbackScreen 接线**

`NewFeedbackScreen.kt` 第 239-248 行，全屏 overlay 换组件：
```kotlin
// 截图全屏查看
ZoomableImageOverlay(
    visible = fullscreenIndex != null && screenshots.isNotEmpty(),
    images = screenshots.map { it.first },
    initialIndex = fullscreenIndex?.coerceIn(0, screenshots.size - 1) ?: 0,
    sharedKeyPrefix = "fb-new",
    onDismiss = { fullscreenIndex = null }
)
```

`ScreenshotRow`（第 256 行起）加参数 `sharedKeyPrefix: String? = null`，第 299 行 `AsyncImage` 加 sharedElement：
```kotlin
AsyncImage(
    model = remember(bytes) { ImageRequest.Builder(context).data(bytes).crossfade(true).build() },
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
```

`ScreenshotRow` 调用点传 `sharedKeyPrefix = "fb-new"`。

- [ ] **Step 3: FeedbackDetailScreen 接线**

第 221-222 行两处全屏 overlay 换组件：
```kotlin
// 原帖截图全屏
ZoomableImageOverlay(
    visible = fullscreenIndex != null && fullscreenUrls.isNotEmpty(),
    images = fullscreenUrls,
    initialIndex = fullscreenIndex?.coerceIn(0, fullscreenUrls.size - 1) ?: 0,
    sharedKeyPrefix = "fb-conv-$feedbackId",
    onDismiss = { fullscreenIndex = null }
)
// 回复框预览全屏
ZoomableImageOverlay(
    visible = replyFullscreenIndex != null && replyScreenshots.isNotEmpty(),
    images = replyScreenshots.map { it.first },
    initialIndex = replyFullscreenIndex?.coerceIn(0, replyScreenshots.size - 1) ?: 0,
    sharedKeyPrefix = "fb-compose",
    onDismiss = { replyFullscreenIndex = null }
)
```

三处缩略图源加 sharedElement：
- `OriginalFeedbackCard`（约 226-288 行）：加参数 `sharedKeyPrefix: String? = null`，其截图 AsyncImage（约 288 行）加 `sharedElement("$sharedKeyPrefix-$index")`，调用处传 `"fb-conv-$feedbackId"`
- `ConversationBubble`（345 行起）：加参数 `sharedKeyPrefix: String? = null`，截图 AsyncImage（400 行）加 `sharedElement("$sharedKeyPrefix-$index")`，调用处（196 行 `onScreenshotClick` 的 bubble）传 `"fb-reply-${reply.id}"`
- `ReplyBar`（446 行起）：加参数 `sharedKeyPrefix: String? = null`，预览 AsyncImage（469 行）加 `sharedElement("$sharedKeyPrefix-$index")`，调用处（214 行）传 `"fb-compose"`

sharedElement 修饰符写法与 Task 3 Step 2 相同（判空 `LocalSharedTransitionScope`/`LocalAnimatedVisibilityScope`/`LocalSharedTransitionEnabled`）。

- [ ] **Step 4: 构建验证**

Run: `./gradlew :app:compileDebugKotlin`
Expected: 编译通过

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/tracktosearch/ui/screen/feedback/
git commit -m "feat(feedback): 反馈截图缩放动画"
```

---

### Task 6: 豆瓣详情页海报缩放动画

**Files:**
- Modify: `app/src/main/java/com/tracktosearch/ui/screen/douban/DoubanItemDetailScreen.kt:1585-1594, 1846-1878`

**Interfaces:**
- Consumes: `PosterFullscreenOverlay`（Task 2 新签名）
- key: 源与全屏端同用 `"douban-poster-zoom-bounds-$doubanId"`（`doubanId` 是屏幕参数）

- [ ] **Step 1: 全屏 overlay 调用点改签名**

`DoubanItemDetailScreen.kt:1585-1594`：
```kotlin
// 海报大图查看(放在最后绘制,关闭/保存按钮不被顶部按钮遮住)
val posterFailure = uiState.failure
val posterUrl = posterFailure?.posterUrl
if (posterUrl != null) {
    PosterFullscreenOverlay(
        visible = showPosterFullscreen,
        posterUrl = posterUrl,
        title = posterFailure?.title.orEmpty(),
        sharedKeyPrefix = "douban-poster-zoom-bounds-$doubanId",
        onDismiss = { showPosterFullscreen = false }
    )
}
```

- [ ] **Step 2: 海报源加 sharedBounds**

`DoubanItemDetailScreen.kt:1855-1878` 的 `AsyncImage` modifier 加 sharedBounds（doubanId 作用域内有 `LocalSharedTransitionScope`/`LocalAnimatedVisibilityScope` 读取）：
```kotlin
// 在 Surface 内容里、AsyncImage 之前读取 scope 并构造 boundsModifier
val sharedTransitionScope = LocalSharedTransitionScope.current
val animatedVisibilityScope = LocalAnimatedVisibilityScope.current
val boundsModifier = if (sharedTransitionScope != null && animatedVisibilityScope != null && LocalSharedTransitionEnabled.current) {
    with(sharedTransitionScope) {
        Modifier.sharedBounds(
            rememberSharedContentState(key = "douban-poster-zoom-bounds-$doubanId"),
            animatedVisibilityScope = animatedVisibilityScope,
            clipInOverlayDuringTransition = OverlayClip(RoundedCornerShape(8.dp))
        )
    }
} else Modifier
AsyncImage(
    // ...原内容...
    modifier = Modifier.fillMaxSize().then(boundsModifier)
)
```

新增 import：`com.tracktosearch.ui.component.LocalAnimatedVisibilityScope`、`LocalSharedTransitionEnabled`、`LocalSharedTransitionScope`、`androidx.compose.animation.SharedTransitionScope.OverlayClip`、`androidx.compose.animation.ExperimentalSharedTransitionApi`（如需 OptIn）。

- [ ] **Step 3: 构建验证**

Run: `./gradlew :app:compileDebugKotlin`
Expected: 编译通过

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/tracktosearch/ui/screen/douban/DoubanItemDetailScreen.kt
git commit -m "feat(douban): 豆瓣详情海报缩放动画"
```

---

### Task 7: 整体构建 + 模拟器验收

**Files:**
- 无新改动（若构建报错则修复相关文件）

- [ ] **Step 1: 全量构建**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL。若失败，按报错修复对应文件后重跑。

- [ ] **Step 2: 模拟器/真机验收（android-emulator-qa）**

前提：`adb devices` 确认在线。

安装并启动：
```bash
./gradlew :app:installDebug
adb shell am start -n com.tracktosearch/.MainActivity
```

逐场景验证（每步 `adb exec-out screencap -p > /tmp/xxx.png` 留存）：
1. **详情页海报**：进详情 → 点海报 → 海报从头部小图平滑放大到全屏；点背景/返回 → 反向缩小回原位。设共享转场关闭时退化为淡入淡出。
2. **详情页截图**：详情 → 预告片截图区 → 点 BackdropCard → 缩放进入全屏；横滑翻页；关闭反向缩放。
3. **人物页图片**：人物页 → 头部横栏点图 → 缩放进入；关闭反向。全部图片面板 → 点网格小图 → 从网格原位缩放飞出；关闭缩回。
4. **反馈页截图**：新反馈截图行 → 点小图 → 缩放进入；反馈详情气泡截图 → 同上；回复框预览 → 同上。
5. **豆瓣详情海报**：豆瓣条目详情 → 点海报 → 缩放进入/退出。
6. 每场景双击缩放、双指缩放、保存按钮行为不回归。
7. `adb logcat -b crash` 无崩溃。

- [ ] **Step 3: 回归共享转场设置**

设置页关闭「共享元素转场」→ 上述场景全部退化为无动画黑底弹出（原行为），确认不崩。

- [ ] **Step 4: 修复问题并提交**

若验收发现动画/崩溃问题，修复后重新构建，按改动内容单独提交：
```bash
git add <修复文件>
git commit -m "fix(<scope>): 描述修复内容"
```

---

## Self-Review

**1. Spec 覆盖：**
- 详情页海报 → Task 2 ✓
- 详情页截图 → Task 3 ✓
- 人物页图片（含方式 B 网格完整转场）→ Task 4 ✓
- 反馈页截图 → Task 5 ✓
- 豆瓣详情海报 → Task 6 ✓
- 共享转场设置关闭时退化 → 所有 sharedElement/sharedBounds 使用点判空 `LocalSharedTransitionEnabled` ✓
- 动画规格（fadeIn+scaleIn / fadeOut+scaleOut）→ sharedElement/sharedBounds 默认弹簧 + AnimatedVisibility fadeIn/fadeOut ✓

**2. 占位符扫描：** 无 TBD/TODO；所有代码步骤含完整代码。Task 3 保存检查逻辑标注为「与原有语义近似」，需实现者按原文件逻辑补齐具体查询代码——已在任务内说明来源（原 BackdropPagerOverlay 的 LaunchedEffect）。实现时直接照原文件搬运。

**3. 类型一致性：**
- `ZoomableImageOverlay` 签名在 Task 1 定义，Task 3/4/5 消费，参数名一致 ✓
- `PosterFullscreenOverlay` 新签名（visible/posterUrl/title/sharedKeyPrefix/onDismiss）Task 2 定义，Task 6 消费 ✓
- key 前缀命名：`poster-zoom-bounds-*`（sharedBounds 对）、`backdrop-zoom-*`、`person-row-*`/`person-grid-*`、`fb-*`、`douban-poster-zoom-bounds-*`，各任务内部统一 ✓
- 端到端匹配：全屏端 key = 源头 key（同一字符串），页面级前缀 + `-$index` 后缀方案在组件与源头两端一致 ✓

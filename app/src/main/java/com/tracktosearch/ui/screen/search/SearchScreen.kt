package com.tracktosearch.ui.screen.search

import android.location.Location
import android.location.LocationManager
import android.os.Build
import com.tracktosearch.BuildConfig
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForwardIos
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp

import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.rememberLottieComposition
import com.tracktosearch.R
import com.tracktosearch.data.ai.AiRecommendation
import com.tracktosearch.data.local.CloudPermissionStorage
import com.tracktosearch.ui.component.DropdownAnchorMenu
import com.tracktosearch.ui.theme.DesignToken
import com.tracktosearch.data.local.SearchHistoryItem
import com.tracktosearch.data.local.ViewedItemStorage
import com.tracktosearch.data.remote.douban.dto.DoubanHotItem
import com.tracktosearch.ui.animation.fadeSlideIn
import com.tracktosearch.ui.component.CloudEasterEgg
import com.tracktosearch.ui.component.CloudOverlay
import com.tracktosearch.ui.component.CloudThemeManager
import com.tracktosearch.ui.screen.ai.AiSpriteCenter
import com.tracktosearch.ui.screen.ai.AiSpriteAnchor
import com.tracktosearch.ui.screen.ai.AiSpriteInterruptReason
import com.tracktosearch.ui.screen.ai.AiSpriteInterruptRequest
import com.tracktosearch.ui.screen.ai.AiSpriteMotion
import com.tracktosearch.ui.screen.ai.AiSpriteOverlayPolicy
import com.tracktosearch.ui.screen.ai.AiSpriteOverlayTrigger
import com.tracktosearch.ui.screen.ai.AiSpriteViewModel
import com.tracktosearch.ui.screen.ai.AiSceneEvent
import com.tracktosearch.ui.screen.ai.automaticSpriteArt
import com.tracktosearch.ui.screen.ai.nextAiSpriteOverlayTrigger
import com.tracktosearch.ui.screen.ai.sceneArtFor
import com.tracktosearch.ui.screen.ai.sceneEventForSearch
import com.tracktosearch.ui.screen.ai.searchAnchorFor
import com.tracktosearch.ui.component.DiscoverModalBottomSheet
import com.tracktosearch.ui.component.DoubanRatingBadge
import com.tracktosearch.ui.component.AdaptiveTwoLineTitle
import com.tracktosearch.ui.component.AppVisualSurface
import com.tracktosearch.ui.component.GlassIconButton
import com.tracktosearch.ui.component.GlassHighlight
import com.tracktosearch.ui.component.GlassScene
import com.tracktosearch.ui.component.GlassSurfaceRole
import com.tracktosearch.ui.component.NeumorphicFrostedSurface
import com.tracktosearch.ui.component.RatingBadge
import com.tracktosearch.ui.component.VisualSurfaceKind
import com.tracktosearch.ui.component.isAppDarkTheme
import com.tracktosearch.ui.component.glassSceneForContent
import com.tracktosearch.ui.component.backdropSource
import com.tracktosearch.ui.component.neumorphicShadow
import com.tracktosearch.ui.theme.LocalVisualEffectMode
import com.tracktosearch.ui.theme.VisualEffectMode
import com.tracktosearch.ui.util.HapticType
import com.tracktosearch.ui.util.performHaptic
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.blur.materials.HazeMaterials
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.compose.foundation.lazy.grid.items as gridItems
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.regex.Pattern

@EntryPoint
@InstallIn(SingletonComponent::class)
interface ViewedStorageProvider {
    fun viewedItemStorage(): ViewedItemStorage
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface CloudThemeProvider {
    fun cloudThemeManager(): CloudThemeManager
}

enum class SearchSourceType { DISK, MOVIE, SHOW, PERSON }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    initialKeyword: String = "",
    onBack: (() -> Unit)? = null,
    onSearchClick: ((String) -> Unit)? = null,
    onTraktSearch: ((SearchSourceType, String) -> Unit)? = null,
    onSpiderTest: (() -> Unit)? = null,
    onMovieClick: (traktId: Int, tmdbId: Int, title: String, imdbId: String, traktRating: Double, inWatchlist: Boolean, isWatched: Boolean) -> Unit = { _, _, _, _, _, _, _ -> },
    onShowClick: (traktId: Int, tmdbId: Int, title: String, imdbId: String, traktRating: Double, inWatchlist: Boolean, isWatched: Boolean) -> Unit = { _, _, _, _, _, _, _ -> },
    onNavigateToLogin: (() -> Unit)? = null,
    onRecommendationClick: ((AiRecommendation) -> Unit)? = null,
    searchSourceType: SearchSourceType = SearchSourceType.DISK,
    onSearchSourceTypeChange: ((SearchSourceType) -> Unit)? = null,
    modifier: Modifier = Modifier,
    viewModel: SearchViewModel = hiltViewModel(),
    spriteViewModel: AiSpriteViewModel = hiltViewModel()
) {
    val isDark = isAppDarkTheme()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val hotSearches by viewModel.hotSearches.collectAsStateWithLifecycle()
    val spriteState by spriteViewModel.uiState.collectAsStateWithLifecycle()
    // 页面重新可见时(ON_RESUME)，若热门搜索为空则重新加载(新片榜重试后可拿到数据)
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                if (viewModel.hotSearches.value.isEmpty()) {
                    viewModel.loadHotSearches()
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
    var searchQuery by rememberSaveable { mutableStateOf(initialKeyword) }
    val context = LocalContext.current
    val view = LocalView.current
    val focusManager = LocalFocusManager.current
    val viewedItemStorage = remember {
        EntryPointAccessors.fromApplication(context, ViewedStorageProvider::class.java).viewedItemStorage()
    }
    val viewedUrls by viewedItemStorage.viewedUrls.collectAsStateWithLifecycle(initialValue = emptySet())

    // 白云彩蛋主题管理
    val cloudThemeManager = remember {
        EntryPointAccessors.fromApplication(context, CloudThemeProvider::class.java).cloudThemeManager()
    }
    val cloudPermissionStorage = remember {
        CloudPermissionStorage(context.applicationContext)
    }
    val scope = rememberCoroutineScope()
    // 定位权限申请
    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            val loc = getLastKnownLocation(context)
            cloudThemeManager.onPermissionGranted()
            cloudThemeManager.loadTheme(loc)
        } else {
            cloudThemeManager.onPermissionDismissed()
        }
    }
    LaunchedEffect(Unit) {
        val hasPermission = context.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (hasPermission) {
            cloudThemeManager.onPermissionGranted()
            // 用 initializeTheme 而非 loadTheme：会话级 guard 防止导航返回时重新触发
            // getLastKnownLocation（避免 Android 12+ 状态栏位置图标反复出现）
            // locationProvider 仅在首次调用时执行，后续直接复用已加载主题
            cloudThemeManager.initializeTheme { getLastKnownLocation(context) }
        } else {
            // 未授权时只加载缓存主题，不自动请求权限
            cloudThemeManager.initializeTheme { null }
        }
        // 触发夜晚交替
        cloudThemeManager.toggleNightAlternate()
    }
    val showPermissionDialog by cloudThemeManager.showPermissionDialog.collectAsStateWithLifecycle()
    val easterEggRes by cloudThemeManager.easterEggRes.collectAsStateWithLifecycle()
    val easterMessageRes by cloudThemeManager.easterMessageRes.collectAsStateWithLifecycle()
    val aiSpriteCloudDescription = stringResource(R.string.ai_sprite_cloud_description)
    var showAiSpriteCenter by rememberSaveable { mutableStateOf(false) }
    var showAiSpriteMotion by rememberSaveable { mutableStateOf(false) }
    var activeSpriteAnchor by remember { mutableStateOf(AiSpriteAnchor.SearchBox) }
    var activeSceneEvent by remember { mutableStateOf<AiSceneEvent?>(null) }
    var spriteInterruptRevision by remember { mutableStateOf(0L) }
    var spriteInterruptReason by remember { mutableStateOf(AiSpriteInterruptReason.BLOCKED) }
    var searchBoxBounds by remember { mutableStateOf<Rect?>(null) }
    var lastInteractionAt by remember { mutableStateOf(System.currentTimeMillis()) }
    var overlayEntryHandled by rememberSaveable { mutableStateOf(false) }
    var wasSearchLoading by remember { mutableStateOf(false) }
    val overlayPreferences = remember(context.applicationContext) {
        context.applicationContext.getSharedPreferences("ai_sprite_overlay_quota_v1", android.content.Context.MODE_PRIVATE)
    }
    val overlayPolicy = remember(overlayPreferences) {
        AiSpriteOverlayPolicy(
            readDailyCount = { dayKey ->
                if (overlayPreferences.getString("day_key", null) == dayKey) {
                    overlayPreferences.getInt("daily_count", 0)
                } else {
                    0
                }
            },
            writeDailyCount = { dayKey, count ->
                overlayPreferences.edit()
                    .putString("day_key", dayKey)
                    .putInt("daily_count", count)
                    .apply()
            }
        )
    }
    var overlayDayKey by remember {
        mutableStateOf(
            SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date())
        )
    }

    LaunchedEffect(Unit) {
        while (true) {
            val now = Calendar.getInstance()
            val nextDay = (now.clone() as Calendar).apply {
                add(Calendar.DAY_OF_YEAR, 1)
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            delay((nextDay.timeInMillis - now.timeInMillis).coerceAtLeast(1_000L))
            overlayDayKey = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date())
        }
    }

    LaunchedEffect(Unit) {
        spriteViewModel.ensureLoaded()
    }
    // 搜索框焦点状态，用于控制搜索历史展开
    var isSearchFocused by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val searchContentScrollState = rememberScrollState()

    fun interruptAiSprite(reason: AiSpriteInterruptReason) {
        lastInteractionAt = System.currentTimeMillis()
        spriteInterruptRevision += 1L
        spriteInterruptReason = reason
        showAiSpriteMotion = false
        activeSceneEvent = null
    }

    LaunchedEffect(
        spriteState.activatedCharacterId,
        uiState.isLoading,
        uiState.resources.size,
        isSearchFocused,
        searchQuery,
        overlayDayKey,
        showAiSpriteCenter,
        showPermissionDialog,
        easterEggRes,
        searchBoxBounds,
        showAiSpriteMotion
    ) {
        val nextBounds = searchBoxBounds
        val hasBlockingState = showAiSpriteCenter ||
            showPermissionDialog ||
            easterEggRes != null ||
            isSearchFocused ||
            searchQuery.isNotBlank() ||
            uiState.isLoading ||
            nextBounds == null
        val trigger = nextAiSpriteOverlayTrigger(
            entryHandled = overlayEntryHandled,
            wasSearchLoading = wasSearchLoading,
            isSearchLoading = uiState.isLoading,
            hasResults = uiState.resources.isNotEmpty(),
            isSearchFocused = isSearchFocused,
            searchQuery = searchQuery,
            activated = spriteState.activatedCharacterId?.let { automaticSpriteArt(it) != null } == true,
            nowMs = System.currentTimeMillis(),
            idleForMs = System.currentTimeMillis() - lastInteractionAt,
            hasBlockingOverlay = showAiSpriteCenter ||
                showPermissionDialog ||
                easterEggRes != null ||
                isSearchFocused ||
                searchQuery.isNotBlank()
        )
        if (trigger != null && !hasBlockingState && !showAiSpriteMotion) {
            if (trigger == AiSpriteOverlayTrigger.FIRST_ENTRY) overlayEntryHandled = true
            activeSpriteAnchor = searchAnchorFor(trigger, hasResultAnchor = false)
            activeSceneEvent = sceneEventForSearch(trigger)
            if (overlayPolicy.tryConsume(true, trigger, overlayDayKey)) {
                showAiSpriteMotion = true
            }
        }
        wasSearchLoading = uiState.isLoading
    }

    LaunchedEffect(
        lastInteractionAt,
        spriteState.activatedCharacterId,
        uiState.isLoading,
        isSearchFocused,
        searchQuery,
        overlayDayKey,
        showAiSpriteCenter,
        showPermissionDialog,
        easterEggRes,
        showAiSpriteMotion
    ) {
        val blocked = showAiSpriteCenter ||
            showPermissionDialog ||
            easterEggRes != null ||
            isSearchFocused ||
            searchQuery.isNotBlank() ||
            uiState.isLoading ||
            showAiSpriteMotion
        if (!blocked && spriteState.activatedCharacterId?.let { automaticSpriteArt(it) != null } == true) {
            delay(8_000L)
            if (System.currentTimeMillis() - lastInteractionAt >= 8_000L &&
                searchBoxBounds != null &&
                !showAiSpriteMotion
            ) {
                activeSpriteAnchor = AiSpriteAnchor.SearchBox
                activeSceneEvent = sceneEventForSearch(AiSpriteOverlayTrigger.IDLE)
                if (overlayPolicy.tryConsume(true, AiSpriteOverlayTrigger.IDLE, overlayDayKey)) {
                    showAiSpriteMotion = true
                }
            }
        }
    }

    LaunchedEffect(searchContentScrollState) {
        var previousValue = searchContentScrollState.value
        snapshotFlow { searchContentScrollState.value }.collect { value ->
            if (value != previousValue) {
                previousValue = value
                interruptAiSprite(AiSpriteInterruptReason.SCROLL)
            }
        }
    }

    LaunchedEffect(showPermissionDialog, easterEggRes, showAiSpriteCenter) {
        if (showPermissionDialog || easterEggRes != null || showAiSpriteCenter) {
            interruptAiSprite(AiSpriteInterruptReason.BLOCKED)
        }
    }

    // Haze 毛玻璃状态
    val hazeState = remember { HazeState() }
    val searchGlassScene = glassSceneForContent(
        contentCount = uiState.searchHistory.size + hotSearches.size,
        readabilityDemand = when {
            isSearchFocused && searchQuery.isNotBlank() -> 0.86f
            isSearchFocused || searchQuery.isNotBlank() -> 0.68f
            else -> 0.42f
        },
        ambientColor = MaterialTheme.colorScheme.background,
        contentCapacity = 32,
        loadingCount =
            (if (uiState.isLoading) 1 else 0) +
                uiState.doubanHotCategories.count { it.isLoading },
        loadingItemWeight = 4
    )

    // Animation state for search box width
    val animatedWidthFraction = remember { Animatable(0.75f) }
    val isActive = isSearchFocused || searchQuery.isNotEmpty()

    // Trigger animation when active state changes
    LaunchedEffect(isActive) {
        if (isActive) {
            animatedWidthFraction.animateTo(
                targetValue = 1f,
                animationSpec = tween(300, easing = FastOutSlowInEasing)
            )
        } else {
            animatedWidthFraction.animateTo(
                targetValue = 0.75f,
                animationSpec = tween(300, easing = FastOutSlowInEasing)
            )
        }
    }

    // 搜索历史展开时，返回手势收起搜索历史而不是退出页面
    BackHandler(enabled = isSearchFocused) {
        interruptAiSprite(AiSpriteInterruptReason.FOCUS)
        focusManager.clearFocus()
    }

    var hadQuery by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(searchQuery) {
        hadQuery = searchQuery.isNotEmpty()
    }

    LaunchedEffect(initialKeyword) {
        if (initialKeyword.isNotEmpty()) {
            searchQuery = initialKeyword
        }
    }

    val configuration = LocalConfiguration.current
    val screenHeight = configuration.screenHeightDp.dp
    val titleAreaHeight = 110.dp
    val searchBoxHeight = 72.dp
    val searchBoxCenterY = screenHeight / 2 - 148.dp
    val cloudIconSize = 120.dp
    val contentTopOffset = searchBoxHeight + 25.dp

    val targetSearchBoxY by animateDpAsState(
        targetValue = if (isSearchFocused || searchQuery.isNotEmpty()) titleAreaHeight + 18.dp else searchBoxCenterY,
        label = "search_box_y"
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .hazeSource(state = hazeState)
    ) {
        // 搜索页的历史/热门词是 Glass chip，不能把整块内容注册为 source。
        // 这个空 source 只让搜索框拥有稳定的页面底色采样，避免把 Glass 子树录回同一 backdrop。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .backdropSource()
        )

        // 标题区（固定顶部，不随搜索框移动，padding状态栏避开系统栏）
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
            Column(modifier = Modifier.align(Alignment.CenterStart)) {
                Text(
                    text = stringResource(R.string.search_title),
                    fontSize = 28.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = (-0.5).sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }

        // 白云图标（搜索框未激活时显示在搜索框上方 16dp，水平居中）
        CloudIconWithAnimation(
            cloudThemeManager = cloudThemeManager,
            isActive = isActive,
            onLongClick = {
                interruptAiSprite(AiSpriteInterruptReason.USER_INPUT)
                showAiSpriteCenter = true
            },
            modifier = Modifier
                .size(cloudIconSize)
                .semantics { contentDescription = aiSpriteCloudDescription }
                .align(Alignment.TopCenter)
                .offset(
                    y = targetSearchBoxY - cloudIconSize - 16.dp
                )
        )

        // 权限提示弹窗
        if (showPermissionDialog) {
            ModalBottomSheet(
                onDismissRequest = {
                    interruptAiSprite(AiSpriteInterruptReason.BLOCKED)
                    cloudThemeManager.onPermissionDismissed()
                },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                dragHandle = null
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(0.28f)
                ) {
                    val composition by rememberLottieComposition(LottieCompositionSpec.RawRes(R.raw.easter_cat))
                    LottieAnimation(
                        composition = composition,
                        progress = { 0f },
                        modifier = Modifier
                            .size(160.dp)
                            .offset(x = (-20).dp, y = (-23).dp)
                    )
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 20.dp, start = 27.dp, end = 27.dp, bottom = 20.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.permission_cloud_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(top = 36.dp, bottom = 36.dp)
                        )
                        Text(
                            text = stringResource(R.string.permission_cloud_message),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(bottom = 42.dp)
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            TextButton(
                                onClick = {
                                    interruptAiSprite(AiSpriteInterruptReason.BLOCKED)
                                    cloudThemeManager.onPermissionDismissed()
                                    scope.launch(Dispatchers.IO) {
                                        cloudPermissionStorage.setDismissed(true)
                                    }
                                },
                                contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp)
                            ) {
                                Text(
                                    text = stringResource(R.string.permission_cancel),
                                    style = MaterialTheme.typography.labelLarge
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Button(
                                onClick = {
                                    interruptAiSprite(AiSpriteInterruptReason.BLOCKED)
                                    cloudThemeManager.onPermissionDismissed()
                                    locationPermissionLauncher.launch(android.Manifest.permission.ACCESS_COARSE_LOCATION)
                                },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                            ) {
                                Text(
                                    text = stringResource(R.string.permission_authorize),
                                    style = MaterialTheme.typography.labelLarge
                                )
                            }
                        }
                    }
                }
            }
        }

        // 内容区：搜索历史/热门搜索/建议（初始位置/获得焦点/有查询时显示）
        AnimatedVisibility(
            visible = isSearchFocused || searchQuery.isNotEmpty() || uiState.searchHistory.isNotEmpty() || hotSearches.isNotEmpty(),
            modifier = Modifier
                .fillMaxSize()
                .padding(top = targetSearchBoxY + contentTopOffset),
            enter = fadeIn(animationSpec = tween(250)) + expandVertically(animationSpec = tween(250)),
            exit = fadeOut(animationSpec = tween(200)) + shrinkVertically(animationSpec = tween(200))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp)
                    .verticalScroll(searchContentScrollState)
            ) {
                if (searchQuery.isNotEmpty()) {
                    val suggestions = remember(searchQuery, uiState.searchHistory) {
                        viewModel.getSuggestions(searchQuery)
                    }
                    if (suggestions.isNotEmpty()) {
                        SearchSuggestionsInline(
                            suggestions = suggestions,
                            onSuggestionClick = { item ->
                                interruptAiSprite(AiSpriteInterruptReason.USER_INPUT)
                                searchQuery = item.keyword
                                onTraktSearch?.invoke(searchSourceType, item.keyword)
                                focusManager.clearFocus()
                            }
                        )
                    }
                } else {
                    if (uiState.searchHistory.isNotEmpty()) {
                        SearchHistoryTwoRow(
                            history = uiState.searchHistory,
                            onHistoryClick = { item ->
                                interruptAiSprite(AiSpriteInterruptReason.USER_INPUT)
                                val st = when (item.type) {
                                    "movie" -> SearchSourceType.MOVIE
                                    "show" -> SearchSourceType.SHOW
                                    "person" -> SearchSourceType.PERSON
                                    else -> SearchSourceType.DISK
                                }
                                searchQuery = item.keyword
                                onTraktSearch?.invoke(st, item.keyword)
                                focusManager.clearFocus()
                            },
                            onHistoryDelete = { viewModel.removeHistory(it.keyword, it.type) },
                            onClearAll = { viewModel.clearHistory() },
                            selectedKeyword = searchQuery,
                            isDark = isDark,
                            hazeState = hazeState,
                            scene = searchGlassScene
                        )
                    }
                    Spacer(modifier = Modifier.height(18.dp))
                    PopularSearchesSectionNew(
                        popularSearches = hotSearches,
                        onPopularClick = { keyword ->
                            interruptAiSprite(AiSpriteInterruptReason.USER_INPUT)
                            searchQuery = keyword
                            viewModel.addTraktHistory(keyword, searchSourceType.name.lowercase())
                            onTraktSearch?.invoke(searchSourceType, keyword)
                            focusManager.clearFocus()
                        },
                        selectedKeyword = searchQuery,
                        isDark = isDark,
                        hazeState = hazeState,
                        scene = searchGlassScene
                    )
                }
                Spacer(modifier = Modifier.height(80.dp))
            }
        }

        // 搜索框（带动画垂直位置）- 作为 Backdrop source 的兄弟 overlay
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .offset(y = targetSearchBoxY)
                .padding(horizontal = 16.dp),
            contentAlignment = Alignment.Center
        ) {
            val searchInteractionSource = remember { MutableInteractionSource() }
            NeumorphicFrostedSurface(
                modifier = Modifier
                    .fillMaxWidth(animatedWidthFraction.value)
                    .onGloballyPositioned { searchBoxBounds = it.boundsInRoot() },
                isDark = isDark,
                shape = RoundedCornerShape(32.dp),
                elevation = 14.dp,
                blurRadius = 28.dp,
                shadowOffset = 10.dp,
                backgroundColor = if (isDark) Color(0xFF222244).copy(alpha = 0.7f) else Color.White.copy(alpha = 0.65f),
                borderColor = if (isDark) Color.White.copy(alpha = 0.12f) else Color.White.copy(alpha = 0.8f),
                glassRole = GlassSurfaceRole.SearchField,
                scene = searchGlassScene,
                darkShadowAlpha = if (isDark) 0.65f else 0.28f,
                lightShadowAlpha = if (isDark) 0.12f else 0.9f,
                hazeState = hazeState,
                interactionSource = searchInteractionSource
            ) {
                Box(modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)) {
                    SearchBarTopNew(
                        searchQuery = searchQuery,
                        onQueryChange = {
                            interruptAiSprite(AiSpriteInterruptReason.USER_INPUT)
                            searchQuery = it
                        },
                        onSearch = {
                            interruptAiSprite(AiSpriteInterruptReason.USER_INPUT)
                            if (BuildConfig.DEBUG && searchQuery.trim() == "13638719007") {
                                onSpiderTest?.invoke()
                            } else {
                                viewModel.addTraktHistory(searchQuery, searchSourceType.name.lowercase())
                                onTraktSearch?.invoke(searchSourceType, searchQuery)
                            }
                            focusManager.clearFocus()
                        },
                        onClear = {
                            interruptAiSprite(AiSpriteInterruptReason.USER_INPUT)
                            searchQuery = ""
                        },
                        onBack = if (isActive) {
                            {
                                interruptAiSprite(AiSpriteInterruptReason.USER_INPUT)
                                onBack?.invoke()
                            }
                        } else null,
                        focusRequester = focusRequester,
                        onFocusChanged = {
                            if (it) interruptAiSprite(AiSpriteInterruptReason.FOCUS)
                            isSearchFocused = it
                        },
                        searchSourceType = searchSourceType,
                        onSearchSourceTypeChange = onSearchSourceTypeChange,
                        isDark = isDark,
                        view = view,
                        hazeState = hazeState,
                        interactionSource = searchInteractionSource,
                        scene = searchGlassScene
                    )
                }
            }
        }

        // 全屏彩蛋 Overlay
        CloudOverlay(
            easterEggRes = easterEggRes,
            messageRes = easterMessageRes,
            onDismiss = { cloudThemeManager.onEasterDismissed() }
        )

        AiSpriteMotion(
            characterId = spriteState.activatedCharacterId.orEmpty(),
            anchor = activeSpriteAnchor,
            anchorBounds = searchBoxBounds,
            visible = showAiSpriteMotion && !showAiSpriteCenter &&
                searchBoxBounds != null,
            onClick = {
                interruptAiSprite(AiSpriteInterruptReason.USER_INPUT)
                showAiSpriteCenter = true
            },
            onFinished = {
                showAiSpriteMotion = false
                activeSceneEvent = null
                lastInteractionAt = System.currentTimeMillis()
            },
            modifier = Modifier.zIndex(5f),
            sceneRes = activeSceneEvent?.let { sceneArtFor(it).drawableRes },
            interruptRequest = AiSpriteInterruptRequest(spriteInterruptRevision, spriteInterruptReason)
        )

        AiSpriteCenter(
            visible = showAiSpriteCenter,
            onDismiss = {
                interruptAiSprite(AiSpriteInterruptReason.NAVIGATION)
                showAiSpriteCenter = false
                spriteViewModel.closeFeature()
            },
            onNavigateToLogin = { onNavigateToLogin?.invoke() },
            onMovieClick = onMovieClick,
            onShowClick = onShowClick,
            onRecommendationClick = onRecommendationClick,
            viewModel = spriteViewModel
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchBarTop(
    searchQuery: String,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onClear: () -> Unit,
    onBack: (() -> Unit)?,
    focusRequester: FocusRequester,
    onFocusChanged: (Boolean) -> Unit,
    searchSourceType: SearchSourceType = SearchSourceType.DISK,
    onSearchSourceTypeChange: ((SearchSourceType) -> Unit)? = null
) {
    val context = LocalContext.current
    val view = LocalView.current
    val isDark = isAppDarkTheme()
    var showTypeDropdown by remember { mutableStateOf(false) }
    val typeColorMap = mapOf(
        SearchSourceType.DISK to Color(0xFF26A69A),    // Teal
        SearchSourceType.MOVIE to Color(0xFF7986CB),   // Indigo
        SearchSourceType.SHOW to Color(0xFFFFD54F),    // Amber
        SearchSourceType.PERSON to Color(0xFFF48FB1)   // Rose
    )
    // 搜索类型菜单：标签映射与固定宽度（最长文字 + 打勾 + 间隔 + 内边距，多语言适配）
    val orderedTypes = listOf(SearchSourceType.MOVIE, SearchSourceType.SHOW, SearchSourceType.PERSON, SearchSourceType.DISK)
    val typeLabelMap = orderedTypes.associateWith { type ->
        stringResource(
            when (type) {
                SearchSourceType.DISK -> R.string.search_type_disk
                SearchSourceType.MOVIE -> R.string.search_type_movie
                SearchSourceType.SHOW -> R.string.search_type_show
                SearchSourceType.PERSON -> R.string.search_type_person
            }
        )
    }
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val typeLabelStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp)
    val typeMenuWidth = with(density) {
        typeLabelMap.values.maxOf { textMeasurer.measure(AnnotatedString(it), typeLabelStyle).size.width }.toDp()
    } + 24.dp + 8.dp + 16.dp
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.search_back), tint = MaterialTheme.colorScheme.primary)
            }
            Spacer(modifier = Modifier.width(4.dp))
        }
        OutlinedTextField(
            value = searchQuery,
            onValueChange = onQueryChange,
            modifier = Modifier
                .weight(1f)
                .focusRequester(focusRequester)
                .onFocusChanged { focusState ->
                    onFocusChanged(focusState.isFocused)
                },
            placeholder = {
                val placeholderText = stringResource(
                    when (searchSourceType) {
                        SearchSourceType.MOVIE -> R.string.search_placeholder_movie
                        SearchSourceType.SHOW -> R.string.search_placeholder_show
                        SearchSourceType.PERSON -> R.string.search_placeholder_person
                        SearchSourceType.DISK -> R.string.search_placeholder
                    }
                )
                AdaptivePlaceholderText(
                    text = placeholderText,
                    color = if (isDark) Color.White.copy(alpha = 0.4f) else Color(0xFF90A4AE)
                )
            },
            leadingIcon = {
                if (onSearchSourceTypeChange != null) {
                    DropdownAnchorMenu(
                        expanded = showTypeDropdown,
                        onDismissRequest = { showTypeDropdown = false },
                        menuWidth = typeMenuWidth,
                        anchor = {
                            TextButton(
                                onClick = { showTypeDropdown = true },
                                contentPadding = PaddingValues(start = 8.dp, top = 0.dp, end = 4.dp, bottom = 0.dp),
                                modifier = Modifier.height(32.dp)
                            ) {
                                Text(
                                    text = typeLabelMap[searchSourceType] ?: "",
                                    fontSize = 15.sp,
                                    color = MaterialTheme.colorScheme.primary,
                                    maxLines = 1
                                )
                                Icon(
                                    Icons.Rounded.ArrowDropDown,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = typeColorMap[searchSourceType] ?: Color(0xFF4CAF50)
                                )
                            }
                        }
                    ) {
                        orderedTypes.forEach { type ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        view.performHaptic(HapticType.TICK)
                                        showTypeDropdown = false
                                        if (type != searchSourceType) {
                                            onSearchSourceTypeChange.invoke(type)
                                        }
                                    }
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = typeLabelMap[type] ?: "",
                                    style = typeLabelStyle,
                                    color = typeColorMap[type] ?: Color(0xFF4CAF50),
                                    modifier = Modifier.weight(1f)
                                )
                                if (type == searchSourceType) {
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Icon(
                                        Icons.Rounded.Check,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(24.dp),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                disabledContainerColor = Color.Transparent,
                errorContainerColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                disabledIndicatorColor = Color.Transparent,
                errorIndicatorColor = Color.Transparent,
                focusedTextColor = MaterialTheme.colorScheme.onBackground,
                unfocusedTextColor = MaterialTheme.colorScheme.onBackground
            ),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSearch() }),
            trailingIcon = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(end = 4.dp)
                ) {
                    if (searchQuery.isEmpty()) {
                        Icon(
                            Icons.Rounded.Search,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        IconButton(
                            onClick = onClear,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                Icons.Rounded.Close,
                                contentDescription = stringResource(R.string.search_clear_input),
                                modifier = Modifier.size(18.dp),
                                tint = if (isDark) Color.White.copy(alpha = 0.72f) else Color(0xFF546E7A)
                            )
                        }
                        TextButton(
                            onClick = { view.performHaptic(HapticType.CLICK); onSearch() },
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                            modifier = Modifier.height(32.dp)
                        ) {
                            Text(stringResource(R.string.search_button))
                        }
                    }
                }
            }
        )
        // 当有返回按钮时，右侧添加等宽 Spacer 保持左右边距一致
        if (onBack != null) {
            Spacer(modifier = Modifier.width(44.dp))
        }
    }
}

@Composable
private fun SearchHistoryInline(
    history: List<SearchHistoryItem>,
    onHistoryClick: (SearchHistoryItem) -> Unit,
    onHistoryDelete: (SearchHistoryItem) -> Unit,
    onClearAll: () -> Unit,
    selectedKeyword: String? = null,
    isDark: Boolean = false
) {
    val typeColorMap = mapOf(
        "disk" to Color(0xFF26A69A),
        "movie" to Color(0xFF7986CB),
        "show" to Color(0xFFFFD54F),
        "person" to Color(0xFFF48FB1)
    )
    val typeNameMap = mapOf(
        "disk" to stringResource(R.string.search_type_disk),
        "movie" to stringResource(R.string.search_type_movie),
        "show" to stringResource(R.string.search_type_show),
        "person" to stringResource(R.string.search_type_person)
    )
    Column(
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.search_history_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            TextButton(onClick = onClearAll, contentPadding = PaddingValues(0.dp)) {
                Text(
                    text = stringResource(R.string.search_history_clear_all),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(history, key = { "${it.type}_${it.keyword}" }) { item ->
                val tagColor = typeColorMap[item.type] ?: Color(0xFF4CAF50)
                val isSelected = item.keyword == selectedKeyword
                val interactionSource = remember { MutableInteractionSource() }
                val isPressed by interactionSource.collectIsPressedAsState()
                val scale by animateFloatAsState(
                    targetValue = if (isPressed) 0.96f else 1f,
                    label = "history_chip_scale"
                )
                val bgColor = if (isSelected) {
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.12f)
                } else {
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                }
                val borderColor = if (isSelected) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
                } else {
                    MaterialTheme.colorScheme.outline.copy(alpha = 0.1f)
                }
                val textColor = if (isSelected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                }
                val iconTint = if (isSelected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
                val shadowColor = if (isSelected) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                } else {
                    Color.Black.copy(alpha = 0.1f)
                }
                Box(
                    modifier = Modifier
                        .scale(scale)
                        .shadow(
                            elevation = if (isDark) 4.dp else 2.dp,
                            shape = DesignToken.Tag,
                            ambientColor = if (isSelected) shadowColor else Color.Black.copy(alpha = if (isDark) 0.25f else 0.08f),
                            spotColor = if (isSelected) shadowColor else Color.Black.copy(alpha = if (isDark) 0.20f else 0.06f),
                        )
                        .border(1.dp, borderColor, DesignToken.Tag)
                        .clip(DesignToken.Tag)
                        .background(color = bgColor)
                        .clickable(
                            interactionSource = interactionSource,
                            indication = null,
                            onClick = { onHistoryClick(item) }
                        )
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Rounded.History,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = iconTint
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        Text(
                            text = typeNameMap[item.type] ?: item.type,
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                            color = if (isSelected) MaterialTheme.colorScheme.primary else tagColor,
                            maxLines = 1
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        Text(
                            text = item.keyword,
                            style = MaterialTheme.typography.bodySmall,
                            color = textColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        IconButton(
                            onClick = { onHistoryDelete(item) },
                            modifier = Modifier.size(18.dp)
                        ) {
                            Icon(
                                Icons.Rounded.Close,
                                contentDescription = stringResource(R.string.search_history_delete),
                                modifier = Modifier.size(14.dp),
                                tint = iconTint
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchBarTopNew(
    searchQuery: String,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onClear: () -> Unit,
    onBack: (() -> Unit)?,
    focusRequester: FocusRequester,
    onFocusChanged: (Boolean) -> Unit,
    searchSourceType: SearchSourceType = SearchSourceType.DISK,
    onSearchSourceTypeChange: ((SearchSourceType) -> Unit)? = null,
    isDark: Boolean = false,
    view: android.view.View,
    hazeState: HazeState,
    interactionSource: MutableInteractionSource,
    scene: GlassScene
) {
    var showTypeDropdown by remember { mutableStateOf(false) }
    val typeColorMap = mapOf(
        SearchSourceType.DISK to Color(0xFF26A69A),
        SearchSourceType.MOVIE to Color(0xFF7986CB),
        SearchSourceType.SHOW to Color(0xFFFFD54F),
        SearchSourceType.PERSON to Color(0xFFF48FB1)
    )
    // 搜索类型菜单：标签映射与固定宽度（最长文字 + 打勾 + 间隔 + 内边距，多语言适配）
    val orderedTypes = listOf(SearchSourceType.MOVIE, SearchSourceType.SHOW, SearchSourceType.PERSON, SearchSourceType.DISK)
    val typeLabelMap = orderedTypes.associateWith { type ->
        stringResource(
            when (type) {
                SearchSourceType.DISK -> R.string.search_type_disk
                SearchSourceType.MOVIE -> R.string.search_type_movie
                SearchSourceType.SHOW -> R.string.search_type_show
                SearchSourceType.PERSON -> R.string.search_type_person
            }
        )
    }
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val typeLabelStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp)
    val typeMenuWidth = with(density) {
        typeLabelMap.values.maxOf { textMeasurer.measure(AnnotatedString(it), typeLabelStyle).size.width }.toDp()
    } + 24.dp + 8.dp + 16.dp
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.search_back), tint = if (isDark) Color(0xFF9FA8DA) else Color(0xFF5C6BC0))
            }
            Spacer(modifier = Modifier.width(2.dp))
        }
        OutlinedTextField(
            value = searchQuery,
            onValueChange = onQueryChange,
            modifier = Modifier
                .weight(1f)
                .focusRequester(focusRequester)
                .onFocusChanged { focusState ->
                    onFocusChanged(focusState.isFocused)
                },
            interactionSource = interactionSource,
            placeholder = {
                val placeholderText = stringResource(
                    when (searchSourceType) {
                        SearchSourceType.MOVIE -> R.string.search_placeholder_movie
                        SearchSourceType.SHOW -> R.string.search_placeholder_show
                        SearchSourceType.PERSON -> R.string.search_placeholder_person
                        SearchSourceType.DISK -> R.string.search_placeholder
                    }
                )
                AdaptivePlaceholderText(
                    text = placeholderText,
                    color = if (isDark) Color.White.copy(alpha = 0.4f) else Color(0xFF90A4AE)
                )
            },
            leadingIcon = {
                if (onSearchSourceTypeChange != null) {
                    DropdownAnchorMenu(
                        expanded = showTypeDropdown,
                        onDismissRequest = { showTypeDropdown = false },
                        menuWidth = typeMenuWidth,
                        anchor = {
                            TextButton(
                                onClick = { showTypeDropdown = true },
                                contentPadding = PaddingValues(start = 2.dp, top = 0.dp, end = 2.dp, bottom = 0.dp),
                                modifier = Modifier.height(36.dp)
                            ) {
                                Text(
                                    text = typeLabelMap[searchSourceType] ?: "",
                                    fontSize = 15.sp,
                                    color = if (isDark) Color(0xFF9FA8DA) else Color(0xFF5C6BC0),
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1
                                )
                                Icon(
                                    Icons.Rounded.ArrowDropDown,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = typeColorMap[searchSourceType] ?: Color(0xFF4CAF50)
                                )
                            }
                        }
                    ) {
                        orderedTypes.forEach { type ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        view.performHaptic(HapticType.TICK)
                                        showTypeDropdown = false
                                        if (type != searchSourceType) {
                                            onSearchSourceTypeChange.invoke(type)
                                        }
                                    }
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = typeLabelMap[type] ?: "",
                                    style = typeLabelStyle,
                                    color = typeColorMap[type] ?: Color(0xFF4CAF50),
                                    modifier = Modifier.weight(1f)
                                )
                                if (type == searchSourceType) {
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Icon(
                                        Icons.Rounded.Check,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(24.dp),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                disabledContainerColor = Color.Transparent,
                errorContainerColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                disabledIndicatorColor = Color.Transparent,
                errorIndicatorColor = Color.Transparent,
                focusedTextColor = if (isDark) Color.White else Color(0xFF263238),
                unfocusedTextColor = if (isDark) Color.White else Color(0xFF263238),
                cursorColor = if (isDark) Color(0xFF9FA8DA) else Color(0xFF5C6BC0)
            ),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSearch() }),
            trailingIcon = {
                if (searchQuery.isEmpty()) {
                    SearchActionButton(
                        onClick = { view.performHaptic(HapticType.CLICK); onSearch() },
                        size = 44.dp,
                        iconSize = 20.dp,
                        isDark = isDark,
                        hazeState = hazeState,
                        scene = scene,
                        modifier = Modifier.padding(end = 2.dp)
                    )
                } else {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(end = 2.dp)
                    ) {
                        IconButton(
                            onClick = onClear,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                Icons.Rounded.Close,
                                contentDescription = stringResource(R.string.search_clear_input),
                                modifier = Modifier.size(18.dp),
                                tint = if (isDark) Color.White.copy(alpha = 0.72f) else Color(0xFF546E7A)
                            )
                        }
                        SearchActionButton(
                            onClick = { view.performHaptic(HapticType.CLICK); onSearch() },
                            size = 40.dp,
                            iconSize = 18.dp,
                            isDark = isDark,
                            hazeState = hazeState,
                            scene = scene
                        )
                    }
                }
            }
        )
        if (onBack != null) {
            Spacer(modifier = Modifier.width(44.dp))
        }
    }
}

/**
 * C方案搜索按钮：圆形拟态阴影（右下暗投影 + 左上高光）
 */
@Composable
private fun SearchActionButton(
    onClick: () -> Unit,
    size: Dp,
    iconSize: Dp,
    isDark: Boolean,
    hazeState: HazeState,
    scene: GlassScene,
    modifier: Modifier = Modifier
) {
    if (LocalVisualEffectMode.current == VisualEffectMode.GLASS) {
        GlassIconButton(
            onClick = onClick,
            modifier = modifier,
            size = size,
            hazeState = hazeState,
            role = GlassSurfaceRole.CircularControl,
            scene = scene
        ) {
            Icon(
                Icons.Rounded.Search,
                contentDescription = null,
                modifier = Modifier.size(iconSize),
                tint = MaterialTheme.colorScheme.primary
            )
        }
        return
    }

    Box(
        modifier = modifier
            .size(size)
            .neumorphicShadow(
                shape = CircleShape,
                isDark = isDark,
                elevation = 6.dp,
                darkAlpha = if (isDark) 0.45f else 0.55f,
                lightAlpha = if (isDark) 0.10f else 0.70f,
                blurRadius = 14.dp,
                shadowOffset = 6.dp,
                darkColor = Color(0xFF3C50A0),
                lightColor = Color.White
            )
            .clip(CircleShape)
            .background(
                Brush.linearGradient(
                    0.0f to MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                    1.0f to MaterialTheme.colorScheme.primary
                )
            )
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            Icons.Rounded.Search,
            contentDescription = null,
            modifier = Modifier.size(iconSize),
            tint = Color.White
        )
    }
}

@Composable
private fun NeumorphicChip(
    onClick: () -> Unit,
    isSelected: Boolean,
    isDark: Boolean,
    hazeState: HazeState,
    scene: GlassScene,
    blurRadius: Dp = 10.dp,
    shadowOffset: Dp = 3.dp,
    content: @Composable () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.96f else 1f,
        label = "chip_scale"
    )
    val bgColor = if (isSelected) {
        if (isDark) Color(0xFF3949AB).copy(alpha = 0.25f) else Color(0xFF5C6BC0).copy(alpha = 0.12f)
    } else {
        if (isDark) Color(0xFF222244).copy(alpha = 0.55f) else Color.White.copy(alpha = 0.70f)
    }
    val borderColor = if (isSelected) {
        if (isDark) Color(0xFF5C6BC0).copy(alpha = 0.4f) else Color(0xFF5C6BC0).copy(alpha = 0.25f)
    } else {
        if (isDark) Color.White.copy(alpha = 0.12f) else Color(0xFFD0D5DC).copy(alpha = 0.85f)
    }
    val elevation = 5.dp
    val chipShape = RoundedCornerShape(22.dp)

    if (LocalVisualEffectMode.current == VisualEffectMode.GLASS) {
        AppVisualSurface(
            kind = VisualSurfaceKind.Glass,
            modifier = Modifier
                .scale(scale)
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onClick
                ),
            shape = chipShape,
            hazeState = hazeState,
            role = GlassSurfaceRole.SearchField,
            interactionSource = interactionSource,
            scene = scene,
            backgroundColor = bgColor,
            borderColor = borderColor,
            content = content
        )
    } else {
        Box(
            modifier = Modifier
                .scale(scale)
                .neumorphicShadow(
                    shape = chipShape,
                    isDark = isDark,
                    elevation = elevation,
                    darkAlpha = if (isDark) 0.30f else 0.18f,
                    lightAlpha = if (isDark) 0.06f else 0.65f,
                    blurRadius = blurRadius,
                    shadowOffset = shadowOffset
                )
                .clip(chipShape)
                .background(bgColor, chipShape)
                .border(1.dp, borderColor, chipShape)
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onClick
                )
        ) {
            GlassHighlight(isDark = isDark, shape = chipShape)
            content()
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun SearchHistoryTwoRow(
    history: List<SearchHistoryItem>,
    onHistoryClick: (SearchHistoryItem) -> Unit,
    onHistoryDelete: (SearchHistoryItem) -> Unit,
    onClearAll: () -> Unit,
    selectedKeyword: String? = null,
    isDark: Boolean = false,
    hazeState: HazeState,
    scene: GlassScene
) {
    val typeColorMap = mapOf(
        "disk" to Color(0xFF26A69A),
        "movie" to Color(0xFF7986CB),
        "show" to Color(0xFFFFD54F),
        "person" to Color(0xFFF48FB1)
    )
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.search_history_title),
                style = MaterialTheme.typography.titleSmall,
                color = if (isDark) Color.White else Color(0xFF37474F),
                fontWeight = FontWeight.SemiBold
            )
            TextButton(onClick = onClearAll, contentPadding = PaddingValues(0.dp)) {
                Text(
                    text = stringResource(R.string.search_history_clear_all),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            history.forEach { item ->
                val tagColor = typeColorMap[item.type] ?: Color(0xFF4CAF50)
                val isSelected = item.keyword == selectedKeyword
                val textColor = if (isSelected) {
                    if (isDark) Color(0xFF9FA8DA) else Color(0xFF5C6BC0)
                } else {
                    if (isDark) Color.White.copy(alpha = 0.85f) else Color(0xFF455A64)
                }
                val iconTint = if (isSelected) {
                    if (isDark) Color(0xFF9FA8DA) else Color(0xFF5C6BC0)
                } else {
                    if (isDark) Color.White.copy(alpha = 0.5f) else Color(0xFF78909C)
                }
                NeumorphicChip(
                    onClick = { onHistoryClick(item) },
                    isSelected = isSelected,
                    isDark = isDark,
                    hazeState = hazeState,
                    scene = scene
                ) {
                    Row(
                        modifier = Modifier.padding(start = 15.dp, top = 9.dp, end = 10.dp, bottom = 9.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Rounded.History,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = iconTint
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = when (item.type) {
                                "disk" -> stringResource(R.string.search_type_disk)
                                "movie" -> stringResource(R.string.search_type_movie)
                                "show" -> stringResource(R.string.search_type_show)
                                "person" -> stringResource(R.string.search_type_person)
                                else -> item.type
                            },
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                            color = if (isSelected) (if (isDark) Color(0xFF9FA8DA) else Color(0xFF5C6BC0)) else tagColor,
                            maxLines = 1,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = item.keyword,
                            style = MaterialTheme.typography.bodySmall,
                            color = textColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.width(2.dp))
                        Box(
                            modifier = Modifier
                                .size(16.dp)
                                .clickable { onHistoryDelete(item) },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Rounded.Close,
                                contentDescription = stringResource(R.string.search_history_delete),
                                modifier = Modifier.size(14.dp),
                                tint = iconTint
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PopularSearchesSectionNew(
    popularSearches: List<String>,
    onPopularClick: (String) -> Unit,
    selectedKeyword: String? = null,
    isDark: Boolean = false,
    hazeState: HazeState,
    scene: GlassScene
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.hot_search),
            style = MaterialTheme.typography.titleSmall,
            color = if (isDark) Color.White else Color(0xFF37474F),
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(bottom = 10.dp)
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            popularSearches.forEach { keyword ->
                val isSelected = keyword == selectedKeyword
                val textColor = if (isSelected) {
                    if (isDark) Color(0xFF9FA8DA) else Color(0xFF5C6BC0)
                } else {
                    if (isDark) Color.White.copy(alpha = 0.85f) else Color(0xFF455A64)
                }
                val iconTint = if (isSelected) {
                    if (isDark) Color(0xFF9FA8DA) else Color(0xFF5C6BC0)
                } else {
                    if (isDark) Color.White.copy(alpha = 0.5f) else Color(0xFF78909C)
                }
                NeumorphicChip(
                    onClick = { onPopularClick(keyword) },
                    isSelected = isSelected,
                    isDark = isDark,
                    hazeState = hazeState,
                    scene = scene
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 17.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Rounded.Search,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = iconTint
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = keyword,
                            color = textColor,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchSuggestionsInline(
    suggestions: List<SearchHistoryItem>,
    onSuggestionClick: (SearchHistoryItem) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
    ) {
        Text(
            text = stringResource(R.string.search_suggestions_title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp)
        )
        suggestions.forEach { item ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSuggestionClick(item) }
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Rounded.Search,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = item.keyword,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                    maxLines = 1
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PopularSearchesSection(
    popularSearches: List<String>,
    onPopularClick: (String) -> Unit,
    selectedKeyword: String? = null,
    isDark: Boolean = false
) {
    Column(
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = stringResource(R.string.hot_search),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            popularSearches.forEach { keyword ->
                val isSelected = keyword == selectedKeyword
                val interactionSource = remember { MutableInteractionSource() }
                val isPressed by interactionSource.collectIsPressedAsState()
                val scale by animateFloatAsState(
                    targetValue = if (isPressed) 0.96f else 1f,
                    label = "popular_chip_scale"
                )
                val bgColor = if (isSelected) {
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.12f)
                } else {
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                }
                val borderColor = if (isSelected) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
                } else {
                    MaterialTheme.colorScheme.outline.copy(alpha = 0.1f)
                }
                val textColor = if (isSelected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                }
                val iconTint = if (isSelected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
                val shadowColor = if (isSelected) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                } else {
                    Color.Black.copy(alpha = 0.1f)
                }
                Box(
                    modifier = Modifier
                        .scale(scale)
                        .shadow(
                            elevation = if (isDark) 4.dp else 2.dp,
                            shape = DesignToken.Tag,
                            ambientColor = if (isSelected) shadowColor else Color.Black.copy(alpha = if (isDark) 0.25f else 0.08f),
                            spotColor = if (isSelected) shadowColor else Color.Black.copy(alpha = if (isDark) 0.20f else 0.06f),
                        )
                        .border(1.dp, borderColor, DesignToken.Tag)
                        .clip(DesignToken.Tag)
                        .background(color = bgColor)
                        .clickable(
                            interactionSource = interactionSource,
                            indication = null,
                            onClick = { onPopularClick(keyword) }
                        )
                ) {
                    // 顶部高光层
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .background(
                                Brush.verticalGradient(
                                    0.0f to Color.White.copy(alpha = if (isDark) 0.06f else 0.12f),
                                    0.3f to Color.White.copy(alpha = if (isDark) 0.02f else 0.04f),
                                    1.0f to Color.Transparent,
                                )
                            )
                    )
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Rounded.Search,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = iconTint
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        Text(
                            text = keyword,
                            color = textColor,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}

// ========== 豆瓣热榜组件（供 DiscoverScreen 复用） ==========

/** 去除标题开头排名 #1 前缀（评分不再内嵌标题，直接用接口 rating 字段）。 */
private val DOUBAN_RANK_PREFIX = Pattern.compile("^#\\d+\\s*")

/** 解析接口评分字段："暂无评分"/空白返回 null，其余转 Double。 */
private fun parseDoubanRating(rating: String): Double? =
    rating.takeIf { it.isNotBlank() && it != "暂无评分" }?.toDoubleOrNull()

/** 清理展示标题：仅去除排名前缀（评分由 [parseDoubanRating] 单独从接口字段解析）。 */
private fun cleanDoubanTitle(title: String): String =
    DOUBAN_RANK_PREFIX.matcher(title).replaceAll("")

@Composable
fun DoubanHotCategorySection(
    category: DoubanHotCategory,
    resolvingItemId: Int?,
    onItemClick: (DoubanHotItem) -> Unit,
    onViewAll: () -> Unit,
    onRetry: () -> Unit
) {
    Column {
        // 榜单标题 + 全部按钮
        com.tracktosearch.ui.component.SectionHeader(
            title = doubanCategoryLabel(category.id),
            actionText = stringResource(R.string.search_view_all_count, doubanCategoryTotal(category.id)),
            onActionClick = onViewAll
        )

        if (category.isLoading) {
            androidx.compose.foundation.lazy.LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(5) {
                    com.tracktosearch.ui.component.DoubanHotCardSkeleton()
                }
            }
        } else if (category.error != null) {
            com.tracktosearch.ui.screen.discover.ErrorRetryRow(
                error = category.error,
                onRetry = onRetry
            )
        } else {
            androidx.compose.foundation.lazy.LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                itemsIndexed(category.items, key = { _, item -> item.id ?: item.title }) { index, item ->
                    Box(modifier = Modifier.fadeSlideIn(index)) {
                        DoubanHotCard(
                            item = item,
                            isResolving = resolvingItemId == item.id,
                            onClick = { onItemClick(item) }
                        )
                    }
                }
                // Top250 保留箭头卡片
                if (category.id == "douban-top250") {
                    item {
                        Box(modifier = Modifier.fadeSlideIn(category.items.size)) {
                            Card(
                                modifier = Modifier
                                    .width(40.dp)
                                    .height(172.dp)
                                    .clickable { onViewAll() },
                                shape = RoundedCornerShape(14.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                                ),
                                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                            ) {
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        Icons.AutoMirrored.Rounded.ArrowForwardIos,
                                        contentDescription = stringResource(R.string.content_desc_view_all),
                                        modifier = Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DoubanHotCard(
    item: DoubanHotItem,
    isResolving: Boolean = false,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    // 评分直接用接口返回的 rating 字段；标题仅去除排名前缀（不再从标题正则提取评分）
    val rating = remember(item.rating) { parseDoubanRating(item.rating) }
    val displayTitle = remember(item.title) { cleanDoubanTitle(item.title) }

    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.96f else 1f,
        label = "douban_hot_card_scale"
    )

    Column(
        modifier = Modifier
            .width(105.dp)
            .scale(scale)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .shadow(8.dp, RoundedCornerShape(14.dp))
                .clip(RoundedCornerShape(14.dp))
                .clickable(
                    enabled = !isResolving,
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onClick
                )
        ) {
            if (!item.cover.isNullOrBlank()) {
                val imageRequest = remember(item.cover) {
                    coil.request.ImageRequest.Builder(context)
                        .data(item.cover)
                        .size(200)
                        .crossfade(false)
                        .build()
                }
                coil.compose.AsyncImage(
                    model = imageRequest,
                    contentDescription = displayTitle,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Rounded.Search,
                        contentDescription = null,
                        modifier = Modifier.size(28.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                    )
                }
            }
            if (rating != null) {
                DoubanRatingBadge(
                    rating = rating,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(2.dp)
                )
            }
            if (isResolving) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.4f)),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = Color.White
                    )
                }
            }
        }
        Column(modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp)) {
            AdaptiveTwoLineTitle(
                text = displayTitle,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onBackground
                ),
                maxFontSize = 12.sp,
                minFontSize = 12.sp
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DoubanHotAllSheet(
    category: DoubanHotCategory,
    resolvingItemId: Int?,
    onItemClick: (DoubanHotItem) -> Unit,
    onLoadMore: () -> Unit,
    onDismiss: () -> Unit
) {
    DiscoverModalBottomSheet(
        onDismissRequest = onDismiss
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = doubanCategoryLabel(category.id),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = stringResource(R.string.common_close)
                    )
                }
            }

            androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier.fillMaxHeight(0.8f),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                gridItems(category.items, key = { it.id ?: it.title }) { item ->
                    DoubanHotGridItem(
                        item = item,
                        isResolving = resolvingItemId == item.id,
                        onClick = {
                            onItemClick(item)
                            onDismiss()
                        }
                    )
                }
                if (category.hasMore) {
                    item(span = { GridItemSpan(3) }) {
                        LaunchedEffect(category.currentPage) { onLoadMore() }
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 12.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DoubanHotGridItem(
    item: DoubanHotItem,
    isResolving: Boolean = false,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    // 评分直接用接口返回的 rating 字段；标题仅去除排名前缀（不再从标题正则提取评分）
    val rating = remember(item.rating) { parseDoubanRating(item.rating) }
    val displayTitle = remember(item.title) { cleanDoubanTitle(item.title) }

    Card(
        onClick = { onClick() },
        enabled = !isResolving,
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(2f / 3f)
                    .clip(RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp))
            ) {
                if (!item.cover.isNullOrBlank()) {
                    val imageRequest = remember(item.cover) {
                        coil.request.ImageRequest.Builder(context)
                            .data(item.cover)
                            .size(200)
                            .crossfade(false)
                            .build()
                    }
                    coil.compose.AsyncImage(
                        model = imageRequest,
                        contentDescription = displayTitle,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop
                    )
                } else {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Rounded.Search,
                            contentDescription = null,
                            modifier = Modifier.size(28.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                        )
                    }
                }
                if (rating != null) {
                    DoubanRatingBadge(
                        rating = rating,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(2.dp)
                    )
                }
                if (isResolving) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.4f)),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = Color.White
                        )
                    }
                }
            }
            Column(modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)) {
                AdaptiveTwoLineTitle(
                    text = displayTitle,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                    maxFontSize = 12.sp,
                    minFontSize = 12.sp
                )
                if (item.desc.isNotBlank()) {
                    Text(
                        text = item.desc,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 1.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun doubanCategoryLabel(categoryId: String): String = when (categoryId) {
    "douban-movie" -> stringResource(R.string.discover_douban_new_movies)
    "douban-weekly" -> stringResource(R.string.discover_douban_weekly)
    "douban-top250" -> stringResource(R.string.discover_douban_top250)
    "douban-nowplaying" -> stringResource(R.string.discover_douban_nowplaying)
    else -> categoryId
}

private fun doubanCategoryTotal(categoryId: String): Int = when (categoryId) {
    "douban-top250" -> 250
    else -> 10
}

private fun getLastKnownLocation(context: android.content.Context): Location? {
    val lm = context.getSystemService(android.content.Context.LOCATION_SERVICE) as? LocationManager ?: return null
    return try {
        // 优先网络定位（粗略），再 GPS
        lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            ?: lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
    } catch (_: SecurityException) {
        null
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CloudIconWithAnimation(
    cloudThemeManager: CloudThemeManager,
    isActive: Boolean,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val hasPermission by cloudThemeManager.hasLocationPermission.collectAsStateWithLifecycle()
    AnimatedVisibility(
        visible = !isActive,
        enter = fadeIn(animationSpec = tween(300)) + expandVertically(animationSpec = tween(300)),
        exit = fadeOut(animationSpec = tween(200)) + shrinkVertically(animationSpec = tween(200)),
        modifier = modifier
    ) {
        if (hasPermission) {
            CloudEasterEgg(
                themeManager = cloudThemeManager,
                modifier = Modifier.fillMaxSize(),
                onLongClick = onLongClick
            )
        } else {
            Image(
                painter = painterResource(R.drawable.ic_search_cloud),
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .combinedClickable(
                        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                        indication = null,
                        onClick = { cloudThemeManager.onCloudClicked() },
                        onLongClick = onLongClick
                    )
            )
        }
    }
}

/**
 * 自适应字号的单行文字。
 *
 * 用于搜索框 placeholder：不同搜索类型对应不同长度的文案，
 * 长文案（如 "Enter show title to search TV shows"）在窄屏上会溢出搜索框被截断。
 * 用 [rememberTextMeasurer] 在可用宽度内二分查找最大可放下的字号，
 * 保证文字完整显示。短文案使用默认字号不受影响。
 *
 * @param text 待显示文字
 * @param color 文字颜色
 * @param maxFontSize 最大字号（默认 16sp，与搜索框文字一致）
 * @param minFontSize 最小字号（低于此字号会不可读，默认 10sp）
 */
@Composable
private fun AdaptivePlaceholderText(
    text: String,
    color: Color,
    maxFontSize: androidx.compose.ui.unit.TextUnit = 16.sp,
    minFontSize: androidx.compose.ui.unit.TextUnit = 10.sp
) {
    val textMeasurer = rememberTextMeasurer()
    BoxWithConstraints {
        val maxWidthPx = constraints.maxWidth.toFloat()
        // 宽度未确定（=0）时直接用最大字号，避免初始帧误判
        if (maxWidthPx <= 0f) {
            Text(
                text = text,
                color = color,
                fontSize = maxFontSize,
                maxLines = 1,
                overflow = TextOverflow.Visible
            )
            return@BoxWithConstraints
        }
        // 二分查找：找到文字在 maxWidthPx 内能完整显示的最大字号
        var lo = minFontSize.value
        var hi = maxFontSize.value
        var best = lo
        // 0.5sp 步长足够细
        while (lo <= hi) {
            val mid = (lo + hi) / 2f
            val style = TextStyle(fontSize = mid.sp, color = color)
            val result = textMeasurer.measure(
                text = text,
                style = style,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Visible
            )
            if (result.size.width <= maxWidthPx) {
                best = mid
                lo = mid + 0.5f
            } else {
                hi = mid - 0.5f
            }
        }
        Text(
            text = text,
            color = color,
            fontSize = best.sp,
            maxLines = 1,
            overflow = TextOverflow.Visible
        )
    }
}

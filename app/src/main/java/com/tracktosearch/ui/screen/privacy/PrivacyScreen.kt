package com.tracktosearch.ui.screen.privacy

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.view.View
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tracktosearch.R
import com.tracktosearch.ui.component.GlassSurfaceRole
import com.tracktosearch.ui.component.NeumorphicFrostedSurface
import com.tracktosearch.ui.component.hasListScrolled
import com.tracktosearch.ui.component.hazeTopBar
import com.tracktosearch.ui.component.isAppDarkTheme
import com.tracktosearch.ui.screen.settings.GroupDivider
import com.tracktosearch.ui.screen.settings.settingsIconContainerColor
import com.tracktosearch.ui.theme.LocalVisualEffectMode
import com.tracktosearch.ui.theme.VisualEffectMode
import com.tracktosearch.ui.theme.appSwitchColors
import com.tracktosearch.ui.util.HapticType
import com.tracktosearch.ui.util.performHaptic
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.materials.HazeMaterials
import dev.chrisbanes.haze.hazeSource

/** 权利请求页地址（CustomTabs 打开），页面私有常量避免散落 */
private const val RIGHTS_URL = "https://tracktosearch.pages.dev/rights.html"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrivacyScreen(
    onBack: () -> Unit,
    viewModel: PrivacyViewModel = hiltViewModel()
) {
    val hazeState = remember { HazeState() }
    // HazeMaterials.thin() 读 colorScheme，是 @Composable 函数，不能 remember 缓存
    val hazeStyle = HazeMaterials.thin()
    val lazyListState = rememberLazyListState()
    // 静止时列表未位移、栏下无内容，顶栏保持全透明；滚动后再启用模糊/玻璃
    val hasContentUnderTopBar by remember {
        derivedStateOf {
            hasListScrolled(
                firstVisibleItemIndex = lazyListState.firstVisibleItemIndex,
                firstVisibleItemScrollOffsetPx = lazyListState.firstVisibleItemScrollOffset
            )
        }
    }
    val aiTasteEnabled by viewModel.aiTasteEnabled.collectAsStateWithLifecycle()
    val crashLogEnabled by viewModel.crashLogEnabled.collectAsStateWithLifecycle()
    val view = LocalView.current

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            val statusBarHeight = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
            LazyColumn(
                state = lazyListState,
                modifier = Modifier
                    .fillMaxSize()
                    .hazeSource(state = hazeState),
                contentPadding = PaddingValues(
                    top = 64.dp + statusBarHeight,
                    bottom = 80.dp
                )
            ) {
                // 首屏摘要：先给出数据边界，再进入可操作开关
                item {
                    PrivacySummaryCard()
                }

                // 区块 A：数据开关（AI taste / 崩溃上报 / 位置授权）
                item {
                    PrivacySectionCard(
                        title = stringResource(R.string.privacy_section_switches),
                        hazeState = hazeState
                    ) {
                        PrivacySwitchRow(
                            icon = Icons.Rounded.AutoAwesome,
                            iconTint = MaterialTheme.colorScheme.primary,
                            title = stringResource(R.string.ai_feature_taste),
                            subtitle = stringResource(R.string.settings_ai_taste_subtitle),
                            checked = aiTasteEnabled,
                            view = view,
                            onToggle = { viewModel.setAiTasteEnabled(it) }
                        )
                        GroupDivider()
                        PrivacySwitchRow(
                            icon = Icons.Rounded.BugReport,
                            iconTint = MaterialTheme.colorScheme.error,
                            title = stringResource(R.string.settings_crash_log_title),
                            subtitle = stringResource(R.string.settings_crash_log_subtitle),
                            checked = crashLogEnabled,
                            view = view,
                            onToggle = { viewModel.setCrashLogEnabled(it) }
                        )
                        GroupDivider()
                        PrivacyLocationRow()
                    }
                }

                // 区块 B：隐私说明（本地/网络/技术细节折叠）
                item {
                    PrivacySectionCard(
                        title = stringResource(R.string.privacy_section_statement),
                        hazeState = hazeState
                    ) {
                        PrivacyStatementContent()
                    }
                }

                // 区块 C：使用与权利（5 节说明 + 权利请求卡片）
                item {
                    PrivacySectionCard(
                        title = stringResource(R.string.privacy_section_legal),
                        hazeState = hazeState
                    ) {
                        PrivacyLegalContent()
                    }
                }

                // 页脚
                item {
                    Text(
                        text = stringResource(R.string.privacy_footer),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 16.dp)
                    )
                }
            }

            // 毛玻璃吸顶标题栏（与帮助页一致）
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .hazeTopBar(
                        state = hazeState,
                        style = hazeStyle,
                        blurRadius = 24.dp,
                        isContentUnderTopBar = hasContentUnderTopBar
                    )
                    // 拦截点击：顶栏覆盖可滚动列表，不消费会让点击穿透到下方列表项
                    .clickable(enabled = false, onClick = {})
            ) {
                Spacer(modifier = Modifier.statusBarsPadding())
                TopAppBar(
                    title = {
                        Text(
                            text = stringResource(R.string.privacy_title),
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                                contentDescription = stringResource(R.string.content_desc_back),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        // 底色交给外层 hazeTopBar；M3 默认容器色会盖死毛玻璃
                        containerColor = Color.Transparent
                    ),
                    windowInsets = WindowInsets(0, 0, 0, 0)
                )
            }
        }
    }
}

/** 首屏数据边界摘要：用一张轻量卡片建立阅读预期。 */
@Composable
private fun PrivacySummaryCard() {
    val isDark = isAppDarkTheme()
    val accent = MaterialTheme.colorScheme.primary
    Surface(
        color = accent.copy(alpha = if (isDark) 0.14f else 0.09f),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, accent.copy(alpha = if (isDark) 0.22f else 0.16f)),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = stringResource(R.string.privacy_hero_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.privacy_hero_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                lineHeight = MaterialTheme.typography.bodyMedium.lineHeight * 1.3f,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                PrivacyBadgePill(
                    icon = Icons.Rounded.Shield,
                    text = stringResource(R.string.privacy_badge_b1)
                )
                Spacer(modifier = Modifier.width(8.dp))
                PrivacyBadgePill(
                    icon = Icons.Rounded.VisibilityOff,
                    text = stringResource(R.string.privacy_badge_b2)
                )
            }
        }
    }
}

/**
 * 隐私页分组卡片：与设置页 SettingsGroupCard 同款玻璃拟态容器
 * （分组标题 13sp + NeumorphicFrostedSurface 圆角 20dp），内容交给调用方组织。
 */
@Composable
private fun PrivacySectionCard(
    title: String,
    hazeState: HazeState?,
    content: @Composable ColumnScope.() -> Unit
) {
    val isDark = isAppDarkTheme()
    // BLUR 模式列表卡片不再各自开一层离屏做真模糊，改用更实的填充；GLASS 模式不变。
    val realBlur = LocalVisualEffectMode.current == VisualEffectMode.GLASS
    val blurFill = if (isDark) Color.White.copy(alpha = 0.08f)
                   else Color.White.copy(alpha = 0.70f)
    val solidFill = if (isDark) MaterialTheme.colorScheme.surface.copy(alpha = 0.72f)
                    else blurFill.copy(alpha = (blurFill.alpha + 0.12f).coerceAtMost(0.92f))
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            text = title,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.primary,
            letterSpacing = 0.3.sp,
            modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 6.dp)
        )
        NeumorphicFrostedSurface(
            modifier = Modifier.fillMaxWidth(),
            isDark = isDark,
            shape = RoundedCornerShape(20.dp),
            backgroundColor = if (realBlur) blurFill else solidFill,
            borderColor = if (isDark) Color.White.copy(alpha = 0.10f)
                          else Color(0xFFE0E5EC).copy(alpha = 0.9f),
            elevation = 6.dp,
            blurRadius = 18.dp,
            hazeState = if (realBlur) hazeState else null,
            hazeStyle = HazeMaterials.thin(),
            glassRole = GlassSurfaceRole.Card
        ) {
            Column(
                modifier = Modifier.padding(vertical = 4.dp),
                content = content
            )
        }
    }
}

/**
 * 隐私页开关行：40dp 图标容器 + 标题/副标题 + Switch。
 * 样式与设置页原「AI 与隐私」分组行一致（整行可点，带触感反馈）。
 */
@Composable
private fun PrivacySwitchRow(
    icon: ImageVector,
    iconTint: Color,
    title: String,
    subtitle: String,
    checked: Boolean,
    view: View,
    onToggle: (Boolean) -> Unit
) {
    val isDark = isAppDarkTheme()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { view.performHaptic(HapticType.CLICK); onToggle(!checked) }
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(
                    color = settingsIconContainerColor(isDark),
                    shape = RoundedCornerShape(12.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(22.dp)
            )
        }
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 15.sp
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Switch(
            checked = checked,
            onCheckedChange = { view.performHaptic(HapticType.CLICK); onToggle(it) },
            colors = appSwitchColors()
        )
    }
}

/**
 * 位置授权行：运行时检查 ACCESS_COARSE_LOCATION 授权状态，
 * ON_RESUME 时重查（从系统设置页返回/授权弹窗关闭后状态即时刷新）。
 * 已授权 → 副标题「已授权」+ 尾部「管理」跳应用详情；
 * 未授权 → 副标题「未授权」+ 尾部「授权」拉起权限弹窗。
 */
@Composable
private fun PrivacyLocationRow() {
    val context = LocalContext.current
    val view = LocalView.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var locationGranted by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { /* 授权结果回来时走 ON_RESUME 重查，统一更新状态 */ }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                locationGranted = ContextCompat.checkSelfPermission(
                    context, Manifest.permission.ACCESS_COARSE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val isDark = isAppDarkTheme()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(
                    color = settingsIconContainerColor(isDark),
                    shape = RoundedCornerShape(12.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Rounded.LocationOn,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp)
            )
        }
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.privacy_location_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 15.sp
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = stringResource(
                    if (locationGranted) R.string.privacy_location_granted
                    else R.string.privacy_location_denied
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp
            )
        }
        if (locationGranted) {
            // 已授权：跳系统应用详情页，用户可在权限管理里收回
            TextButton(onClick = {
                view.performHaptic(HapticType.CLICK)
                val intent = Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:${context.packageName}")
                )
                context.startActivity(intent)
            }) {
                Text(stringResource(R.string.privacy_location_manage))
            }
        } else {
            // 未授权：拉起系统权限弹窗（仅粗略位置，定位需求见对应功能页说明）
            TextButton(onClick = {
                view.performHaptic(HapticType.CLICK)
                permissionLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
            }) {
                Text(stringResource(R.string.privacy_location_grant))
            }
        }
    }
}

private data class PrivacyFlowUi(
    val title: String,
    val caption: String,
    val middleNode: String,
    val endNode: String
)

/** 说明分组：用次级表面拆开长内容，降低连续阅读负担。 */
@Composable
private fun PrivacyInfoBlock(
    icon: ImageVector,
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = if (isAppDarkTheme()) 0.32f else 0.52f),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.12f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Column(content = content)
        }
    }
}

/** 与网页版隐私区同一信息结构的原生数据流动画：仅示意，不读取真实网络状态。 */
@Composable
private fun PrivacyDataFlowCard() {
    val flows = listOf(
        PrivacyFlowUi(
            title = stringResource(R.string.privacy_flow_local_title),
            caption = stringResource(R.string.privacy_flow_local_caption),
            middleNode = stringResource(R.string.privacy_flow_local_node),
            endNode = stringResource(R.string.privacy_flow_local_end)
        ),
        PrivacyFlowUi(
            title = stringResource(R.string.privacy_flow_account_title),
            caption = stringResource(R.string.privacy_flow_account_caption),
            middleNode = stringResource(R.string.privacy_flow_account_node),
            endNode = stringResource(R.string.privacy_flow_account_end)
        ),
        PrivacyFlowUi(
            title = stringResource(R.string.privacy_flow_metadata_title),
            caption = stringResource(R.string.privacy_flow_metadata_caption),
            middleNode = stringResource(R.string.privacy_flow_metadata_node),
            endNode = stringResource(R.string.privacy_flow_metadata_end)
        ),
        PrivacyFlowUi(
            title = stringResource(R.string.privacy_flow_resource_title),
            caption = stringResource(R.string.privacy_flow_resource_caption),
            middleNode = stringResource(R.string.privacy_flow_resource_node),
            endNode = stringResource(R.string.privacy_flow_resource_end)
        ),
        PrivacyFlowUi(
            title = stringResource(R.string.privacy_flow_feedback_title),
            caption = stringResource(R.string.privacy_flow_feedback_caption),
            middleNode = stringResource(R.string.privacy_flow_feedback_node),
            endNode = stringResource(R.string.privacy_flow_feedback_end)
        )
    )
    var selectedIndex by rememberSaveable { mutableStateOf(0) }
    val selectedFlow = flows[selectedIndex.coerceIn(0, flows.lastIndex)]
    val infiniteTransition = rememberInfiniteTransition(label = "privacy_flow_packet")
    val packetProgress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "privacy_flow_packet_progress"
    )
    val primary = MaterialTheme.colorScheme.primary
    val isDark = isAppDarkTheme()

    Surface(
        color = primary.copy(alpha = if (isDark) 0.10f else 0.06f),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, primary.copy(alpha = 0.16f)),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.privacy_flow_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Surface(
                    color = primary.copy(alpha = 0.14f),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text(
                        text = stringResource(R.string.privacy_flow_live),
                        style = MaterialTheme.typography.labelSmall,
                        color = primary,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            PrivacyFlowTrack(
                middleNode = selectedFlow.middleNode,
                endNode = selectedFlow.endNode,
                packetProgress = packetProgress
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.privacy_flow_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 6.dp)
            )
            PrivacyFlowOptionRow(
                options = listOf(0 to flows[0], 1 to flows[1]),
                selectedIndex = selectedIndex,
                onSelect = { selectedIndex = it }
            )
            Spacer(modifier = Modifier.height(6.dp))
            PrivacyFlowOptionRow(
                options = listOf(2 to flows[2], 3 to flows[3]),
                selectedIndex = selectedIndex,
                onSelect = { selectedIndex = it }
            )
            Spacer(modifier = Modifier.height(6.dp))
            PrivacyFlowOption(
                flow = flows[4],
                selected = selectedIndex == 4,
                onClick = { selectedIndex = 4 },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(10.dp))
            Surface(
                color = MaterialTheme.colorScheme.surface.copy(alpha = if (isDark) 0.28f else 0.60f),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = selectedFlow.title,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = primary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = selectedFlow.caption,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = MaterialTheme.typography.bodySmall.lineHeight * 1.35f
                    )
                }
            }
        }
    }
}

@Composable
private fun PrivacyFlowTrack(
    middleNode: String,
    endNode: String,
    packetProgress: Float
) {
    val primary = MaterialTheme.colorScheme.primary
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(86.dp)
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val nodeWidth = 92.dp.toPx()
            val startX = nodeWidth / 2f
            val middleX = size.width / 2f
            val endX = size.width - nodeWidth / 2f
            val y = 16.dp.toPx()
            val trackColor = primary.copy(alpha = 0.22f)
            drawLine(trackColor, Offset(startX, y), Offset(endX, y), 4.dp.toPx(), StrokeCap.Round)
            drawCircle(trackColor, 8.dp.toPx(), Offset(startX, y))
            drawCircle(trackColor, 8.dp.toPx(), Offset(middleX, y))
            drawCircle(trackColor, 8.dp.toPx(), Offset(endX, y))
            val packetX = startX + (endX - startX) * packetProgress
            drawCircle(primary, 6.dp.toPx(), Offset(packetX, y))
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            PrivacyFlowNode(stringResource(R.string.privacy_flow_device))
            PrivacyFlowNode(middleNode)
            PrivacyFlowNode(endNode)
        }
    }
}

@Composable
private fun PrivacyFlowNode(label: String) {
    Column(
        modifier = Modifier.width(92.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .background(
                    color = MaterialTheme.colorScheme.surface,
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape)
            )
        }
        Spacer(modifier = Modifier.height(5.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 2
        )
    }
}

@Composable
private fun PrivacyFlowOptionRow(
    options: List<Pair<Int, PrivacyFlowUi>>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        options.forEach { (index, flow) ->
            PrivacyFlowOption(
                flow = flow,
                selected = selectedIndex == index,
                onClick = { onSelect(index) },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun PrivacyFlowOption(
    flow: PrivacyFlowUi,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val primary = MaterialTheme.colorScheme.primary
    Surface(
        color = if (selected) primary.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surface.copy(alpha = 0.48f),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, if (selected) primary.copy(alpha = 0.32f) else MaterialTheme.colorScheme.outline.copy(alpha = 0.10f)),
        modifier = modifier.clickable(onClick = onClick)
    ) {
        Text(
            text = flow.title,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) primary else MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 1,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp)
        )
    }
}

/** 区块 B「隐私说明」内容：本地/联网边界与技术实现动画示意。 */
@Composable
private fun PrivacyStatementContent() {
    var techExpanded by rememberSaveable { mutableStateOf(false) }
    val expandRotation by animateFloatAsState(
        targetValue = if (techExpanded) 180f else 0f,
        label = "privacy_tech_expand"
    )
    Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
        Text(
            text = stringResource(R.string.privacy_statement_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            lineHeight = MaterialTheme.typography.bodyMedium.lineHeight * 1.35f,
            modifier = Modifier.padding(bottom = 14.dp)
        )

        PrivacyInfoBlock(
            icon = Icons.Rounded.Shield,
            title = stringResource(R.string.privacy_local_title)
        ) {
            PrivacyBullet(stringResource(R.string.privacy_local_b1))
            PrivacyBullet(stringResource(R.string.privacy_local_b2))
            PrivacyBullet(stringResource(R.string.privacy_local_b3))
        }
        Spacer(modifier = Modifier.height(10.dp))
        PrivacyInfoBlock(
            icon = Icons.Rounded.Public,
            title = stringResource(R.string.privacy_network_title)
        ) {
            PrivacyBullet(stringResource(R.string.privacy_network_b1))
            PrivacyBullet(stringResource(R.string.privacy_network_b2))
            PrivacyBullet(stringResource(R.string.privacy_network_b3))
            PrivacyBullet(stringResource(R.string.privacy_network_b4))
            PrivacyBullet(stringResource(R.string.privacy_network_b5))
            PrivacyBullet(stringResource(R.string.privacy_network_b6))
            PrivacyBullet(stringResource(R.string.privacy_network_b7))
            PrivacyBullet(stringResource(R.string.privacy_network_b8))
        }
        Spacer(modifier = Modifier.height(8.dp))

        // 技术实现展开后同时显示与网页版一致的数据流动画示意。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { techExpanded = !techExpanded }
                .padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Rounded.Shield,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.privacy_tech_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            Icon(
                imageVector = Icons.Rounded.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.rotate(expandRotation)
            )
        }
        AnimatedVisibility(
            visible = techExpanded,
            enter = expandVertically() + fadeIn(),
            exit = androidx.compose.animation.shrinkVertically() + androidx.compose.animation.fadeOut()
        ) {
            Column {
                PrivacyBullet(stringResource(R.string.privacy_tech_b1))
                PrivacyBullet(stringResource(R.string.privacy_tech_b2))
                PrivacyBullet(stringResource(R.string.privacy_tech_b3))
                PrivacyDataFlowCard()
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
    }
}

/** 区块 C「使用与权利」内容：导语 + 5 节说明 + 权利请求卡片。 */
@Composable
private fun PrivacyLegalContent() {
    val context = LocalContext.current
    val view = LocalView.current
    Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
        Text(
            text = stringResource(R.string.privacy_legal_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            lineHeight = MaterialTheme.typography.bodyMedium.lineHeight * 1.35f,
            modifier = Modifier.padding(bottom = 14.dp)
        )

        PrivacyLegalSection(
            title = stringResource(R.string.privacy_legal_b1_title),
            body = stringResource(R.string.privacy_legal_b1)
        )
        PrivacyLegalSection(
            title = stringResource(R.string.privacy_legal_b2_title),
            body = stringResource(R.string.privacy_legal_b2)
        )
        PrivacyLegalSection(
            title = stringResource(R.string.privacy_legal_b3_title),
            body = stringResource(R.string.privacy_legal_b3)
        )
        PrivacyLegalSection(
            title = stringResource(R.string.privacy_legal_b4_title),
            body = stringResource(R.string.privacy_legal_b4)
        )
        PrivacyLegalSection(
            title = stringResource(R.string.privacy_legal_b5_title),
            body = stringResource(R.string.privacy_legal_b5)
        )

        Surface(
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 2.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(R.string.privacy_rights_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(5.dp))
                Text(
                    text = stringResource(R.string.privacy_rights_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = MaterialTheme.typography.bodySmall.lineHeight * 1.35f
                )
                Spacer(modifier = Modifier.height(8.dp))
                PrivacyBullet(stringResource(R.string.privacy_rights_b1))
                PrivacyBullet(stringResource(R.string.privacy_rights_b2))
                PrivacyBullet(stringResource(R.string.privacy_rights_b3))
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = {
                        view.performHaptic(HapticType.CLICK)
                        CustomTabsIntent.Builder().build()
                            .launchUrl(context, RIGHTS_URL.toUri())
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.privacy_rights_open))
                }
            }
        }
    }
}

/** 使用与权利单节：小标题 + 正文，节间留白。 */
@Composable
private fun PrivacyLegalSection(title: String, body: String) {
    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = if (isAppDarkTheme()) 0.28f else 0.48f),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.10f)),
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(5.dp))
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = MaterialTheme.typography.bodyMedium.lineHeight * 1.35f
            )
        }
    }
}

/** 徽章胶囊：surface 底 + 12dp 圆角 + 图标 + 小字。 */
@Composable
private fun PrivacyBadgePill(icon: ImageVector, text: String) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(14.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 隐私页 bullet 行（照帮助页 HelpBullet 同款：圆点 + 正文）。 */
@Composable
private fun PrivacyBullet(text: String) {
    Row(
        modifier = Modifier.padding(bottom = 7.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .padding(top = 2.dp)
                .size(18.dp)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape)
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            lineHeight = MaterialTheme.typography.bodyMedium.lineHeight * 1.35f,
            modifier = Modifier.weight(1f)
        )
    }
}

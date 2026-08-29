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
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.LocationOn
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
import androidx.compose.ui.graphics.Color
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

                // 区块 B：隐私说明（本地/网络/技术细节折叠 + 徽章）
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

/**
 * 区块 B「隐私说明」内容：导语 + 本地说明 + 网络说明 + 技术细节折叠块 + 徽章行。
 */
@Composable
private fun PrivacyStatementContent() {
    var techExpanded by rememberSaveable { mutableStateOf(false) }
    // 展开时箭头旋转 180° 的动画
    val expandRotation by animateFloatAsState(
        targetValue = if (techExpanded) 180f else 0f,
        label = "privacy_tech_expand"
    )
    Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
        Text(
            text = stringResource(R.string.privacy_statement_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(bottom = 12.dp)
        )

        // 本地数据说明
        Text(
            text = stringResource(R.string.privacy_local_title),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(bottom = 6.dp)
        )
        PrivacyBullet(stringResource(R.string.privacy_local_b1))
        PrivacyBullet(stringResource(R.string.privacy_local_b2))
        PrivacyBullet(stringResource(R.string.privacy_local_b3))
        Spacer(modifier = Modifier.height(12.dp))

        // 网络传输说明
        Text(
            text = stringResource(R.string.privacy_network_title),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(bottom = 6.dp)
        )
        PrivacyBullet(stringResource(R.string.privacy_network_b1))
        PrivacyBullet(stringResource(R.string.privacy_network_b2))
        PrivacyBullet(stringResource(R.string.privacy_network_b3))
        PrivacyBullet(stringResource(R.string.privacy_network_b4))
        PrivacyBullet(stringResource(R.string.privacy_network_b5))
        PrivacyBullet(stringResource(R.string.privacy_network_b6))
        PrivacyBullet(stringResource(R.string.privacy_network_b7))
        PrivacyBullet(stringResource(R.string.privacy_network_b8))
        Spacer(modifier = Modifier.height(4.dp))

        // 技术细节折叠块
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { techExpanded = !techExpanded }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
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
            enter = expandVertically() + fadeIn()
        ) {
            Column {
                PrivacyBullet(stringResource(R.string.privacy_tech_b1))
                PrivacyBullet(stringResource(R.string.privacy_tech_b2))
                PrivacyBullet(stringResource(R.string.privacy_tech_b3))
            }
        }
        Spacer(modifier = Modifier.height(12.dp))

        // 徽章行：两个小胶囊概括隐私承诺
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PrivacyBadgePill(
                icon = Icons.Rounded.Shield,
                text = stringResource(R.string.privacy_badge_b1)
            )
            PrivacyBadgePill(
                icon = Icons.Rounded.VisibilityOff,
                text = stringResource(R.string.privacy_badge_b2)
            )
        }
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
            modifier = Modifier.padding(bottom = 12.dp)
        )

        // 5 节使用与权利说明，节间留白
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

        // 权利请求卡片：CustomTabs 打开权利请求页
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(R.string.privacy_rights_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.privacy_rights_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                Button(onClick = {
                    view.performHaptic(HapticType.CLICK)
                    // CustomTabs 打开外部网页（与开源页仓库链接同款方式）
                    CustomTabsIntent.Builder().build()
                        .launchUrl(context, RIGHTS_URL.toUri())
                }) {
                    Text(stringResource(R.string.privacy_rights_open))
                }
            }
        }
    }
}

/** 使用与权利单节：小标题 + 正文，节间留白。 */
@Composable
private fun PrivacyLegalSection(title: String, body: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(bottom = 4.dp)
    )
    Text(
        text = body,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 12.dp)
    )
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
        modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = "•",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(end = 6.dp)
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            lineHeight = MaterialTheme.typography.bodyMedium.lineHeight * 1.4f
        )
    }
}

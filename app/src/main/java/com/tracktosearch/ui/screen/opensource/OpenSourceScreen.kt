package com.tracktosearch.ui.screen.opensource

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.VolunteerActivism
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.net.toUri
import com.tracktosearch.R
import com.tracktosearch.ui.component.AppAlertDialog
import com.tracktosearch.ui.component.DialogAction
import com.tracktosearch.ui.component.hasListScrolled
import com.tracktosearch.ui.component.hazeTopBar
import com.tracktosearch.ui.haptic.HapticSemantic
import com.tracktosearch.ui.haptic.hapticClickable
import com.tracktosearch.ui.theme.DesignToken
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.blur.materials.HazeMaterials

/** 特别鸣谢外跳仓库：豆瓣同步爬取方式与反爬思路的参考来源，与 README 鸣谢口径一致。 */
private const val SPECIAL_THANKS_REPO_URL = "https://github.com/Geetheshe/DoubanMovieListBackUpToNotion"

/** 特别鸣谢正文中的项目名：作为超链接锚文本，四语文案均含此字面量。 */
private const val SPECIAL_THANKS_PROJECT_NAME = "DoubanMovieListBackUpToNotion"

/**
 * 开源相关页：按分组列出本 App 使用的第三方开源库（名称版本/许可/开发者），
 * 点击条目弹窗展示在本 App 中的用途与仓库链接。页面结构照帮助页模板。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OpenSourceScreen(
    onBack: () -> Unit
) {
    var selected by remember { mutableStateOf<OssLibrary?>(null) }
    val groups = OpenSourceData.groups
    val libraryTotal = remember(groups) { groups.sumOf { it.libraries.size } }
    val listState = rememberLazyListState()
    val hazeState = remember { HazeState() }
    // HazeMaterials.thin() 读 colorScheme，是 @Composable 函数，不能 remember 缓存
    val hazeStyle = HazeMaterials.thin()
    // 静止时列表未位移、栏下无内容，顶栏保持全透明；滚动后再启用模糊/玻璃
    val hasContentUnderTopBar by remember {
        derivedStateOf {
            hasListScrolled(
                firstVisibleItemIndex = listState.firstVisibleItemIndex,
                firstVisibleItemScrollOffsetPx = listState.firstVisibleItemScrollOffset
            )
        }
    }

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
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .hazeSource(state = hazeState),
                contentPadding = PaddingValues(
                    top = 64.dp + statusBarHeight,
                    bottom = 80.dp
                )
            ) {
                item(key = "thanks_card") {
                    OssThanksCard(libraryTotal = libraryTotal, groupTotal = groups.size)
                }
                item(key = "special_thanks_card") {
                    OssSpecialThanksCard()
                }
                groups.forEach { group ->
                    item(key = "group_${group.titleRes}") {
                        OssGroupHeader(titleRes = group.titleRes, count = group.libraries.size)
                    }
                    items(group.libraries, key = { "${group.titleRes}_${it.name}" }) { lib ->
                        OssLibraryCard(lib) { selected = lib }
                    }
                }
            }

            // 毛玻璃吸顶标题栏（按视觉模式切 Blur/Glass，与帮助页及设置各子页一致）
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
                            text = stringResource(R.string.opensource_title),
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                                contentDescription = "Back",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        // 底色交给外层 hazeTopBar；这里若留 surface 会盖死毛玻璃
                        containerColor = Color.Transparent
                    ),
                    // 外层 Column 已让出状态栏，这里必须清零，否则状态栏高度被算两遍、标题栏变高
                    windowInsets = WindowInsets(0, 0, 0, 0)
                )
            }
        }
    }

    selected?.let { lib ->
        OssLibraryDialog(lib) { selected = null }
    }
}

/**
 * 顶部感谢文案：纯 Compose 绘制，无外部图片素材，无卡片样式，文字直接显示。
 * 视觉从上到下：捧心徽标（致谢意象）→ 两行主文案 → 致谢标语胶囊 → 库数量小结。
 */
@Composable
private fun OssThanksCard(libraryTotal: Int, groupTotal: Int) {
    val scheme = MaterialTheme.colorScheme
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp)
    ) {
            // 捧心手势 = 致谢/回馈，比奖杯麦穗更贴「感谢贡献者」而非「自我表彰」
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(scheme.primary.copy(alpha = 0.16f))
            ) {
                Icon(
                    imageVector = Icons.Rounded.VolunteerActivism,
                    contentDescription = null,
                    tint = scheme.primary,
                    modifier = Modifier.size(28.dp)
                )
            }
            Spacer(modifier = Modifier.height(14.dp))
            Text(
                text = stringResource(R.string.opensource_thanks_line1),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                color = scheme.onSurface
            )
            Text(
                text = stringResource(R.string.opensource_thanks_line2),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                color = scheme.onSurface
            )
            Spacer(modifier = Modifier.height(14.dp))
            Surface(
                shape = DesignToken.Tag,
                color = scheme.primary.copy(alpha = 0.12f)
            ) {
                Text(
                    text = stringResource(R.string.opensource_thanks_line3),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    color = scheme.primary,
                    modifier = Modifier.padding(
                        horizontal = DesignToken.ChipPaddingH,
                        vertical = DesignToken.ChipPaddingV
                    )
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.opensource_thanks_summary, libraryTotal, groupTotal),
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant
            )
    }
}

/**
 * 特别鸣谢卡：豆瓣同步（标记列表爬取/反爬思路）参考的开源项目，与库条目卡同族样式。
 * 标题水平居中、不带图标；整卡不可点，正文里的项目名是超链接（CustomTabs 外跳，不发触感）。
 */
@Composable
private fun OssSpecialThanksCard() {
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val bodyText = stringResource(R.string.opensource_special_thanks_body)
    // 文案是纯字符串资源，项目名以子串定位加链接；四语文案均含项目名字面量
    val linkedBody = buildAnnotatedString {
        append(bodyText)
        val start = bodyText.indexOf(SPECIAL_THANKS_PROJECT_NAME)
        if (start >= 0) {
            addLink(
                LinkAnnotation.Url(
                    url = SPECIAL_THANKS_REPO_URL,
                    styles = TextLinkStyles(
                        style = SpanStyle(
                            color = scheme.primary,
                            fontWeight = FontWeight.SemiBold,
                            textDecoration = TextDecoration.Underline
                        )
                    ),
                    linkInteractionListener = { link ->
                        val url = (link as? LinkAnnotation.Url)?.url ?: return@Url
                        CustomTabsIntent.Builder().build()
                            .launchUrl(context, url.toUri())
                    }
                ),
                start,
                start + SPECIAL_THANKS_PROJECT_NAME.length
            )
        }
    }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = scheme.surfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text(
                text = stringResource(R.string.opensource_special_thanks_title),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = scheme.primary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = linkedBody,
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant,
                lineHeight = MaterialTheme.typography.bodyMedium.lineHeight * 1.35f
            )
        }
    }
}

/** 分组标题行：标题 + 该组库数量胶囊 */
@Composable
private fun OssGroupHeader(titleRes: Int, count: Int) {
    val scheme = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 6.dp)
    ) {
        Text(
            text = stringResource(titleRes),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = scheme.primary
        )
        val countDesc = stringResource(R.string.opensource_group_count, count)
        Surface(
            shape = CircleShape,
            color = scheme.primary.copy(alpha = 0.12f)
        ) {
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = scheme.primary,
                modifier = Modifier
                    .padding(horizontal = 8.dp, vertical = 2.dp)
                    .semantics { contentDescription = countDesc }
            )
        }
    }
}

/** 单个库条目卡片：名称+版本 / 许可 / 版权·开发者 三行，点击弹详情 */
@Composable
private fun OssLibraryCard(lib: OssLibrary, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .hapticClickable(semantic = HapticSemantic.LIGHT_TAP, onClick = onClick)
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text(
                text = "${lib.name} ${lib.version}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.opensource_license_label, lib.license),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = stringResource(R.string.opensource_developer_label, lib.developer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 库详情弹窗：版本/许可/开发者 + 在本 App 中的用途 + 仓库链接 */
@Composable
private fun OssLibraryDialog(lib: OssLibrary, onDismiss: () -> Unit) {
    val context = LocalContext.current
    AppAlertDialog(
        onDismissRequest = onDismiss,
        title = lib.name,
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = stringResource(R.string.opensource_version_label, lib.version),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = stringResource(R.string.opensource_license_label, lib.license),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = stringResource(R.string.opensource_developer_label, lib.developer),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                Text(
                    text = stringResource(R.string.opensource_usage_label),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = stringResource(lib.usageRes),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        },
        confirm = DialogAction(
            // 外跳 GitHub 的「查看仓库」仍是主按钮，按压触感由按钮行按主按钮档位给出
            label = stringResource(R.string.opensource_view_repo),
            onClick = {
                CustomTabsIntent.Builder().build()
                    .launchUrl(context, lib.repoUrl.toUri())
            }
        ),
        dismiss = DialogAction(
            label = stringResource(R.string.common_cancel),
            onClick = { onDismiss() }
        )
    )
}

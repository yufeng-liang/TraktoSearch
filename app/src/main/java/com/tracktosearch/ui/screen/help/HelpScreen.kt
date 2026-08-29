package com.tracktosearch.ui.screen.help

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.tracktosearch.R
import com.tracktosearch.data.local.SplashQuote
import com.tracktosearch.ui.screen.splash.grainBrush
import com.tracktosearch.ui.util.LocalScrollToTopProvider
import kotlinx.coroutines.launch

/**
 * 列表里的一行。
 *
 * 分组小标题和段落摊进同一个列表，[LazyColumn] 的 item 下标才和这里的下标一致——
 * 深链要滚到第 N 段时得知道那一段在列表里排第几行，而它前面有多少个小标题是变量。
 */
private sealed interface HelpRow {
    /** 页首：题签，或搜索时的结果计数 */
    data object Head : HelpRow

    data class GroupLabel(val group: HelpGroup) : HelpRow

    data class Section(val index: Int, val spec: HelpSectionSpec) : HelpRow

    /** 搜索一段都没命中 */
    data object Empty : HelpRow
}

/** 按命中集合摊平成行。空集合（搜不到）只出一行空状态，连题签都不留。 */
private fun helpRows(matched: Set<Int>): List<HelpRow> {
    val rows = mutableListOf<HelpRow>(HelpRow.Head)
    if (matched.isEmpty()) {
        rows += HelpRow.Empty
        return rows
    }
    HelpGroupBlocks.forEach { block ->
        val hits = block.sections.filter { it.index in matched }
        if (hits.isEmpty()) return@forEach
        rows += HelpRow.GroupLabel(block.group)
        hits.forEach { rows += HelpRow.Section(it.index, it.value) }
    }
    return rows
}

/**
 * 帮助与说明。
 *
 * 做成一本纸质说明书：暖纸底、衬线字、中文大写数字编号、细墨线代替卡片。色板和纹理
 * 直接取开屏台词层那一套（[rememberHelpPaper]、[grainBrush]），这三处必须是同一张纸。
 *
 * 代价说清楚：这一页不跟随用户自定义强调色，也不跟随全局 Glass/Blur/拟态视觉模式——
 * 顶栏是纸色的，没有毛玻璃。全 App 只有这一页这样，是刻意的。
 *
 * @param initialSection 功能页带过来的段落 key（见 [HelpSections]）：进入后展开并滚到该段
 */
@Composable
fun HelpScreen(
    onBack: () -> Unit,
    initialSection: String? = null,
) {
    val paper = rememberHelpPaper()
    val context = LocalContext.current
    // 语言从 Configuration 取：「跟随系统」这一档只有它知道最终落到了哪种语言
    val locale = LocalConfiguration.current.locales[0]
    val lang = remember(locale) { SplashQuote.resolveLang(locale.language) }

    var query by rememberSaveable { mutableStateOf("") }
    var searchVisible by rememberSaveable { mutableStateOf(false) }
    // 浏览态是单开手风琴：十四段全开就等于没有折叠
    var browseExpanded by rememberSaveable { mutableIntStateOf(0) }
    val searching = query.isNotBlank()

    // 搜索态下命中段落默认全开，这里只记用户手动收起的那几段。
    // 用 remember(query) 而不是 rememberSaveable：换搜索词就该重新全开，不该记着上一次的收放。
    val collapsedWhileSearching = remember(query) { mutableStateListOf<Int>() }

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val scrollToTopProvider = LocalScrollToTopProvider.current

    val matched = remember(query, context) {
        HelpCatalog.withIndex()
            .filter { (_, spec) ->
                helpMatchesQuery(spec.searchable.map(context::getString), query)
            }
            .map { it.index }
            .toSet()
    }
    val rows = remember(matched) { helpRows(matched) }

    // 深链：展开对应段并滚到它，省得用户在十四段里自己找
    LaunchedEffect(initialSection) {
        val target = helpIndexOf(initialSection) ?: return@LaunchedEffect
        browseExpanded = target
        val row = rows.indexOfFirst { it is HelpRow.Section && it.index == target }
        if (row >= 0) listState.animateScrollToItem(row)
    }

    // 状态栏回顶
    DisposableEffect(Unit) {
        scrollToTopProvider.register { scope.launch { listState.animateScrollToItem(0) } }
        onDispose { scrollToTopProvider.unregister() }
    }

    // 题签滚出视野后顶栏才浮出标题：静止时标题在纸上只该出现一次
    val titleInBar by remember {
        derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 40 }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(paper.palette.paper)
    ) {
        HelpPaperBackdrop(paper)
        val statusBarHeight = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 22.dp,
                end = 22.dp,
                top = 52.dp + statusBarHeight,
                bottom = 96.dp,
            ),
        ) {
            items(
                count = rows.size,
                key = { index ->
                    when (val row = rows[index]) {
                        HelpRow.Head -> "head"
                        HelpRow.Empty -> "empty"
                        is HelpRow.GroupLabel -> "group-${row.group.name}"
                        is HelpRow.Section -> "section-${row.spec.key}"
                    }
                },
            ) { index ->
                HelpRowContent(
                    row = rows[index],
                    paper = paper,
                    lang = lang,
                    query = query,
                    matchCount = matched.size,
                    isExpanded = { section ->
                        if (searching) section !in collapsedWhileSearching else browseExpanded == section
                    },
                    onToggle = { section ->
                        if (searching) {
                            if (section in collapsedWhileSearching) {
                                collapsedWhileSearching.remove(section)
                            } else {
                                collapsedWhileSearching.add(section)
                            }
                        } else {
                            browseExpanded = if (browseExpanded == section) -1 else section
                        }
                    },
                )
            }
        }

        HelpPaperTopBar(
            paper = paper,
            titleVisible = titleInBar,
            ruleVisible = titleInBar,
            searchVisible = searchVisible,
            query = query,
            onQueryChange = { query = it },
            onBack = onBack,
            onToggleSearch = {
                searchVisible = !searchVisible
                if (!searchVisible) query = ""
            },
        )
    }
}

@Composable
private fun HelpRowContent(
    row: HelpRow,
    paper: HelpPaper,
    lang: String,
    query: String,
    matchCount: Int,
    isExpanded: (Int) -> Boolean,
    onToggle: (Int) -> Unit,
) {
    when (row) {
        // 搜索时题签让位给结果计数：正在筛的时候书名不重要，命中几段才重要
        HelpRow.Head -> if (query.isBlank()) {
            HelpMasthead(stringResource(R.string.help_title), paper)
        } else {
            HelpResultCount(matchCount, paper)
        }

        HelpRow.Empty -> HelpEmptyResult(paper)

        is HelpRow.GroupLabel -> HelpGroupLabel(stringResource(row.group.label), paper)

        is HelpRow.Section -> HelpSectionBlock(
            index = row.index,
            spec = row.spec,
            paper = paper,
            lang = lang,
            query = query,
            expanded = isExpanded(row.index),
            onToggle = { onToggle(row.index) },
        )
    }
}

/** 一段：标题行 + 展开的正文 + 收底的墨线。 */
@Composable
private fun HelpSectionBlock(
    index: Int,
    spec: HelpSectionSpec,
    paper: HelpPaper,
    lang: String,
    query: String,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val seal = paper.palette.seal
    Column(modifier = Modifier.fillMaxWidth()) {
        HelpSectionHeader(
            numeral = helpSectionNumeral(lang, index),
            title = helpHighlight(stringResource(spec.title), query, seal),
            expanded = expanded,
            paper = paper,
            onToggle = onToggle,
        )
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(tween(220)),
            exit = shrinkVertically(tween(180)),
        ) {
            Column(modifier = Modifier.padding(start = 2.dp, bottom = 16.dp)) {
                spec.bullets.forEachIndexed { position, res ->
                    HelpItem(
                        numeral = helpItemNumeral(lang, position),
                        text = helpHighlight(stringResource(res), query, seal),
                        paper = paper,
                    )
                }
                spec.extra?.let { extra ->
                    Spacer(Modifier.height(6.dp))
                    HelpExtraContent(extra = extra, paper = paper, lang = lang, query = query)
                }
            }
        }
        HelpInkRule(paper)
    }
}

/** 搜索结果计数。朱砂小字居中，占位和题签一样高，切换时页面不跳。 */
@Composable
private fun HelpResultCount(count: Int, paper: HelpPaper) {
    Text(
        text = pluralStringResource(R.plurals.help_search_matches, count, count),
        color = paper.palette.seal,
        fontSize = 11.sp,
        fontFamily = FontFamily.Serif,
        letterSpacing = 0.14.em,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp, bottom = 20.dp),
    )
}

/** 一段都没命中。以前这里是一片空白，看不出是搜错了还是页面坏了。 */
@Composable
private fun HelpEmptyResult(paper: HelpPaper) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 64.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.help_search_empty),
            color = paper.palette.inkSoft,
            fontSize = 13.sp,
            fontFamily = FontFamily.Serif,
            letterSpacing = 0.06.em,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * 背景：顶部一团很弱的暖光 + 整屏胶片颗粒。
 *
 * 光比日签页还弱、也不动：这一页是拿来读的，背景一动就成了干扰。颗粒用的是开屏那一块
 * 噪点，三处纸面纹理必须同源。暗色主题下改 Screen 混合，深棕叠深底会糊成一片黑。
 */
@Composable
private fun HelpPaperBackdrop(paper: HelpPaper) {
    val grain = remember { grainBrush() }
    Canvas(modifier = Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@Canvas
        val center = Offset(0.5f * w, 0.02f * h)
        val radius = 0.86f * w
        drawCircle(
            brush = Brush.radialGradient(
                0f to paper.palette.caramel,
                0.7f to Color.Transparent,
                center = center,
                radius = radius,
            ),
            radius = radius,
            center = center,
            alpha = if (paper.palette.isDark) 0.26f else 0.30f,
            blendMode = if (paper.palette.isDark) BlendMode.Screen else BlendMode.Multiply,
        )
        drawRect(brush = grain, alpha = paper.palette.grainAlpha * 0.7f)
    }
}

/**
 * 纸色顶栏。
 *
 * 没有毛玻璃：这一页整张是纸，一条玻璃横切过去就把纸切断了。代价是它不再跟随全局
 * Glass/Blur/拟态视觉模式——见 [HelpScreen] 的说明。
 *
 * 标题只在题签滚出视野后才浮出来，静止时纸上不会同时出现两个「使用说明」。
 * 底下那道墨线同时出现，它是唯一表示「上面还有内容」的信号。
 */
@Composable
private fun HelpPaperTopBar(
    paper: HelpPaper,
    titleVisible: Boolean,
    ruleVisible: Boolean,
    searchVisible: Boolean,
    query: String,
    onQueryChange: (String) -> Unit,
    onBack: () -> Unit,
    onToggleSearch: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(paper.palette.paper)
            // 顶栏盖在可滚动列表上，不消费点击会穿透到下面的段落标题
            .clickable(enabled = false, onClick = {})
    ) {
        Spacer(Modifier.statusBarsPadding())
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = stringResource(R.string.content_desc_back),
                    tint = paper.palette.ink,
                    modifier = Modifier.size(20.dp),
                )
            }
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                HelpTopBarTitle(visible = titleVisible, paper = paper)
            }
            IconButton(onClick = onToggleSearch) {
                Icon(
                    imageVector = if (searchVisible) Icons.Rounded.Close else Icons.Rounded.Search,
                    contentDescription = stringResource(
                        if (searchVisible) R.string.common_cancel else R.string.help_search_hint
                    ),
                    tint = paper.palette.ink,
                    modifier = Modifier.size(19.dp),
                )
            }
        }
        if (searchVisible) {
            HelpSearchField(
                query = query,
                onQueryChange = onQueryChange,
                paper = paper,
            )
        }
        AnimatedVisibility(
            visible = ruleVisible,
            enter = fadeIn(tween(180)),
            exit = fadeOut(tween(180)),
        ) {
            HelpInkRule(paper)
        }
    }
}

/**
 * 顶栏标题。
 *
 * 抽成独立 Composable 而不是写在 Row 里：Row 的作用域会让 RowScope.AnimatedVisibility
 * 参与重载决议，隐式接收者对不上就编译不过。
 */
@Composable
private fun HelpTopBarTitle(visible: Boolean, paper: HelpPaper) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(180)),
        exit = fadeOut(tween(180)),
    ) {
        Text(
            text = stringResource(R.string.help_title),
            color = paper.palette.ink,
            fontSize = 14.sp,
            fontFamily = FontFamily.Serif,
            letterSpacing = 0.16.em,
        )
    }
}

/**
 * 搜索框：一行衬线字 + 一道墨线。
 *
 * 用 BasicTextField 而不是 OutlinedTextField：M3 的描边框、悬浮 label、填充底色是一整套
 * 玻璃时代的语言，放在纸上像贴了张塑料条。这里只要「在纸上划一道线，在线上写字」。
 */
@Composable
private fun HelpSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    paper: HelpPaper,
) {
    val seal = paper.palette.seal
    val selectionColors = remember(seal) {
        TextSelectionColors(handleColor = seal, backgroundColor = seal.copy(alpha = 0.22f))
    }
    Column(modifier = Modifier.padding(start = 22.dp, end = 22.dp, bottom = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CompositionLocalProvider(LocalTextSelectionColors provides selectionColors) {
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    textStyle = TextStyle(
                        color = paper.body,
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Serif,
                        letterSpacing = 0.04.em,
                    ),
                    cursorBrush = SolidColor(seal),
                    decorationBox = { field ->
                        Box(modifier = Modifier.padding(vertical = 6.dp)) {
                            if (query.isEmpty()) {
                                Text(
                                    text = stringResource(R.string.help_search_hint),
                                    color = paper.palette.inkFaint,
                                    fontSize = 13.sp,
                                    fontFamily = FontFamily.Serif,
                                    letterSpacing = 0.04.em,
                                )
                            }
                            field()
                        }
                    },
                )
            }
            if (query.isNotEmpty()) {
                IconButton(
                    onClick = { onQueryChange("") },
                    modifier = Modifier.size(28.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = stringResource(R.string.common_cancel),
                        tint = paper.palette.inkSoft,
                        modifier = Modifier.size(15.dp),
                    )
                }
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(paper.palette.inkFaint)
        )
    }
}

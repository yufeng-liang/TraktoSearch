package com.tracktosearch.ui.screen.help

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.tracktosearch.R
import com.tracktosearch.ui.component.hasListScrolled
import com.tracktosearch.ui.component.hazeTopBar
import com.tracktosearch.ui.util.LocalScrollToTopProvider
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.materials.HazeMaterials
import dev.chrisbanes.haze.hazeSource
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
 * 保留说明书的衬线字、清晰层级和细分隔线，但颜色完全跟随当前 MaterialTheme。
 * 顶栏初始透明沉浸，列表发生位移后才启用与其他设置子页一致的 Haze。
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
    val focusRequester = remember { FocusRequester() }
    val searchInteractionSource = remember { MutableInteractionSource() }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    var rootPositionInRoot by remember { mutableStateOf(Offset.Zero) }
    var searchBoundsInRoot by remember { mutableStateOf<Rect?>(null) }
    val searchBoundsInRootLocal = searchBoundsInRoot?.let { bounds ->
        Rect(
            left = bounds.left - rootPositionInRoot.x,
            top = bounds.top - rootPositionInRoot.y,
            right = bounds.right - rootPositionInRoot.x,
            bottom = bounds.bottom - rootPositionInRoot.y,
        )
    }
    val currentSearchBoundsInRootLocal by rememberUpdatedState(searchBoundsInRootLocal)
    val collapseSearch: () -> Unit = {
        searchVisible = false
        focusManager.clearFocus(force = true)
        keyboardController?.hide()
    }
    val hazeState = remember { HazeState() }
    // HazeMaterials.thin() 读取 MaterialTheme，不能用 remember 缓存。
    val hazeStyle = HazeMaterials.thin()

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

    // 与 OpenSourceScreen 相同：列表只要真正发生位移，才启用顶栏 Haze。
    val hasContentUnderTopBar by remember {
        derivedStateOf {
            hasListScrolled(
                firstVisibleItemIndex = listState.firstVisibleItemIndex,
                firstVisibleItemScrollOffsetPx = listState.firstVisibleItemScrollOffset,
            )
        }
    }

    LaunchedEffect(searchVisible) {
        if (searchVisible) {
            focusRequester.requestFocus()
            keyboardController?.show()
        }
    }

    BackHandler(enabled = searchVisible) { collapseSearch() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(paper.palette.paper)
            .onGloballyPositioned { rootPositionInRoot = it.positionInRoot() }
            .pointerInput(searchVisible) {
                if (!searchVisible) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val up = waitForUpOrCancellation()
                    if (up != null && currentSearchBoundsInRootLocal?.contains(down.position) != true) {
                        collapseSearch()
                    }
                }
            }
    ) {
        HelpPaperBackdrop(paper)
        val statusBarHeight = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .hazeSource(state = hazeState),
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
            titleVisible = hasContentUnderTopBar,
            ruleVisible = hasContentUnderTopBar,
            hasContentUnderTopBar = hasContentUnderTopBar,
            hazeState = hazeState,
            hazeStyle = hazeStyle,
            searchVisible = searchVisible,
            query = query,
            focusRequester = focusRequester,
            searchInteractionSource = searchInteractionSource,
            onQueryChange = { query = it },
            onBack = onBack,
            onExpandSearch = { searchVisible = true },
            onCollapseSearch = collapseSearch,
            onSearchAction = {
                focusManager.clearFocus(force = true)
                keyboardController?.hide()
            },
            onSearchBoundsChanged = { searchBoundsInRoot = it },
        )
    }
}

@Composable
private fun HelpRowContent(
    row: HelpRow,
    paper: HelpPaper,
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
    query: String,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val seal = paper.palette.seal
    Column(modifier = Modifier.fillMaxWidth()) {
        HelpSectionHeader(
            numeral = helpSectionNumeral(index),
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
                        numeral = helpItemNumeral(position),
                        text = helpHighlight(stringResource(res), query, seal),
                        paper = paper,
                    )
                }
                spec.extra?.let { extra ->
                    Spacer(Modifier.height(6.dp))
                    HelpExtraContent(extra = extra, paper = paper, query = query)
                }
            }
        }
        HelpInkRule(paper)
    }
}

/** 搜索结果计数。主题强调色小字居中，占位和题签一样高，切换时页面不跳。 */
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

/** 中性背景层：只使用当前主题 background，不绘制黄纸纹理或暖色洗底。 */
@Composable
private fun HelpPaperBackdrop(paper: HelpPaper) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(paper.palette.paper)
    )
}

/**
 * 说明书标题栏。
 *
 * 栏底保持透明；列表发生位移后由 [hazeTopBar] 提供模糊。搜索按钮在右侧原位横向展开，
 * 标题同时淡出。整栏覆盖列表，点击搜索框外由页面根节点优先收起搜索。
 */
@Composable
private fun HelpPaperTopBar(
    paper: HelpPaper,
    titleVisible: Boolean,
    ruleVisible: Boolean,
    hasContentUnderTopBar: Boolean,
    hazeState: HazeState,
    hazeStyle: HazeBlurStyle,
    searchVisible: Boolean,
    query: String,
    focusRequester: FocusRequester,
    searchInteractionSource: MutableInteractionSource,
    onQueryChange: (String) -> Unit,
    onBack: () -> Unit,
    onExpandSearch: () -> Unit,
    onCollapseSearch: () -> Unit,
    onSearchAction: () -> Unit,
    onSearchBoundsChanged: (Rect) -> Unit,
) {
    val topBarInteractionSource = remember { MutableInteractionSource() }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .hazeTopBar(
                state = hazeState,
                style = hazeStyle,
                blurRadius = 24.dp,
                isContentUnderTopBar = hasContentUnderTopBar,
            )
            .clickable(
                interactionSource = topBarInteractionSource,
                indication = null,
            ) {
                if (searchVisible) onCollapseSearch()
            }
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
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(42.dp),
                contentAlignment = Alignment.Center,
            ) {
                HelpTopBarTitle(
                    visible = titleVisible && !searchVisible,
                    paper = paper,
                )
                BoxWithConstraints(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.CenterEnd,
                ) {
                    val searchWidth by animateDpAsState(
                        targetValue = if (searchVisible) maxWidth else 42.dp,
                        animationSpec = tween(durationMillis = 240),
                        label = "help_search_width",
                    )
                    Box(
                        modifier = Modifier
                            .width(searchWidth)
                            .fillMaxHeight()
                            .onGloballyPositioned { onSearchBoundsChanged(it.boundsInRoot()) },
                        contentAlignment = Alignment.CenterEnd,
                    ) {
                        if (searchVisible) {
                            HelpSearchField(
                                query = query,
                                onQueryChange = onQueryChange,
                                paper = paper,
                                focusRequester = focusRequester,
                                interactionSource = searchInteractionSource,
                                onSearchAction = onSearchAction,
                            )
                        } else {
                            IconButton(
                                onClick = onExpandSearch,
                                modifier = Modifier
                                    .size(42.dp)
                                    .background(paper.palette.cream, CircleShape)
                                    .border(1.dp, paper.palette.inkFaint, CircleShape),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Search,
                                    contentDescription = stringResource(R.string.help_search_hint),
                                    tint = if (query.isNotBlank()) {
                                        paper.palette.seal
                                    } else {
                                        paper.palette.ink
                                    },
                                    modifier = Modifier.size(19.dp),
                                )
                            }
                        }
                    }
                }
            }
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

/** 顶栏标题；搜索展开时与列表未滚动时都淡出。 */
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

/** 中性 surface 上的衬线搜索输入；保留清除按钮，不承担搜索匹配逻辑。 */
@Composable
private fun HelpSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    paper: HelpPaper,
    focusRequester: FocusRequester,
    interactionSource: MutableInteractionSource,
    onSearchAction: () -> Unit,
) {
    val seal = paper.palette.seal
    val selectionColors = remember(seal) {
        TextSelectionColors(handleColor = seal, backgroundColor = seal.copy(alpha = 0.22f))
    }
    val shape = RoundedCornerShape(21.dp)
    CompositionLocalProvider(LocalTextSelectionColors provides selectionColors) {
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            singleLine = true,
            modifier = Modifier
                .fillMaxSize()
                .background(paper.palette.cream, shape)
                .border(1.dp, paper.palette.inkFaint, shape)
                .focusRequester(focusRequester),
            textStyle = TextStyle(
                color = paper.body,
                fontSize = 13.sp,
                fontFamily = FontFamily.Serif,
                letterSpacing = 0.04.em,
            ),
            cursorBrush = SolidColor(seal),
            interactionSource = interactionSource,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSearchAction() }),
            decorationBox = { field ->
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(start = 12.dp, end = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Search,
                        contentDescription = null,
                        tint = paper.palette.inkSoft,
                        modifier = Modifier.size(18.dp),
                    )
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 8.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        if (query.isEmpty()) {
                            Text(
                                text = stringResource(R.string.help_search_hint),
                                color = paper.palette.inkSoft,
                                fontSize = 13.sp,
                                fontFamily = FontFamily.Serif,
                                letterSpacing = 0.04.em,
                            )
                        }
                        field()
                    }
                    if (query.isNotEmpty()) {
                        IconButton(
                            onClick = { onQueryChange("") },
                            modifier = Modifier.size(28.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = stringResource(R.string.content_desc_clear),
                                tint = paper.palette.inkSoft,
                                modifier = Modifier.size(15.dp),
                            )
                        }
                    }
                }
            },
        )
    }
}

package com.tracktosearch.ui.screen.help

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tracktosearch.R
import com.tracktosearch.ui.component.EmptyStateCard
import com.tracktosearch.ui.component.NeumorphicIconButton
import com.tracktosearch.ui.component.hasListScrolled
import com.tracktosearch.ui.component.hazeTopBar
import com.tracktosearch.ui.component.isAppDarkTheme
import com.tracktosearch.ui.util.LocalScrollToTopProvider
import dev.chrisbanes.haze.HazeState
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
    /** 搜索时的结果计数。浏览态没有这一行：页名已经写在顶栏上。 */
    data object Head : HelpRow

    data class GroupLabel(val group: HelpGroup) : HelpRow

    data class Section(val index: Int, val spec: HelpSectionSpec) : HelpRow

    /** 搜索一段都没命中 */
    data object Empty : HelpRow
}

/** 按命中集合摊平成行。空集合（搜不到）只出一行空状态。 */
private fun helpRows(matched: Set<Int>, searching: Boolean): List<HelpRow> {
    val rows = mutableListOf<HelpRow>()
    if (searching) rows += HelpRow.Head
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
 * 视觉与搜索源管理页同一套：主题排版、20dp 圆角 surfaceVariant 卡片、毛玻璃顶栏、
 * 拟态圆按钮。原先这一页自成一套「纸质说明书」——整页衬线字、em 级字距、题签加短横线、
 * 用极淡墨线代替卡片，从设置页点进来像换了个应用。
 *
 * @param initialSection 功能页带过来的段落 key（见 [HelpSections]）：进入后展开并滚到该段
 */
@Composable
fun HelpScreen(
    onBack: () -> Unit,
    initialSection: String? = null,
) {
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
    val statusBarHeight = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    val matched = remember(query, context) {
        HelpCatalog.withIndex()
            .filter { (_, spec) ->
                helpMatchesQuery(spec.searchable.map(context::getString), query)
            }
            .map { it.index }
            .toSet()
    }
    val rows = remember(matched, searching) { helpRows(matched, searching) }

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

    // 与搜索源管理页相同：列表只要真正发生位移，才启用顶栏 Haze。
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

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = MaterialTheme.colorScheme.background,
    ) { _ ->
        Box(
            modifier = Modifier
                .fillMaxSize()
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
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .hazeSource(state = hazeState),
                contentPadding = PaddingValues(
                    top = 65.dp + statusBarHeight,
                    bottom = 80.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp),
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

            HelpHeaderBar(
                hazeState = hazeState,
                isContentUnderTopBar = hasContentUnderTopBar,
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
}

@Composable
private fun HelpRowContent(
    row: HelpRow,
    query: String,
    matchCount: Int,
    isExpanded: (Int) -> Boolean,
    onToggle: (Int) -> Unit,
) {
    when (row) {
        HelpRow.Head -> HelpResultCount(matchCount)

        HelpRow.Empty -> HelpEmptyResult()

        is HelpRow.GroupLabel -> HelpGroupLabel(stringResource(row.group.label))

        is HelpRow.Section -> HelpSectionCard(
            index = row.index,
            spec = row.spec,
            query = query,
            expanded = isExpanded(row.index),
            onToggle = { onToggle(row.index) },
        )
    }
}

/** 一段：一张卡，卡里是标题行 + 展开的正文。原先段与段之间靠一道极淡墨线分界。 */
@Composable
private fun HelpSectionCard(
    index: Int,
    spec: HelpSectionSpec,
    query: String,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val primary = MaterialTheme.colorScheme.primary
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .shadow(1.dp, HELP_CARD_SHAPE)
            .clip(HELP_CARD_SHAPE)
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        HelpSectionHeader(
            numeral = helpSectionNumeral(index),
            title = helpHighlight(stringResource(spec.title), query, primary),
            expanded = expanded,
            onToggle = onToggle,
        )
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(tween(220)),
            exit = shrinkVertically(tween(180)),
        ) {
            // 正文左边缘与标题行的编号列对齐
            Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp)) {
                spec.bullets.forEachIndexed { position, res ->
                    HelpItem(
                        numeral = helpItemNumeral(position),
                        text = helpHighlight(stringResource(res), query, primary),
                    )
                }
                spec.extra?.let { extra ->
                    Spacer(Modifier.height(6.dp))
                    HelpExtraContent(extra = extra, query = query)
                }
            }
        }
    }
}

/** 搜索结果计数。 */
@Composable
private fun HelpResultCount(count: Int) {
    Text(
        text = pluralStringResource(R.plurals.help_search_matches, count, count),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 4.dp),
    )
}

/** 一段都没命中。走全 App 共用的空状态卡，不再是一行孤零零的小字。 */
@Composable
private fun HelpEmptyResult() {
    EmptyStateCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 24.dp),
        isDark = isAppDarkTheme(),
        title = stringResource(R.string.help_search_empty),
        icon = Icons.Rounded.SearchOff,
    )
}

/**
 * 毛玻璃吸顶标题栏：返回 + 标题 + 搜索，规格与搜索源管理页的 HeaderBar 一致。
 *
 * 标题不再随滚动淡入淡出——它是页名，一进来就该看见。搜索按钮在右侧原位横向展开成
 * 输入框，标题同时淡出。整栏覆盖列表，点击搜索框外由页面根节点优先收起搜索。
 */
@Composable
private fun HelpHeaderBar(
    hazeState: HazeState,
    isContentUnderTopBar: Boolean,
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
    val isDark = isAppDarkTheme()
    val topBarInteractionSource = remember { MutableInteractionSource() }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .hazeTopBar(
                state = hazeState,
                style = HazeMaterials.thin(MaterialTheme.colorScheme.surface.copy(alpha = 0.55f)),
                blurRadius = 24.dp,
                isContentUnderTopBar = isContentUnderTopBar,
            )
            // 拦截点击：顶栏覆盖可滚动列表，不消费会让点击穿透到下方列表项
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
                .padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 1.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = stringResource(R.string.content_desc_back),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp),
            ) {
                // 搜索展开时标题淡出让位给输入框。这里不用 AnimatedVisibility：
                // 外层是 Row，Box 内同时能看到 RowScope 与 BoxScope 两个隐式接收者，
                // 重载会解析到 RowScope 那个扩展上，而它在 Box 里不成立。
                val titleAlpha by animateFloatAsState(
                    targetValue = if (searchVisible) 0f else 1f,
                    animationSpec = tween(durationMillis = 180),
                    label = "help_title_alpha",
                )
                Text(
                    text = stringResource(R.string.help_title),
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .graphicsLayer { alpha = titleAlpha },
                )
                HelpSearchSlot(
                    searchVisible = searchVisible,
                    query = query,
                    isDark = isDark,
                    hazeState = hazeState,
                    focusRequester = focusRequester,
                    searchInteractionSource = searchInteractionSource,
                    onQueryChange = onQueryChange,
                    onExpandSearch = onExpandSearch,
                    onSearchAction = onSearchAction,
                    onSearchBoundsChanged = onSearchBoundsChanged,
                )
            }
        }
    }
}

/** 右端的搜索位：收起是一枚拟态圆按钮，展开时横向铺满整条顶栏。 */
@Composable
private fun HelpSearchSlot(
    searchVisible: Boolean,
    query: String,
    isDark: Boolean,
    hazeState: HazeState,
    focusRequester: FocusRequester,
    searchInteractionSource: MutableInteractionSource,
    onQueryChange: (String) -> Unit,
    onExpandSearch: () -> Unit,
    onSearchAction: () -> Unit,
    onSearchBoundsChanged: (Rect) -> Unit,
) {
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
                .height(42.dp)
                .onGloballyPositioned { onSearchBoundsChanged(it.boundsInRoot()) },
            contentAlignment = Alignment.CenterEnd,
        ) {
            if (searchVisible) {
                HelpSearchField(
                    query = query,
                    onQueryChange = onQueryChange,
                    focusRequester = focusRequester,
                    interactionSource = searchInteractionSource,
                    onSearchAction = onSearchAction,
                )
            } else {
                NeumorphicIconButton(
                    onClick = onExpandSearch,
                    isDark = isDark,
                    lightBorderAlpha = 0.35f,
                    hazeState = hazeState,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Search,
                        contentDescription = stringResource(R.string.help_search_hint),
                        // 有搜索词时按钮本身就是「正在筛」的指示，用强调色
                        tint = if (query.isNotBlank()) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            LocalContentColor.current
                        },
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}
/** 搜索输入：surfaceVariant 底 + outlineVariant 描边，字号与列表正文同一档。 */
@Composable
private fun HelpSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    focusRequester: FocusRequester,
    interactionSource: MutableInteractionSource,
    onSearchAction: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val selectionColors = remember(colors.primary) {
        TextSelectionColors(
            handleColor = colors.primary,
            backgroundColor = colors.primary.copy(alpha = 0.22f),
        )
    }
    val shape = RoundedCornerShape(21.dp)
    val hintStyle = MaterialTheme.typography.bodyMedium
    CompositionLocalProvider(LocalTextSelectionColors provides selectionColors) {
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            singleLine = true,
            modifier = Modifier
                .fillMaxSize()
                .clip(shape)
                .background(colors.surfaceVariant)
                .border(1.dp, colors.outlineVariant, shape)
                .focusRequester(focusRequester),
            textStyle = hintStyle.copy(color = colors.onSurface),
            cursorBrush = SolidColor(colors.primary),
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
                        tint = colors.onSurfaceVariant,
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
                                style = hintStyle,
                                color = colors.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
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
                                tint = colors.onSurfaceVariant,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }
            },
        )
    }
}

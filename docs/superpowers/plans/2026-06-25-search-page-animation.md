# Search Page Animation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Redesign the homepage search page with animated transitions between default (centered) and focused (top-aligned) states.

**Architecture:** Single file modification to `SearchScreen.kt`. Uses Compose `Animatable` for position/width animations and `AnimatedVisibility` for icon fade-out. No new files or dependencies.

**Tech Stack:** Kotlin, Jetpack Compose, Compose Animation APIs

## Global Constraints
- Min SDK: 26 (Android 8.0)
- Target SDK: 35
- Language: Kotlin 1.9.22
- Compose BOM: 2024.10.00
- Must maintain all 4 language translations (zh/en/ja/ko)
- No new dependencies allowed

---

## Task 1: Define Search Type Colors and Update History Tags

**Files:**
- Modify: `app/src/main/java/com/tracktosearch/ui/screen/search/SearchScreen.kt:590-595, 640-651`

**Goal:** Update search history tag colors to match the new color scheme and use opacity-based backgrounds.

- [ ] **Step 1: Update typeColorMap in SearchHistoryInline**

In `SearchScreen.kt`, find the `SearchHistoryInline` function (around line 584). Replace the `typeColorMap`:

```kotlin
// BEFORE:
val typeColorMap = mapOf(
    "disk" to Color(0xFF9E9E9E),
    "movie" to Color(0xFF2196F3),
    "show" to Color(0xFF4CAF50),
    "person" to Color(0xFF9C27B0)
)

// AFTER:
val typeColorMap = mapOf(
    "disk" to Color(0xFF4CAF50),   // Green
    "movie" to Color(0xFF2196F3),  // Blue
    "show" to Color(0xFFFF9800),   // Orange
    "person" to Color(0xFF9C27B0)  // Purple
)
```

- [ ] **Step 2: Update tag background to use opacity**

Replace the `Surface` for the type tag (around line 640):

```kotlin
// BEFORE:
Surface(
    shape = RoundedCornerShape(4.dp),
    color = typeColorMap[item.type] ?: Color(0xFF9E9E9E),
    modifier = Modifier.padding(end = 8.dp)
) {
    Text(
        text = typeNameMap[item.type] ?: item.type,
        style = MaterialTheme.typography.labelSmall,
        color = Color.White,
        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
    )
}

// AFTER:
val tagColor = typeColorMap[item.type] ?: Color(0xFF4CAF50)
Surface(
    shape = RoundedCornerShape(4.dp),
    color = tagColor.copy(alpha = 0.15f),
    modifier = Modifier.padding(end = 8.dp)
) {
    Text(
        text = typeNameMap[item.type] ?: item.type,
        style = MaterialTheme.typography.labelSmall,
        color = tagColor,
        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
    )
}
```

- [ ] **Step 3: Verify build**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

---

## Task 2: Update SearchBarTop - Move Icon to Right, Add Type Colors

**Files:**
- Modify: `app/src/main/java/com/tracktosearch/ui/screen/search/SearchScreen.kt:277-417`

**Goal:** Restructure SearchBarTop to move magnifying glass to right, add type colors, increase type text size.

- [ ] **Step 1: Add type color mapping at the top of SearchBarTop**

After line 292 (`var showTypeDropdown by remember { mutableStateOf(false) }`), add:

```kotlin
val typeColorMap = mapOf(
    SearchSourceType.DISK to Color(0xFF4CAF50),
    SearchSourceType.MOVIE to Color(0xFF2196F3),
    SearchSourceType.SHOW to Color(0xFFFF9800),
    SearchSourceType.PERSON to Color(0xFF9C27B0)
)
```

- [ ] **Step 2: Remove leading icon (magnifying glass) from OutlinedTextField**

In the `OutlinedTextField`, find the `leadingIcon` parameter (around line 328). Replace the entire `leadingIcon` block to only contain the type selector (remove the magnifying glass):

```kotlin
leadingIcon = {
    if (onSearchSourceTypeChange != null) {
        Box {
            TextButton(
                onClick = { showTypeDropdown = true },
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                modifier = Modifier.height(32.dp)
            ) {
                Text(
                    text = stringResource(
                        when (searchSourceType) {
                            SearchSourceType.DISK -> R.string.search_type_disk
                            SearchSourceType.MOVIE -> R.string.search_type_movie
                            SearchSourceType.SHOW -> R.string.search_type_show
                            SearchSourceType.PERSON -> R.string.search_type_person
                        }
                    ),
                    fontSize = 15.sp,
                    color = typeColorMap[searchSourceType] ?: Color(0xFF4CAF50),
                    maxLines = 1
                )
                Icon(
                    Icons.Default.ArrowDropDown,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = typeColorMap[searchSourceType] ?: Color(0xFF4CAF50)
                )
            }
            DropdownMenu(
                expanded = showTypeDropdown,
                onDismissRequest = { showTypeDropdown = false }
            ) {
                SearchSourceType.entries.forEach { type ->
                    DropdownMenuItem(
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    stringResource(
                                        when (type) {
                                            SearchSourceType.DISK -> R.string.search_type_disk
                                            SearchSourceType.MOVIE -> R.string.search_type_movie
                                            SearchSourceType.SHOW -> R.string.search_type_show
                                            SearchSourceType.PERSON -> R.string.search_type_person
                                        }
                                    ),
                                    color = typeColorMap[type] ?: Color(0xFF4CAF50)
                                )
                                if (type == searchSourceType) {
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                                }
                            }
                        },
                        onClick = {
                            showTypeDropdown = false
                            if (type != searchSourceType) {
                                onSearchSourceTypeChange?.invoke(type)
                            }
                        }
                    )
                }
            }
        }
    }
},
```

- [ ] **Step 3: Update trailing icon to show magnifying glass when empty, search button when text entered**

Replace the `trailingIcon` parameter:

```kotlin
trailingIcon = {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (searchQuery.isEmpty()) {
            // No text: show magnifying glass icon
            Icon(
                Icons.Default.Search,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            // Has text: show clear button + search button
            IconButton(
                onClick = onClear,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = stringResource(R.string.search_clear_input),
                    modifier = Modifier.size(18.dp)
                )
            }
            TextButton(onClick = { view.performHaptic(HapticType.CLICK); onSearch() }) {
                Text(stringResource(R.string.search_button))
            }
        }
    }
}
```

- [ ] **Step 4: Verify build**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

---

## Task 3: Implement Animated Search Box Layout

**Files:**
- Modify: `app/src/main/java/com/tracktosearch/ui/screen/search/SearchScreen.kt:149-275`

**Goal:** Replace the static Column layout with an animated Box layout that moves the search box from center to top.

- [ ] **Step 1: Add animation imports**

At the top of the file, add these imports if missing:

```kotlin
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
```

- [ ] **Step 2: Add animation state variables**

After line 113 (`val focusRequester = remember { FocusRequester() }`), add:

```kotlin
// Animation state for search box position and width
val animatedYOffset = remember { Animatable(0f) }
val animatedWidthFraction = remember { Animatable(0.65f) }
val isActive = isSearchFocused || searchQuery.isNotEmpty()

// Trigger animation when active state changes
LaunchedEffect(isActive) {
    if (isActive) {
        // Animate to top and full width
        launch {
            animatedYOffset.animateTo(
                targetValue = 0f,
                animationSpec = tween(300, easing = FastOutSlowInEasing)
            )
        }
        launch {
            animatedWidthFraction.animateTo(
                targetValue = 1f,
                animationSpec = tween(300, easing = FastOutSlowInEasing)
            )
        }
    } else {
        // Animate back to center and 65% width
        launch {
            animatedYOffset.animateTo(
                targetValue = 200f,
                animationSpec = tween(300, easing = FastOutSlowInEasing)
            )
        }
        launch {
            animatedWidthFraction.animateTo(
                targetValue = 0.65f,
                animationSpec = tween(300, easing = FastOutSlowInEasing)
            )
        }
    }
}
```

- [ ] **Step 3: Replace the Column layout with animated Box layout**

Replace the entire `Column` block (lines 149-274) with:

```kotlin
Box(
    modifier = modifier
        .fillMaxSize()
        .windowInsetsPadding(WindowInsets.statusBars)
) {
    // Decorative icon - only visible in default state
    AnimatedVisibility(
        visible = !isActive,
        exit = fadeOut(animationSpec = tween(200))
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Image(
                painter = painterResource(id = R.drawable.ic_search_cloud),
                contentDescription = null,
                modifier = Modifier.size(182.dp)
            )
        }
    }

    // Search box - animated position and width
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .offset { IntOffset(0, animatedYOffset.value.roundToInt()) }
            .align(Alignment.TopCenter)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(animatedWidthFraction.value)
                .align(Alignment.Center)
        ) {
            SearchBarTop(
                searchQuery = searchQuery,
                onQueryChange = { searchQuery = it },
                onSearch = {
                    viewModel.search(searchQuery)
                    focusManager.clearFocus()
                    keyboardController?.hide()
                },
                onClear = { searchQuery = "" },
                onBack = if (isActive) onBack else null,
                focusRequester = focusRequester,
                onFocusChanged = { isSearchFocused = it },
                searchSourceType = searchSourceType,
                onSearchSourceTypeChange = onSearchSourceTypeChange
            )
        }
    }

    // Content below search box
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 80.dp)
    ) {
        when {
            // Search results
            uiState.resources.isNotEmpty() -> {
                Text(
                    text = stringResource(R.string.search_results, uiState.resources.size),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp)
                )
                SearchResultsContent(
                    resources = uiState.resources,
                    typeFilter = uiState.typeFilter,
                    viewedUrls = viewedUrls,
                    onTypeFilterChange = { viewModel.setTypeFilter(it) },
                    onItemClick = { item ->
                        openResourceLink(context, item)
                        viewModel.markViewed(item.url)
                    }
                )
            }
            // Loading
            uiState.isLoading -> {
                LoadingView(message = stringResource(R.string.search_loading))
            }
            // No results
            uiState.keyword.isNotEmpty() && uiState.resources.isEmpty() -> {
                EmptyView(message = stringResource(R.string.search_no_results))
            }
            // Default: history + popular
            else -> {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Spacer(modifier = Modifier.height(24.dp))
                    if (searchQuery.isNotEmpty()) {
                        val suggestions = remember(searchQuery, uiState.searchHistory) {
                            viewModel.getSuggestions(searchQuery)
                        }
                        if (suggestions.isNotEmpty()) {
                            SearchSuggestionsInline(
                                suggestions = suggestions,
                                onSuggestionClick = { item ->
                                    searchQuery = item.keyword
                                    viewModel.search(item.keyword)
                                    focusManager.clearFocus()
                                    keyboardController?.hide()
                                }
                            )
                        }
                    } else {
                        if (uiState.searchHistory.isNotEmpty()) {
                            SearchHistoryInline(
                                history = uiState.searchHistory,
                                onHistoryClick = { item ->
                                    searchQuery = item.keyword
                                    if (item.type == "disk") {
                                        viewModel.search(item.keyword)
                                    } else {
                                        val st = when (item.type) {
                                            "movie" -> SearchSourceType.MOVIE
                                            "show" -> SearchSourceType.SHOW
                                            "person" -> SearchSourceType.PERSON
                                            else -> SearchSourceType.DISK
                                        }
                                        onTraktSearch?.invoke(st, item.keyword)
                                    }
                                    focusManager.clearFocus()
                                    keyboardController?.hide()
                                },
                                onHistoryDelete = { viewModel.removeHistory(it.keyword) },
                                onClearAll = { viewModel.clearHistory() }
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                        }
                        PopularSearchesSection(
                            popularSearches = viewModel.popularSearches,
                            onPopularClick = { keyword ->
                                searchQuery = keyword
                                viewModel.search(keyword)
                                focusManager.clearFocus()
                                keyboardController?.hide()
                            }
                        )
                    }
                }
            }
        }
    }
}
```

- [ ] **Step 4: Add IntOffset import**

Add this import at the top of the file:

```kotlin
import androidx.compose.ui.unit.IntOffset
```

- [ ] **Step 5: Verify build**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

---

## Task 4: Fix Back Button Behavior

**Files:**
- Modify: `app/src/main/java/com/tracktosearch/ui/screen/search/SearchScreen.kt:115-127`

**Goal:** Ensure back button behavior works correctly with the new animated layout.

- [ ] **Step 1: Update BackHandler for active state**

The existing BackHandler logic should work, but verify that when the user presses back in the active state:
1. If search has results: clear results and animate back to center
2. If search is focused but no results: unfocus and animate back to center

The current logic at lines 115-127 should handle this correctly. No changes needed.

- [ ] **Step 2: Test manually**

Verify:
- Pressing back when focused with no results: search box animates back to center
- Pressing back when focused with results: results clear, search box animates back to center
- Pressing back when not focused: exits the search page (if onBack is provided)

---

## Verification Checklist

After all tasks are complete:

- [ ] Run `./gradlew assembleDebug` — must succeed
- [ ] Manual test: Open app, verify search box is centered with 65% width
- [ ] Manual test: Verify decorative icon is 182dp and centered above search box
- [ ] Manual test: Tap search box, verify smooth animation to top with width expansion
- [ ] Manual test: Verify decorative icon fades out during animation
- [ ] Manual test: Type text, verify magnifying glass disappears and "搜索" button appears
- [ ] Manual test: Verify search type text has correct colors (Green/Blue/Orange/Purple)
- [ ] Manual test: Verify search history tags have colored backgrounds
- [ ] Manual test: Press back, verify search box animates back to center

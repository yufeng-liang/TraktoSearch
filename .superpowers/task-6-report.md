# Task 6: Fix collectAsState → collectAsStateWithLifecycle

## Summary
Replaced lifecycle-unaware `collectAsState()` calls with `collectAsStateWithLifecycle()` in SearchScreen.kt and DiscoverScreen.kt. This ensures state collection stops when the screen is in the background, preventing wasted resources.

## Changes Made

### SearchScreen.kt
- Added import: `import androidx.lifecycle.compose.collectAsStateWithLifecycle`
- Line 100: `viewModel.uiState.collectAsState()` → `viewModel.uiState.collectAsStateWithLifecycle()`

### DiscoverScreen.kt
- Added import: `import androidx.lifecycle.compose.collectAsStateWithLifecycle`
- Line 79: `viewModel.uiState.collectAsState()` → `viewModel.uiState.collectAsStateWithLifecycle()`
- Line 80: `viewModel.sectionConfigs.collectAsState()` → `viewModel.sectionConfigs.collectAsStateWithLifecycle()`

## Notes
- `viewedItemStorage.viewedUrls.collectAsState(initial = emptySet())` in SearchScreen.kt was intentionally left unchanged — it's a Room database Flow (not a ViewModel StateFlow) and the task spec only targets ViewModel flows.
- Pre-existing build errors exist in other files (ScrollToTopButton.kt, DetailScreen.kt, WebViewScreen.kt) unrelated to these changes.

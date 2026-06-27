# Task 11: Merge searchPanSou and searchPanHub (DRY)

## Status: SUCCESS

## Changes Made

**File**: `app/src/main/java/com/tracktosearch/data/repository/ResourceRepository.kt`

1. **Deleted** `searchPanSou()` and `searchPanHub()` (lines 379–431)
2. **Added** single generic `searchPanSource()` method accepting `apiService`, `keyword`, `enabledDiskTypes`, and `sourceName`
3. **Updated 4 call sites** in `searchResourcesFlow()` and `fetchEnabledSources()` to pass the correct API service and source name constants

## Build Verification

`./gradlew assembleDebug` — all compilation errors are **pre-existing** in unrelated files (`ScrollToTopButton.kt`, `DetailScreen.kt`, `SearchScreen.kt`, `WebViewScreen.kt`). No errors from `ResourceRepository.kt`.

## Net Impact

- **-50 lines** of duplicated code eliminated
- Behavior preserved identically — `sourceName` parameter replaces hardcoded `SOURCE_PANSOU`/`SOURCE_PANHUB`

# Task 2: Fix runBlocking in DiscoverViewModel (ANR Risk)

## Status: Completed

## Changes Made
1. **Removed `runBlocking` call** in `DiscoverViewModel.kt` line 107 — replaced with a static default value that mirrors what `DiscoverSectionStorage.sectionConfigs` emits when no preferences are saved (all sections visible, default order).
2. **Removed unused import** `kotlinx.coroutines.runBlocking`.

## Files Modified
- `app/src/main/java/com/tracktosearch/ui/screen/discover/DiscoverViewModel.kt`

## Build Verification
Ran `./gradlew assembleDebug`. Pre-existing compilation errors in unrelated files (ScrollToTopButton.kt, DetailScreen.kt, SearchScreen.kt, WebViewScreen.kt) prevent a clean build, but the DiscoverViewModel.kt changes compiled successfully — no new errors introduced.

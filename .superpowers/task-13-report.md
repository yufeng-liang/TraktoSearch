# Task 13: Merge TraktRepository Pagination Methods (DRY)

## Status: Complete

## What was done

Added a generic `fetchAllPages<T>` private helper method to `TraktRepository.kt` and rewrote four identical pagination methods to use it, eliminating ~50 lines of duplicated code.

## Changes

**File**: `app/src/main/java/com/tracktosearch/data/repository/TraktRepository.kt`

1. Added `private suspend fun <T> fetchAllPages(fetchPage: suspend (page: Int) -> Result<Pair<List<T>, Int>>): Result<List<T>>` — a generic pagination helper that handles page iteration and error propagation.

2. Simplified four methods to single-expression calls:
   - `getAllMovieHistory()` → `fetchAllPages { page -> getMovieHistory(page = page, limit = 200) }`
   - `getAllShowHistory()` → `fetchAllPages { page -> getShowHistory(page = page, limit = 200) }`
   - `getAllMovieWatchlist()` → `fetchAllPages { page -> getMovieWatchlist(page = page, limit = 200) }`
   - `getAllShowWatchlist()` → `fetchAllPages { page -> getShowWatchlist(page = page, limit = 200) }`

## Net reduction

~50 lines of duplicated pagination boilerplate replaced with ~15 lines (1 helper + 4 one-liners).

## Build verification

`./gradlew assembleDebug` was run. All errors are **pre-existing** in unrelated files (`ScrollToTopButton.kt`, `DetailScreen.kt`, `SearchScreen.kt`, `WebViewScreen.kt`). No errors in `TraktRepository.kt`.

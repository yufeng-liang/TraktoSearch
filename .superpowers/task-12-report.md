# Task 12: Merge WatchlistViewModel MovieUiItem/ShowUiItem (DRY)

## Status: ✅ Complete

## Changes Made

### WatchlistViewModel.kt
- **Replaced `MovieUiItem` and `ShowUiItem`** with a single `MediaUiItem` data class (lines 27-38)
- **Updated `WatchlistUiState`** to use `MediaUiItem` for `movies`, `shows`, `historyMovies`, and `historyShows` lists
- **Merged `enrichMovieItem` and `enrichShowItem`** into `enrichMediaItem()` with an `isMovie: Boolean` parameter that routes to `tmdbRepository.enrichMovie()` or `tmdbRepository.enrichTv()` accordingly
- **Merged `createPlaceholderMovie` and `createPlaceholderShow`** into a single `createPlaceholder()` that accepts raw parameters
- **Merged `toMovieUiItem` and `toShowUiItem`** into a single `toMediaUiItem()` extension on `MediaItemEntity`
- **Consolidated `toMediaItemEntity`** extensions — now a single extension on `MediaUiItem` (was two identical ones on `MovieUiItem`/`ShowUiItem`)
- **Updated all callers** in `loadMovies`, `loadShows`, `loadHistoryMovies`, `loadHistoryShows`, and `loadMoreMovies` to use the unified methods
- **Removed unused imports**: `TraktWatchlistMovieItem`, `TraktWatchlistShowItem`, `MediaType`

### WatchlistScreen.kt
- Updated `MovieTabContent` composable: `List<MovieUiItem>` → `List<MediaUiItem>`, callback type updated
- Updated `ShowTabContent` composable: `List<ShowUiItem>` → `List<MediaUiItem>`, callback type updated
- Updated `MovieGrid` composable: `List<MovieUiItem>` → `List<MediaUiItem>`, callback type updated
- Updated `ShowGrid` composable: `List<ShowUiItem>` → `List<MediaUiItem>`, callback type updated

## Build Verification
- `./gradlew assembleDebug` was run
- **Zero errors from watchlist files** — all remaining errors are pre-existing in other files (DetailScreen, SearchScreen, WebViewScreen, ScrollToTopButton)
- No references to `MovieUiItem` or `ShowUiItem` remain anywhere in the project

## Lines Reduced
- WatchlistViewModel.kt: 554 → ~499 lines (removed ~55 lines of duplicated code)

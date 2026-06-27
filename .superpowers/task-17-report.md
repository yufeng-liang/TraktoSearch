# Task 17: Remove themeMode from WatchlistViewModel

## Summary
Successfully removed theme management duplication from `WatchlistViewModel`. Theme state is now only owned by `SettingsViewModel` / `ThemeStorage`.

## Changes Made

### WatchlistViewModel.kt
- Removed `ThemeStorage` import
- Removed `SharingStarted` and `stateIn` imports (no longer needed)
- Removed `themeStorage: ThemeStorage` from constructor parameters
- Removed `themeMode` StateFlow property
- Removed `setThemeMode()` function

### WatchlistScreen.kt
- Removed `ThemeStorage` import
- Removed `val currentTheme by viewModel.themeMode.collectAsState()` line
- Removed unused `ThemeSelectionDialog` composable (was private, never called)
- Removed unused `ThemeOptionRow` composable (was private, never called)

## Verification
Build completed with pre-existing errors only (in ScrollToTopButton.kt, DetailScreen.kt, SearchScreen.kt, WebViewScreen.kt). No compilation errors from the modified files.

## Notes
- The `ThemeSelectionDialog` and `ThemeOptionRow` composables in WatchlistScreen.kt were dead code — never invoked from any composable in the file
- `viewModel.setThemeMode` was also unused (not called anywhere)
- `viewModel.themeMode` was collected but `currentTheme` was never read after collection
- Theme switching functionality remains fully available via SettingsViewModel / SettingsScreen

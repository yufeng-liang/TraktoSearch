# Task 16: Fix ViewedItemStorage Naming

## Summary
Renamed `getviewedUrls()` to `getViewedUrls()` (camelCase) in the method definition and all callers.

## Changes

1. **ViewedItemStorage.kt:33** — Renamed method from `getviewedUrls()` to `getViewedUrls()`
2. **DetailViewModel.kt:1002** — Updated caller to use `getViewedUrls()`

## Verification
- `./gradlew assembleDebug` — Build failed with **pre-existing errors** unrelated to this task (ScrollToTopButton.kt, DetailScreen.kt, SearchScreen.kt, WebViewScreen.kt). No errors related to the rename.

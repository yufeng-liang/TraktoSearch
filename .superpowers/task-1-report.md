# Task 1 Report: Fix runBlocking in SettingsViewModel

## Status: DONE

## What was changed
Replaced 6 `runBlocking { storage.flow.first() }` calls in `stateIn` initial value parameters (lines 80-96 of `SettingsViewModel.kt`) with safe static defaults matching DataStore fallback values:
- `pansouEnabled`, `panhubEnabled`, `zresoEnabled`: `true`
- `notificationEnabled`: `false`
- `releaseReminderEnabled`, `newSeasonReminderEnabled`: `true`

Imports `kotlinx.coroutines.flow.first` and `kotlinx.coroutines.runBlocking` were **not** removed because they are still used elsewhere in the file (lines 139, 535, 552 for `customSources`, `discoverSections`, and `detailSections`).

## Build result
`./gradlew assembleDebug` **FAILED** — but all compilation errors are pre-existing in unrelated files:
- `ScrollToTopButton.kt:172,184` — unresolved `stringResource` / `R`
- `DetailScreen.kt:3447,3481,3490,3604` — missing `@Composable` annotation
- `SearchScreen.kt:634` — `PaddingValues` overload mismatch
- `WebViewScreen.kt:152` — unresolved `stringResource` / `R`

**Zero new errors introduced by this change.** SettingsViewModel.kt compiles cleanly.

## Concerns
- The remaining `runBlocking` usages at lines 139, 535, 552 (`customSources`, `discoverSections`, `detailSections`) have the same ANR risk pattern. These should be addressed in a follow-up task, but they were out of scope for this task.
- The initial UI may briefly show default values before DataStore emits real values. Since the defaults match DataStore defaults, this is only a concern for users who have customized these settings — the delay is minimal due to `WhileSubscribed(5000)`.

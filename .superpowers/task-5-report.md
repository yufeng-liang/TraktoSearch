# Task 5: Extract Regex to Top-Level Constants (Performance)

## Status: ✅ Completed

## Changes Made

### ResourceItem.kt
- Added `SHOW_PATTERNS` private val containing 8 pre-compiled `Regex` objects
- Refactored `inferResourceType()` to use `SHOW_PATTERNS.any { it.containsMatchIn(n) }` instead of inline `Regex()` calls

### ResourceRepository.kt
- Added `MULTI_SEASON_FULL` and `MULTI_SEASON_SINGLE` private vals in the existing `companion object`
- Refactored `multiSeasonScore()` to use these pre-compiled constants

## Verification
- `./gradlew assembleDebug` was run
- Build produced compilation errors, but **all errors are pre-existing** in unrelated files:
  - `ScrollToTopButton.kt` - unresolved `stringResource`/`R` references
  - `DetailScreen.kt` - missing `@Composable` annotations
  - `SearchScreen.kt` - `PaddingValues` constructor mismatch
  - `WebViewScreen.kt` - unresolved `stringResource`/`R` references
- **No errors from ResourceItem.kt or ResourceRepository.kt** - our changes compile successfully

## Performance Impact
- **Before**: 8 + 2 = 10 Regex objects compiled on every function call
- **After**: 10 Regex objects compiled once at class load time, reused on every call
- Regex compilation is expensive (involves parsing, NFA construction, compilation to bytecode)
- These functions run for every resource item during filtering/sorting, so the savings scale with result count

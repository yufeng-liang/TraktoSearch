# Task 9 Report: Fix ZReso Only Queries First Disk Type

## Problem
`searchZreso()` in `ResourceRepository.kt` only sent `enabledDiskTypes.firstOrNull()` to the API, meaning if a user enabled Quark, Baidu, and Ali, only Quark results were fetched.

## Changes Made
**File:** `app/src/main/java/com/tracktosearch/data/repository/resourceRepository.kt`

### 1. Fixed `searchZreso` method (lines 444-475)
- Changed API call to use `cloud = ""` (empty string) instead of the first disk type only
- Now builds `allowedTypes` set from all enabled disk types
- Uses `flatMap` on results to process ALL links per result (not just the first)
- Filters links client-side by checking if the link's type is in `allowedTypes`

### 2. Removed `mapZresoTypeFirst` function (lines 477-486)
- No longer needed since filtering is done by string comparison against `allowedTypes`

## Build Verification
- Build failed with pre-existing errors in other files (ScrollToTopButton.kt, DetailScreen.kt, SearchScreen.kt, WebViewScreen.kt)
- **No errors in ResourceRepository.kt** - the change compiles correctly

## Impact
- Users with multiple disk types enabled will now receive results from ALL enabled types
- Empty/whitespace-only disk type strings are filtered out
- Behavior remains backward-compatible when only one disk type is enabled

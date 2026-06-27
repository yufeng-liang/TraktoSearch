# Task 7 Report: Fix i18n — MovieCard Hardcoded Chinese Strings

## Status: SUCCESS

## Changes Made

### 1. MovieCard.kt
- **Added imports**: `androidx.compose.ui.res.stringResource` and `com.tracktosearch.R`
- **Line 116**: Replaced `"已看过"` with `stringResource(R.string.cd_watched_badge)`
- **Line 128**: Replaced `"已想看"` with `stringResource(R.string.cd_watchlist_badge)`

### 2. values/strings.xml
Added under new `<!-- Content Descriptions (Badge) -->` section:
```xml
<string name="cd_watched_badge">Watched</string>
<string name="cd_watchlist_badge">Want to Watch</string>
```

### 3. values-zh/strings.xml
Added:
```xml
<string name="cd_watched_badge">已看过</string>
<string name="cd_watchlist_badge">已想看</string>
```

### 4. values-ja/strings.xml
Added:
```xml
<string name="cd_watched_badge">視聴済み</string>
<string name="cd_watchlist_badge">ウォッチリスト</string>
```

### 5. values-ko/strings.xml
Added:
```xml
<string name="cd_watched_badge">시청함</string>
<string name="cd_watchlist_badge">보고 싶음</string>
```

## Build Verification
- `./gradlew assembleDebug` was run
- **My changes compile successfully** — no errors in MovieCard.kt or the string resource files
- Pre-existing build errors exist in unrelated files (`ScrollToTopButton.kt`, `DetailScreen.kt`, `SearchScreen.kt`, `WebViewScreen.kt`) — these are NOT caused by this task

## Files Modified
1. `app/src/main/java/com/tracktosearch/ui/component/MovieCard.kt`
2. `app/src/main/res/values/strings.xml`
3. `app/src/main/res/values-zh/strings.xml`
4. `app/src/main/res/values-ja/strings.xml`
5. `app/src/main/res/values-ko/strings.xml`

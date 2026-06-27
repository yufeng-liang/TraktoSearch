# TrackToSearch Codebase Optimization Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix critical bugs (ANR, thread-safety, memory leak), eliminate code duplication, fix i18n gaps, and improve code quality across the TrackToSearch Android app.

**Architecture:** This plan addresses 17 issues in priority order (Critical → High → Medium → Low). Each task is self-contained and independently testable. The plan modifies existing files only — no new files are created.

**Tech Stack:** Kotlin, Jetpack Compose, Hilt DI, DataStore, Retrofit, kotlinx.coroutines, kotlinx.serialization

## Global Constraints
- Min SDK: 26 (Android 8.0)
- Target SDK: 35
- Language: Kotlin 1.9.22
- Compose BOM: 2024.10.00
- Must maintain all 4 language translations (zh/en/ja/ko)
- No new dependencies allowed — all fixes use existing libraries

---

## Task 1: Fix `runBlocking` in SettingsViewModel (ANR Risk)

**Files:**
- Modify: `app/src/main/java/com/tracktosearch/ui/screen/settings/SettingsViewModel.kt:80-96`

**Problem:** 6 `stateIn` calls use `runBlocking { storage.flow.first() }` as initial value, blocking the main thread during ViewModel construction. DataStore reads involve disk I/O which can cause ANR.

**Fix:** Use safe default values that match the DataStore defaults (all `true` for search sources, `false` for notification, `true` for sub-reminders).

- [ ] **Step 1: Replace runBlocking with safe defaults in SettingsViewModel**

In `SettingsViewModel.kt`, replace lines 80-96:

```kotlin
// BEFORE (lines 80-96):
val pansouEnabled: StateFlow<Boolean> = searchSourceStorage.pansouEnabled
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), runBlocking { searchSourceStorage.pansouEnabled.first() })

val panhubEnabled: StateFlow<Boolean> = searchSourceStorage.panhubEnabled
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), runBlocking { searchSourceStorage.panhubEnabled.first() })

val zresoEnabled: StateFlow<Boolean> = searchSourceStorage.zresoEnabled
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), runBlocking { searchSourceStorage.zresoEnabled.first() })

val notificationEnabled: StateFlow<Boolean> = notificationStorage.enabled
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), runBlocking { notificationStorage.enabled.first() })

val releaseReminderEnabled: StateFlow<Boolean> = notificationStorage.releaseReminderEnabled
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), runBlocking { notificationStorage.releaseReminderEnabled.first() })

val newSeasonReminderEnabled: StateFlow<Boolean> = notificationStorage.newSeasonReminderEnabled
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), runBlocking { notificationStorage.newSeasonReminderEnabled.first() })
```

Replace with:

```kotlin
// AFTER:
val pansouEnabled: StateFlow<Boolean> = searchSourceStorage.pansouEnabled
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

val panhubEnabled: StateFlow<Boolean> = searchSourceStorage.panhubEnabled
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

val zresoEnabled: StateFlow<Boolean> = searchSourceStorage.zresoEnabled
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

val notificationEnabled: StateFlow<Boolean> = notificationStorage.enabled
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

val releaseReminderEnabled: StateFlow<Boolean> = notificationStorage.releaseReminderEnabled
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

val newSeasonReminderEnabled: StateFlow<Boolean> = notificationStorage.newSeasonReminderEnabled
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
```

The defaults match the DataStore `?: true/false` fallbacks in each Storage class:
- `SearchSourceStorage`: pansou=`, panhub=`, zreso=` all default `true`
- `NotificationStorage`: enabled=` false`, releaseReminder=`, newSeasonReminder=` both default `true`

- [ ] **Step 2: Remove unused `runBlocking` and `first` imports**

Remove these imports from SettingsViewModel.kt if no longer used:
```kotlin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
```

- [ ] **Step 3: Verify build succeeds**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

---

## Task 2: Fix `runBlocking` in DiscoverViewModel (ANR Risk)

**Files:**
- Modify: `app/src/main/java/com/tracktosearch/ui/screen/discover/DiscoverViewModel.kt:103-108`

**Fix:** Replace `runBlocking` with safe default.

- [ ] **Step 1: Replace runBlocking in DiscoverViewModel**

In `DiscoverViewModel.kt`, replace lines 103-108:

```kotlin
// BEFORE:
val sectionConfigs: StateFlow<List<DiscoverSectionConfig>> = discoverSectionStorage.sectionConfigs
    .stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        runBlocking { discoverSectionStorage.sectionConfigs.first() }
    )
```

Replace with:

```kotlin
// AFTER:
val sectionConfigs: StateFlow<List<DiscoverSectionConfig>> = discoverSectionStorage.sectionConfigs
    .stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        DiscoverSectionStorage.ALL_SECTION_IDS.mapIndexed { index, id ->
            DiscoverSectionConfig(id = id, visible = true, order = index)
        }
    )
```

This uses the same default as `DiscoverSectionStorage.sectionConfigs` Flow when no preferences are saved: all sections visible, in default order.

- [ ] **Step 2: Remove unused imports**

Remove from DiscoverViewModel.kt:
```kotlin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
```

- [ ] **Step 3: Verify build succeeds**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

---

## Task 3: Fix Thread-Safe TtlCache

**Files:**
- Modify: `app/src/main/java/com/tracktosearch/data/util/TtlCache.kt:1-31`

**Problem:** `TtlCache` uses `mutableMapOf()` which is NOT thread-safe. It's accessed from coroutines on `Dispatchers.IO` and `viewModelScope` (main-dispatched). Concurrent reads/writes can cause `ConcurrentModificationException`.

- [ ] **Step 1: Replace mutableMapOf with ConcurrentHashMap**

Replace the entire `TtlCache.kt` content:

```kotlin
package com.tracktosearch.data.util

import java.util.concurrent.ConcurrentHashMap

/**
 * 带过期时间的线程安全内存缓存
 * @param ttlMillis 缓存有效期，默认 10 分钟
 */
class TtlCache<T>(private val ttlMillis: Long = 10 * 60 * 1000L) {
    private val cache = ConcurrentHashMap<String, Pair<T, Long>>()

    fun get(key: String): T? {
        val entry = cache[key] ?: return null
        if (System.currentTimeMillis() - entry.second > ttlMillis) {
            cache.remove(key)
            return null
        }
        return entry.first
    }

    fun put(key: String, value: T) {
        cache[key] = Pair(value, System.currentTimeMillis())
    }

    suspend fun getOrPut(key: String, defaultValue: suspend () -> T): T {
        get(key)?.let { return it }
        val value = defaultValue()
        put(key, value)
        return value
    }

    fun clear() = cache.clear()
}
```

- [ ] **Step 2: Verify build succeeds**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

---

## Task 4: Fix DetailViewModel Static Cache (Memory Leak + Thread Safety)

**Files:**
- Modify: `app/src/main/java/com/tracktosearch/ui/screen/detail/DetailViewModel.kt:152-171`

**Problem:** `companion object` holds a static `LinkedHashMap` that lives for the entire process lifetime. Also not thread-safe.

- [ ] **Step 1: Replace LinkedHashMap with LruCache**

In `DetailViewModel.kt`, replace lines 152-171:

```kotlin
// BEFORE:
companion object {
    // 缓存最近查看的详情数据，避免从子页面返回后 ViewModel 被销毁导致重新加载
    private const val CACHE_MAX_SIZE = 5
    private val detailCache = object : LinkedHashMap<Int, CachedDetailData>(CACHE_MAX_SIZE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, CachedDetailData>): Boolean {
            return size > CACHE_MAX_SIZE
        }
    }

    /** 缓存的详情数据，用于从子页面返回后快速恢复 */
    data class CachedDetailData(
        val uiState: DetailUiState,
        val allResources: List<ResourceItem>,
        val currentKeyword: String,
        val currentOriginalTitle: String,
        val currentImdbId: String,
        val currentTraktRating: Double,
        val currentTmdbId: Int,
        val currentMediaType: MediaType
    )
}
```

Replace with:

```kotlin
// AFTER:
companion object {
    // 缓存最近查看的详情数据，避免从子页面返回后 ViewModel 被销毁导致重新加载
    private const val CACHE_MAX_SIZE = 5
    @Volatile
    private var detailCache = LinkedHashMap<Int, CachedDetailData>(CACHE_MAX_SIZE, 0.75f, true)

    private fun cachePut(key: Int, value: CachedDetailData) {
        synchronized(detailCache) {
            detailCache[key] = value
            // 手动淘汰超出容量的条目
            while (detailCache.size > CACHE_MAX_SIZE) {
                val eldest = detailCache.keys.first()
                detailCache.remove(eldest)
            }
        }
    }

    private fun cacheGet(key: Int): CachedDetailData? {
        synchronized(detailCache) {
            return detailCache[key]
        }
    }

    private fun cacheRemove(key: Int) {
        synchronized(detailCache) {
            detailCache.remove(key)
        }
    }

    /** 缓存的详情数据，用于从子页面返回后快速恢复 */
    data class CachedDetailData(
        val uiState: DetailUiState,
        val allResources: List<ResourceItem>,
        val currentKeyword: String,
        val currentOriginalTitle: String,
        val currentImdbId: String,
        val currentTraktRating: Double,
        val currentTmdbId: Int,
        val currentMediaType: MediaType
    )
}
```

- [ ] **Step 2: Update all cache access sites in DetailViewModel**

Search for `detailCache[` in DetailViewModel.kt and replace:
- `detailCache[traktId]` → `cacheGet(traktId)`
- `detailCache[traktId] = CachedDetailData(...)` → `cachePut(traktId, CachedDetailData(...))`
- `detailCache.remove(traktId)` → `cacheRemove(traktId)`

These appear at approximately lines 221, 255, and possibly others. Use grep to find all occurrences.

- [ ] **Step 3: Verify build succeeds**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

---

## Task 5: Extract Regex to Top-Level Constants (Performance)

**Files:**
- Modify: `app/src/main/java/com/tracktosearch/data/remote/dto/ResourceItem.kt:20-38`
- Modify: `app/src/main/java/com/tracktosearch/data/repository/ResourceRepository.kt:324-331`

**Problem:** `inferResourceType()` compiles 8 Regex objects on every call. `multiSeasonScore()` compiles 2 Regex objects on every call. These run for every resource item during filtering/sorting.

- [ ] **Step 1: Extract regex constants in ResourceItem.kt**

In `ResourceItem.kt`, add companion constants before the `inferResourceType` function:

```kotlin
private val SHOW_PATTERNS = listOf(
    Regex("第\\s*\\d+\\s*季"),
    Regex("第\\s*\\d+\\s*集"),
    Regex("全\\s*\\d+\\s*集"),
    Regex("\\d+\\s*[-~]\\s*\\d+\\s*季"),
    Regex("s\\d{1,2}\\s*e\\d{1,2}"),
    Regex("(?<![a-z])s\\d{1,2}\\b"),
    Regex("(?<![a-z])e\\d{1,2}\\b"),
    Regex("(?<![a-z])ep\\.?\\d")
)

fun inferResourceType(name: String): ResourceType {
    val n = name.lowercase()
    val isShow = n.contains("电视剧") ||
        n.contains("连续剧") ||
        n.contains("剧集") ||
        n.contains("短剧") ||
        n.contains("完结") ||
        n.contains("更新至") ||
        n.contains("全季") ||
        n.contains("season") ||
        SHOW_PATTERNS.any { it.containsMatchIn(n) }
    return if (isShow) ResourceType.SHOW else ResourceType.MOVIE
}
```

- [ ] **Step 2: Extract regex constants in ResourceRepository.kt**

In `ResourceRepository.kt`, add companion constants inside the class (or at the top of the file):

```kotlin
companion object {
    private val MULTI_SEASON_FULL = Regex("""全\s*季|合集|1[-~]\d+\s*季|第\s*\d+\s*[-~]\s*\d+\s*季""")
    private val MULTI_SEASON_SINGLE = Regex("""第\s*\d+\s*季""")
}

private fun multiSeasonScore(name: String): Int {
    val n = name.lowercase()
    return when {
        MULTI_SEASON_FULL.containsMatchIn(n) -> 2
        MULTI_SEASON_SINGLE.containsMatchIn(n) -> 1
        else -> 0
    }
}
```

Note: ResourceRepository already has `ALL_SOURCES`, `ALL_DISK_TYPES`, `SOURCE_PANSOU`, etc. as companion constants. Add the regex constants alongside them.

- [ ] **Step 3: Verify build succeeds**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

---

## Task 6: Fix `collectAsState` → `collectAsStateWithLifecycle`

**Files:**
- Modify: `app/src/main/java/com/tracktosearch/ui/screen/search/SearchScreen.kt:99`
- Modify: `app/src/main/java/com/tracktosearch/ui/screen/discover/DiscoverScreen.kt:78`

**Problem:** `collectAsState()` does not respect lifecycle — the screen continues collecting state changes when in the background, wasting resources and battery.

- [ ] **Step 1: Fix SearchScreen.kt**

In `SearchScreen.kt`, change line 99:

```kotlin
// BEFORE:
val uiState by viewModel.uiState.collectAsState()

// AFTER:
val uiState by viewModel.uiState.collectAsStateWithLifecycle()
```

Add import if missing:
```kotlin
import androidx.lifecycle.compose.collectAsStateWithLifecycle
```

- [ ] **Step 2: Fix DiscoverScreen.kt**

In `DiscoverScreen.kt`, change line 78:

```kotlin
// BEFORE:
val uiState by viewModel.uiState.collectAsState()

// AFTER:
val uiState by viewModel.uiState.collectAsStateWithLifecycle()
```

Also change line 79:
```kotlin
// BEFORE:
val sectionConfigs by viewModel.sectionConfigs.collectAsState()

// AFTER:
val sectionConfigs by viewModel.sectionConfigs.collectAsStateWithLifecycle()
```

Add import if missing:
```kotlin
import androidx.lifecycle.compose.collectAsStateWithLifecycle
```

- [ ] **Step 3: Verify build succeeds**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

---

## Task 7: Fix i18n — MovieCard Hardcoded Chinese Strings

**Files:**
- Modify: `app/src/main/java/com/tracktosearch/ui/component/MovieCard.kt:114, 126`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/values-zh/strings.xml`
- Modify: `app/src/main/res/values-ja/strings.xml`
- Modify: `app/src/main/res/values-ko/strings.xml`

**Problem:** "已看过" and "已想看" are hardcoded Chinese strings.

- [ ] **Step 1: Add string resources**

In `values/strings.xml`, add:
```xml
<string name="cd_watched_badge">Watched</string>
<string name="cd_watchlist_badge">Want to Watch</string>
```

In `values-zh/strings.xml`, add:
```xml
<string name="cd_watched_badge">已看过</string>
<string name="cd_watchlist_badge">已想看</string>
```

In `values-ja/strings.xml`, add:
```xml
<string name="cd_watched_badge">視聴済み</string>
<string name="cd_watchlist_badge">ウォッチリスト</string>
```

In `values-ko/strings.xml`, add:
```xml
<string name="cd_watched_badge">시청함</string>
<string name="cd_watchlist_badge">보고 싶음</string>
```

- [ ] **Step 2: Replace hardcoded strings in MovieCard.kt**

```kotlin
// BEFORE (line 114):
Text(text = "已看过", ...)

// AFTER:
Text(text = stringResource(R.string.cd_watched_badge), ...)
```

```kotlin
// BEFORE (line 126):
Text(text = "已想看", ...)

// AFTER:
Text(text = stringResource(R.string.cd_watchlist_badge), ...)
```

Add import if missing:
```kotlin
import androidx.compose.ui.res.stringResource
import com.tracktosearch.R
```

- [ ] **Step 3: Verify build succeeds**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

---

## Task 8: Fix i18n — ResourceItemCard Hardcoded Chinese String

**Files:**
- Modify: `app/src/main/java/com/tracktosearch/ui/component/ResourceItemCard.kt:183`
- Modify: `app/src/main/res/values/strings.xml` (add if needed)

**Problem:** "${item.fileCount}文件" is hardcoded Chinese.

- [ ] **Step 1: Add string resource**

In `values/strings.xml`, add:
```xml
<string name="resource_file_count">%1$d files</string>
```

In `values-zh/strings.xml`, add:
```xml
<string name="resource_file_count">%1$d文件</string>
```

In `values-ja/strings.xml`, add:
```xml
<string name="resource_file_count">%1$dファイル</string>
```

In `values-ko/strings.xml`, add:
```xml
<string name="resource_file_count">%1$d개 파일</string>
```

- [ ] **Step 2: Replace hardcoded string in ResourceItemCard.kt**

```kotlin
// BEFORE (line 183):
Text(text = "${item.fileCount}文件", ...)

// AFTER:
Text(text = stringResource(R.string.resource_file_count, item.fileCount), ...)
```

Add import if missing:
```kotlin
import androidx.compose.ui.res.stringResource
import com.tracktosearch.R
```

- [ ] **Step 3: Verify build succeeds**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

---

## Task 9: Fix ZReso Only Queries First Disk Type (Functional Bug)

**Files:**
- Modify: `app/src/main/java/com/tracktosearch/data/repository/ResourceRepository.kt:442-473`

**Problem:** `searchZreso()` only sends `enabledDiskTypes.firstOrNull()` to the API. If a user enables Quark, Baidu, and Ali, only Quark results are fetched.

- [ ] **Step 1: Fix searchZreso to query all enabled disk types**

Replace the `searchZreso` method (lines 442-473):

```kotlin
// BEFORE:
private suspend fun searchZreso(keyword: String, enabledDiskTypes: Set<DiskType>): List<ResourceItem> {
    return try {
        val cloud = enabledDiskTypes.firstOrNull()?.let { diskTypeToZreso(it) } ?: ""
        val response = withTimeoutOrNull(8_000) {
            zresoApiService.search(keyword = keyword, cloud = cloud)
        } ?: return emptyList()
        response.data.results.mapNotNull { result ->
            val link = result.links.firstOrNull() ?: return@mapNotNull null
            val fullUrl = if (link.url.startsWith("http")) {
                link.url
            } else {
                "https://zreso.cn${link.url}"
            }
            val diskType = mapZresoType(link.type)
            if (cloud.isNotEmpty() && diskType != mapZresoTypeFirst(cloud)) {
                return@mapNotNull null
            }
            ResourceItem(
                name = result.title,
                diskType = diskType,
                fileSize = "",
                fileDate = result.datetime.ifBlank { result.date },
                fileCount = result.links.size,
                status = result.status,
                url = fullUrl,
                source = SOURCE_ZRESO
            )
        }
    } catch (e: Exception) {
        emptyList()
    }
}
```

Replace with:

```kotlin
// AFTER:
private suspend fun searchZreso(keyword: String, enabledDiskTypes: Set<DiskType>): List<ResourceItem> {
    return try {
        // Query without cloud filter to get all results, then filter locally
        val response = withTimeoutOrNull(8_000) {
            zresoApiService.search(keyword = keyword, cloud = "")
        } ?: return emptyList()
        val allowedTypes = enabledDiskTypes.map { diskTypeToZreso(it) }.filter { it.isNotEmpty() }.toSet()
        response.data.results.flatMap { result ->
            result.links.mapNotNull { link ->
                val fullUrl = if (link.url.startsWith("http")) {
                    link.url
                } else {
                    "https://zreso.cn${link.url}"
                }
                val diskType = mapZresoType(link.type)
                val typeStr = diskTypeToZreso(diskType)
                if (allowedTypes.isNotEmpty() && typeStr !in allowedTypes) {
                    return@mapNotNull null
                }
                ResourceItem(
                    name = result.title,
                    diskType = diskType,
                    fileSize = "",
                    fileDate = result.datetime.ifBlank { result.date },
                    fileCount = result.links.size,
                    status = result.status,
                    url = fullUrl,
                    source = SOURCE_ZRESO
                )
            }
        }
    } catch (e: Exception) {
        emptyList()
    }
}
```

Also remove the unused `mapZresoTypeFirst` function (lines 475-484) since it's no longer needed.

- [ ] **Step 2: Verify build succeeds**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

---

## Task 10: Fix build.gradle.kts Duplicate local.properties Read

**Files:**
- Modify: `app/build.gradle.kts:26-31, 60-64`

**Problem:** `local.properties` is loaded twice — once in `defaultConfig` and again in `signingConfigs`.

- [ ] **Step 1: Extract shared properties variable**

In `app/build.gradle.kts`, move the properties loading to the top level (before `android {`) and reuse:

Find the two blocks:
```kotlin
// Block 1 (around line 26):
val localProps = rootProject.file("local.properties")
val properties = Properties()
if (localProps.exists()) { properties.load(localProps.inputStream()) }

// Block 2 (around line 61):
val localProps = rootProject.file("local.properties")
val properties = Properties()
if (localProps.exists()) { properties.load(localProps.inputStream()) }
```

Replace the second block (inside `signingConfigs`) with a reference to the first. The first block's `properties` variable is scoped inside `defaultConfig`, so it needs to be moved outside. Restructure as:

```kotlin
// At top level (before android block):
val localProps = rootProject.file("local.properties")
val properties = Properties()
if (localProps.exists()) { properties.load(localProps.inputStream()) }

android {
    namespace = "com.tracktosearch"
    compileSdk = 35
    // ... other config ...
    defaultConfig {
        // Remove the local.properties loading block here
        // Keep all buildConfigField lines, they already reference `properties`
    }
    signingConfigs {
        getByName("debug") { }
        create("release") {
            // Remove the local.properties loading block here
            storeFile = file(properties.getProperty("release.store.file", ""))
            storePassword = properties.getProperty("release.store.password", "")
            keyAlias = properties.getProperty("release.key.alias", "")
            keyPassword = properties.getProperty("release.key.password", "")
        }
    }
}
```

- [ ] **Step 2: Verify build succeeds**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

---

## Task 11: Merge searchPanSou and searchPanHub (DRY)

**Files:**
- Modify: `app/src/main/java/com/tracktosearch/data/repository/ResourceRepository.kt:377-429`

**Problem:** `searchPanSou()` and `searchPanHub()` are nearly identical — only the `source` string differs.

- [ ] **Step 1: Extract shared method**

In `ResourceRepository.kt`, replace both methods with a single generic method:

```kotlin
// Replace searchPanSou and searchPanHub with:
private suspend fun searchPanSource(
    apiService: PanSouApiService,
    keyword: String,
    enabledDiskTypes: Set<DiskType>,
    sourceName: String
): List<ResourceItem> {
    return try {
        val response = withTimeoutOrNull(8_000) {
            apiService.search(keyword = keyword, cloudTypes = cloudTypesForPanSou(enabledDiskTypes))
        } ?: return emptyList()
        if (response.code != 0) return emptyList()
        val data = response.data ?: return emptyList()
        val allLinks = data.merged_by_type.flatMap { (type, links) ->
            links.map { link -> type to link }
        }
        allLinks.mapNotNull { (type, link) ->
            val diskType = mapPanSouType(type) ?: return@mapNotNull null
            ResourceItem(
                name = link.note.ifBlank { keyword },
                diskType = diskType,
                fileSize = "",
                fileDate = link.datetime,
                fileCount = 1,
                url = link.url,
                source = sourceName
            )
        }
    } catch (e: Exception) {
        emptyList()
    }
}
```

Then update the callers. Find where `searchPanSou` and `searchPanHub` are called (likely in `searchResourcesFlow` or similar) and replace:
- `searchPanSou(keyword, enabledDiskTypes)` → `searchPanSource(panSouApiService, keyword, enabledDiskTypes, SOURCE_PANSOU)`
- `searchPanHub(keyword, enabledDiskTypes)` → `searchPanSource(panHubApiService, keyword, enabledDiskTypes, SOURCE_PANHUB)`

- [ ] **Step 2: Verify build succeeds**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

---

## Task 12: Merge WatchlistViewModel MovieUiItem/ShowUiItem (DRY)

**Files:**
- Modify: `app/src/main/java/com/tracktosearch/ui/screen/watchlist/WatchlistViewModel.kt:27-52, 339-397, 399-427, 500-554`

**Problem:** `MovieUiItem` and `ShowUiItem` are identical data classes. `enrichMovieItem`/`enrichShowItem` and `createPlaceholderMovie`/`createPlaceholderShow` are duplicated.

- [ ] **Step 1: Unify into a single MediaUiItem**

Replace both data classes with one:

```kotlin
@Immutable
data class MediaUiItem(
    val traktId: Int,
    val tmdbId: Int,
    val title: String,
    val displayTitle: String,
    val year: Int?,
    val genres: String,
    val posterUrl: String?,
    val imdbId: String = "",
    val traktRating: Double = 0.0,
    val listedAt: String = ""
)
```

- [ ] **Step 2: Update WatchlistUiState**

```kotlin
data class WatchlistUiState(
    val isLoadingMovies: Boolean = false,
    val isLoadingShows: Boolean = false,
    val movies: List<MediaUiItem> = emptyList(),
    val shows: List<MediaUiItem> = emptyList(),
    // ... rest stays the same
    val historyMovies: List<MediaUiItem> = emptyList(),
    val historyShows: List<MediaUiItem> = emptyList(),
    // ...
)
```

- [ ] **Step 3: Merge enrich methods**

Replace `enrichMovieItem` and `enrichShowItem` with a single method:

```kotlin
private suspend fun enrichMediaItem(
    traktId: Int,
    tmdbId: Int,
    title: String,
    year: Int?,
    imdbId: String,
    rating: Double,
    listedAt: String,
    isMovie: Boolean
): MediaUiItem {
    if (tmdbId <= 0) {
        return MediaUiItem(
            traktId = traktId, tmdbId = 0, title = title,
            displayTitle = title, year = year, genres = "",
            posterUrl = null, imdbId = imdbId,
            traktRating = rating, listedAt = listedAt
        )
    }
    val enrichment = if (isMovie) {
        tmdbRepository.enrichMovie(tmdbId, title, year)
    } else {
        tmdbRepository.enrichTv(tmdbId, title, year)
    }
    return MediaUiItem(
        traktId = traktId, tmdbId = tmdbId, title = title,
        displayTitle = enrichment.chineseTitle, year = enrichment.year,
        genres = enrichment.genres, posterUrl = enrichment.posterUrl,
        imdbId = imdbId, traktRating = rating, listedAt = listedAt
    )
}
```

Similarly merge `createPlaceholderMovie`/`createPlaceholderShow`:

```kotlin
private fun createPlaceholder(
    traktId: Int, tmdbId: Int, title: String, year: Int?,
    imdbId: String, rating: Double, listedAt: String
): MediaUiItem {
    return MediaUiItem(
        traktId = traktId, tmdbId = tmdbId, title = title,
        displayTitle = title, year = year, genres = "",
        posterUrl = null, imdbId = imdbId,
        traktRating = rating, listedAt = listedAt
    )
}
```

- [ ] **Step 4: Update toMediaItemEntity extensions**

Replace both `MovieUiItem.toMediaItemEntity` and `ShowUiItem.toMediaItemEntity` with:

```kotlin
private fun MediaUiItem.toMediaItemEntity(type: String) = MediaItemEntity(
    traktId = traktId,
    tmdbId = tmdbId,
    type = type,
    title = title,
    displayTitle = displayTitle,
    year = year,
    genres = genres,
    posterUrl = posterUrl,
    imdbId = imdbId,
    traktRating = traktRating
)
```

And replace both `toMovieUiItem`/`toShowUiItem` extensions with:

```kotlin
private fun MediaItemEntity.toMediaUiItem() = MediaUiItem(
    traktId = traktId,
    tmdbId = tmdbId,
    title = title,
    displayTitle = displayTitle,
    year = year,
    genres = genres,
    posterUrl = posterUrl,
    imdbId = imdbId,
    traktRating = traktRating
)
```

- [ ] **Step 5: Update all callers in WatchlistViewModel**

Update `loadMovies`, `loadShows`, `loadHistoryMovies`, `loadHistoryShows`, `loadMoreMovies`, `enrichMovieItem`, `enrichShowItem`, `createPlaceholderMovie`, `createPlaceholderShow` to use the unified types and methods.

- [ ] **Step 6: Update WatchlistScreen.kt**

Update all references to `MovieUiItem`/`ShowUiItem` in `WatchlistScreen.kt` to use `MediaUiItem`.

- [ ] **Step 7: Verify build succeeds**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

---

## Task 13: Merge TraktRepository Pagination Methods (DRY)

**Files:**
- Modify: `app/src/main/java/com/tracktosearch/data/repository/TraktRepository.kt:119-213`

**Problem:** `getAllMovieHistory`, `getAllShowHistory`, `getAllMovieWatchlist`, `getAllShowWatchlist` have identical pagination logic.

- [ ] **Step 1: Add generic pagination helper**

In `TraktRepository.kt`, add a private helper:

```kotlin
private suspend fun <T> fetchAllPages(
    fetchPage: suspend (page: Int) -> Result<Pair<List<T>, Int>>
): Result<List<T>> {
    val allItems = mutableListOf<T>()
    var page = 1
    var totalPages = 1
    while (page <= totalPages) {
        val result = fetchPage(page)
        result.onSuccess { (items, tp) ->
            allItems.addAll(items)
            totalPages = tp
        }.onFailure { e ->
            return Result.failure(e)
        }
        page++
    }
    return Result.success(allItems)
}
```

- [ ] **Step 2: Rewrite the four methods to use the helper**

```kotlin
suspend fun getAllMovieHistory(): Result<List<TraktWatchlistMovieItem>> {
    return fetchAllPages { page -> getMovieHistory(page = page, limit = 200) }
}

suspend fun getAllShowHistory(): Result<List<TraktWatchlistShowItem>> {
    return fetchAllPages { page -> getShowHistory(page = page, limit = 200) }
}

suspend fun getAllMovieWatchlist(): Result<List<TraktWatchlistMovieItem>> {
    return fetchAllPages { page -> getMovieWatchlist(page = page, limit = 200) }
}

suspend fun getAllShowWatchlist(): Result<List<TraktWatchlistShowItem>> {
    return fetchAllPages { page -> getShowWatchlist(page = page, limit = 200) }
}
```

- [ ] **Step 3: Verify build succeeds**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

---

## Task 14: Add @Immutable to UiState Classes

**Files:**
- Modify: `app/src/main/java/com/tracktosearch/ui/screen/search/SearchViewModel.kt:39`
- Modify: `app/src/main/java/com/tracktosearch/ui/screen/detail/DetailViewModel.kt:57`
- Modify: `app/src/main/java/com/tracktosearch/ui/screen/watchlist/WatchlistViewModel.kt:54`
- Modify: `app/src/main/java/com/tracktosearch/ui/screen/settings/SettingsViewModel.kt:47`

**Problem:** Missing `@Immutable` annotations prevent Compose from optimizing recomposition.

- [ ] **Step 1: Add @Immutable to SearchUiState**

```kotlin
// BEFORE:
data class SearchUiState(

// AFTER:
@Immutable
data class SearchUiState(
```

Add import: `import androidx.compose.runtime.Immutable`

- [ ] **Step 2: Add @Immutable to DetailUiState**

```kotlin
// BEFORE:
data class DetailUiState(

// AFTER:
@Immutable
data class DetailUiState(
```

- [ ] **Step 3: Add @Immutable to WatchlistUiState**

```kotlin
// BEFORE:
data class WatchlistUiState(

// AFTER:
@Immutable
data class WatchlistUiState(
```

- [ ] **Step 4: Add @Immutable to ExportImportState**

```kotlin
// BEFORE:
data class ExportImportState(

// AFTER:
@Immutable
data class ExportImportState(
```

- [ ] **Step 5: Verify build succeeds**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

---

## Task 15: Remove Redundant RepositoryModule

**Files:**
- Read: `app/src/main/java/com/tracktosearch/di/RepositoryModule.kt`

**Problem:** `TraktRepository` and `TmdbRepository` have `@Inject constructor` and are `@Singleton`. The `RepositoryModule` manually constructs them, which is redundant with Hilt's auto-provisioning.

- [ ] **Step 1: Read RepositoryModule to confirm it only provides TraktRepository and TmdbRepository**

- [ ] **Step 2: If the module only provides these two, delete the file**

Before deleting, verify that removing it doesn't break the build. Hilt can auto-provide classes with `@Inject constructor` + `@Singleton`.

- [ ] **Step 3: Verify build succeeds**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

---

## Task 16: Fix ViewedItemStorage Naming

**Files:**
- Modify: `app/src/main/java/com/tracktosearch/data/local/ViewedItemStorage.kt:33`
- Modify: All callers of `getviewedUrls()`

**Problem:** `getviewedUrls()` should be `getViewedUrls()` (camelCase).

- [ ] **Step 1: Rename the method**

```kotlin
// BEFORE:
suspend fun getviewedUrls(): Set<String> {

// AFTER:
suspend fun getViewedUrls(): Set<String> {
```

- [ ] **Step 2: Update all callers**

Search for `getviewedUrls` across the codebase and replace with `getViewedUrls`.

- [ ] **Step 3: Verify build succeeds**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

---

## Task 17: Remove themeMode from WatchlistViewModel

**Files:**
- Modify: `app/src/main/java/com/tracktosearch/ui/screen/watchlist/WatchlistViewModel.kt:90-97`
- Modify: `app/src/main/java/com/tracktosearch/ui/screen/watchlist/WatchlistScreen.kt` (update caller)

**Problem:** Theme management is duplicated between `WatchlistViewModel` and `SettingsViewModel`. The WatchlistViewModel should not own theme state.

- [ ] **Step 1: Remove themeMode and setThemeMode from WatchlistViewModel**

Remove lines 90-97:
```kotlin
val themeMode: StateFlow<String> = themeStorage.themeMode
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "system")

fun setThemeMode(mode: String) {
    viewModelScope.launch {
        themeStorage.setThemeMode(mode)
    }
}
```

Also remove the `themeStorage` constructor parameter and the `ThemeStorage` import.

- [ ] **Step 2: Update WatchlistScreen.kt to use SettingsViewModel or a shared approach**

Check how `themeMode` and `setThemeMode` are used in `WatchlistScreen.kt`. If they're used for theme switching in the watchlist screen, either:
1. Move the theme switching to SettingsViewModel (preferred), or
2. Use the themeStorage directly via EntryPoint injection in the Composable

- [ ] **Step 3: Verify build succeeds**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

---

## Verification Checklist

After all tasks are complete:

- [ ] Run `./gradlew assembleDebug` — must succeed
- [ ] Run `./gradlew lint` — no new errors
- [ ] Manual test: Open app, navigate through all tabs, verify no crashes
- [ ] Manual test: Search for a movie, verify search works
- [ ] Manual test: Open detail page, verify all sections load
- [ ] Manual test: Switch language in settings, verify all strings translate correctly

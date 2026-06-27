# Search Page Animated Layout Design

## Goal
Redesign the homepage search page with animated transitions between default and focused states.

## Current State
- Search bar always fixed at top
- Decorative icon (140dp) below search bar in default state
- No animation when transitioning between states
- Search box always full width

## Proposed Design

### Default State (no search, no focus)
```
┌─────────────────────────┐
│         (status bar)     │
│                          │
│      [ic_search_cloud]   │  ← 182dp (30% larger than current 140dp)
│       (white icon)       │
│                          │
│    ┌───────────────┐     │  ← search box: 65% width, centered
│    │ 搜索影视资源 ▾ │     │  ← search type text with dropdown arrow
│    └───────────────┘     │
│                          │
│    搜索历史              │
│    热门搜索              │
└─────────────────────────┘
```

### Focused/Active State (user taps search box)
```
┌─────────────────────────┐
│         (status bar)     │
│  ← 搜索影视资源 ▾ 🔍 ✕  │  ← search box: 100% width, at top
├─────────────────────────┤
│                          │
│    搜索历史              │
│    热门搜索              │
└─────────────────────────┘
```

### Animation Details

1. **Search Box Position**: `Animatable<Float>` for Y offset
   - Default: ~40% from top (centered vertically in upper area)
   - Active: 0 (top of screen)
   - Duration: 300ms, `FastOutSlowInEasing`

2. **Search Box Width**: `Animatable<Float>` for width fraction
   - Default: 0.65 (65% of screen width)
   - Active: 1.0 (100% of screen width)
   - Duration: 300ms, `FastOutSlowInEasing`

3. **Decorative Icon**: `AnimatedVisibility` with `fadeOut`
   - Default: visible
   - Active: fades out and disappears
   - Duration: 200ms

4. **Trigger**: When `isSearchFocused == true` OR `searchQuery.isNotEmpty()`

### Search Type Colors

| Type | Chinese | Color |
|------|---------|-------|
| 网盘资源 (Disk) | 网盘资源 | `Color(0xFF4CAF50)` (Green) |
| 电影 (Movie) | 电影 | `Color(0xFF2196F3)` (Blue) |
| 电视剧 (Show) | 电视剧 | `Color(0xFFFF9800)` (Orange) |
| 人物 (Person) | 人物 | `Color(0xFF9C27B0)` (Purple) |

These colors are used in:
- Search box type selector text
- Search history type tag background (with 15% opacity)

### Search Box Layout Changes

**Default state (no input text):**
```
┌─────────────────────────┐
│  搜索影视资源 ▾          │  ← type selector on left, no search icon
└─────────────────────────┘
```

**With input text:**
```
┌─────────────────────────┐
│  搜索影视资源 ▾  🔍 ✕ 搜索 │  ← magnifying glass + clear + search button on right
└─────────────────────────┘
```

Changes from current:
1. Move magnifying glass icon from left to right side
2. When text is entered, right magnifying glass disappears
3. Replace with "搜索" (Search) text button
4. Search type text font size increased slightly (14sp → 15sp)

### Search History Type Tags

Search history items show a colored tag indicating the search type:
```
┌─────────────────────────┐
│  [网盘资源] 漫威电影     │  ← green background tag
│  [电影] 肖申克的救赎      │  ← blue background tag
│  [电视剧] 权力的游戏      │  ← orange background tag
│  [人物] 克里斯·埃文斯    │  ← purple background tag
└─────────────────────────┘
```

Tag background color: type color at 15% opacity
Tag text color: type color at full opacity

### Layout Structure

```
Box(modifier = fillMaxSize()) {
    // Decorative icon - only visible in default state
    AnimatedVisibility(
        visible = !isActive,
        exit = fadeOut(200ms)
    ) {
        Image(
            painter = painterResource(R.drawable.ic_search_cloud),
            modifier = Modifier.size(182.dp)
        )
    }

    // Search box - animated position and width
    Box(
        modifier = Modifier
            .fillMaxWidth(animatedWidthFraction)  // 0.65 → 1.0
            .offset(y = animatedYOffset)          // center → top
            .align(Alignment.TopCenter)
    ) {
        SearchBarContent(...)
    }

    // Content below search box (history, popular, results)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = searchBarHeight)
    ) {
        // history, popular searches, or results
    }
}
```

### Key Implementation Points

1. Use `remember { Animatable(0f) }` for Y position and width fraction
2. Use `LaunchedEffect(isActive)` to trigger animations
3. The search history / popular searches scroll with the search box
4. When search results are shown, layout switches to full-width top bar (no animation needed)
5. Back press: if focused, unfocus and animate back to center

### Files to Modify
- `app/src/main/java/com/tracktosearch/ui/screen/search/SearchScreen.kt`

### Dependencies
- No new dependencies needed (uses existing Compose animation APIs)

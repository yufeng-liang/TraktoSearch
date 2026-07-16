# 天气动画和彩蛋扩展实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use compose:subagent (recommended) or compose:execute to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 扩展搜索页白云图标的天气主题和彩蛋动画，增加多云、阴天、雾霾、夜晚等天气主题，以及国庆节、中秋节、端午节、劳动节等节日主题，并新增6种彩蛋动画。

**Architecture:** 修改 CloudThemeManager.kt 扩展主题枚举和彩蛋列表，修改 HolidayDetector.kt 新增节日检测，修改 WeatherRepository.kt 扩展天气码映射，修改 CloudEasterEgg.kt 添加夜晚交替显示逻辑。用户需手动下载 Lottie 动画文件。

**Tech Stack:** Kotlin, Jetpack Compose, Lottie Compose

## Global Constraints
- Min SDK: 26 (Android 8.0)
- Target SDK: 35
- Language: Kotlin 1.9.22
- Compose BOM: 2024.10.00
- 不新增依赖
- Lottie 动画文件需用户手动下载

---

## Task 1: 扩展 CloudTheme 枚举

**Covers:** [S2]

**Files:**
- Modify: `app/src/main/java/com/tracktosearch/ui/component/CloudThemeManager.kt:22-30`

**Interfaces:**
- Consumes: 无
- Produces: CloudTheme 枚举新增 CLOUDY, OVERCAST, MIST, NIGHT, NATIONAL, MIDAUTUMN, DRAGONBOAT, LABOR

- [ ] **Step 1: 扩展 CloudTheme 枚举**

在 `CloudThemeManager.kt` 中找到 CloudTheme 枚举，添加新的主题：

```kotlin
/** 白云主题，对应不同的 Lottie 动画 */
enum class CloudTheme(val rawRes: Int) {
    SUNNY(R.raw.cloud_sunny),
    RAINY(R.raw.cloud_rainy),
    SNOWY(R.raw.cloud_snowy),
    THUNDER(R.raw.cloud_thunder),
    CHRISTMAS(R.raw.cloud_christmas),
    SPRING_FESTIVAL(R.raw.cloud_spring_festival),
    HALLOWEEN(R.raw.cloud_halloween),
    // 新增天气主题
    CLOUDY(R.raw.cloud_cloudy),
    OVERCAST(R.raw.cloud_overcast),
    MIST(R.raw.cloud_mist),
    NIGHT(R.raw.cloud_night),
    // 新增节日主题
    NATIONAL(R.raw.cloud_national),
    MIDAUTUMN(R.raw.cloud_midautumn),
    DRAGONBOAT(R.raw.cloud_dragonboat),
    LABOR(R.raw.cloud_labor);
}
```

- [ ] **Step 2: 验证构建**

运行: `./gradlew assembleDebug`
预期: BUILD SUCCESSFUL（注意：此时会因为缺少资源文件而失败，这是正常的）

---

## Task 2: 扩展彩蛋动画列表

**Covers:** [S2]

**Files:**
- Modify: `app/src/main/java/com/tracktosearch/ui/component/CloudThemeManager.kt:32-37`

**Interfaces:**
- Consumes: 无
- Produces: EASTER_EGG_RES 列表新增 easter_dog, easter_bunny, easter_panda, easter_rainbow, easter_firework, easter_lantern

- [ ] **Step 1: 扩展彩蛋动画列表**

在 `CloudThemeManager.kt` 中找到 EASTER_EGG_RES 列表，添加新的彩蛋：

```kotlin
/** 彩蛋动画资源列表 */
private val EASTER_EGG_RES = listOf(
    R.raw.easter_shy,
    R.raw.easter_cat,
    R.raw.easter_sleepy,
    // 新增彩蛋
    R.raw.easter_dog,
    R.raw.easter_bunny,
    R.raw.easter_panda,
    R.raw.easter_rainbow,
    R.raw.easter_firework,
    R.raw.easter_lantern
)
```

- [ ] **Step 2: 验证构建**

运行: `./gradlew assembleDebug`
预期: BUILD SUCCESSFUL（注意：此时会因为缺少资源文件而失败，这是正常的）

---

## Task 3: 扩展节日检测

**Covers:** [S2]

**Files:**
- Modify: `app/src/main/java/com/tracktosearch/util/HolidayDetector.kt:7-31`

**Interfaces:**
- Consumes: 无
- Produces: Holiday 枚举新增 NATIONAL_DAY, MID_AUTUMN, DRAGON_BOAT, LABOR_DAY; detect() 方法扩展

- [ ] **Step 1: 扩展 Holiday 枚举**

在 `HolidayDetector.kt` 中找到 Holiday 枚举，添加新的节日：

```kotlin
enum class Holiday {
    CHRISTMAS,
    SPRING_FESTIVAL,
    HALLOWEEN,
    // 新增节日
    NATIONAL_DAY,
    MID_AUTUMN,
    DRAGON_BOAT,
    LABOR_DAY
}
```

- [ ] **Step 2: 扩展 detect() 方法**

在 `HolidayDetector.kt` 中找到 detect() 方法，添加新的节日检测逻辑：

```kotlin
fun detect(date: LocalDate = LocalDate.now()): Holiday? {
    val month = date.monthValue
    val day = date.dayOfMonth

    return when {
        // 圣诞节：12/20 ~ 12/26
        month == 12 && day in 20..26 -> Holiday.CHRISTMAS
        // 春节（近似）：1/25 ~ 2/15
        month == 1 && day >= 25 -> Holiday.SPRING_FESTIVAL
        month == 2 && day <= 15 -> Holiday.SPRING_FESTIVAL
        // 万圣节：10/28 ~ 10/31
        month == 10 && day in 28..31 -> Holiday.HALLOWEEN
        // 国庆节：10/1 ~ 10/7
        month == 10 && day in 1..7 -> Holiday.NATIONAL_DAY
        // 中秋节（近似）：9/15 ~ 10/5
        (month == 9 && day >= 15) || (month == 10 && day <= 5) -> Holiday.MID_AUTUMN
        // 端午节（近似）：6/10 ~ 6/20
        month == 6 && day in 10..20 -> Holiday.DRAGON_BOAT
        // 劳动节：5/1 ~ 5/5
        month == 5 && day in 1..5 -> Holiday.LABOR_DAY
        else -> null
    }
}
```

- [ ] **Step 3: 验证构建**

运行: `./gradlew assembleDebug`
预期: BUILD SUCCESSFUL

---

## Task 4: 扩展天气码映射

**Covers:** [S2]

**Files:**
- Modify: `app/src/main/java/com/tracktosearch/ui/component/CloudThemeManager.kt:178-187`

**Interfaces:**
- Consumes: WeatherInfo
- Produces: CloudTheme

- [ ] **Step 1: 扩展 weatherCodeToTheme 方法**

在 `CloudThemeManager.kt` 中找到 weatherCodeToTheme 方法，添加新的天气码映射：

```kotlin
private fun weatherCodeToTheme(weather: WeatherInfo): CloudTheme {
    return when (weather.weatherCode) {
        0, 1 -> CloudTheme.SUNNY
        2 -> CloudTheme.CLOUDY
        3 -> CloudTheme.OVERCAST
        45, 48 -> CloudTheme.MIST
        in 51..67 -> CloudTheme.RAINY
        in 71..86 -> CloudTheme.SNOWY
        in 95..99 -> CloudTheme.THUNDER
        else -> CloudTheme.SUNNY
    }
}
```

- [ ] **Step 2: 验证构建**

运行: `./gradlew assembleDebug`
预期: BUILD SUCCESSFUL

---

## Task 5: 修改 CloudThemeManager 支持夜晚交替

**Covers:** [S2]

**Files:**
- Modify: `app/src/main/java/com/tracktosearch/ui/component/CloudThemeManager.kt:56-112`

**Interfaces:**
- Consumes: 无
- Produces: isNightAlternate: StateFlow<Boolean>; toggleNightAlternate(); isNightTime(): Boolean

- [ ] **Step 1: 添加夜晚交替状态**

在 `CloudThemeManager` 类中添加新的状态和方法：

```kotlin
// 夜晚交替状态
private val _isNightAlternate = MutableStateFlow(false)
val isNightAlternate: StateFlow<Boolean> = _isNightAlternate

/** 检查当前是否为夜间（20:00-06:00） */
fun isNightTime(): Boolean {
    val hour = java.time.LocalTime.now().hour
    return hour >= 20 || hour < 6
}

/** 切换夜晚交替状态 */
fun toggleNightAlternate() {
    if (isNightTime()) {
        _isNightAlternate.value = !_isNightAlternate.value
    }
}

/** 获取当前应显示的主题 */
fun getCurrentDisplayTheme(): CloudTheme {
    return if (isNightTime() && _isNightAlternate.value) {
        CloudTheme.NIGHT
    } else {
        _currentTheme.value
    }
}
```

- [ ] **Step 2: 验证构建**

运行: `./gradlew assembleDebug`
预期: BUILD SUCCESSFUL

---

## Task 6: 修改 CloudEasterEgg 支持夜晚交替

**Covers:** [S2]

**Files:**
- Modify: `app/src/main/java/com/tracktosearch/ui/component/CloudEasterEgg.kt:29-67`

**Interfaces:**
- Consumes: CloudThemeManager
- Produces: 无

- [ ] **Step 1: 修改 CloudEasterEgg 组件**

在 `CloudEasterEgg.kt` 中修改组件，使用 getCurrentDisplayTheme() 替代直接使用 theme：

```kotlin
@Composable
fun CloudEasterEgg(
    themeManager: CloudThemeManager,
    size: Dp = 182.dp,
    modifier: Modifier = Modifier,
    onCloudClicked: () -> Unit = { themeManager.onCloudClicked() }
) {
    val theme by themeManager.currentTheme.collectAsStateWithLifecycle()
    val isNightAlternate by themeManager.isNightAlternate.collectAsStateWithLifecycle()
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    // 按下缩放反馈
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.9f else 1f,
        animationSpec = tween(100),
        label = "cloud_press_scale"
    )

    // 获取当前应显示的主题
    val displayTheme = themeManager.getCurrentDisplayTheme()

    // 加载 Lottie 动画
    val composition by rememberLottieComposition(
        LottieCompositionSpec.RawRes(displayTheme.rawRes)
    )
    val progress by animateLottieCompositionAsState(
        composition = composition,
        iterations = LottieConstants.IterateForever
    )

    LottieAnimation(
        composition = composition,
        progress = { progress },
        modifier = modifier
            .size(size)
            .scale(scale)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onCloudClicked
            )
    )
}
```

- [ ] **Step 2: 验证构建**

运行: `./gradlew assembleDebug`
预期: BUILD SUCCESSFUL

---

## Task 7: 修改 SearchScreen 触发夜晚交替

**Covers:** [S2]

**Files:**
- Modify: `app/src/main/java/com/tracktosearch/ui/screen/search/SearchScreen.kt:180-187`

**Interfaces:**
- Consumes: CloudThemeManager
- Produces: 无

- [ ] **Step 1: 在 SearchScreen 中触发夜晚交替**

在 `SearchScreen.kt` 中找到 LaunchedEffect(hasPermission) 块，在加载主题后添加夜晚交替触发：

```kotlin
LaunchedEffect(hasPermission) {
    if (hasPermission) {
        cloudThemeManager.onPermissionGranted()
        cloudThemeManager.loadTheme(getLastKnownLocation(context))
    } else {
        // 未授权时只加载缓存主题，不自动请求权限
        cloudThemeManager.loadTheme(null)
    }
    // 触发夜晚交替
    cloudThemeManager.toggleNightAlternate()
}
```

- [ ] **Step 2: 验证构建**

运行: `./gradlew assembleDebug`
预期: BUILD SUCCESSFUL

---

## Task 8: 创建动画文件占位符

**Covers:** [S3]

**Files:**
- Create: `app/src/main/res/raw/cloud_cloudy.json`（占位符）
- Create: `app/src/main/res/raw/cloud_overcast.json`（占位符）
- Create: `app/src/main/res/raw/cloud_mist.json`（占位符）
- Create: `app/src/main/res/raw/cloud_night.json`（占位符）
- Create: `app/src/main/res/raw/cloud_national.json`（占位符）
- Create: `app/src/main/res/raw/cloud_midautumn.json`（占位符）
- Create: `app/src/main/res/raw/cloud_dragonboat.json`（占位符）
- Create: `app/src/main/res/raw/cloud_labor.json`（占位符）
- Create: `app/src/main/res/raw/easter_dog.json`（占位符）
- Create: `app/src/main/res/raw/easter_bunny.json`（占位符）
- Create: `app/src/main/res/raw/easter_panda.json`（占位符）
- Create: `app/src/main/res/raw/easter_rainbow.json`（占位符）
- Create: `app/src/main/res/raw/easter_firework.json`（占位符）
- Create: `app/src/main/res/raw/easter_lantern.json`（占位符）

**Interfaces:**
- Consumes: 无
- Produces: 14 个 Lottie 动画占位文件

- [ ] **Step 1: 创建占位符文件**

为每个新增的动画创建一个空的 JSON 占位符文件。内容为：

```json
{
  "v": "5.7.4",
  "fr": 30,
  "ip": 0,
  "op": 60,
  "w": 200,
  "h": 200,
  "nm": "placeholder",
  "ddd": 0,
  "assets": [],
  "layers": []
}
```

使用 bash 命令批量创建：

```bash
cd app/src/main/res/raw

for file in cloud_cloudy cloud_overcast cloud_mist cloud_night cloud_national cloud_midautumn cloud_dragonboat cloud_labor easter_dog easter_bunny easter_panda easter_rainbow easter_firework easter_lantern; do
  cat > "${file}.json" << 'EOF'
{
  "v": "5.7.4",
  "fr": 30,
  "ip": 0,
  "op": 60,
  "w": 200,
  "h": 200,
  "nm": "placeholder",
  "ddd": 0,
  "assets": [],
  "layers": []
}
EOF
done
```

- [ ] **Step 2: 验证构建**

运行: `./gradlew assembleDebug`
预期: BUILD SUCCESSFUL

---

## Task 9: 最终验证

**Covers:** [S5]

**Files:**
- None（手动测试）

**Interfaces:**
- Consumes: 所有之前的任务
- Produces: 无

- [ ] **Step 1: 验证构建**

运行: `./gradlew assembleDebug`
预期: BUILD SUCCESSFUL

- [ ] **Step 2: 手动测试天气主题**

1. 确保设备联网，获取真实天气数据
2. 打开搜索页，确认天气主题正常显示
3. 如果天气码为 2，应显示多云主题
4. 如果天气码为 3，应显示阴天主题
5. 如果天气码为 45 或 48，应显示雾霾主题

- [ ] **Step 3: 手动测试节日主题**

1. 修改设备日期到节日期间（如 10/1 国庆节）
2. 打开搜索页，确认节日主题正常显示
3. 返回正常日期

- [ ] **Step 4: 手动测试夜晚交替**

1. 修改设备时间到 20:00 以后
2. 打开搜索页，确认显示天气主题
3. 切换到其他页面再切换回来，确认显示夜晚主题
4. 再次切换，确认又显示天气主题

- [ ] **Step 5: 手动测试彩蛋**

1. 点击白云图标，确认彩蛋随机触发
2. 多次点击，确认覆盖所有新增彩蛋动画

- [ ] **Step 6: 提交代码**

```bash
git add app/src/main/java/com/tracktosearch/ui/component/CloudThemeManager.kt
git add app/src/main/java/com/tracktosearch/ui/component/CloudEasterEgg.kt
git add app/src/main/java/com/tracktosearch/util/HolidayDetector.kt
git add app/src/main/java/com/tracktosearch/ui/screen/search/SearchScreen.kt
git add app/src/main/res/raw/*.json
git commit -m "feat: 扩展天气主题和彩蛋动画，支持夜晚交替显示"
```

---

## Verification Checklist

完成所有任务后：

- [ ] 运行 `./gradlew assembleDebug` — 必须成功
- [ ] 手动测试：多云、阴天、雾霾天气主题正常显示
- [ ] 手动测试：国庆节、中秋节、端午节、劳动节节日主题正常显示
- [ ] 手动测试：夜晚交替显示逻辑正常工作
- [ ] 手动测试：所有新增彩蛋动画正常触发
- [ ] 注意：实际动画效果需要用户手动下载替换占位符文件

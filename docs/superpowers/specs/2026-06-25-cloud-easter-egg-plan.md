# 白云彩蛋 — 实现计划

> 基于设计文档：`docs/superpowers/specs/2026-06-25-cloud-easter-egg-design.md`

## 实现顺序

按依赖关系从底层到上层分 6 个步骤：

---

### Step 1: 添加依赖 + 查找 Lottie 素材

**改动文件：**
- `app/build.gradle.kts` — 添加 `lottie-compose:6.6.6`

**素材准备：**
- 从 LottieFiles 下载 10 个动画 JSON 文件
- 放入 `app/src/main/res/raw/`
- 天气主题 4 个：`cloud_sunny.json`, `cloud_rainy.json`, `cloud_snowy.json`, `cloud_thunder.json`
- 节日主题 3 个：`cloud_christmas.json`, `cloud_spring_festival.json`, `cloud_halloween.json`
- 点击彩蛋 3 个：`easter_shy.json`, `easter_cat.json`, `easter_sleepy.json`

**验证点：** `./gradlew assembleDebug` 编译通过

---

### Step 2: 天气 API 数据层

**新增文件：**
- `data/remote/weather/OpenMeteoApi.kt` — Retrofit 接口
- `data/remote/weather/WeatherDto.kt` — 响应数据类

**修改文件：**
- `di/NetworkModule.kt` — 提供 OpenMeteoApi 实例（复用现有 Retrofit，baseURL 为 `https://api.open-meteo.com/v1/`）

**接口设计：**
```kotlin
interface OpenMeteoApi {
    @GET("forecast")
    suspend fun getCurrentWeather(
        @Query("latitude") lat: Double,
        @Query("longitude") lon: Double,
        @Query("current") current: String = "temperature_2m,weather_code",
        @Query("timezone") timezone: String = "auto"
    ): OpenMeteoResponse
}
```

**验证点：** 接口可被 Hilt 正确注入

---

### Step 3: 天气仓库 + 缓存 + 定位

**新增文件：**
- `data/repository/WeatherRepository.kt`

**职责：**
1. 调用 OpenMeteoApi 获取天气
2. DataStore 缓存（3 小时有效期）
3. 获取定位（coarse location），失败时降级

**公开接口：**
```kotlin
class WeatherRepository @Inject constructor(...) {
    suspend fun getCurrentWeather(): WeatherInfo
}

data class WeatherInfo(
    val weatherCode: Int,    // WMO code
    val temperature: Double
)
```

**缓存键：** `weather_cache_timestamp`，3 小时内直接返回缓存

**验证点：** 单元测试验证缓存逻辑

---

### Step 4: 节日检测 + 主题管理器

**新增文件：**
- `util/HolidayDetector.kt` — 纯函数，输入日期输出节日（或 null）
- `ui/component/CloudThemeManager.kt` — 组合节日 + 天气，输出当前主题

**CloudThemeManager 职责：**
```kotlin
enum class CloudTheme {
    SUNNY, RAINY, SNOWY, THUNDER,
    CHRISTMAS, SPRING_FESTIVAL, HALLOWEEN
}

class CloudThemeManager @Inject constructor(
    private val weatherRepository: WeatherRepository,
    private val holidayDetector: HolidayDetector
) {
    val currentTheme: StateFlow<CloudTheme>
    val easterEggAnimation: StateFlow<Int?>  // Lottie rawRes
    val easterMessage: StateFlow<String?>

    fun onCloudClicked()  // 触发随机彩蛋
    fun onEasterDismissed()  // 关闭彩蛋
}
```

**随机策略：** 记录最近 2 个播放过的彩蛋索引，避免连续重复

**验证点：** HolidayDetector 对各节日边界日期返回正确结果

---

### Step 5: CloudEasterEgg Composable

**新增文件：**
- `ui/component/CloudEasterEgg.kt` — 平时天气动画
- `ui/component/CloudOverlay.kt` — 全屏彩蛋 Overlay

**CloudEasterEgg：**
- 接收 `CloudTheme`，选择对应 Lottie 动画
- 182dp 尺寸，循环播放
- 点击回调 `onCloudClicked`
- 搜索框聚焦时随 `AnimatedVisibility` 一起淡出

**CloudOverlay：**
- `AnimatedVisibility` 控制显隐
- 半透明蒙层 (alpha 0.85)
- 居中 Lottie 动画 300~400dp
- 底部趣味文案
- 点击蒙层 / 返回键 / 动画播完 → 关闭
- 进入淡入 200ms，退出淡出 200ms

**验证点：** 各主题动画正确切换，彩蛋随机不重复

---

### Step 6: 集成到 SearchScreen

**修改文件：**
- `ui/screen/search/SearchScreen.kt` — 第 198~203 行的 `Image` 替换为 `CloudEasterEgg`

**改动范围：**
```kotlin
// 替换前
Image(
    painter = painterResource(id = R.drawable.ic_search_cloud),
    contentDescription = null,
    modifier = Modifier.size(182.dp)
)

// 替换后
CloudEasterEgg(
    themeManager = cloudThemeManager,
    modifier = Modifier.size(182.dp)
)
// + CloudOverlay 在 Box 层级上方
```

**验证点：** `./gradlew assembleDebug` 编译通过，运行后白云显示天气动画，点击触发全屏彩蛋

---

## 文件变更汇总

| 操作 | 文件 |
|------|------|
| 修改 | `app/build.gradle.kts` |
| 修改 | `di/NetworkModule.kt` |
| 修改 | `ui/screen/search/SearchScreen.kt` |
| 新增 | `data/remote/weather/OpenMeteoApi.kt` |
| 新增 | `data/remote/weather/WeatherDto.kt` |
| 新增 | `data/repository/WeatherRepository.kt` |
| 新增 | `util/HolidayDetector.kt` |
| 新增 | `ui/component/CloudThemeManager.kt` |
| 新增 | `ui/component/CloudEasterEgg.kt` |
| 新增 | `ui/component/CloudOverlay.kt` |
| 新增 | `res/raw/*.json` × 10 |

## 依赖关系

```
Step 1 (依赖+素材)
    ↓
Step 2 (API 数据层)
    ↓
Step 3 (仓库+缓存)
    ↓
Step 4 (主题管理器)
    ↓
Step 5 (Composable)
    ↓
Step 6 (集成)
```

每一步完成后跑 `assembleDebug` 验证编译。

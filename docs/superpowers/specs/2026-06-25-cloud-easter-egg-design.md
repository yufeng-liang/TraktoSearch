# 白云彩蛋 — 百变剧场

> 搜索页白云图标的状态主题 + 点击全屏治愈动画

## 背景

搜索页首页有一个 182dp 的白云装饰图标（`ic_search_cloud.png`），当前仅作静态展示。希望赋予它「生命感」：

- **平时**：根据实时天气、节日、季节自动切换主题状态和微动效
- **点击**：触发全屏治愈型 Lottie 动画 + 随机趣味文案

## 目标

1. 白云图标在未聚焦搜索框时显示天气/节日主题动画（Lottie 循环播放）
2. 点击白云后弹出全屏 overlay，播放随机治愈动画 + 趣味文案
3. 动画播完或点击蒙层自动关闭，恢复天气动画
4. 天气数据通过 Open-Meteo API 获取（免费，无需 API Key）

## 非目标

- 不做天气预报 UI / 天气详情页
- 不做动画资源的网络动态下载（全部打包在 APK 内）
- 不做用户自定义动画主题

---

## 架构设计

### 数据流

```
┌─────────────────────────────────────────────────┐
│  SearchScreen                                    │
│  ┌─────────────────────┐                        │
│  │  CloudEasterEgg     │ ← 替换现有 Image       │
│  │  (Composable)       │                        │
│  └────────┬────────────┘                        │
│           │ observes                            │
│  ┌────────▼────────────┐                        │
│  │  CloudThemeState    │ ← StateFlow            │
│  │  (ViewModel 层)     │                        │
│  └────────┬────────────┘                        │
│           │                                     │
│  ┌────────▼────────────┐  ┌──────────────────┐  │
│  │  WeatherRepository  │  │ HolidayDetector  │  │
│  │  (Open-Meteo API)   │  │ (本地日期判断)    │  │
│  └─────────────────────┘  └──────────────────┘  │
└─────────────────────────────────────────────────┘
```

### 主题优先级

```
节日 (本地日期判断) > 天气 (Open-Meteo API) > 默认晴天
```

### 天气码映射 (WMO Code → 动画)

| WMO Code | 天气类型 | 动画文件 | 描述 |
|----------|---------|---------|------|
| 0 | 晴 | `cloud_sunny.json` | 白云戴墨镜，微微摇晃 |
| 1, 2, 3 | 多云/阴 | `cloud_sunny.json` | 同晴天（无墨镜变体由 Lottie 内部状态控制） |
| 45, 48 | 雾 | `cloud_rainy.json` | 灰白云朵 |
| 51-67 | 雨/冻雨 | `cloud_rainy.json` | 白云变灰，头顶滴水 |
| 71-86 | 雪 | `cloud_snowy.json` | 白云戴围巾，飘雪花 |
| 95-99 | 雷暴 | `cloud_thunder.json` | 白云害怕发抖，偶尔闪电 |

### 节日检测 (本地逻辑)

| 节日 | 日期范围 | 动画文件 | 描述 |
|------|---------|---------|------|
| 圣诞节 | 12/20 ~ 12/26 | `cloud_christmas.json` | 白云戴圣诞帽 |
| 春节 | 1/25 ~ 2/15 (近似) | `cloud_spring_festival.json` | 白云变红 + 对联 |
| 万圣节 | 10/28 ~ 10/31 | `cloud_halloween.json` | 白云灰暗 + 南瓜 |

> 注：春节日期每年不同，使用固定范围近似覆盖大部分年份，不做农历换算。

### 点击彩蛋动画 (随机选 1 个)

| 动画 | 文件名 | 效果 |
|------|--------|------|
| 😊 害羞云 | `easter_shy.json` | 云朵脸红，喷出一道小彩虹 |
| 🐱 猫耳云 | `easter_cat.json` | 云朵长出猫耳朵 + 猫尾巴 |
| 💤 打瞌睡 | `easter_sleepy.json` | 云朵闭眼打呼，头顶飘出 Zzz |

**随机策略**：记住最近播放过的 2 个动画，避免连续重复，直到所有选项都播过一遍再重置。

### 趣味文案池

```kotlin
val easterMessages = listOf(
    "今天也要开心哦~ ☁️",
    "摸鱼时间到！🐟",
    "你发现了隐藏彩蛋！🎉",
    "云朵向你比了个心 💕",
    "休息一下，喝杯水吧 ☕",
    "愿你的搜索永远有结果 🔍",
    "天空飘来五个字：那都不是事儿~",
    "你戳到我了，好痒！🤭",
)
```

每次点击随机选一条（避免连续重复同一条）。

---

## UI 设计

### 平时状态

```
┌─────────────────────────────┐
│                             │
│        [Lottie 动画]        │ ← 182dp，替换原静态图片
│         天气/节日主题        │    循环播放微动效
│                             │
│     ┌─────────────────┐    │
│     │   搜索框         │    │
│     └─────────────────┘    │
│                             │
└─────────────────────────────┘
```

- 尺寸保持 182dp
- 搜索框获得焦点时，动画和图标一起淡出（沿用现有 `AnimatedVisibility` 逻辑）

### 点击后全屏 Overlay

```
┌─────────────────────────────┐
│ ▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓ │ ← 半透明蒙层 (alpha 0.85)
│ ▓▓                       ▓▓ │
│ ▓▓     ┌─────────────┐   ▓▓ │
│ ▓▓     │             │   ▓▓ │
│ ▓▓     │  Lottie 动画 │   ▓▓ │ ← 300~400dp 居中
│ ▓▓     │  全屏治愈效果 │   ▓▓ │
│ ▓▓     │             │   ▓▓ │
│ ▓▓     └─────────────┘   ▓▓ │
│ ▓▓                         ▓▓ │
│ ▓▓   "摸鱼时间到！🐟"      ▓▓ │ ← 随机趣味文案
│ ▓▓                       ▓▓ │
│ ▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓ │
└─────────────────────────────┘
```

- 蒙层：Material 3 surface color，alpha 0.85
- 动画尺寸：300~400dp，居中显示
- 文案：动画下方，Material 3 bodyLarge 样式
- 退出方式：点击蒙层 / 动画播完(3秒) / 按返回键，取先发生者
- 进入/退出动画：淡入 200ms，淡出 200ms

---

## 新增文件清单

### 数据层

| 文件 | 用途 |
|------|------|
| `data/remote/weather/OpenMeteoApi.kt` | Retrofit 接口定义 |
| `data/remote/weather/WeatherDto.kt` | Open-Meteo 响应数据类 |
| `data/repository/WeatherRepository.kt` | 天气数据仓库，含 DataStore 缓存 (3小时) |

### UI 层

| 文件 | 用途 |
|------|------|
| `ui/component/CloudEasterEgg.kt` | 核心 Composable：天气动画 + 点击交互 |
| `ui/component/CloudOverlay.kt` | 全屏彩蛋 Overlay Composable |
| `ui/component/CloudThemeManager.kt` | 主题状态管理（天气 + 节日 + 彩蛋随机） |

### 工具层

| 文件 | 用途 |
|------|------|
| `util/HolidayDetector.kt` | 本地节日检测逻辑 |

### 资源文件

| 文件 | 用途 |
|------|------|
| `res/raw/cloud_sunny.json` | 晴天 Lottie 动画 |
| `res/raw/cloud_rainy.json` | 雨天 Lottie 动画 |
| `res/raw/cloud_snowy.json` | 雪天 Lottie 动画 |
| `res/raw/cloud_thunder.json` | 雷暴 Lottie 动画 |
| `res/raw/cloud_christmas.json` | 圣诞节 Lottie 动画 |
| `res/raw/cloud_spring_festival.json` | 春节 Lottie 动画 |
| `res/raw/cloud_halloween.json` | 万圣节 Lottie 动画 |
| `res/raw/easter_shy.json` | 害羞彩蛋 Lottie 动画 |
| `res/raw/easter_cat.json` | 猫耳彩蛋 Lottie 动画 |
| `res/raw/easter_sleepy.json` | 打瞌睡彩蛋 Lottie 动画 |

### 修改文件

| 文件 | 改动 |
|------|------|
| `app/build.gradle.kts` | 添加 lottie-compose 依赖 |
| `di/NetworkModule.kt` | 注入 OpenMeteoApi |
| `ui/screen/search/SearchScreen.kt` | 将白云 Image 替换为 CloudEasterEgg |
| `local.properties` | 无需改动（Open-Meteo 不需要 Key） |

---

## 缓存策略

- 天气数据缓存到 DataStore，有效期 3 小时
- 缓存键：`weather_cache_{lat}_{lon}`（四舍五入到小数点后 1 位）
- 避免频繁请求：每次进入搜索页检查缓存是否过期

## 定位策略

1. 优先使用系统定位（`ACCESS_COARSE_LOCATION`）
2. 定位失败时使用 IP 地理定位（Open-Meteo 支持不传坐标，默认返回 IP 所在地天气）
3. 两者都失败时使用默认主题（晴天）

## 性能考虑

- Lottie 动画文件单个控制在 200KB 以内
- 天气请求异步执行，不阻塞 UI
- 全屏 Overlay 使用 `AnimatedVisibility`，不播放时零开销
- 缓存避免重复网络请求

## 依赖新增

```kotlin
// Lottie 动画
implementation("com.airbnb.android:lottie-compose:6.6.6")
```

仅此一个新增依赖。天气 API 复用项目已有的 Retrofit + Kotlin Serialization。

# 天气动画和彩蛋扩展设计

## [S1] 问题

当前搜索页白云图标仅有 7 种天气主题和 3 种彩蛋动画，用户希望：
- 增加更多天气主题（多云、阴天等）
- 增加更多节日主题（国庆节、中秋节、端午节、劳动节）
- 增加更多彩蛋动画（可爱动物、搞怪互动、节日限定混合风格）

## [S2] 解决方案

### 新增天气主题（共 4 种）

| 主题 | 动画文件 | 天气码/条件 | 描述 |
|------|----------|-------------|------|
| 多云 | `cloud_cloudy.json` | WMO 2 | 云朵叠加，部分遮挡太阳 |
| 阴天 | `cloud_overcast.json` | WMO 3 | 云层厚重，灰色调 |
| 雾霾 | `cloud_mist.json` | WMO 45,48 | 云朵周围弥漫薄雾 |
| 夜晚 | `cloud_night.json` | 夜间（20:00-06:00） | 云朵变深蓝色，伴随月亮和星星 |

### 新增节日主题（共 4 种）

| 节日 | 动画文件 | 日期范围 | 描述 |
|------|----------|----------|------|
| 国庆节 | `cloud_national.json` | 10/1-10/7 | 云朵变红色，飘落五星红旗 |
| 中秋节 | `cloud_midautumn.json` | 农历八月十五（近似 9/15-10/5） | 云朵旁边出现月亮和月饼 |
| 端午节 | `cloud_dragonboat.json` | 农历五月初五（近似 6/10-6/20） | 云朵变成粽子形状 |
| 劳动节 | `cloud_labor.json` | 5/1-5/5 | 云朵戴着工人帽，手持工具 |

### 新增彩蛋动画（共 6 种）

| 彩蛋 | 动画文件 | 风格 | 描述 |
|------|----------|------|------|
| 小狗 | `easter_dog.json` | 可爱动物 | 云朵长出狗耳朵和尾巴 |
| 兔子 | `easter_bunny.json` | 可爱动物 | 云朵长出兔耳朵，蹦跳 |
| 熊猫 | `easter_panda.json` | 可爱动物 | 云朵变成黑白熊猫脸 |
| 喷彩虹 | `easter_rainbow.json` | 搞怪互动 | 云朵从嘴里喷出彩虹 |
| 放烟花 | `easter_firework.json` | 搞怪互动 | 云朵头顶绽放烟花 |
| 元宵 | `easter_lantern.json` | 节日限定 | 云朵提着灯笼 |

### 优先级规则

```
节日（本地日期判断）> 天气（Open-Meteo API）> 默认晴天
```

### 夜晚交替显示规则

当时间为夜间（20:00-06:00）时，搜索页首页的天气图标与夜晚图标交替显示：

- **交替时机**：每次切换到搜索页首页时触发
- **交替方式**：当前天气主题 ↔ 夜晚主题（`cloud_night.json`）
- **状态管理**：使用 `isNightAlternate` 布尔值记录当前状态，每次进入搜索页时翻转
- **动画过渡**：使用淡入淡出动画平滑切换（200ms）

**实现逻辑：**
1. 检测当前时间是否为夜间（20:00-06:00）
2. 如果是夜间，每次进入搜索页时翻转 `isNightAlternate` 状态
3. 当 `isNightAlternate = true` 时显示 `cloud_night.json`
4. 当 `isNightAlternate = false` 时显示当前天气主题

### 节日检测扩展

| 节日 | 日期范围 | 动画 |
|------|----------|------|
| 国庆节 | 10/1-10/7 | `cloud_national.json` |
| 中秋节 | 农历八月十五（近似 9/15-10/5） | `cloud_midautumn.json` |
| 端午节 | 农历五月初五（近似 6/10-6/20） | `cloud_dragonboat.json` |
| 劳动节 | 5/1-5/5 | `cloud_labor.json` |
| 元宵节 | 农历正月十五（近似 2/10-2/15） | `easter_lantern.json` |

## [S3] Lottie 动画下载网站

以下是获取免费 Lottie 动画的网站：

| 网站 | 网址 | 说明 |
|------|------|------|
| LottieFiles | https://lottiefiles.com | 最大的 Lottie 动画库，有大量免费动画 |
| Lordicon | https://lordicon.com | 高质量图标动画 |
| IconScout | https://iconscout.com/lottie-animations | 丰富的动画素材 |
| Animaticons | https://animaticons.co | 简洁的动画图标 |

**下载步骤：**
1. 访问上述网站
2. 搜索关键词（如 "cloud"、"rainbow"、"dog"、"firework"）
3. 选择喜欢的动画
4. 下载 JSON 格式
5. 重命名为 `cloud_xxx.json` 或 `easter_xxx.json`
6. 放入 `app/src/main/res/raw/` 目录

## [S4] 修改文件

| 文件 | 改动 |
|------|------|
| `app/src/main/res/raw/` | 新增 14 个 Lottie 动画文件（需手动下载） |
| `app/src/main/java/com/tracktosearch/ui/component/CloudThemeManager.kt` | 扩展 CloudTheme 枚举、EASTER_EGG_RES 列表、夜晚交替逻辑 |
| `app/src/main/java/com/tracktosearch/ui/component/CloudEasterEgg.kt` | 添加夜晚交替显示逻辑 |
| `app/src/main/java/com/tracktosearch/util/HolidayDetector.kt` | 新增节日检测 |
| `app/src/main/java/com/tracktosearch/data/repository/WeatherRepository.kt` | 天气码映射扩展 |

## [S5] 测试要点

1. 各天气主题在对应条件下正常显示
2. 彩蛋随机触发，覆盖所有新增动画
3. 节日检测在各节日期间正常工作
4. 缺少动画文件时回退到默认主题
5. 夜间（20:00-06:00）切换到搜索页时，天气图标与夜晚图标交替显示
6. 非夜间时间不触发交替逻辑

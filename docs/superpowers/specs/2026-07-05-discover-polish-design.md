# 发现页打磨与彩蛋优化 设计规格

> 日期：2026-07-05
> 主题：暗色模式文字颜色修复、国际化硬编码修复、彩蛋动画-文案配对、社区列表共享元素转场、错误信息可查看

## 背景

用户反馈多个问题：
1. 影视筛选页标题栏/卡片标题/高级设置标题在暗色模式下文字仍为黑色
2. 年代选项 label 硬编码中文（"2020年代"等）
3. 全屏彩蛋文案硬编码中文
4. 彩蛋动画与文案内容不匹配（随机独立选取）
5. 发现页社区列表卡片需用共享元素转场到详情页标题栏
6. 栏目加载失败时无具体错误信息查看入口

## 设计

### 第 1 部分：暗色模式文字颜色修复

**问题根因**：DiscoverFilterScreen 使用半透明 surface + Haze 模糊层作为吸顶标题栏背景，标题类 Text 未显式设置 color，依赖默认 LocalContentColor，在暗色模式下对比度不足，看起来是黑色。

**修复范围**：
- `DiscoverFilterScreen.kt` 第 277-282 行（标题栏标题）
- `DiscoverFilterScreen.kt` 第 650-656 行（影视卡片标题 DiscoverFilterListItem）
- `DiscoverFilterScreen.kt` 第 403/436/472/501 行（高级设置分类标题：评分、排序、年代、隐藏已看）
- `TraktListDetailScreen.kt` 第 246-260 行（详情页标题栏标题）

**修复方式**：上述 Text 添加 `color = MaterialTheme.colorScheme.onSurface`。

### 第 2 部分：国际化硬编码修复

#### 2.1 年代 label

**问题**：`DiscoverFilterConstants.kt` 第 179-189 行硬编码：
```kotlin
list.add(DecadeOption("2020年代", 2020, 2029))
list.add(DecadeOption("90年代", 1990, 1999))  // 特殊格式
```

**修复**：
- `DecadeOption` data class 移除 `label: String` 字段，只保留 `startYear` 和 `endYear`
- UI 层（DiscoverFilterScreen 第 488 行）改为 `label = { Text(stringResource(R.string.decade_format, opt.startYear)) }`
- 新增 4 语言字符串 `decade_format`：
  - values/: `%ds`
  - values-zh/: `%d年代`
  - values-ja/: `%d年代`
  - values-ko/: `%d년대`
- 统一显示"1990年代"（不再对 90/80/70/60 特殊用"90年代"），保证一致性

#### 2.2 彩蛋文案国际化

**问题**：`CloudThemeManager.kt` 第 56-66 行 `EASTER_MESSAGES` 硬编码中文列表。

**修复**：彩蛋文案改为存储 stringRes ID（详见第 3 部分重构）。

#### 2.3 返回按钮 contentDescription

**问题**：`TraktListDetailScreen.kt` 第 249 行 `contentDescription = "返回"` 硬编码中文，与同文件第 140 行 `stringResource(R.string.search_back)` 不一致。

**修复**：改用 `stringResource(R.string.content_desc_back)`（与 DoubanLoginScreen 等其他位置统一）。

### 第 3 部分：彩蛋动画-文案 1:N 候选池配对

**问题**：当前 9 个动画和 8 条文案独立随机选取，导致动画与文案语义不匹配（如"困倦"动画配"摸鱼时间到"文案）。

**设计**：重构为 `EasterEgg` 数据类，每个动画绑定 2-3 条语义匹配的候选文案：

```kotlin
data class EasterEgg(
    val animRes: Int,            // Lottie 动画资源
    val messageResIds: List<Int> // 2-3 条语义匹配的候选文案 stringRes ID
)
```

**9 个动画的语义配对**（每条文案需 4 语言）：

| 动画 | 候选文案（中文示例） |
|------|---------|
| shy（害羞） | 你戳到我了，好痒！/ 云朵向你比了个心 / 哎呀，被发现了~ |
| cat（猫） | 喵~ 摸鱼时间到！/ 今天也要像猫一样慵懒 / 猫咪向你蹭了蹭 |
| sleepy（困倦） | 休息一下，喝杯水吧 / 困了就歇会儿~ / 午安，小憩一下 |
| dog（狗） | 汪！陪你搜索到底 / 忠诚如我，永远在身边 / 摇摇尾巴，加油！ |
| bunny（兔子） | 蹦蹦跳跳，烦恼丢掉 / 兔子送你一朵小花 / 软软的，抱一下？ |
| panda（熊猫） | 国宝级治愈~ / 慢慢来，不着急 / 竹子给你，别生气啦 |
| rainbow（彩虹） | 雨后天晴，好事将近 / 七彩祥云来袭！/ 愿日子缤纷绚烂 |
| firework（烟花） | 庆祝一下！你发现彩蛋啦 / 砰！好运绽放 / 今天值得纪念 |
| lantern（灯笼） | 照亮你的搜索之路 / 许个愿吧~ / 温暖相伴，夜不再黑 |

共 9 个动画 × 3 条文案 = 27 条文案 × 4 语言 = 108 条字符串。

**点击逻辑**：
1. 随机选一个 EasterEgg（避免与上次相同，已有逻辑）
2. 从该 EasterEgg 的 messageResIds 随机选一条
3. CloudOverlay 通过 `stringResource(resId)` 解析展示

### 第 4 部分：社区列表卡片共享元素转场

**参考**：已实现的"设置→帮助"转场（sharedBounds key = "settings-help-entry"）。

**设计**：
- 发现页社区列表卡片（Card 整体）包裹 `sharedBounds(key = "listCard:${listId}")`
- TraktListDetailScreen 标题栏（返回箭头 + 标题 的 Row 整体）包裹相同 key 的 `sharedBounds`
- DiscoverScreen 已接收 sharedTransitionScope/animatedVisibilityScope（前面做过 MovieCard 转场），TraktListDetailScreen 需新增这两个参数
- AppNavigation 在 composable(Routes.LIST_DETAIL) 中通过 CompositionLocalProvider 传递 scopes

**关键技术点**：
- 卡片点击时立即触发转场（依赖 AnimatedVisibilityScope 的 nav transition）
- 卡片需用 `Modifier.sharedBounds(...)` 而非 `sharedElement`，因为是不同尺寸的容器
- 标题栏 Row 整体作为目标容器

### 第 5 部分：错误信息可查看（对话框 + 全栏目补齐）

**用户决策**：点击 info 图标弹出 AlertDialog 显示完整错误信息。

#### 5.1 ErrorRetryRow 改造

`DiscoverComponents.kt` 第 260-281 行：
- "加载失败" 右侧加 info 图标（`Icons.Outlined.Info`）
- 点击图标弹出 AlertDialog，显示完整 error 字符串
- 对话框含"关闭"按钮

#### 5.2 ViewModel 改造

给 4 个无 error 分支的 Trakt 栏目 + 社区热门列表补 error 字段：
- DiscoverViewModel 新增 `traktTrendingMovieError`、`traktTrendingShowError`、`traktAnticipatedError`、`traktShowRecommendationError`、`trendingListsError` 等 StateFlow
- 加载失败时设置 error，重试时清空，再次失败时更新为新错误信息

#### 5.3 DiscoverSections 改造

给无 error 分支的 4 个栏目补：
```kotlin
error != null -> { ErrorRetryRow(error = error, onRetry = onRetry) }
```

#### 5.4 社区热门列表

`DiscoverScreen.kt` 第 480-529 行增加 error 分支，与 empty 分支区分开：
- isLoading → 进度条
- error != null → ErrorRetryRow
- isEmpty → "暂无社区热门列表"
- else → 卡片列表

## YAGNI 边界

- 不做错误信息分类（如网络错误/服务器错误/解析错误），直接展示原始 error 字符串
- 不做错误信息复制功能
- 不做错误历史记录
- "查看全部"弹窗（TrendingListsAllSheet）里的卡片不做共享元素转场（避免复杂度，仅主区域卡片支持）
- 彩蛋文案不做 A/B 测试或点击统计
- 年代格式不做特殊处理（如"90年代"统一改为"1990年代"）

## 测试要点

1. 暗色模式下打开筛选页，标题/卡片标题/高级设置标题应清晰可见（onSurface 色）
2. 切换系统语言到英文/日文/韩文，年代选项应显示对应格式（2020s / 2020年代 / 2020년대）
3. 切换语言后彩蛋文案应显示对应语言
4. 多次点击白云彩蛋，动画与文案应语义匹配（如害羞动画不会配"摸鱼时间到"）
5. 点击发现页社区列表卡片，应有放大展开转场到详情页标题栏
6. 关闭网络后下拉发现页，栏目应显示"加载失败" + info 图标，点击图标弹出具体错误
7. 重试后若仍失败，错误信息应更新

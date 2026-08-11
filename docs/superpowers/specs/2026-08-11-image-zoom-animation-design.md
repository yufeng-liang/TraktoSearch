# 全 App 图片缩放动画设计

日期: 2026-08-11
状态: 待评审

## 背景

App 内所有"点击缩略图查看大图"场景目前都是瞬间切换:点击后黑底全屏 overlay 直接弹出,无过渡动画。与 Telegram 等主流 App 的平滑缩放过渡体验差距明显。

本文档设计统一的图片缩放过渡,覆盖 App 内全部 5 处点击图片看大图场景。

## 目标

- 缩略图 → 全屏大图:平滑缩放过渡(Telegram 风格)
- 全屏大图 → 缩略图:反向缩放过渡
- 覆盖全部 5 处场景,行为一致

## 现状

App 已有共享元素转场基础设施:
- `SharedTransitionLayout` 在 `AppNavigation` 顶层,作用域覆盖整个 NavHost
- `LocalSharedTransitionScope` / `LocalAnimatedVisibilityScope` / `LocalSharedTransitionEnabled` 三个 CompositionLocal
- 已有 `sharedElement` 用于「列表页海报 → 详情页海报」的导航转场

当前 5 处图片查看场景全部没有接入共享转场:

| 场景 | 组件 | 宿主方式 | 当前过渡 |
|---|---|---|---|
| 详情页海报 | `PosterFullscreenOverlay` | 内联在 DetailScreen 根 Box | 瞬间弹出 |
| 详情页截图 | `BackdropPagerOverlay` | Dialog 包裹 | 瞬间弹出 |
| 人物页图片 | `PersonImagePagerOverlay` | Dialog 包裹 | 瞬间弹出 |
| 反馈页截图 | `ScreenshotFullscreenOverlay` | 内联(NewFeedback/FeedbackDetail) | 瞬间弹出 |
| 豆瓣详情页海报 | `PosterFullscreenOverlay`(复用) | 内联在 DoubanItemDetailScreen | 瞬间弹出 |

## 关键技术约束

**sharedBounds 两端必须在同一 AnimatedVisibilityScope 内才能配对动画。** 这是 Compose 共享转场的硬约束:

1. **Dialog 是独立 window,composition locals 丢失,无法在 dialog 内容里配对 sharedBounds。**
   因此 Dialog 包裹的 overlay(详情截图、人物图)必须改为内联在宿主组合内。

2. **仅 `if (show) { Overlay() }` 条件组合,两端不共享 AnimatedVisibilityScope,转场不触发。**
   必须用 `AnimatedVisibility(visible = show)` 包裹,让共享元素在 enter/exit 时处于同一 scope。

3. **缩略图源头必须也存在共享 scope 内。** 源头在 LazyColumn item 内,item 需要拿到
   与 overlay 相同的 AnimatedVisibilityScope,或者源头直接包 `sharedBounds`。

## 方案:统一 ZoomableImageOverlay + AnimatedVisibility 配对

### 架构

1. **新建通用组件 `ZoomableImageOverlay`**(`ui/component/`)
   封装全屏图片查看的通用 UI:
   - 全屏黑底
   - `HorizontalPager` 多图滑动
   - `zoomable` 双击缩放/双指缩放
   - 顶部关闭/保存按钮
   - 页码指示
   接受 `images: List<Any>`(URL 或 ByteArray)+ `initialIndex` + `onDismiss`,
   与现有 `ScreenshotFullscreenOverlay` / `BackdropPagerOverlay` 结构一致,
   但内部用 `AnimatedVisibility` + `sharedBounds` 实现缩放过渡。

2. **每个全屏 overlay 改为内联在宿主 Screen 根 Box 内,用 `AnimatedVisibility(visible = show)` 包裹。**

3. **每个缩略图源头加 `sharedBounds`**,key 唯一。

### 各场景改动

#### 1. 详情页海报(DetailScreen)

- 源头:`DetailHeaderContent.kt` 的 `AsyncImage`(已有 `sharedElement("poster-$tmdbId")` 用于导航转场)
- 目标:新增 overlay,`AnimatedVisibility` 包裹
- key:`poster-fullscreen-$tmdbId`
- 注意:详情海报已有 `sharedElement("poster-$tmdbId")` 用于列表→详情导航转场,
  新增全屏转场用**不同 key**,避免冲突。

#### 2. 详情页截图(DetailVideosImages.kt)

- 源头:`BackdropCard` / `FullBackdropItem` 的 `AsyncImage`
- 目标:`BackdropPagerOverlay` → 改为内联 AnimatedVisibility
- key:`backdrop-fullscreen-$tmdbId-$index`

#### 3. 人物页图片(PersonScreen)

用户选择**完整转场(方式 B)**,要求 sheet 内点击也有缩放过渡。

- 现状:头部横向栏 `PosterCard` 点击直接开 `PersonImagePagerOverlay`(Dialog);
  `AllPersonImagesSheet`(ModalBottomSheet)内网格点击也开 pager。
- 方案:
  - **去掉 ModalBottomSheet,改为 PersonScreen 根 Box 内的内联网格面板**(普通 Box,非独立 window)。
  - 网格面板与全屏 pager 兄弟、同处 AnimatedVisibility 层级,天然共享 AnimatedVisibilityScope。
  - 网格小图点击 → 就地缩放飞到全屏;关闭 → 反向缩回网格对应位置。
  - 失去 sheet 手势下滑关闭 + 模糊背景,换回完整转场。
- key:`person-fullscreen-$personId-$index`

#### 4. 反馈页截图(ScreenshotFullscreenOverlay.kt)

- 源头:
  - `FeedbackDetailScreen.kt`:`ConversationBubble` 内 64dp `AsyncImage`
    + `ReplyBar` 内 64dp `AsyncImage`(截图在气泡/回复框内)
  - `NewFeedbackScreen.kt`:`ScreenshotRow` 内 80dp `AsyncImage`
- 目标:`ScreenshotFullscreenOverlay` → 内联 AnimatedVisibility
- key:`fb-screenshot-$feedbackId-$index`(detail 气泡)、`fb-reply-$...`(reply)、
  `fb-new-$index`(new feedback)

#### 5. 豆瓣详情页海报(DoubanItemDetailScreen.kt)

- 复用详情页做法
- key:`douban-poster-fullscreen-$doubanId`

### 动画规格

- `sharedBounds` 使用 Compose 默认弹簧动画(≈Telegram 手感)
- 进入:`fadeIn + scaleIn`;退出:`fadeOut + scaleOut`
- 约 220-280ms,与现有详情页 fade 过渡一致
- 黑底背景用独立 `fadeIn`/`fadeOut`,不参与 sharedBounds

### 回退

`LocalSharedTransitionEnabled` 关闭时(设置里可关共享转场):
- 所有 `sharedBounds` 不附加
- overlay 退化为无动画黑底弹出(保持现有行为)

## 测试

- 模拟器验证各场景:
  - 点缩略图 → 放大平滑过渡
  - 关闭 → 反向缩放过渡
  - 多图横滑翻页
  - 双击缩放、双指缩放
  - 保存按钮
  - 共享转场设置关闭时退化正常
- `adb` 手动验证
- 检查 logcat 无异常

## 风险

- Dialog → 内联改造涉及 4 个 overlay,改动面大;但统一走 `ZoomableImageOverlay` 可复用
- ModalBottomSheet → 内联面板(人物图)有交互变化:失去手势下滑关闭,需要用户接受
- 反馈页 ByteArray 缩略图 ↔ 全屏(ByteArray)sharedBounds 配对:需确认 Coil 能处理内存图片的 sharedBounds 匹配

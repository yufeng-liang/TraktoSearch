# Watchlist 列表卡片入场动画设计

- 日期：2026-07-15
- 范围：watchlist 页 `LazyVerticalGrid` 中的 `WatchlistPosterCard` 入场动画
- 现状：卡片已用 `Modifier.fadeSlideIn(index)`（`ui/animation/Animations.kt`），效果是淡入 + 上滑 16dp、220ms、无错峰；`index` 参数已弃用。

## 目标

为 watchlist 卡片提供更有秩序感、且性能稳定的入场动画，区分「常规出现」与「用户主动操作（切 tab / 下拉刷新）」两种语境。

## 决策摘要

- 默认入场 = **B（错峰淡入 + 上滑）**：克制、稳、通用。
- 强调态 = **E（弹性上滑）**：仅在切 tab / 下拉刷新时触发，duration 380ms。
- 滚动露出 = B（微错峰），每张卡**一生只播一次**。
- 错峰延迟 = `(index % 3) × 30ms`（按行内位置，避免深列表延迟爆炸）。

## 动画参数

| 模式 | 触发 | alpha | translationY | 时长 | 缓动 |
|---|---|---|---|---|---|
| B 默认（错峰淡入+上滑） | 首次出现 | 0→1 | 16dp→0 | 260ms | easeOut（`FastOutSlowIn` 或 `CubicBezierEasing(0f,0f,0.2f,1f)`） |
| E 强调态（弹性上滑） | 切 tab / 下拉刷新 | 0→1 | 40dp→0 | 380ms | 过冲 `CubicBezierEasing(0.34f,1.56f,0.64f,1f)` |

两种模式均通过 `graphicsLayer { alpha; translationY }` 实现（非 layout/measure 变更），GPU 仅做纹理变换，不重栅格化内容。

## 错峰规则

- 延迟 = `(index % 3) × 30ms`，即同一行内 3 张卡片各延 0 / 30 / 60ms。
- 用取模而非全局 index，避免列表靠后的卡片累计出过大延迟（如第 50 张 ≈ 1.9s）。
- 首屏与滚动露出统一适用该公式。

## 触发与重播规则

- **默认 B**：每张卡一生只播一次；滚出视口再滚回不重播。
- **强调 E**：切 tab / 下拉刷新完成时，当前可见卡片重播 E（显式用户动作，允许重播）。
- 初始进页面走 B，不走 E。

## 实现机制

替换现有 `Modifier.fadeSlideIn(index)`，改为父作用域状态驱动。

### Screen 级状态（WatchlistScreen）

- `animatedIds: MutableSet<Long>` —— 记录已播过的卡片 `traktId`。用 `rememberSaveable` + `Saver` 跨配置变更（旋转）保留；若不想处理 Saver，可退化为普通 `remember`（旋转会重播，影响很小）。
- `enterMode: EnterMode`（枚举：`DEFAULT` / `EMPHASIS`）。
- 切 tab：`LaunchedEffect(selectedMode, selectedTab)` 跳过首次发射 → `enterMode = EMPHASIS` → `delay(500)` → 复位 `DEFAULT`。500ms 为强调窗口期。
- 下拉刷新完成：同样置 `EMPHASIS` 一个窗口期（在 `pullToRefreshConnection` 刷新成功处 bump）。

### 卡片级（Modifier.cardEnter）

签名：`fun Modifier.cardEnter(id: Long, index: Int, enterMode: EnterMode, animatedIds: MutableState<MutableSet<Long>>): Modifier`

行为：
```
LaunchedEffect(enterMode, id) {
    if (enterMode == EMPHASIS) {
        // 强调态：从 40dp 下方弹性上滑 380ms，alpha 0→1
        // 播完后 animatedIds.add(id)，防止窗口复位时补播 B
        play E
        animatedIds.add(id)
    } else if (id !in animatedIds) {
        animatedIds.add(id)
        // 默认：淡入 + 上滑 16dp，260ms，delay = (index % 3) * 30ms
        play B
    }
}
graphicsLayer(alpha = alpha.value, translationY = offsetY.value)
```

用两个 `Animatable`（alpha、offsetY），初始值依据 `animatedIds` 是否已含该 id 决定（已含则初始即为终态，避免闪烁）。

## 改动文件

- `ui/animation/Animations.kt`
  - 删除 `fadeSlideIn`。
  - 新增 `cardEnter(...)`：含 B/E 两套 `Animatable` 驱动、`CubicBezierEasing(0.34f,1.56f,0.64f,1f)` 缓动常量、错峰延迟计算。
- `ui/screen/watchlist/WatchlistScreen.kt`
  - 新增 `animatedIds` / `enterMode` 状态。
  - `LazyVerticalGrid` 的 `items` lambda 中，将 `Modifier.fadeSlideIn(index)` 替换为 `Modifier.cardEnter(item.traktId, index, enterMode, animatedIds)`。
  - 在 tab 切换（`selectedMode` / `selectedTab` 变化）与下拉刷新完成处设置 `enterMode = EMPHASIS` 窗口。

## 风险 / 边界

- 强调窗口期（500ms）内若用户快速滚动，新露出卡片会播 E（窗口很短，可接受）。
- `CubicBezierEasing` 控制点 y>1 产生过冲，Compose 官方支持。
- 动画只包在卡片外层 `Box`，不影响 `WatchlistPosterCard` 内部布局、多选态、长按逻辑。
- `LazyVerticalGrid` 仅组合可见 item，离屏卡片动画零开销。

## 测试建议

- 进页面：首屏卡片错峰淡入上滑，无空白/闪烁。
- 向下滚：未见过的卡片播 B 微错峰；往回滚已见卡片静止。
- 切 tab / 下拉刷新：可见卡片弹性质上滑（E，380ms）。
- 低端机：首屏与滚动无掉帧（动画走 graphicsLayer）。

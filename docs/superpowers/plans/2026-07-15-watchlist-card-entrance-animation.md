# Watchlist 列表卡片入场动画 实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 把 watchlist 卡片的入场动画从「淡入+上滑（无错峰）」升级为「默认 B 错峰淡入+上滑；切 tab / 下拉刷新时用 E 弹性上滑强调态」，且每张卡默认动画一生只播一次。

**架构：** 删除现有 `Modifier.fadeSlideIn(index)`，新增 `Modifier.cardEnter(id, index, enterMode, animatedIds)`。动画状态由 Screen 级驱动：`animatedIds` 记录已播过的 traktId（保证只播一次），`enterMode` 在切 tab / 下拉刷新时被置为 `EMPHASIS` 一个 500ms 窗口期。卡片通过 `graphicsLayer` 跑 `Animatable` 动画，不走 layout/measure。

**技术栈：** Jetpack Compose（`androidx.compose.animation.core` 的 `Animatable` / `tween` / `CubicBezierEasing`），Kotlin 协程。

---

## 文件结构

- **修改** `app/src/main/java/com/tracktosearch/ui/animation/Animations.kt`
  - 删除 `fadeSlideIn`。
  - 新增 `enum class EnterMode { DEFAULT, EMPHASIS }`、`private val EMPHASIS_EASING`、`fun enterStaggerDelayMs(index: Int): Int`、`fun shouldPlayDefault(id: Long, played: Set<Long>): Boolean`、`fun Modifier.cardEnter(...)`。
- **修改** `app/src/main/java/com/tracktosearch/ui/screen/watchlist/WatchlistScreen.kt`
  - 新增 import（`EnterMode`、`cardEnter`、`rememberSaveable`、`listSaver`）。
  - 新增 Screen 级状态 `enterMode`、`animatedIds`。
  - 在 tab 切换 `LaunchedEffect(selectedMode, selectedTab)` 内 bump `enterMode`。
  - 在下拉刷新 `onPreFling` 的 `viewModel.refresh()` 后 bump `enterMode`。
  - `LazyVerticalGrid` 的 items lambda 中把 `Modifier.fadeSlideIn(index)` 换成 `Modifier.cardEnter(...)`。
- **新建** `app/src/test/java/com/tracktosearch/ui/animation/AnimationsTest.kt`
  - JVM 单元测试，验证纯函数 `enterStaggerDelayMs` 与 `shouldPlayDefault`。

---

### 任务 1：提取纯函数并写失败测试

**文件：**
- 新建：`app/src/test/java/com/tracktosearch/ui/animation/AnimationsTest.kt`
- 修改：`app/src/main/java/com/tracktosearch/ui/animation/Animations.kt`

- [ ] **步骤 1：在 Animations.kt 末尾追加两个纯函数**

```kotlin
/** 错峰延迟：按行内位置 (index%3) 各延 0/30/60ms，避免深列表累计延迟爆炸。 */
fun enterStaggerDelayMs(index: Int): Int = (index % 3) * 30

/** 默认动画是否仍需播放：id 不在已播集合中才播（一生一次）。 */
fun shouldPlayDefault(id: Long, played: Set<Long>): Boolean = !played.contains(id)
```

- [ ] **步骤 2：编写失败的单测**

```kotlin
package com.tracktosearch.ui.animation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnimationsTest {
    @Test
    fun stagger_delay_per_row() {
        assertEquals(0, enterStaggerDelayMs(0))
        assertEquals(30, enterStaggerDelayMs(1))
        assertEquals(60, enterStaggerDelayMs(2))
        assertEquals(0, enterStaggerDelayMs(3))
        assertEquals(60, enterStaggerDelayMs(11))
    }

    @Test
    fun should_play_default_only_once() {
        val played = mutableSetOf<Long>()
        assertTrue(shouldPlayDefault(1L, played))
        played.add(1L)
        assertFalse(shouldPlayDefault(1L, played))
    }
}
```

- [ ] **步骤 3：运行测试确认失败**

运行：`gradlew :app:testDebugUnitTest --tests "com.tracktosearch.ui.animation.AnimationsTest"`
预期：编译失败 / 报 `Unresolved reference: enterStaggerDelayMs`（函数尚未加入被测模块前应先加函数——若步骤 1 已加则本步直接 PASS，跳过步骤 4 验证）。若先写测试再写函数，此处 FAIL。

- [ ] **步骤 4：确认测试通过（函数已在步骤 1 加入）**

运行：`gradlew :app:testDebugUnitTest --tests "com.tracktosearch.ui.animation.AnimationsTest"`
预期：BUILD SUCCESSFUL，2 个测试 PASS。

- [ ] **步骤 5：Commit**

```bash
git add app/src/main/java/com/tracktosearch/ui/animation/Animations.kt app/src/test/java/com/tracktosearch/ui/animation/AnimationsTest.kt
git commit -m "test: watchlist 入场动画纯函数与单测（错峰延迟/只播一次）"
```

---

### 任务 2：实现 cardEnter 修饰符

**文件：**
- 修改：`app/src/main/java/com/tracktosearch/ui/animation/Animations.kt`

- [ ] **步骤 1：替换整个 Animations.kt 内容**

用以下完整内容覆盖文件（删除旧 `fadeSlideIn`）：

```kotlin
package com.tracktosearch.ui.animation

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.launch

/** 入场模式：DEFAULT=B 错峰淡入上滑；EMPHASIS=E 弹性上滑强调态。 */
enum class EnterMode { DEFAULT, EMPHASIS }

/** 过冲缓动，y 控制点 >1 产生回弹（弹性上滑）。 */
private val EMPHASIS_EASING = CubicBezierEasing(0.34f, 1.56f, 0.64f, 1f)

/** 错峰延迟：按行内位置 (index%3) 各延 0/30/60ms。 */
fun enterStaggerDelayMs(index: Int): Int = (index % 3) * 30

/** 默认动画是否仍需播放：id 不在已播集合中才播（一生一次）。 */
fun shouldPlayDefault(id: Long, played: Set<Long>): Boolean = !played.contains(id)

/**
 * watchlist 卡片入场动画。
 * - DEFAULT：淡入 + 上滑 16dp，260ms，按 enterStaggerDelayMs 微错峰（B）。
 * - EMPHASIS：弹性上滑 40dp→0，380ms 过冲（E），用于切 tab / 下拉刷新。
 * animatedIds 记录已播过的 id，保证每张卡默认动画一生只播一次。
 */
fun Modifier.cardEnter(
    id: Long,
    index: Int,
    enterMode: EnterMode,
    animatedIds: MutableState<MutableSet<Long>>,
): Modifier = composed {
    val alreadyPlayed = animatedIds.value.contains(id)
    val alpha = remember(id) { Animatable(if (alreadyPlayed) 1f else 0f) }
    val offsetY = remember(id) { Animatable(if (alreadyPlayed) 0f else 16f) }

    LaunchedEffect(enterMode, id) {
        if (enterMode == EnterMode.EMPHASIS) {
            alpha.snapTo(0f)
            offsetY.snapTo(40f)
            launch { alpha.animateTo(1f, tween(380)) }
            offsetY.animateTo(0f, tween(380, easing = EMPHASIS_EASING))
            val set = animatedIds.value.toMutableSet().apply { add(id) }
            animatedIds.value = set
        } else if (shouldPlayDefault(id, animatedIds.value)) {
            val delayMs = enterStaggerDelayMs(index)
            val set = animatedIds.value.toMutableSet().apply { add(id) }
            animatedIds.value = set
            launch { alpha.animateTo(1f, tween(260, delayMillis = delayMs)) }
            offsetY.animateTo(0f, tween(260, delayMillis = delayMs))
        }
    }
    graphicsLayer(alpha = alpha.value, translationY = offsetY.value)
}
```

- [ ] **步骤 2：运行单测确认未破坏**

运行：`gradlew :app:testDebugUnitTest --tests "com.tracktosearch.ui.animation.AnimationsTest"`
预期：BUILD SUCCESSFUL，2 个测试 PASS。

- [ ] **步骤 3：Commit**

```bash
git add app/src/main/java/com/tracktosearch/ui/animation/Animations.kt
git commit -m "feat: 新增 cardEnter 入场动画（B默认+E强调态）"
```

---

### 任务 3：WatchlistScreen 接入状态与 import

**文件：**
- 修改：`app/src/main/java/com/tracktosearch/ui/screen/watchlist/WatchlistScreen.kt`

- [ ] **步骤 1：新增 import**

在文件顶部 import 区（与现有 `androidx.compose.runtime.*` 相邻处）新增：

```kotlin
import com.tracktosearch.ui.animation.EnterMode
import com.tracktosearch.ui.animation.cardEnter
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
```

（若现有文件已通过 `import com.tracktosearch.ui.animation.fadeSlideIn` 引入旧函数，删除该行。）

- [ ] **步骤 2：在 `selectedMode`/`selectedTab` 声明之后新增 Screen 级状态**

定位 `var selectedTab by rememberSaveable { mutableIntStateOf(0) }`（约 :206），在其后插入：

```kotlin
    // 卡片入场动画状态
    var enterMode by remember { mutableStateOf(EnterMode.DEFAULT) }
    val animatedIds = rememberSaveable(
        saver = listSaver(
            save = { it.value.toList() },
            restore = { mutableStateOf(it.toMutableSet()) },
        ),
    ) { mutableStateOf(mutableSetOf<Long>()) }
```

> 说明：`animatedIds` 需在 `pullToRefreshConnection`（约 :268）之前声明，因为下拉刷新的 `onPreFling` 闭包会写 `enterMode`。`enterMode` 用普通 `remember` 即可（不跨旋转保留，影响极小）。

- [ ] **步骤 3：Commit**

```bash
git add app/src/main/java/com/tracktosearch/ui/screen/watchlist/WatchlistScreen.kt
git commit -m "feat: watchlist 接入入场动画状态（enterMode/animatedIds）"
```

---

### 任务 4：切 tab 时触发 E 强调态

**文件：**
- 修改：`app/src/main/java/com/tracktosearch/ui/screen/watchlist/WatchlistScreen.kt`

- [ ] **步骤 1：在 `LaunchedEffect(selectedMode, selectedTab)`（约 :346）内 bump enterMode**

该 effect 已有滚动位置保存/恢复逻辑，保留不动，仅在开头插入强调态触发（跳过首次发射，避免进页面就播 E）：

```kotlin
    var firstTabSwitch by remember { mutableStateOf(true) }
    LaunchedEffect(selectedMode, selectedTab) {
        val currentKey = "${selectedMode}_${selectedTab}"
        if (firstTabSwitch) {
            firstTabSwitch = false
        } else {
            enterMode = EnterMode.EMPHASIS
            delay(500)
            enterMode = EnterMode.DEFAULT
        }
        // —— 以下保留原有逻辑，不要删除 ——
        // 保存旧 tab 的位置
        savedScrollPositions[prevTabKey] = Pair(
            // ... 原有代码 ...
```

> 注意：`delay` 已在文件中可用（Coroutine 作用域内，`LaunchedEffect` 提供 `suspend`）。`prevTabKey` 等原变量保留。

- [ ] **步骤 2：Commit**

```bash
git add app/src/main/java/com/tracktosearch/ui/screen/watchlist/WatchlistScreen.kt
git commit -m "feat: 切 tab 触发 E 强调态入场动画"
```

---

### 任务 5：下拉刷新完成时触发 E 强调态

**文件：**
- 修改：`app/src/main/java/com/tracktosearch/ui/screen/watchlist/WatchlistScreen.kt`

- [ ] **步骤 1：在 `pullToRefreshConnection` 的 `onPreFling`（约 :290-300）内 bump enterMode**

定位：
```kotlin
                if (overscrollOffset >= triggerThreshold && !isRefreshing) {
                    isRefreshing = true
                    viewModel.refresh()
                    isRefreshing = false
                }
```
改为：
```kotlin
                if (overscrollOffset >= triggerThreshold && !isRefreshing) {
                    isRefreshing = true
                    viewModel.refresh()
                    enterMode = EnterMode.EMPHASIS
                    gridCoroutineScope.launch { delay(500); enterMode = EnterMode.DEFAULT }
                    isRefreshing = false
                }
```

> `gridCoroutineScope` 已在 :315 声明（`rememberCoroutineScope()`），`onPreFling` 闭包可捕获引用（首次 fling 前已初始化）。

- [ ] **步骤 2：Commit**

```bash
git add app/src/main/java/com/tracktosearch/ui/screen/watchlist/WatchlistScreen.kt
git commit -m "feat: 下拉刷新触发 E 强调态入场动画"
```

---

### 任务 6：items lambda 应用 cardEnter

**文件：**
- 修改：`app/src/main/java/com/tracktosearch/ui/screen/watchlist/WatchlistScreen.kt`

- [ ] **步骤 1：替换 items lambda 中的修饰符**

定位（约 :555-559）：
```kotlin
                        items(items.size, key = { items[it].traktId }, contentType = { "media_card" }) { index ->
                            val item = items[index]
                            val isSelected = selectedItems[item.traktId] == true
                            val isResolving = isRemoving && isSelected
                            Box(modifier = Modifier.fadeSlideIn(index)) {
```
改为：
```kotlin
                        items(items.size, key = { items[it].traktId }, contentType = { "media_card" }) { index ->
                            val item = items[index]
                            val isSelected = selectedItems[item.traktId] == true
                            val isResolving = isRemoving && isSelected
                            Box(modifier = Modifier.cardEnter(item.traktId, index, enterMode, animatedIds)) {
```

- [ ] **步骤 2：Commit**

```bash
git add app/src/main/java/com/tracktosearch/ui/screen/watchlist/WatchlistScreen.kt
git commit -m "feat: watchlist 卡片应用 cardEnter 入场动画"
```

---

### 任务 7：构建与回归验证

**文件：**
- 验证：`app/src/androidTest/java/com/tracktosearch/ui/screen/watchlist/WatchlistScreenTest.kt`

- [ ] **步骤 1：编译 debug**

运行：`gradlew :app:compileDebugKotlin`
预期：BUILD SUCCESSFUL，无未解析引用（确认 `fadeSlideIn` 已无残留引用、`cardEnter`/`EnterMode` 已正确导入）。

- [ ] **步骤 2：运行单测**

运行：`gradlew :app:testDebugUnitTest --tests "com.tracktosearch.ui.animation.AnimationsTest"`
预期：BUILD SUCCESSFUL，2 个测试 PASS。

- [ ] **步骤 3：运行现有 watchlist 仪表化测试确认无回归**

运行：`gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.tracktosearch.ui.screen.watchlist.WatchlistScreenTest`
（无设备时跳过，改为 `gradlew :app:compileDebugAndroidTestKotlin` 确认编译通过。）
预期：卡片正常渲染、无崩溃；进场动画不阻断 `ComposeUiTest` 的 `onNode` 查找。

- [ ] **步骤 4：安装 debug 包手动验收**

构建并安装：`gradlew :app:installDebug`。
手动检查清单：
1. 进 watchlist：首屏卡片错峰淡入上滑，无空白/闪烁。
2. 向下滚：未见过的卡片播 B 微错峰；往回滚已见卡片静止（不重播）。
3. 切「想看/已看」或「电影/剧集」tab：可见卡片弹性上滑（E，380ms）。
4. 下拉刷新：可见卡片弹性上滑（E）。
5. 低端机首屏与滚动无掉帧。

- [ ] **步骤 5：Commit（若有伴随微调）**

```bash
git add -A
git commit -m "fix: 入场动画回归微调"
```
（若无改动则跳过此 commit。）

---

## 自检记录

- **规格覆盖度**：B 默认（任务 2/6）、E 强调态（任务 2/4/5）、滚动= B 微错峰（任务 2 的 `enterStaggerDelayMs`）、一生一次（任务 2/3 `animatedIds` + `shouldPlayDefault`）、切 tab（任务 4）、下拉刷新（任务 5）、改动文件与规格一致。
- **占位符**：无 TODO/待定；每步均含代码或确切命令与预期。
- **类型一致性**：`EnterMode` 在 Animations.kt 定义、WatchlistScreen 引用一致；`cardEnter(id, index, enterMode, animatedIds)` 签名在定义与调用处（任务 6）一致；`animatedIds` 类型为 `MutableState<MutableSet<Long>>`，与 `cardEnter` 参数一致。

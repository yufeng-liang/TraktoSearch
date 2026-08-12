# Watchlist 标题栏:搜索展开时隐藏标题「我的」实现计划

> **面向 AI 代理的工作者:** 必需子技能:使用 superpowers:subagent-driven-development(推荐)或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框(`- [ ]`)语法来跟踪进度。

**目标:** watchlist 页搜索框展开(`isSearchExpanded = true`)时,标题「我的」淡出隐藏;收起时淡入恢复,动画与搜索框 240ms 展开动画同步。

**架构:** 标题 `Text` 外包 `AnimatedVisibility(visible = !isSearchExpanded, enter = fadeIn(tween(240)), exit = fadeOut(tween(240)))`,标题的 `Modifier.align(Alignment.CenterStart)` 上移到 AnimatedVisibility。只做透明度动画(无尺寸动画),布局零跳动。

**技术栈:** Jetpack Compose(AnimatedVisibility / fadeIn / fadeOut / tween 均已 import,无需新增)。

规格: `docs/superpowers/specs/2026-08-12-watchlist-title-search-hide-design.md`

---

### 任务 1:标题随搜索展开隐藏/收起恢复 + UI 测试

**文件:**
- 修改:`app/src/main/java/com/tracktosearch/ui/screen/watchlist/WatchlistScreen.kt:897-904`(标题 Text 外包 AnimatedVisibility)
- 修改:`app/src/androidTest/java/com/tracktosearch/ui/screen/watchlist/WatchlistScreenTest.kt`(在「点击搜索图标展开并保留查询」测试之后新增一条测试)

- [ ] **步骤 1:编写失败测试**

在 `WatchlistScreenTest.kt` 的「点击搜索图标展开并保留查询」测试(约 357-377 行)之后插入:

```kotlin
    @Test
    fun `展开搜索时隐藏标题收起时恢复`() {
        composeRule.setContent {
            WatchlistScreen(
                onMovieClick = { _, _, _, _, _, _, _ -> },
                onShowClick = { _, _, _, _, _, _, _ -> },
                onSearchClick = {},
                onTraktSearch = { _, _ -> },
                viewModel = createMockWatchlistViewModel()
            )
        }
        composeRule.waitForIdle()

        val titleText = context.getString(R.string.tab_me)
        // 初始:标题可见
        composeRule.onNodeWithText(titleText).assertIsDisplayed()

        // 点击搜索图标展开:标题淡出后从组合树移除
        val searchDescription = context.getString(R.string.watchlist_search)
        composeRule.onNodeWithContentDescription(searchDescription).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText(titleText).assertDoesNotExist()

        // 点击分类 Tab 收起搜索:标题恢复显示
        composeRule.onNodeWithTag("watchlist_category_tab_1").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText(titleText).assertIsDisplayed()
    }
```

- [ ] **步骤 2:运行测试确认失败**

先确认测试设备在线:

运行:`adb devices`
预期:有设备输出(如 `emulator-5554 device`)。

运行:`./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.tracktosearch.ui.screen.watchlist.WatchlistScreenTest --console=plain`
预期:新测试 FAIL,失败断言为 `assertDoesNotExist`(当前实现中标题在搜索展开后仍在组合树里)。

> 若设备不在线:运行 `./gradlew :app:compileDebugAndroidTestKotlin --console=plain` 确认测试代码编译通过,记录「测试未执行,待设备在线后验证」,继续步骤 3。

- [ ] **步骤 3:实现标题隐藏逻辑**

修改 `WatchlistScreen.kt` 中标题区域(当前 897-904 行):

把

```kotlin
                                        Text(
                                            text = stringResource(R.string.tab_me),
                                            modifier = Modifier.align(Alignment.CenterStart),
                                            fontSize = 28.sp,
                                            fontWeight = FontWeight.ExtraBold,
                                            letterSpacing = (-0.5).sp,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
```

替换为

```kotlin
                                        androidx.compose.animation.AnimatedVisibility(
                                            visible = !isSearchExpanded,
                                            enter = fadeIn(animationSpec = tween(240)),
                                            exit = fadeOut(animationSpec = tween(240)),
                                            modifier = Modifier.align(Alignment.CenterStart)
                                        ) {
                                            Text(
                                                text = stringResource(R.string.tab_me),
                                                fontSize = 28.sp,
                                                fontWeight = FontWeight.ExtraBold,
                                                letterSpacing = (-0.5).sp,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                        }
```

说明:`fadeIn`/`fadeOut`/`tween`/`AnimatedVisibility` 均已 import(文件 8/16/17/18 行),无需新增 import;`isSearchExpanded` 是已有的 `rememberSaveable` 状态(397 行),所有收起路径(点击空白、Back、过滤按钮、切 Tab)都通过 `collapseSearch()` 置 false,标题随之淡入。

**注意(实现期修正):** 该调用点位于两层 `ColumnScope` 内,裸名 `AnimatedVisibility` 会被解析为 `androidx.compose.animation` 包中的 `ColumnScope.AnimatedVisibility` 扩展(与顶层函数同包,import 顶层函数时扩展同时在作用域内),导致编译错误。必须使用全限定名 `androidx.compose.animation.AnimatedVisibility` 强制调用顶层函数。

- [ ] **步骤 4:运行测试确认通过**

运行:`./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.tracktosearch.ui.screen.watchlist.WatchlistScreenTest --console=plain`
预期:全部 PASS(含新增的「展开搜索时隐藏标题收起时恢复」)。

- [ ] **步骤 5:构建验证**

运行:`./gradlew :app:assembleDebug --console=plain`
预期:`BUILD SUCCESSFUL`,无新增警告。

- [ ] **步骤 6:Commit**

```bash
git add app/src/main/java/com/tracktosearch/ui/screen/watchlist/WatchlistScreen.kt app/src/androidTest/java/com/tracktosearch/ui/screen/watchlist/WatchlistScreenTest.kt
git commit -m "feat(watchlist): 搜索展开时隐藏标题栏标题"
```

提交前执行 `git diff --cached --check` 确认无空白错误,`git diff --cached --name-only` 确认只含上述两个文件。

---

## 自检记录

- **规格覆盖度:** 展开隐藏 ✅(步骤 3 AnimatedVisibility)、收起恢复 ✅(同一状态驱动)、240ms 动画与搜索框一致 ✅(tween(240))、无障碍移除 ✅(AnimatedVisibility 退出组合树)、布局零跳动 ✅(仅 fade 无尺寸动画)、其余交互不变 ✅(外层 Box clickable / Back / 过滤 / 切 Tab 均不触碰)。
- **占位符扫描:** 无 TODO / 待定 / 模糊描述。
- **类型一致性:** `isSearchExpanded`、`collapseSearch`、`fadeIn/fadeOut/tween` 均为 WatchlistScreen.kt 既有符号,与规格一致。

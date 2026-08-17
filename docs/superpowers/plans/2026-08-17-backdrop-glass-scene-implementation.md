# Backdrop 场景玻璃实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking progress.

**Goal:** 将 TrackToSearch 的正式 Glass 路径从 `hazeGlass` 迁移到 Backdrop，并按底栏、顶栏、搜索框、圆形按钮、详情按钮和卡片使用独立光学参数，同时保持 Blur 的成熟 Haze 行为不变；设置页只保留一套 Backdrop Glass 预设并默认使用 Glass。

**Architecture:** 在每个需要采样的页面根部创建一个 `BackdropProvider`，由 `LayerBackdrop` 采集后方内容并通过 CompositionLocal 提供给 Glass 组件。`AppVisualSurface`、`AppIconButton` 和 `appVisualEffect` 在 Blur 模式继续分发到 Haze/拟态实现，在 Glass 模式分发到 `drawBackdrop`；底栏只创建一个采样外壳，选中指示器用独立的普通半透明层和 spring 位移实现 A 方案。

**Tech Stack:** Jetpack Compose、Material 3、Haze `2.0.0-alpha04`（仅 Blur）、Backdrop `2.0.0`、Preferences DataStore、JUnit/Truth、Robolectric、Compose UI Test、ADB。

**Spec:** `docs/superpowers/specs/2026-08-17-backdrop-glass-scene-design.md`

## Global Constraints

- 模糊模式继续使用现有 `hazeBlur`、`NeumorphicFrostedSurface` 和 `NeumorphicIconButton`，不改变视觉和交互。
- 玻璃模式不得调用 `hazeGlass`、`dev.chrisbanes.haze.glass.*` 或拟态 Glass 装饰；无 Backdrop host 时只使用普通半透明 fallback。
- 六类核心角色的基础参数固定为：BottomNavigation `20/8/36`、TopBar `20/8/36`、SearchField `14/8/26`、Card `14/8/26`、CircularControl `12/6/18`、DetailAction `12/6/18`，顺序分别为 blur/refractionHeight/refractionAmount。
- BottomNavigation、TopBar、SearchField、Card 开启 depth 和 chromatic；CircularControl、DetailAction 开启 depth、关闭 chromatic。
- Backdrop effect 顺序固定为色彩滤镜或 vibrancy、blur、lens；代码统一完成 `Dp` 到 API 像素值的转换。
- 底栏按压只缩放内容层和独立指示器，不能缩放承载采样的外层；指示器不创建第二个 Backdrop surface。
- 保留 `VisualEffectMode`、`GlassVariant` 和 DataStore key 的读取兼容性，但设置页只展示 Blur 与一项 Backdrop Glass，不再展示 Clear/Focused 两个 Glass 预设；旧的 `focused` 存储值归一到唯一 Backdrop Glass。没有已保存材质时默认使用 Glass，用户明确保存的 Blur 仍保持 Blur。
- `Card` 角色对应设置页卡片、发现页社区热门列表卡片和详情页评论卡片；其 lens 与开关必须使用截图明确的 `14/8/26`、depth 开启、chromatic 开启，高光/投影沿用 SearchField 的可读性包络。
- 用户可见文字必须使用 `stringResource`，新增或修改文案同步 `values/`、`values-zh/`、`values-ja/`、`values-ko/`。
- 代码注释和面向用户说明使用中文；ViewModel 内部错误使用英文；不修改网络、缓存、数据库或 DTO 契约。
- 每个任务都必须先完成针对性测试/编译，再只提交该任务的授权文件；提交前执行 `git diff --cached --check` 和 `git diff --cached --name-only`。

## 文件结构与职责

**创建：**

- `app/src/main/java/com/tracktosearch/ui/component/BackdropHost.kt`：创建 `LayerBackdrop`、背景绘制和 `LocalBackdrop`，不包含角色 token。
- `app/src/test/java/com/tracktosearch/ui/component/BackdropGlassInteractionTest.kt`：验证 Glass 控件的点击/禁用语义和底栏指示器状态，不依赖截图像素。

**修改：**

- `app/src/main/java/com/tracktosearch/ui/component/GlassTokens.kt`：把 Haze Glass 专用 token 替换为 Backdrop 角色 token，保留 `GlassScene`、环境色和 fallback 辅助函数。
- `app/src/main/java/com/tracktosearch/ui/component/AppGlassStyle.kt`：Task 1 阶段保留旧 Haze Glass 兼容 shim，并补齐 `Card` 角色；Task 7 完成调用迁移后删除旧 Haze Glass token/样式定义。
- `app/src/main/java/com/tracktosearch/ui/component/GlassSurface.kt`：用 `drawBackdrop` 重写 Glass surface、icon button 和普通指示器。
- `app/src/main/java/com/tracktosearch/ui/component/VisualSurface.kt`：统一分发 Blur、Backdrop Glass、Content 和 Modal。
- `app/src/main/java/com/tracktosearch/ui/component/AppVisualEffect.kt`：保留 Haze Blur 分支，把 Glass 分支改为 Backdrop。
- `app/src/main/java/com/tracktosearch/ui/component/NeumorphicGlass.kt`：保留 Blur 实现，迁移完成后移除其中的 Glass 旁路。
- `app/src/main/java/com/tracktosearch/ui/screen/pilot/GlassEnginePilotScreen.kt`：使用正式 token 展示五类场景和 A 方案动效。
- `app/src/main/java/com/tracktosearch/ui/theme/VisualEffectMode.kt`、`app/src/main/java/com/tracktosearch/data/local/ThemeStorage.kt`：保留旧存储读取兼容，新增无记录默认 Glass 和旧 Focused 归一逻辑。
- `app/src/main/java/com/tracktosearch/ui/screen/settings/SettingsDialogs.kt`、`SettingsScreen.kt`：材质弹窗只展示 Blur/Backdrop Glass 两项，选项均带小字说明，设置卡片在 Glass 模式使用 Card token。
- `app/src/main/java/com/tracktosearch/ui/screen/main/MainScreen.kt`、Discover、Search、TraktSearch、Watchlist、MarkRecord、Detail、Person、Douban、Statistics、DiscoverFilter、Settings、Login 相关页面：接入 Backdrop host 和角色入口。
- `app/src/main/java/com/tracktosearch/ui/component/GlassSearchBar.kt`、`ActionButtonRow.kt`、`ScrollToTopButton.kt`、`WatchlistCategoryTabs.kt`：迁移直接使用效果 modifier 的组件。
- `app/build.gradle.kts`、`gradle/libs.versions.toml`：所有正式 Glass 调用迁移后删除 `haze-glass` 依赖，保留 Haze blur 和 Backdrop。
- `app/src/test/java/com/tracktosearch/ui/component/GlassTokenTest.kt`、`NeumorphicGlassTest.kt`、主题/设置现有测试：锁定 token、分发、兼容状态和 Blur 回归。
- 必要时修改四套 `strings.xml`：只补充试点新文案或过时帮助文案，不把底层 Backdrop 参数暴露到设置页。

## Task 1: 建立 Backdrop host 与场景 token

**Files:**

- Create: `app/src/main/java/com/tracktosearch/ui/component/BackdropHost.kt`
- Modify: `app/src/main/java/com/tracktosearch/ui/component/GlassTokens.kt`
- Modify: `app/src/main/java/com/tracktosearch/ui/component/AppGlassStyle.kt`
- Test: `app/src/test/java/com/tracktosearch/ui/component/GlassTokenTest.kt`

**Interfaces:**

- Produces `val LocalBackdrop: ProvidableCompositionLocal<LayerBackdrop?>` with default `null`.
- Produces `@Composable fun BackdropProvider(modifier: Modifier = Modifier, backgroundColor: Color = MaterialTheme.colorScheme.background, content: @Composable BoxScope.() -> Unit)`.
- Produces `data class BackdropGlassToken(...)` and `fun backdropGlassToken(role: GlassSurfaceRole, variant: GlassVariant, isDark: Boolean, scene: GlassScene = GlassScene()): BackdropGlassToken`.
- `BackdropGlassToken` fields are `tintAlpha: Float`, `borderAlpha: Float`, `blurRadius: Dp`, `refractionHeight: Dp`, `refractionAmount: Dp`, `depthEffect: Boolean`, `chromaticAberration: Boolean`, `highlightWidth: Dp`, `highlightAlpha: Float`, `shadowRadius: Dp`, `shadowAlpha: Float`, `innerShadowRadius: Dp`, `pressLighting: Float`, and `pressScale: Float`.

- [ ] **Step 1: Add failing token table tests**

Append tests to `GlassTokenTest.kt` with exact baseline assertions:

```kotlin
@Test
fun backdropRolesUseApprovedLensTable() {
    val bottom = backdropGlassToken(GlassSurfaceRole.BottomNavigation, GlassVariant.CLEAR, false)
    val top = backdropGlassToken(GlassSurfaceRole.TopBar, GlassVariant.CLEAR, false)
    val search = backdropGlassToken(GlassSurfaceRole.SearchField, GlassVariant.CLEAR, false)
    val card = backdropGlassToken(GlassSurfaceRole.Card, GlassVariant.CLEAR, false)
    val circular = backdropGlassToken(GlassSurfaceRole.CircularControl, GlassVariant.CLEAR, false)
    val detail = backdropGlassToken(GlassSurfaceRole.DetailAction, GlassVariant.CLEAR, false)

    assertThat(bottom.blurRadius).isEqualTo(20.dp)
    assertThat(bottom.refractionHeight).isEqualTo(8.dp)
    assertThat(bottom.refractionAmount).isEqualTo(36.dp)
    assertThat(top.blurRadius).isEqualTo(20.dp)
    assertThat(top.refractionHeight).isEqualTo(8.dp)
    assertThat(top.refractionAmount).isEqualTo(36.dp)
    assertThat(search.blurRadius).isEqualTo(14.dp)
    assertThat(search.refractionHeight).isEqualTo(8.dp)
    assertThat(search.refractionAmount).isEqualTo(26.dp)
    assertThat(card.blurRadius).isEqualTo(14.dp)
    assertThat(card.refractionHeight).isEqualTo(8.dp)
    assertThat(card.refractionAmount).isEqualTo(26.dp)
    assertThat(card.depthEffect).isTrue()
    assertThat(card.chromaticAberration).isTrue()
    assertThat(circular.blurRadius).isEqualTo(12.dp)
    assertThat(circular.refractionHeight).isEqualTo(6.dp)
    assertThat(circular.refractionAmount).isEqualTo(18.dp)
    assertThat(detail.blurRadius).isEqualTo(12.dp)
    assertThat(detail.refractionHeight).isEqualTo(6.dp)
    assertThat(detail.refractionAmount).isEqualTo(18.dp)
}

@Test
fun backdropChromaticFlagsMatchApprovedSceneTable() {
    assertThat(backdropGlassToken(GlassSurfaceRole.BottomNavigation, GlassVariant.CLEAR, false).chromaticAberration).isTrue()
    assertThat(backdropGlassToken(GlassSurfaceRole.TopBar, GlassVariant.CLEAR, false).chromaticAberration).isTrue()
    assertThat(backdropGlassToken(GlassSurfaceRole.SearchField, GlassVariant.CLEAR, false).chromaticAberration).isTrue()
    assertThat(backdropGlassToken(GlassSurfaceRole.CircularControl, GlassVariant.CLEAR, false).chromaticAberration).isFalse()
    assertThat(backdropGlassToken(GlassSurfaceRole.DetailAction, GlassVariant.CLEAR, false).chromaticAberration).isFalse()
}

@Test
fun focusedVariantDoesNotChangeLensValues() {
    GlassSurfaceRole.entries.forEach { role ->
        val clear = backdropGlassToken(role, GlassVariant.CLEAR, false)
        val focused = backdropGlassToken(role, GlassVariant.FOCUSED, false)
        assertThat(focused.blurRadius).isEqualTo(clear.blurRadius)
        assertThat(focused.refractionHeight).isEqualTo(clear.refractionHeight)
        assertThat(focused.refractionAmount).isEqualTo(clear.refractionAmount)
    }
}
```

Run `./gradlew.bat :app:testDebugUnitTest --tests 'com.tracktosearch.ui.component.GlassTokenTest' --no-daemon --console=plain`.

Expected: FAIL because the Backdrop token type and function do not exist yet.

- [ ] **Step 2: Implement the token table and variant modulation**

In `GlassTokens.kt`, create the new Backdrop token model independently of the temporary Haze compatibility shim in `AppGlassStyle.kt`; the old `GlassOptics`, `GlassStyle`, and `SurfaceProfile` definitions remain only until Task 7. Keep the existing `GlassSurfaceRole`, `GlassScene`, `glassSceneForContent`, `resolveGlassFallbackFill`, `resolveGlassAmbientColor`, `resolveGlassEnvironmentTint`, and cached poster ambient helpers in their current package/API location. Implement the six rows exactly as follows:

```kotlin
GlassSurfaceRole.BottomNavigation -> lens(20.dp, 8.dp, 36.dp, true, true, 1.2.dp, 0.52f, 26.dp, 0.38f)
GlassSurfaceRole.TopBar -> lens(20.dp, 8.dp, 36.dp, true, true, 0.9.dp, 0.36f, 18.dp, 0.28f)
GlassSurfaceRole.SearchField -> lens(14.dp, 8.dp, 26.dp, true, true, 0.9.dp, 0.46f, 12.dp, 0.22f)
GlassSurfaceRole.Card -> lens(14.dp, 8.dp, 26.dp, true, true, 0.9.dp, 0.46f, 12.dp, 0.22f)
GlassSurfaceRole.CircularControl -> lens(12.dp, 6.dp, 18.dp, true, false, 0.7.dp, 0.76f, 14.dp, 0.32f)
GlassSurfaceRole.DetailAction -> lens(12.dp, 6.dp, 18.dp, true, false, 0.8.dp, 0.58f, 16.dp, 0.28f)
```

Keep `LoginSurface` as a separate form profile with no dependency on the five control rows. Let scene protection raise tint/border and reduce highlight only; never mutate the three lens values or the role chromatic flag. Let Focused raise tint, highlight alpha and press lighting while keeping every `pressScale` in `0.98f..1f`.

- [ ] **Step 3: Implement the host and local source contract**

In `BackdropHost.kt`, implement `BackdropProvider` using `rememberLayerBackdrop` and a remembered `onDraw` lambda:

```kotlin
val backdrop = rememberLayerBackdrop(
    onDraw = remember(backgroundColor) {
        { drawRect(backgroundColor); drawContent() }
    }
)
CompositionLocalProvider(LocalBackdrop provides backdrop) {
    Box(modifier) { content() }
}
```

Document in the function comment that each screen must apply `.layerBackdrop(LocalBackdrop.current ?: error("Backdrop host is missing"))` only to the scrolling/background source layer; overlay Glass surfaces must remain siblings of that source layer. Do not apply `layerBackdrop` to the whole root containing the Glass overlays.

- [ ] **Step 4: Run token tests and commit the core model**

Run the focused test again and then `git diff --check`. Stage only `BackdropHost.kt`, `GlassTokens.kt`, `AppGlassStyle.kt`, and `GlassTokenTest.kt`; inspect `git diff --cached --name-only`; commit with `refactor(glass): 建立 Backdrop 场景 token 与采样 host`.

## Task 2: Replace the formal Glass renderer and mode dispatcher

**Files:**

- Modify: `app/src/main/java/com/tracktosearch/ui/component/GlassSurface.kt`
- Modify: `app/src/main/java/com/tracktosearch/ui/component/VisualSurface.kt`
- Modify: `app/src/main/java/com/tracktosearch/ui/component/AppVisualEffect.kt`
- Modify: `app/src/main/java/com/tracktosearch/ui/component/NeumorphicGlass.kt`
- Test: `app/src/test/java/com/tracktosearch/ui/component/NeumorphicGlassTest.kt`
- Test: `app/src/test/java/com/tracktosearch/ui/component/GlassTokenTest.kt`

**Interfaces:**

- `GlassSurface` and `GlassIconButton` keep their public behavior and content lambdas, but read `LocalBackdrop.current`; their Glass implementation no longer accepts or creates `HazeState` for sampling.
- `AppVisualSurface` keeps its existing call signature and dispatches `VisualSurfaceKind.Glass` to Backdrop when `LocalVisualEffectMode.current == GLASS`.
- Add a role-based `Modifier.appVisualEffect` overload with `role: GlassSurfaceRole`, `shape: RoundedCornerShape`, and `scene: GlassScene`; keep the old `glassStyle` signature as a temporary compatibility bridge until Task 7. The bridge must map to the Backdrop role path and must not construct `GlassStyle` or call `hazeGlass` at runtime. Both signatures still accept `HazeInput` and `HazeBlurStyle` for the Blur branch.

- [ ] **Step 1: Add failing dispatch tests**

Extend `NeumorphicGlassTest.kt` with a no-host Glass fallback and an enabled/disabled AppIconButton contract. Use the existing Robolectric Compose rule and assert that no exception is thrown for a Glass `AppVisualSurface` without `HazeState`; assert that disabled `AppIconButton` does not call its callback and enabled `AppIconButton` does. Add a source-level test helper in `GlassTokenTest.kt` if needed to assert `surfaceTreatmentFor(GLASS) == GLASS` and `surfaceTreatmentFor(BLUR) == NEUMORPHIC` remain unchanged.

Run `./gradlew.bat :app:testDebugUnitTest --tests 'com.tracktosearch.ui.component.NeumorphicGlassTest' --no-daemon --console=plain`.

Expected: the fallback test fails because the current Glass branch still requires Haze Glass or relies on the old implementation.

- [ ] **Step 2: Implement `drawBackdrop` with the fixed effect order**

In `GlassSurface.kt`, implement the shared Backdrop modifier with the exact structure below, adapting only the token field names and existing Backdrop API types:

```kotlin
drawBackdrop(
    backdrop = backdrop,
    shape = { tokenShape },
    effects = {
        colorControls(contrast = 1.03f, saturation = 1.05f)
        blur(token.blurRadius.toPx())
        lens(
            refractionHeight = token.refractionHeight.toPx(),
            refractionAmount = token.refractionAmount.toPx(),
            depthEffect = token.depthEffect,
            chromaticAberration = token.chromaticAberration
        )
    },
    highlight = { Highlight(width = token.highlightWidth, alpha = token.highlightAlpha) },
    shadow = { Shadow(radius = token.shadowRadius, offset = DpOffset(0.dp, token.shadowRadius / 4f), color = shadowColor) },
    innerShadow = { InnerShadow(radius = token.innerShadowRadius, offset = DpOffset(0.dp, -token.innerShadowRadius / 4f), color = innerShadowColor) }
)
```

Use `vibrancy()` only where the role token explicitly requests it; if a color control is present it must remain before blur and lens. On a missing host, render the existing deterministic fallback fill/border without Haze sampling. Apply press scale through the Backdrop `layerBlock` or a content-only `graphicsLayer`; never transform the modifier carrying the backdrop sample.

- [ ] **Step 3: Split Blur and Glass in the public dispatchers**

Make `AppVisualSurface` and `AppIconButton` call the new Backdrop components for Glass and the unchanged `NeumorphicFrostedSurface`/`NeumorphicIconButton` for Blur. Keep `VisualSurfaceKind.Content` and `VisualSurfaceKind.Modal` as non-sampling paths. Keep `requireRoundedGlassShape` and fail fast for invalid Glass shapes.

Change `appVisualEffect` so its branch is:

```kotlin
when (LocalVisualEffectMode.current) {
    VisualEffectMode.BLUR -> hazeBlur(input = input, style = hazeStyle, sampling = blurSampling)
    VisualEffectMode.GLASS -> drawBackdropFromLocalHost(role, shape, scene, interactionSource)
}
```

`drawBackdropFromLocalHost` must use `LocalBackdrop.current`; when it is null it returns the original modifier after the caller has already supplied the fallback fill. Delete the runtime `hazeGlass` branch and the transform/reduced-motion plumbing from this dispatcher. Keep only the temporary `glassStyle` compatibility bridge; its parameter and any Haze Glass-only type imports are removed in Task 7 after all callers have migrated.

- [ ] **Step 4: Keep the legacy compatibility boundary and make the indicator non-sampling**

Keep the legacy Glass definitions in `NeumorphicGlass.kt` as a temporary compatibility boundary; do not remove them until Task 7, but ensure the new public dispatcher never routes Glass through them. Keep their Blur parameters and tests. Implement `GlassTabIndicator` as a clipped ordinary fill plus border; it must not call `drawBackdrop`, `hazeGlass`, `GlassHighlight`, or either neumorphic shadow modifier.

- [ ] **Step 5: Run focused tests and compile**

Run:

```bash
./gradlew.bat :app:testDebugUnitTest --tests 'com.tracktosearch.ui.component.GlassTokenTest' --tests 'com.tracktosearch.ui.component.NeumorphicGlassTest' --no-daemon --console=plain
./gradlew.bat :app:compileDebugKotlin --no-daemon --console=plain
```

Expected: focused tests pass and the module compiles while the temporary `glassStyle` compatibility bridge remains. Direct callers are migrated in Tasks 3-6; do not reintroduce a Haze Glass runtime path to preserve compatibility.

- [ ] **Step 6: Commit the renderer boundary**

Run `rg -n "hazeGlass|dev\.chrisbanes\.haze\.glass" app/src/main/java/com/tracktosearch/ui/component/GlassSurface.kt app/src/main/java/com/tracktosearch/ui/component/VisualSurface.kt app/src/main/java/com/tracktosearch/ui/component/AppVisualEffect.kt`. The expected result is empty for the runtime renderer and dispatcher; the temporary `glassStyle` compatibility bridge is intentionally removed in Task 7. Stage only Task 2 files, check the staged list, and commit `refactor(glass): 使用 Backdrop 替换正式玻璃绘制`.

## Task 3: Align the pilot page and implement bottom navigation motion

**Files:**

- Modify: `app/src/main/java/com/tracktosearch/ui/screen/pilot/GlassEnginePilotScreen.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/values-zh/strings.xml`
- Modify: `app/src/main/res/values-ja/strings.xml`
- Modify: `app/src/main/res/values-ko/strings.xml`
- Create: `app/src/test/java/com/tracktosearch/ui/component/BackdropGlassInteractionTest.kt`

**Interfaces:**

- Pilot mode is a two-state mapping: `BLUR -> Haze`, `GLASS -> Backdrop`; there is no selectable Haze + Glass combination.
- Bottom content exposes `dataIndex: Int`, `selected: Boolean`, and `MutableInteractionSource` per tab; indicator animation uses `Animatable<Float, AnimationVector1D>` with target values `0f`, `1f`, and `2f`. The pure target helper lives in the `com.tracktosearch.ui.component` package so the component-package interaction test can access its `internal` API.

- [ ] **Step 1: Add interaction contract tests**

In `BackdropGlassInteractionTest.kt`, compose `AppIconButton` twice with `VisualEffectMode.GLASS`: one enabled and one disabled. Give the icons content descriptions `"Enabled action"` and `"Disabled action"`, perform clicks, and assert only the enabled callback runs. Add a tab state test around the extracted pure helper:

```kotlin
@Test
fun indicatorTargetUsesSelectedTabIndex() {
    assertThat(bottomTabIndicatorTarget(selectedIndex = 0, tabCount = 3)).isEqualTo(0f)
    assertThat(bottomTabIndicatorTarget(selectedIndex = 2, tabCount = 3)).isEqualTo(2f)
    assertThat(bottomTabIndicatorTarget(selectedIndex = 8, tabCount = 3)).isEqualTo(2f)
}
```

Run the new test class; expected failure is the missing helper and the incomplete Backdrop interaction path.

- [ ] **Step 2: Replace pilot parameter state with the shared role token**

Remove the private pilot-only `BackdropGlassParams` and `pilotParams` copies. Render `backdropGlassToken(role, LocalGlassVariant.current, isDark, scene)` as the displayed baseline. Keep DEBUG sliders only as an explicit `temporaryOverride` layered over the token; reset must restore the token values. Add scenes for BottomNavigation, TopBar, SearchField, CircularControl, and DetailAction.

- [ ] **Step 3: Implement A indicator animation and stable sampling**

Extract `internal fun bottomTabIndicatorTarget(selectedIndex: Int, tabCount: Int): Float = selectedIndex.coerceIn(0, tabCount - 1).toFloat()` in the `ui.component` package and import it from the pilot page. Use `Animatable` and `spring(stiffness = Spring.StiffnessMediumLow, dampingRatio = Spring.DampingRatioNoBouncy)` to animate the indicator target. Draw the outer Backdrop surface once, then draw the indicator and tab content as siblings above it. Use a shared press state to apply `scale(1.06f)` only to the indicator/content layer; do not apply scale to the outer `drawBackdrop` modifier.

- [ ] **Step 4: Make all five pilot icons visible and localized**

Use Material icons with a fixed `Modifier.size(24.dp)` or larger content size and non-null `contentDescription`. Add string keys for the five scene labels and the three bottom tabs in all four resource directories. Keep the pilot title and parameter labels localized; do not add implementation terminology to the normal Settings UI.

- [ ] **Step 5: Run pilot-focused tests and compile**

Run:

```bash
./gradlew.bat :app:testDebugUnitTest --tests 'com.tracktosearch.ui.component.BackdropGlassInteractionTest' --tests 'com.tracktosearch.ui.component.NeumorphicGlassTest' --no-daemon --console=plain
./gradlew.bat :app:compileDebugKotlin --no-daemon --console=plain
```

Stage only pilot, resource and test files after `git diff --cached --check`; commit `feat(glass): 完成试点页与底栏液态指示器动效`.

## Task 4: Migrate Main/Discover and the single bottom navigation surface

**Files:**

- Modify: `app/src/main/java/com/tracktosearch/ui/screen/main/MainScreen.kt`
- Modify: `app/src/main/java/com/tracktosearch/ui/screen/discover/DiscoverScreen.kt`
- Modify: `app/src/main/java/com/tracktosearch/ui/screen/discover/DiscoverComponents.kt`
- Modify: `app/src/main/java/com/tracktosearch/ui/screen/discover/DiscoverSections.kt`
- Modify: `app/src/main/java/com/tracktosearch/ui/screen/discover/DiscoverSheets.kt`
- Test: `app/src/androidTest/java/com/tracktosearch/ui/screen/discover/DiscoverScreenTest.kt`

**Interfaces:**

- Each root screen wraps its source content in `BackdropProvider`; the scrolling/background child receives `.layerBackdrop(LocalBackdrop.current ?: error("Backdrop host is missing"))`.
- Main bottom navigation uses one `AppVisualSurface(kind = VisualSurfaceKind.Glass, role = GlassSurfaceRole.BottomNavigation)` and one `GlassTabIndicator`; it never creates a Glass effect inside an individual tab.
- The Discover community-popular list cards use `GlassSurfaceRole.Card` in Glass mode; poster content and modal sheets remain outside the sampling surface.

- [ ] **Step 1: Add source-layer host to Main and Discover**

Wrap the existing content/background layer in `BackdropProvider` without moving overlays or changing `Scaffold` insets. Keep the existing Haze source for Blur. Ensure the source modifier is applied to the `LazyColumn`/scroll content only, while the top/bottom Glass siblings remain outside that source layer.

- [ ] **Step 2: Migrate the bottom bar to the shared A structure**

Keep current navigation routes, badges, avatar content, haptic behavior, selected index and edge-to-edge padding. Replace the outer Glass branch with the shared Backdrop surface; replace per-tab `appVisualEffect`, `AppGlassStyles.bottomNavigationItem` and `NeumorphicActiveTab` with the non-sampling indicator and the shared spring helper from Task 3. Blur continues to use the existing outer Haze/neumorphic branch.

- [ ] **Step 3: Migrate Discover top bar and circular controls**

Use `AppIconButton(role = GlassSurfaceRole.CircularControl)` for filter, sort, back and scroll actions. Use `AppVisualSurface(role = GlassSurfaceRole.TopBar)` for the top bar. Preserve poster ambient colors and `GlassScene` readability inputs; do not wrap poster cards, ratings, titles, hero content or sheets in Glass.

- [ ] **Step 4: Add screen regression assertions**

Extend `DiscoverScreenTest.kt` to assert that the main navigation content descriptions and at least one filter action remain present after composition. Keep existing navigation and scroll assertions unchanged.

- [ ] **Step 5: Compile and commit**

Run `./gradlew.bat :app:compileDebugKotlin --no-daemon --console=plain` and the focused Discover test task. Run `rg -n "bottomNavigationItem|NeumorphicActiveTab|hazeGlass" app/src/main/java/com/tracktosearch/ui/screen/main app/src/main/java/com/tracktosearch/ui/screen/discover`; expected result is no Glass direct path. Commit `style(discover): 接入 Backdrop 导航与发现页场景`.

## Task 5: Migrate Search, Trakt Search, Watchlist and MarkRecord

**Files:**

- Modify: `app/src/main/java/com/tracktosearch/ui/component/GlassSearchBar.kt`
- Modify: `app/src/main/java/com/tracktosearch/ui/component/ActionButtonRow.kt`
- Modify: `app/src/main/java/com/tracktosearch/ui/component/ScrollToTopButton.kt`
- Modify: `app/src/main/java/com/tracktosearch/ui/screen/search/SearchScreen.kt`
- Modify: `app/src/main/java/com/tracktosearch/ui/screen/traktsearch/TraktSearchScreen.kt`
- Modify: `app/src/main/java/com/tracktosearch/ui/screen/watchlist/WatchlistScreen.kt`
- Modify: `app/src/main/java/com/tracktosearch/ui/screen/watchlist/WatchlistCategoryTabs.kt`
- Modify: `app/src/main/java/com/tracktosearch/ui/screen/markrecord/MarkRecordScreen.kt`
- Test: `app/src/test/java/com/tracktosearch/ui/component/ActionButtonRowTest.kt`
- Test: `app/src/androidTest/java/com/tracktosearch/ui/screen/search/SearchScreenTest.kt`
- Test: `app/src/androidTest/java/com/tracktosearch/ui/screen/watchlist/WatchlistScreenTest.kt`

**Interfaces:**

- `GlassSearchBar` keeps its current value, focus, keyboard, trailing icon and click callbacks; only its surface modifier changes to `GlassSurfaceRole.SearchField`.
- `ActionButtonRow` and `ScrollToTopButton` use `AppIconButton` with `CircularControl` or `DetailAction` roles and keep current content descriptions.

- [ ] **Step 1: Migrate GlassSearchBar without changing input behavior**

Replace the conditional `glassStyle = AppGlassStyles.searchField(...)` call with the unified Backdrop role path. Keep `BasicTextField`, `FocusRequester`, `KeyboardOptions`, `KeyboardActions`, type selector and trailing icon unchanged. Preserve `searchBarBaseColor` tests: Blur keeps its fallback fill; Glass with an active host lets Backdrop supply the surface tint; Glass without a host uses the deterministic fallback fill.

- [ ] **Step 2: Migrate direct action modifiers**

Replace direct Glass `appVisualEffect` calls in `ActionButtonRow.kt`, `ScrollToTopButton.kt`, `SearchScreen.kt` and `TraktSearchScreen.kt` with `AppIconButton`/`AppVisualSurface` or the new role-aware modifier. Keep `MutableInteractionSource` shared between clickable and the Glass surface so press lighting is observable without moving the sample layer.

- [ ] **Step 3: Migrate Watchlist and MarkRecord controls**

Wrap their scrolling source content in `BackdropProvider`. Use TopBar for fixed headers, CircularControl for sort/delete/refresh/scroll actions, and ordinary non-sampling indicator layers for category tabs. Do not add Glass to poster cards, rating content, list rows or selection dialogs. Preserve multi-select, batch deletion, tab switching and scroll-to-top semantics.

- [ ] **Step 4: Run focused tests and static audit**

Run:

```bash
./gradlew.bat :app:testDebugUnitTest --tests 'com.tracktosearch.ui.component.ActionButtonRowTest' --no-daemon --console=plain
./gradlew.bat :app:compileDebugKotlin --no-daemon --console=plain
rg -n "hazeGlass|glassStyle|bottomNavigationItem|GlassHighlight" app/src/main/java/com/tracktosearch/ui/component/GlassSearchBar.kt app/src/main/java/com/tracktosearch/ui/screen/search app/src/main/java/com/tracktosearch/ui/screen/traktsearch app/src/main/java/com/tracktosearch/ui/screen/watchlist app/src/main/java/com/tracktosearch/ui/screen/markrecord
```

Expected: no Haze Glass symbol in these paths and existing search/action tests pass. Stage the listed files and commit `style(search): 迁移搜索与列表场景玻璃`.

## Task 6: Migrate Detail, Person, Douban, Statistics and DiscoverFilter

**Files:**

- Modify: `app/src/main/java/com/tracktosearch/ui/screen/detail/DetailScreen.kt`
- Modify: `app/src/main/java/com/tracktosearch/ui/screen/detail/DetailHeaderContent.kt`
- Modify: `app/src/main/java/com/tracktosearch/ui/screen/detail/DetailComments.kt`
- Modify: `app/src/main/java/com/tracktosearch/ui/screen/detail/DetailSeasonsSection.kt`
- Modify: `app/src/main/java/com/tracktosearch/ui/screen/person/PersonScreen.kt`
- Modify: `app/src/main/java/com/tracktosearch/ui/screen/douban/DoubanItemDetailScreen.kt`
- Modify: `app/src/main/java/com/tracktosearch/ui/screen/statistics/StatisticsScreen.kt`
- Modify: `app/src/main/java/com/tracktosearch/ui/screen/discover/DiscoverFilterScreen.kt`
- Test: `app/src/androidTest/java/com/tracktosearch/ui/screen/detail/DetailScreenTest.kt`
- Test: `app/src/test/java/com/tracktosearch/ui/screen/detail/DetailHeaderContentTest.kt`

**Interfaces:**

- Detail/Person/Douban action buttons use `AppIconButton(role = CircularControl or DetailAction)` and keep existing `NeumorphicIconButtonStyle.DetailTopBar` behavior in Blur.
- Detail comment cards use `GlassSurfaceRole.Card` in Glass mode and retain their existing loading, translation and click semantics.
- Detail content, comments, seasons, stills and full-screen preview remain `Content`/`Modal` and never receive a Backdrop Glass role.

- [ ] **Step 1: Migrate detail action buttons and hosts**

Add Backdrop source hosts around the scrolling detail content. Replace return, favorite, share, more and season action Glass calls with the shared role entry. Keep navigation, favorite state, share intent, comments, image pager and season expansion unchanged.

- [ ] **Step 2: Migrate Person, Douban and secondary detail paths**

Apply the same TopBar/CircularControl/DetailAction mapping to Person and Douban detail. Do not add a Glass wrapper around poster, title, score, year, overview, comments, season list, still grid or full-screen image/video overlays.

- [ ] **Step 3: Migrate Statistics and DiscoverFilter**

Use Backdrop only for the top bar and small filter/sort controls. Keep charts, filter rows, dialogs and sheets as ordinary Content/Modal surfaces. Preserve reset, submit, navigation and back behavior.

- [ ] **Step 4: Run tests and commit**

Run `./gradlew.bat :app:compileDebugKotlin --no-daemon --console=plain`, the focused DetailHeaderContent JVM test, and the existing DetailScreen instrumentation test when a device is available. Run `rg -n "hazeGlass|GlassHighlight|neumorphicOuterShadow|neumorphicInnerShadow"` over the listed screen directories; only Blur-only branches may remain. Commit `style(detail): 迁移详情与辅助页面玻璃场景`.

## Task 7: Migrate Settings/Login, remove Haze Glass dependency and close the static boundary

**Files:**

- Modify: `app/src/main/java/com/tracktosearch/ui/screen/settings/SettingsScreen.kt`
- Modify: `app/src/main/java/com/tracktosearch/ui/screen/settings/SettingsComponents.kt`
- Modify: `app/src/main/java/com/tracktosearch/ui/screen/settings/SettingsDialogs.kt`
- Modify: `app/src/main/java/com/tracktosearch/ui/screen/login/ActivationLoginScreen.kt`
- Modify: `app/src/main/java/com/tracktosearch/ui/theme/VisualEffectMode.kt`
- Modify: `app/src/main/java/com/tracktosearch/data/local/ThemeStorage.kt`
- Modify: `app/src/main/java/com/tracktosearch/ui/component/NeumorphicGlass.kt`
- Modify: `app/src/main/java/com/tracktosearch/ui/component/AppGlassStyle.kt`
- Modify: `app/src/main/java/com/tracktosearch/ui/component/GlassSurface.kt`
- Modify: `app/src/main/java/com/tracktosearch/ui/component/AppVisualEffect.kt`
- Modify: `gradle/libs.versions.toml`
- Modify: `app/build.gradle.kts`
- Modify if needed: `app/src/main/res/values/strings.xml`, `values-zh/strings.xml`, `values-ja/strings.xml`, `values-ko/strings.xml`
- Test: `app/src/test/java/com/tracktosearch/ui/screen/settings/SettingsViewModelTest.kt`
- Test: `app/src/test/java/com/tracktosearch/ui/theme/VisualEffectModeTest.kt`
- Test: `app/src/androidTest/java/com/tracktosearch/ui/screen/settings/SettingsScreenTest.kt`

**Interfaces:**

- Settings continues using the existing `VisualEffectMode`/`GlassVariant` DataStore keys for compatibility, but the material popup exposes exactly two options: Blur and one Backdrop Glass. Missing material defaults to Glass; explicit Blur remains selectable; old Focused values normalize to the sole Glass option.
- Login uses one `LoginSurface` Backdrop profile around the form; inputs and error surfaces do not each create Glass effects.
- Settings group cards use `GlassSurfaceRole.Card` in Glass mode and show the same screenshot-derived lens values as other Card surfaces.
- After this task, no production source or dependency references Haze Glass.

- [ ] **Step 1: Migrate Settings and preserve modal surfaces**

Add a Backdrop source host for the settings content, use TopBar/CircularControl only for fixed navigation/actions, apply Card to settings group cards, and leave AlertDialog/BottomSheet on `surfaceVariant` or existing Material surfaces. Check the existing help copy; if it still describes Glass as experimental Haze Glass, update the same key in all four resource directories using `stringResource`.

- [ ] **Step 2: Collapse the material popup to one Backdrop Glass preset**

Replace the two Glass rows (Clear/Focused) with one localized Backdrop Glass row plus the Blur row. Give both rows a primary label and a smaller localized description below it. Keep `GlassVariant` and its storage key only as a compatibility boundary; normalize old Focused values to the single Glass presentation. Set the no-record/default `VisualEffectMode` to Glass while preserving an explicit stored Blur selection. Add/update unit and Compose assertions for option count, descriptions, default selection and legacy-value normalization across all four resource directories.

- [ ] **Step 3: Migrate ActivationLoginScreen**

Replace direct `appVisualEffect` Glass modifiers with one `AppVisualSurface(kind = Glass, role = LoginSurface)` around the form. Keep activation, guest mode, error state, keyboard and navigation behavior. Ensure the form itself is readable in both themes and that input fields are not nested Glass surfaces.

- [ ] **Step 4: Remove the legacy Glass branches**

After all call sites compile through `AppVisualSurface`, `AppIconButton` or the role-aware effect modifier, remove Glass branches from `NeumorphicGlass.kt`, delete Haze Glass imports and delete `glassStyle`/Haze Glass-only parameters from shared APIs. Remove `haze-glass` from `gradle/libs.versions.toml` and `implementation(libs.haze.glass)` from `app/build.gradle.kts`; keep `haze`, `haze-blur`, `haze-blur-materials`, and `backdrop`.

- [ ] **Step 5: Run the full static boundary audit**

Run:

```bash
rg -n "hazeGlass|dev\.chrisbanes\.haze\.glass|haze-glass|GlassStyle|GlassTransform|GlassReducedMotionPolicy" app gradle/libs.versions.toml
rg -n "appVisualEffect\(|drawBackdrop\(" app/src/main/java/com/tracktosearch/ui --glob '*.kt'
```

The first command must return no production/dependency hits. The second command must show only the centralized Backdrop dispatcher/surface and explicit Blur calls; page code must not pass optical numbers. Run `./gradlew.bat :app:compileDebugKotlin --no-daemon --console=plain` and `./gradlew.bat :app:testDebugUnitTest --no-daemon --console=plain`.

- [ ] **Step 6: Commit the completed migration boundary**

Run `git diff --check`, inspect `git diff --cached --name-only`, and commit `refactor(glass): 清理 Haze Glass 并完成全局迁移` with only the files listed for this task.

## Task 8: Device, visual and performance verification

**Files:**

- Modify: none in production source unless a verification failure identifies a concrete defect.
- Evidence: local screenshots, UI dumps, logcat and gfxinfo outside Git; do not stage APKs or screenshots.

**Interfaces:**

- The verification target is the APK produced by Tasks 1-7, with the exact commit list recorded in Git.
- Runtime claims require device evidence; source compilation and unit tests do not count as visual acceptance.

- [ ] **Step 1: Check device identity and build the APK**

Run:

```bash
adb devices
DEVICE_SERIAL="$(adb devices | awk 'NR > 1 && $2 == "device" { print $1; exit }')"
if [ -n "$DEVICE_SERIAL" ]; then
  adb -s "$DEVICE_SERIAL" shell getprop ro.build.version.release
  adb -s "$DEVICE_SERIAL" shell getprop ro.build.version.sdk
else
  echo "No online Android device; runtime evidence will be unavailable."
fi
./gradlew.bat :app:assembleDebug --no-daemon --console=plain
```

If no device is online, report that runtime evidence is unavailable and continue only with source/test verification; do not claim device acceptance.

- [ ] **Step 2: Install, launch and capture baseline evidence**

When `DEVICE_SERIAL` is set, install with `adb -s "$DEVICE_SERIAL" install -r app/build/outputs/apk/debug/app-debug.apk`, launch the main Activity using the existing package/activity from the project, then capture `uiautomator dump`, `screencap -p`, and `logcat -b crash`. Verify the screenshot is a valid PNG and record resolution/density/API. Otherwise skip this step and label runtime evidence unavailable.

- [ ] **Step 3: Verify modes, settings and five interaction scenes**

In Blur, verify the mature Haze visuals are unchanged. In Glass/Clear and Glass/Focused, verify Discover and the pilot page show Backdrop. On the pilot page, click all three bottom tabs and hold each icon; confirm the indicator moves, content/icon scale responds, the sampling layer stays fixed, and all five scene icons remain visible. Verify Search focus, circular button press, DetailAction press, Settings persistence across process restart, and unchanged Dialog/BottomSheet/full-screen preview surfaces.

- [ ] **Step 4: Verify performance evidence**

For Blur, Glass/Clear and Glass/Focused on the same data path, first create a concrete local evidence directory and use the discovered device serial:

```bash
BACKDROP_EVIDENCE_DIR="$(mktemp -d)"
mkdir -p "$BACKDROP_EVIDENCE_DIR"
for MODE in blur glass-clear glass-focused; do
  adb -s "$DEVICE_SERIAL" shell dumpsys gfxinfo com.tracktosearch reset
  adb -s "$DEVICE_SERIAL" shell dumpsys gfxinfo com.tracktosearch framestats > "$BACKDROP_EVIDENCE_DIR/${MODE}-framestats.txt"
  adb -s "$DEVICE_SERIAL" shell dumpsys meminfo com.tracktosearch > "$BACKDROP_EVIDENCE_DIR/${MODE}-meminfo.txt"
done
```

Record maximum visible Backdrop surface count, scroll frame duration/overrun, and press/tab-switch behavior. If tuning is needed, change one role token at a time, rerun its focused tests and device capture, and never silently switch the user back to Blur.

- [ ] **Step 5: Final repository audit**

Run `git status --short --branch`, `git log --oneline --decorate -12`, `git diff --check`, and the full static audit from Task 7. Confirm no screenshots, APKs, `.superpowers/brainstorm/` files or sensitive configuration are staged. Only then report completion, separating source, unit-test, compile, device and performance evidence.

## Plan self-review

- Spec section 2 is covered by Tasks 2, 4, 5, 6 and 7 through Content/Modal dispatch and the final static audit.
- Spec section 3 is covered by Task 1's exact five-role table and Task 2's Backdrop effect order.
- Spec section 4 is covered by Task 3's `Animatable` indicator target, content-only press transform and localized pilot controls.
- Spec section 5 is covered by Tasks 1 and 2's host, token, surface and dispatcher interfaces.
- Spec section 6 is covered by Tasks 4-7's explicit screen/file migration lists.
- Spec section 7 is preserved because no task changes `VisualEffectMode`, `GlassVariant`, DataStore keys or DTOs; resource checks are explicit in Tasks 3 and 7.
- Spec section 8 is covered by focused tests in Tasks 1-7 and device/performance evidence in Task 8.
- No step relies on `TBD`, `TODO`, an unnamed file, or a later design decision; all planned types and helper names are defined before use.
- `BackdropGlassToken` is produced in Task 1 and consumed by `GlassSurface`, `AppVisualEffect` and the pilot in Tasks 2-3; `LocalBackdrop` is produced in Task 1 and consumed by all screen migrations in Tasks 4-7.

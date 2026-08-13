# 任务 2 实现报告

## 范围

初始实现只修改简报列出的 7 个业务/测试文件；本次审查修复另外允许修改
`MainScreen.kt`，并只触及任务 2 的共享 Glass 组件与测试：

- `app/src/main/java/com/tracktosearch/ui/component/AppGlassStyle.kt`
- `app/src/main/java/com/tracktosearch/ui/component/AppVisualEffect.kt`
- `app/src/main/java/com/tracktosearch/ui/component/NeumorphicGlass.kt`
- `app/src/main/java/com/tracktosearch/ui/component/GlassSurface.kt`
- `app/src/main/java/com/tracktosearch/ui/component/VisualSurface.kt`
- `app/src/test/java/com/tracktosearch/ui/component/GlassTokenTest.kt`
- `app/src/test/java/com/tracktosearch/ui/component/NeumorphicGlassTest.kt`
- `app/src/main/java/com/tracktosearch/ui/screen/main/MainScreen.kt`（本次修复）

未修改任务 1 文件、其他页面、主题接口文件和其他用户文件。原有
`GlassTokenTest.kt` 未跟踪草稿已保留，并在核对后补上了 `GlassVariant` 的正确
import。

## 失败优先验证

先运行：

```bash
./gradlew.bat :app:testDebugUnitTest --tests 'com.tracktosearch.ui.component.GlassTokenTest' --no-daemon --console=plain
```

初始结果为预期红灯：`compileDebugUnitTestKotlin` 失败。错误集中在草稿引用但正式代码尚不存在的 `glassToken`、`GlassSurfaceRole` 和 `GlassVariant`；Truth 断言的后续 unresolved reference 是由前述类型缺失导致的编译连锁错误。草稿覆盖了清透/聚焦强度、所有正式角色禁用色散和按压 scale 上限三条规范。

## 实现内容

- 在 `AppGlassStyle.kt` 集中定义 `GlassSurfaceRole`、`GlassToken` 和纯函数 `glassToken(role, variant, isDark)`。
- 按简报基线实现六个角色的浅色/深色清透与聚焦 tint alpha；`DetailAction` 沿用 `CircularControl` 的基线。
- 所有正式角色的 `chromaticAberrationStrength` 固定为 `0f`；交互 press scale 固定在大于 `0f` 且不超过 `1f` 的范围。
- `AppGlassStyles.style` 读取 `LocalGlassVariant.current` 和 `isAppDarkTheme()`，使用 `GlassOptics.Adaptive`，并将交互 lighting/refraction/white-point/scale 从 token 分发。
- `GlassSurface.kt` 新增独立 `GlassSurface`、`GlassIconButton`、`GlassTabIndicator`。GlassSurface 的绘制顺序为 clip、hazeGlass、border、content；Glass 绘制路径不调用拟态阴影、高光或拟态 Tab。
- `VisualSurface.kt` 新增 `VisualSurfaceKind`、`AppVisualSurface` 和 `AppIconButton`，按 Glass/Blur、Content/Modal 分发。Glass 的 Content/Modal 分支不采样、不复用拟态阴影；Blur 分支保留原有拟态实现。
- `NeumorphicGlass.kt` 在全局 Glass 模式下将旧 `NeumorphicIconButton`、`NeumorphicActiveTab` 转到独立 Glass 组件；`NeumorphicFrostedSurface` 只有显式传入 `glassRole` 才进入 Glass 采样，默认保留无采样 legacy 普通表面，且不再猜测 `SearchField`。
- `NeumorphicGlassTest.kt` 保留原有禁用 Blur 按钮测试，并新增 Glass 分支启用点击和禁用点击测试。
- `AppVisualEffect.kt` 明确保留 alpha04 的 Glass `HazeSampling.Default` 与 Blur 的调用点采样策略分离。

## Haze alpha04 核对

已读取当前本机缓存的 `haze-android` 和 `haze-glass` `2.0.0-alpha04` AAR 字节码，并用当前工程已有调用交叉核对。实际可用 API 包括 `GlassStyle { ... }`、`GlassOptics.Adaptive`、`GlassStyleScope` 的 `tint/specularIntensity/ambientResponse/edgeSoftness/shape/surfaceProfile/chromaticAberrationStrength`，以及交互块的 `lightingIntensity/refractionMultiplier/whitePointDelta/scale`；`hazeGlass` 接受 `HazeSampling.Default`、`HazeSourceSelection` 和 `InteractionSource`。

## 测试与构建结果

聚焦测试命令：

```bash
./gradlew.bat :app:testDebugUnitTest --tests 'com.tracktosearch.ui.component.GlassTokenTest' --tests 'com.tracktosearch.ui.component.NeumorphicGlassTest' --no-daemon --console=plain
```

结果：`BUILD SUCCESSFUL`，`GlassTokenTest` XML 为 `tests=3, skipped=0, failures=0, errors=0`，`NeumorphicGlassTest` XML 为 `tests=4, skipped=0, failures=0, errors=0`。

Kotlin 编译命令：

```bash
./gradlew.bat :app:compileDebugKotlin --no-daemon --console=plain
```

结果：`BUILD SUCCESSFUL`。只有既有的 Experimental Haze API 警告，没有 Kotlin 或 Haze API 编译错误。Gradle 运行期间按要求轮询了进程和报告；最终没有残留构建会话，也没有需要用 APK 结果替代的超时。

## 疑虑

本次未执行设备运行时截图或性能采样；当前证据范围是组件单元测试、静态契约检查和 Kotlin 编译。

## 修复记录

### 根因与修改

- 删除 `AppGlassStyles.bottomNavigationItem`，新增明确的 `searchField` 短入口。
- `MainScreen.kt` 已开始并完成迁移：底部导航外层只使用一个
  `AppVisualSurface(kind = Glass, glassRole = BottomNavigation)`，透传
  `HazeSourceSelection.Behind.where { it.zIndex < 1f }`；移除逐 Tab
  `appVisualEffect`、逐 Tab 采样和旧 item 入口，Glass 选中态使用
  `GlassTabIndicator`，Blur 选中态继续使用 `NeumorphicActiveTab`。
- `NeumorphicFrostedSurface` 新增可选 `glassRole`，默认 Glass 路径为无采样普通表面；显式角色才调用 `GlassSurfaceImpl`，不再硬编码 `SearchField`。
- `GlassSurface` 的 Glass API 要求 `RoundedCornerShape`；`AppVisualSurface` 对外恢复为 `Shape`，仅在真实 Glass 采样路径进入前校验并对非圆角形状抛出清晰错误，Plain/Modal/Blur 继续支持任意 `Shape`。
- `AppVisualSurface` 的 Blur 分支改为按 `GlassSurfaceRole` 读取组件内部有限配置，并保留 `sourceSelection`；Glass Content/Modal 仍无采样、无拟态阴影。

### 失败优先与验证证据

先新增 `legacy_surface_accepts_an_explicit_glass_role`，运行组件测试时按预期红灯：
`NeumorphicFrostedSurface` 报 `No parameter with name 'glassRole' found`，测试还暴露了草稿缺少 `dp` import；随后只补齐测试 import 和生产 API。

修复后运行：

```bash
./gradlew.bat :app:testDebugUnitTest --tests 'com.tracktosearch.ui.component.GlassTokenTest' --tests 'com.tracktosearch.ui.component.NeumorphicGlassTest' --no-daemon --console=plain
```

结果为 `BUILD SUCCESSFUL`；
`app/build/test-results/testDebugUnitTest/TEST-com.tracktosearch.ui.component.GlassTokenTest.xml`
为 `tests=3, skipped=0, failures=0, errors=0`，
`app/build/test-results/testDebugUnitTest/TEST-com.tracktosearch.ui.component.NeumorphicGlassTest.xml`
为 `tests=4, skipped=0, failures=0, errors=0`。

```bash
./gradlew.bat :app:compileDebugKotlin --no-daemon --console=plain
```

结果为 `BUILD SUCCESSFUL`。另行执行 `git diff --check` 通过；静态搜索确认
`bottomNavigationItem`、`asRoundedCornerShape` 和 legacy 默认
`GlassSurfaceRole.SearchField` 引用均已删除。

## 复核修复：登录语义与兼容入口

### 根因

- `AppGlassStyles.surface` 暴露旧光学参数却固定转发到 `TopBar`，导致调用方传入的 `edgeSoftness`、`specularIntensity` 和 `ambientResponse` 被静默忽略。
- `AppGlassStyles.control` 实际固定使用 `SearchField`，登录页豆瓣操作按钮因此使用了错误的表面角色。

### 修复

- `ActivationLoginScreen` 的登录卡片改用 `AppGlassStyles.loginSurface`，豆瓣按钮改用可复用按钮形状的 `AppGlassStyles.detailAction`。
- 删除 `AppGlassStyles.surface` 和 `control` 兼容入口；搜索栏改用明确的 `searchField` 入口。
- `AppVisualEffect` 的无样式 Glass fallback 改为明确的 `topBar` 默认；`NeumorphicFrostedSurface` 不再构造会丢弃参数的旧样式。
- `GlassTokenTest` 新增登录表面与操作角色的聚焦断言，证明登录角色区别于 TopBar、圆形控件和搜索框。

### 本轮验证

```bash
./gradlew.bat :app:testDebugUnitTest --tests 'com.tracktosearch.ui.component.GlassTokenTest' --tests 'com.tracktosearch.ui.component.NeumorphicGlassTest' --no-daemon --console=plain
```

结果：`BUILD SUCCESSFUL`；`GlassTokenTest` 4/4，`NeumorphicGlassTest` 4/4。

```bash
./gradlew.bat :app:compileDebugKotlin --no-daemon --console=plain
```

结果：`BUILD SUCCESSFUL`。`git diff --check` 通过；静态搜索未发现 `AppGlassStyles.surface`、`AppGlassStyles.control` 或对应定义残留。

### 边界

本轮未执行设备运行时截图或交互验收，证据范围为聚焦单元测试、Kotlin 编译和静态残留检查。

## 最终修复：收敛 AppVisualSurface 公开 API

### 修复内容

- `AppVisualSurface.shape` 改回公开的 `androidx.compose.ui.graphics.Shape`；Glass 采样路径通过 `requireRoundedGlassShape` 明确要求 `RoundedCornerShape`，错误信息包含表面角色和实际类型，不再静默替换形状。Content、Modal 和 Blur 路径仍直接使用任意 `Shape`。
- 删除 `AppVisualSurface` 的 `elevation`、`blurRadius`、`shadowOffset`、`hazeStyle`、`glassStyle`、`hazeBlurRadius`、阴影 alpha 和 `showHighlight` 公开参数。Blur 配置收敛到 `VisualSurface.kt` 的内部 `BlurSurfaceConfig`，`BottomNavigation` 保留原有 `8.dp`、`22.dp`、`6.dp`、`40.dp`、明暗阴影 alpha 和 `showHighlight = false`。
- `MainScreen` 删除 `navHazeStyle` 及全部底层参数，只保留唯一外层 `AppVisualSurface`、`BottomNavigation` role、背景/边框、`hazeState` 和 `HazeSourceSelection.Behind.where { source.zIndex < 1f }`。
- `NeumorphicGlassTest` 新增非圆角 Glass 失败契约和 BottomNavigation Blur token 契约；原有 token 与图标按钮行为测试继续保留。

### 最终验证

```bash
./gradlew.bat :app:testDebugUnitTest --tests 'com.tracktosearch.ui.component.GlassTokenTest' --tests 'com.tracktosearch.ui.component.NeumorphicGlassTest' --no-daemon --console=plain
```

结果：`BUILD SUCCESSFUL`；`GlassTokenTest` XML 为 `tests=4, skipped=0, failures=0, errors=0`，`NeumorphicGlassTest` XML 为 `tests=6, skipped=0, failures=0, errors=0`。

```bash
./gradlew.bat :app:compileDebugKotlin --no-daemon --console=plain
```

结果：`BUILD SUCCESSFUL`。另行执行 `git diff --check` 通过；静态检查确认 `AppVisualSurface` 公开签名无拟态/光学底层参数，`MainScreen` 无 `navHazeStyle` 或对应底层参数传入。未执行设备运行时截图或性能采样。

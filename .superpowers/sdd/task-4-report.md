# 任务 4 实现报告：发现页和底部导航迁移

## 实现

- `MainScreen.kt` 保留底部导航尺寸、位置、edge-to-edge、Haze source 选择和 Tab 点击/头像/角标/haptic 行为。Glass 模式只由一个外层 `AppVisualSurface(kind = VisualSurfaceKind.Glass, role = GlassSurfaceRole.BottomNavigation)` 采样，选中项使用普通 `GlassTabIndicator`；Blur 模式继续使用 `NeumorphicActiveTab`。
- Glass 导航 Tab 不再叠加单 Tab Glass 或旧 `appVisualEffect`，仅保留选中/hover/focus 的轻量缩放反馈。
- `DiscoverScreen.kt` 将社区列表卡、筛选入口、Hero 卡和筛选/排序圆形按钮迁移到现有 `AppVisualSurface`/`AppIconButton` 入口。内容卡使用 `VisualSurfaceKind.Content`，圆形按钮使用 `GlassSurfaceRole.CircularControl`；Hero、真实海报布局、横向列表、评分、标题、年份、观看人数、滚动和点击/shared-element 行为保留。
- `DiscoverComponents.kt` 的发现海报卡迁移到 `VisualSurfaceKind.Content`。Blur 保留原顶部高光，Glass 不新增高光、采样或拟态阴影；原有海报比例、状态角标、评分、年份、解析遮罩和点击行为保留。
- `DiscoverSections.kt` 的登录引导卡迁移到 `VisualSurfaceKind.Content`，保留原渐变、文案、按压缩放和登录回调。
- `DiscoverSheets.kt` 的 Sheet 海报卡和社区列表卡迁移到 `VisualSurfaceKind.Content`。海报改为普通内容容器并保留真实 2:3 比例、图片、评分、年份、标题、subtitle、按压缩放和点击行为；Sheet 外层仍使用既有 `DiscoverModalBottomSheet`。

## 命令与结果

1. `./gradlew.bat :app:compileDebugKotlin --no-daemon --console=plain`
   - 最终结果：`BUILD SUCCESSFUL`，退出码 `0`。
   - 期间首次运行曾因 `app/build/kotlin/compileDebugKotlin/cacheable` 文件锁失败；清理残留 Gradle/Kotlin 编译进程后重试，随后源码诊断暴露并修复了 `AppVisualSurface` 内容 lambda 的 `BoxScope` 定位问题及 Sheet 布局 import，最终编译通过。
2. `rg -n "bottomNavigationItem|NeumorphicActiveTab|appVisualEffect" app/src/main/java/com/tracktosearch/ui/screen/main/MainScreen.kt`
   - 结果：仅保留 Blur 分支的 `NeumorphicActiveTab`；未发现 `bottomNavigationItem` 或 `appVisualEffect`。
3. `rg -n "hazeGlass|GlassHighlight|NeumorphicFrostedSurface|PosterCard|neumorphicOuterShadow|neumorphicInnerShadow|appVisualEffect" app/src/main/java/com/tracktosearch/ui/screen/discover/DiscoverScreen.kt app/src/main/java/com/tracktosearch/ui/screen/discover/DiscoverComponents.kt app/src/main/java/com/tracktosearch/ui/screen/discover/DiscoverSections.kt app/src/main/java/com/tracktosearch/ui/screen/discover/DiscoverSheets.kt`
   - 结果：无匹配，退出码 `1`，符合发现内容卡和 Sheet 不新增 Glass/旧拟态表面调用的要求。
4. `git diff --check`
   - 结果：通过；仅有 Git 关于现有工作区文件换行符的提示，没有 whitespace error。
5. `git diff --cached --check`、`git diff --cached --name-only`
   - 提交前执行，确认暂存内容只包含本报告和简报列出的五个源文件。

## 自审

- 未修改设置、ThemeStorage、VisualSurface、Glass 组件；工作区中已有的设置、Glass token 测试和其他无关改动未暂存。
- 导航 Glass 采样只有外层 `BottomNavigation`；Glass 指示层是普通背景/边框，不调用 Haze/Glass API。
- 发现页内容和 Sheet 只使用 `Content` 表面或既有普通 Sheet 容器，没有把内容卡变成 Glass；Blur 分支的拟态分发由既有 `AppVisualSurface` 保留。
- 真实海报、Hero、评分、标题、年份、观看人数、横向列表、滚动、点击、加载/解析遮罩和 shared-element 条件均保留。
- 本次验证覆盖 Kotlin 编译、静态搜索和 Git whitespace 检查；未执行设备运行、截图或网络数据场景验收。

## 疑虑与接口差异

- 简报中的 `AppVisualSurface` 内容回调在当前真实接口中是普通 `@Composable () -> Unit`，不是 `BoxScope`。为保留原海报评分/年份/状态角标的 `align` 定位，在内容回调内部增加了普通 `Box(fillMaxSize())`；没有修改公共 API。
- `DiscoverSheets.kt` 基线使用 `PosterCard`，但该组件自身带拟态阴影。按简报“发现内容卡片和 Sheet 不新增 Glass”以及迁移边界，Sheet 海报改为普通 `Content` 表面，并手动保留原有可见信息和交互。
- 没有进行设备视觉验收，因此最终结论限于源代码和编译证据，不宣称真机视觉通过。

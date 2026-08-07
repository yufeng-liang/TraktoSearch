# QuickSearchWidget 实现计划

> **面向 AI 代理的工作计划：** 实现前先使用 Context7 核对当前依赖锁定的 Glance 1.1.1 API；实现后使用 Android 模拟器 QA 流程验证尺寸、点击、授权和主题刷新。每个逻辑步骤通过针对性测试后单独提交，不能把预览目录、截图或构建产物加入提交。

**目标：** 将纯 Glance 文字组件改造成自适应的 Adaptive Ticket 桌面启动组件，点击整卡可靠进入统一搜索页，并保持现有授权、访客、默认启动页和主题行为不被破坏。

**架构：** 以现有 `NotificationNavigator` 的 `StateFlow` 请求模式增加打开搜索事件；`MainActivity` 负责冷/热启动意图和启动页优先级，`MainScreen` 负责消费事件切换 Pager 第 0 页；Widget 只负责渲染本地静态内容和启动 `MainActivity`。Widget 通过 Hilt EntryPoint 获取 `ThemeStorage` 的直接磁盘快照，避免冷进程中读取尚未完成预加载的 StateFlow 默认值。

**技术栈：** Kotlin、Jetpack Compose、Glance AppWidget 1.1.1、Hilt、DataStore、Material 主题颜色、JUnit、AndroidJUnit4、ADB 模拟器验收。

---

## 文件结构

**新增：**

- `app/src/main/java/com/tracktosearch/ui/navigation/SearchNavigator.kt`：打开搜索请求、Widget 启动意图常量/解析和请求消费。
- `app/src/test/java/com/tracktosearch/ui/navigation/SearchNavigatorTest.kt`：请求状态幂等性和消费行为的 JVM 测试。
- `app/src/test/java/com/tracktosearch/widget/QuickSearchWidgetLayoutTest.kt`：紧凑/完整断点选择的纯逻辑测试。

**修改：**

- `app/src/main/java/com/tracktosearch/MainActivity.kt`：解析 Widget 意图、冷启动搜索页优先级、热启动请求转发。
- `app/src/main/java/com/tracktosearch/ui/navigation/AppNavigation.kt`：让待处理 Widget 请求在登录/访客进入主页面后保留并优先进入搜索页。
- `app/src/main/java/com/tracktosearch/ui/screen/main/MainScreen.kt`：消费打开搜索请求，切换 Pager 到第 0 页并清除请求。
- `app/src/main/java/com/tracktosearch/widget/QuickSearchWidget.kt`：响应式尺寸、Adaptive Ticket 布局、启动图标、颜色、文案、语义和显式启动 Intent。
- `app/src/main/java/com/tracktosearch/data/local/ThemeStorage.kt`：增加不依赖异步 StateFlow 预加载的当前强调色读取方法，复用现有 DataStore key 和回退规则。
- `app/src/main/res/xml/quick_search_widget_info.xml`：将最小尺寸从当前约 `3×2` 调整为 `2×1`，目标宽度调整为 `4×2`。
- `app/src/main/res/values/strings.xml`、`values-zh/strings.xml`、`values-ja/strings.xml`、`values-ko/strings.xml`：替换 Widget 提示资源为标题、范围和无障碍描述。
- `app/src/main/java/com/tracktosearch/MainActivity.kt`：在已加载的主题强调色变化后触发所有 Widget 更新；该入口覆盖设置页和新手引导的两条主题写入路径。

---

## 任务 1：锁定打开搜索请求契约

**文件：**

- 创建：`app/src/main/java/com/tracktosearch/ui/navigation/SearchNavigator.kt`
- 创建：`app/src/test/java/com/tracktosearch/ui/navigation/SearchNavigatorTest.kt`

- [ ] **步骤 1：先写失败的请求状态测试**

测试必须覆盖：

```kotlin
@Test
fun `request sets pending and consume clears only the matching request`() {
    SearchNavigator.request()
    assertThat(SearchNavigator.pending.value).isTrue()

    SearchNavigator.consume()
    assertThat(SearchNavigator.pending.value).isFalse()
}

@Test
fun `repeated request remains one pending navigation`() {
    SearchNavigator.request()
    SearchNavigator.request()
    assertThat(SearchNavigator.pending.value).isTrue()
}
```

预期：测试先因 `SearchNavigator` 不存在或行为未实现而失败。

- [ ] **步骤 2：运行失败测试确认测试确实锁定缺口**

运行：

```bash
gradlew.bat :app:testDebugUnitTest --tests "com.tracktosearch.ui.navigation.SearchNavigatorTest" --no-daemon
```

预期：FAIL，失败原因是待测类型或方法尚不存在，而不是 Gradle 环境错误。

- [ ] **步骤 3：实现最小请求对象**

沿用 `NotificationNavigator` 的模式：

```kotlin
object SearchNavigator {
    private val _pending = MutableStateFlow(false)
    val pending: StateFlow<Boolean> = _pending.asStateFlow()

    fun request() { _pending.value = true }

    fun consume() { _pending.value = false }
}
```

同时定义唯一的 Widget 意图 extra 常量和最小解析函数。解析只认 `true`，空 Intent、缺失 extra 或其他值都返回 `false`，保证普通启动行为不变。

- [ ] **步骤 4：重新运行测试并提交**

运行同一条 `testDebugUnitTest` 命令，预期 PASS；检查 `git diff --check` 后提交：

```bash
git add app/src/main/java/com/tracktosearch/ui/navigation/SearchNavigator.kt app/src/test/java/com/tracktosearch/ui/navigation/SearchNavigatorTest.kt
git diff --cached --check
git commit -m "test(widget): 锁定打开搜索请求契约"
```

---

## 任务 2：接通 Activity 冷启动、热启动和授权后的待处理请求

**文件：**

- 修改：`app/src/main/java/com/tracktosearch/MainActivity.kt`
- 修改：`app/src/main/java/com/tracktosearch/ui/navigation/AppNavigation.kt`
- 修改：`app/src/androidTest/java/com/tracktosearch/ui/navigation/AppNavigationTest.kt` 或创建同包 `SearchLaunchIntentTest.kt`

- [ ] **步骤 1：先增加 Intent 解析的 Android 测试**

覆盖下列事实：

```kotlin
@Test
fun `only explicit open search extra is accepted`() {
    val open = Intent().putExtra(SearchNavigator.EXTRA_OPEN_SEARCH, true)
    val ordinary = Intent()
    val falseValue = Intent().putExtra(SearchNavigator.EXTRA_OPEN_SEARCH, false)

    assertThat(SearchNavigator.isOpenSearchIntent(open)).isTrue()
    assertThat(SearchNavigator.isOpenSearchIntent(ordinary)).isFalse()
    assertThat(SearchNavigator.isOpenSearchIntent(falseValue)).isFalse()
}
```

该测试放在 `androidTest`，因为它直接使用 Android `Intent`，避免 JVM 测试使用未完整实现的 Android framework stub。

- [ ] **步骤 2：运行该 Android 测试确认失败**

运行：

```bash
gradlew.bat :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.tracktosearch.ui.navigation.SearchLaunchIntentTest --no-daemon
```

预期：测试先因解析 API 尚不存在而失败；若设备未连接，只记录为环境前置条件未满足，不把安装/设备问题误判为断言失败。

- [ ] **步骤 3：在 MainActivity 接入显式意图**

在 `onCreate` 的异步启动初始化开始前读取：

```kotlin
val opensSearchFromWidget = SearchNavigator.isOpenSearchIntent(intent)
```

将该值作为启动页优先级的最高业务入口：

```kotlin
initialTab = when {
    opensSearchFromWidget -> 0
    notificationOpensWatchlist -> 2
    isAuthorized -> defaultTabStorage.defaultTab.first()
    else -> 0
}
```

在现有 `handleIntent(intent)` 中识别 Widget 意图并调用 `SearchNavigator.request()`；OAuth 和通知分支保持原有优先级和返回行为。`onNewIntent` 已由 Manifest 的 `singleTop` 支持，只需复用该解析逻辑。

- [ ] **步骤 4：在 AppNavigation/MainScreen 保留并消费登录后的请求**

不在登录页复制导航逻辑。待处理请求保持在 `SearchNavigator` 中，登录或访客切换到 `Routes.MAIN` 后由 `MainScreen` 消费。

在 `MainScreen` 增加与 `NotificationNavigator` 同形的收集器：

```kotlin
LaunchedEffect(Unit) {
    SearchNavigator.pending.collect { pending ->
        if (pending) {
            pagerState.scrollToPage(0)
            selectedTab = 0
            SearchNavigator.consume()
        }
    }
}
```

在 `AppNavigation` 的登录成功分支保留现有“新用户/老用户”默认页逻辑；如果 `SearchNavigator.pending.value` 为 `true`，将 `mainInitialTab` 设为 `0`，避免 Widget 登录后短暂进入默认的“我的”页。普通登录不改变原有默认页。

- [ ] **步骤 5：运行 Intent、导航和现有路由测试**

运行：

```bash
gradlew.bat :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.tracktosearch.ui.navigation.SearchLaunchIntentTest --no-daemon
gradlew.bat :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.tracktosearch.ui.navigation.AppNavigationTest --no-daemon
```

注意第一条命令的参数名必须以实际 Gradle runner 支持的 `android.testInstrumentationRunnerArguments` 为准；执行前用 `--help` 或现有项目命令校对，避免把参数拼写错误当成测试失败。预期：两组测试 PASS。

- [ ] **步骤 6：提交导航接入**

```bash
git add app/src/main/java/com/tracktosearch/MainActivity.kt app/src/main/java/com/tracktosearch/ui/navigation/AppNavigation.kt app/src/main/java/com/tracktosearch/ui/screen/main/MainScreen.kt app/src/main/java/com/tracktosearch/ui/navigation/SearchNavigator.kt app/src/androidTest/java/com/tracktosearch/ui/navigation/SearchLaunchIntentTest.kt
git diff --cached --check
git commit -m "feat(widget): 接通打开搜索导航"
```

---

## 任务 3：锁定 Glance 响应式断点和主题回退逻辑

**文件：**

- 创建：`app/src/test/java/com/tracktosearch/widget/QuickSearchWidgetLayoutTest.kt`
- 修改：`app/src/main/java/com/tracktosearch/widget/QuickSearchWidget.kt`
- 修改：`app/src/main/java/com/tracktosearch/data/local/ThemeStorage.kt`
- 可能创建：`app/src/main/java/com/tracktosearch/widget/QuickSearchWidgetThemeEntryPoint.kt`

- [ ] **步骤 1：先写尺寸和强调色回退测试**

将断点选择提取为不依赖 Glance 渲染的纯函数/枚举，测试边界：

```kotlin
@Test
fun `size below expanded threshold uses compact layout`() {
    assertThat(layoutFor(DpSize(120.dp, 60.dp))).isEqualTo(WidgetLayout.COMPACT)
    assertThat(layoutFor(DpSize(239.dp, 100.dp))).isEqualTo(WidgetLayout.COMPACT)
}

@Test
fun `size at expanded threshold uses full layout`() {
    assertThat(layoutFor(DpSize(240.dp, 100.dp))).isEqualTo(WidgetLayout.EXPANDED)
}

@Test
fun `missing accent falls back to vintage ticket`() {
    assertThat(resolveWidgetAccent(null)).isEqualTo(MonetAccent.VINTAGE_TICKET)
}
```

预期：测试先因断点函数和回退函数不存在而失败。

- [ ] **步骤 2：运行失败测试**

```bash
gradlew.bat :app:testDebugUnitTest --tests "com.tracktosearch.widget.QuickSearchWidgetLayoutTest" --no-daemon
```

- [ ] **步骤 3：实现最小纯逻辑和主题快照读取**

在 `ThemeStorage` 内复用现有 DataStore key，新增一个挂起读取方法，直接读取 `themeDataStore.data.first()`，按当前规则把不存在、`dynamic` 或非法枚举转换为 `null`/Vintage Ticket 回退；不要读取初始化阶段的 StateFlow 当前默认值。

Widget 通过 Hilt EntryPoint 获取 Singleton `ThemeStorage`，在 `provideGlance` 中读取快照后再调用 `provideContent`。这保证 Widget 冷进程首次渲染使用已保存主题，而不是等待异步预加载。

使用 Context7 已核对的 Glance API：

```kotlin
override val sizeMode = SizeMode.Responsive(
    setOf(DpSize(120.dp, 60.dp), DpSize(240.dp, 100.dp))
)

val size = LocalSize.current
val layout = layoutFor(size)
val iconProvider = ImageProvider(R.mipmap.ic_launcher)
val lightDarkAccent = ColorProvider(day = accent.light, night = accent.dark)
```

背景使用 Glance 支持的 `ColorProvider` 和圆角 modifier；不引入 Compose Canvas、网络图片或自绘 SVG。跨系统明暗的表面和文字颜色必须保证对比度。

- [ ] **步骤 4：重新运行断点/回退测试**

预期：PASS；再运行 `:app:compileDebugKotlin`，确认 Glance 1.1.1 的具体 `ColorProvider`、圆角和 `TextStyle` 参数签名与计划一致。若编译提示 API 差异，只按当前依赖 API 调整实现，不改变设计契约。

- [ ] **步骤 5：提交主题和断点逻辑**

```bash
git add app/src/main/java/com/tracktosearch/widget/QuickSearchWidget.kt app/src/main/java/com/tracktosearch/data/local/ThemeStorage.kt app/src/main/java/com/tracktosearch/widget/QuickSearchWidgetThemeEntryPoint.kt app/src/test/java/com/tracktosearch/widget/QuickSearchWidgetLayoutTest.kt
git diff --cached --check
git commit -m "feat(widget): 增加响应式尺寸与主题回退"
```

若 EntryPoint 不需要单独文件，不要把不存在的路径加入 `git add`。

---

## 任务 4：实现 Adaptive Ticket 视觉和本地化资源

**文件：**

- 修改：`app/src/main/java/com/tracktosearch/widget/QuickSearchWidget.kt`
- 修改：`app/src/main/res/xml/quick_search_widget_info.xml`
- 修改：`app/src/main/res/values/strings.xml`
- 修改：`app/src/main/res/values-zh/strings.xml`
- 修改：`app/src/main/res/values-ja/strings.xml`
- 修改：`app/src/main/res/values-ko/strings.xml`

- [ ] **步骤 1：替换 Glance 文本和布局**

紧凑布局只渲染启动图标、`widget_quick_search_title` 和箭头；完整布局增加 `app_name`、`widget_quick_search_scope` 和扁平动作行。所有内容都位于同一个根 `Box` 中。

根 modifier 组合应包含：

```kotlin
GlanceModifier
    .fillMaxSize()
    .background(surfaceColorProvider)
    .semantics {
        setContentDescription(context.getString(R.string.widget_quick_search_content_description))
    }
    .clickable(
        actionStartActivity(
            Intent(context, MainActivity::class.java).putExtra(
                SearchNavigator.EXTRA_OPEN_SEARCH,
                true
            )
        )
    )
```

具体圆角 modifier、`Image` 的 content description 和颜色文字参数以 Glance 1.1.1 编译结果为准。图标使用现有 `R.mipmap.ic_launcher`，应用名使用 `R.string.app_name`。

- [ ] **步骤 2：修正 Widget provider 尺寸**

将 XML 调整为：

```xml
android:minWidth="120dp"
android:minHeight="60dp"
android:minResizeWidth="120dp"
android:minResizeHeight="60dp"
android:targetCellWidth="4"
android:targetCellHeight="2"
android:resizeMode="horizontal|vertical"
```

保留 `home_screen` 和现有 `glance_default_loading_layout`。安装后以实际桌面网格校对 `120×60dp` 与 `240×100dp` 两个响应式断点，若桌面传入的 `LocalSize` 与目标尺寸存在偏差，只调整断点映射，不增加第三套布局。

- [ ] **步骤 3：同步四套字符串并删除旧硬编码提示**

新增/替换：

- `widget_quick_search_title`
- `widget_quick_search_scope`
- `widget_quick_search_content_description`

删除只被该 Widget 使用的 `widget_quick_search_hint`，并逐文件确认不存在遗漏的旧资源引用。英文、中文、日文、韩文文案分别按自然短语翻译，不直接复制中文。

- [ ] **步骤 4：编译资源和 Widget 代码**

```bash
gradlew.bat :app:compileDebugKotlin :app:processDebugResources --no-daemon
```

预期：PASS，且 `rg -n "TraktoSearch|widget_quick_search_hint" app/src/main/java app/src/main/res` 不再命中 Widget 硬编码或旧提示引用。

- [ ] **步骤 5：提交视觉和资源**

```bash
git add app/src/main/java/com/tracktosearch/widget/QuickSearchWidget.kt app/src/main/res/xml/quick_search_widget_info.xml app/src/main/res/values/strings.xml app/src/main/res/values-zh/strings.xml app/src/main/res/values-ja/strings.xml app/src/main/res/values-ko/strings.xml
git diff --cached --check
git commit -m "style(widget): 优化快速搜索组件视觉"
```

---

## 任务 5：主题变化后主动刷新 Widget

**文件：**

- 修改：`app/src/main/java/com/tracktosearch/MainActivity.kt`
- 如编译边界需要，修改：`app/src/main/java/com/tracktosearch/widget/QuickSearchWidget.kt`

- [ ] **步骤 1：在主题 StateFlow 成功加载后接入更新**

复用 `MainActivity.setContent` 已收集的 `accentColor`，添加生命周期受 Compose 管理的更新协程：

```kotlin
LaunchedEffect(accentColor) {
    QuickSearchWidget().updateAll(applicationContext)
}
```

更新只负责重新渲染 Widget，不触发网络请求。初次启动触发一次刷新可接受；主题设置页和 MainScreen 新手引导都通过同一 `ThemeStorage` StateFlow 自动覆盖。

- [ ] **步骤 2：运行编译和主题相关测试**

```bash
gradlew.bat :app:testDebugUnitTest :app:assembleDebug --no-daemon
```

预期：PASS；检查没有重复的 Widget update loop，也没有因主题更新触发 Activity 重启或搜索请求。

- [ ] **步骤 3：提交刷新行为**

```bash
git add app/src/main/java/com/tracktosearch/MainActivity.kt
git diff --cached --check
git commit -m "feat(widget): 同步主题变化"
```

---

## 任务 6：Android 模拟器功能和视觉验收

**文件/产物：** 不新增提交文件；截图、UI 树、logcat 和 APK 只放在临时 QA 目录，不加入 Git。

- [ ] **步骤 1：构建并安装 Debug APK**

```bash
adb devices
gradlew.bat :app:assembleDebug --no-daemon
adb -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk
```

记录设备真实的 `ro.build.version.release`、`ro.build.version.sdk`、安装结果和 APK 路径。若 Gradle 设备筛选异常，按 ADB 直接安装事实核验，不仅凭 `installDebug` 结论。

- [ ] **步骤 2：放置两个尺寸并验证静态视觉**

在模拟器桌面分别放置 `2×1` 和 `4×2`，浅色/深色系统环境各截图一次。检查：

- 表面圆角、主题强调色和启动图标与 Adaptive Ticket 设计一致。
- `2×1` 文案不截断，`4×2` 范围说明和动作行不重叠。
- 组件只有一个无障碍点击节点，contentDescription 为当前语言资源。
- 横向/纵向调整到中间尺寸时稳定回退到紧凑布局。

- [ ] **步骤 3：验证冷启动、热启动和授权分支**

分别执行：

1. App 被杀时点击 Widget：已授权/访客直接进入搜索页。
2. App 在详情页或“我的”页时点击 Widget：不新建 Activity，切到搜索页。
3. 未激活时点击 Widget：进入激活页，完成激活或选择访客后进入搜索页。
4. 连续快速点击两次：只发生一次有效切页，不产生重复导航。

每条场景记录启动进程、UI 树和最终页面；失败时抓取 `logcat -b crash`。

- [ ] **步骤 4：验证主题刷新**

在 App 设置页切换至少两种 Monet 强调色，分别检查桌面上的已放置组件是否立即更新；再切换系统深浅色，检查表面和文字对比度。冷启动 Widget 时验证已持久化主题仍被正确读取。

- [ ] **步骤 5：执行最终验证并检查 Git**

```bash
gradlew.bat :app:testDebugUnitTest :app:assembleDebug --no-daemon
git diff --check
git status --short --untracked-files=all
```

只允许源码、资源和测试提交记录存在；QA 截图、`app/build`、`.superpowers` 和临时目录不得进入 staged 列表。

---

## 提交顺序

1. `test(widget): 锁定打开搜索请求契约`
2. `feat(widget): 接通打开搜索导航`
3. `feat(widget): 增加响应式尺寸与主题回退`
4. `style(widget): 优化快速搜索组件视觉`
5. `feat(widget): 同步主题变化`

每次提交前都运行 `git diff --cached --check` 和 `git diff --cached --name-only`，确认没有混入截图、构建产物、`.superpowers`、`local.properties` 或敏感配置。

## 完成定义

- 所有单元/仪器测试和 Debug 构建通过。
- 模拟器上两个尺寸、两种系统明暗和四种语言均无布局重叠或截断。
- Widget 冷/热启动、授权/访客流程和重复点击行为符合设计。
- 主题变更可即时刷新已放置组件，Widget 冷进程读取持久化主题无竞态回退错误。
- `git status` 与 staged 检查证明没有提交预览、截图、构建产物或敏感配置。

# Haze Glass 双风格视觉语言实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（- [ ]）语法跟踪进度。

**目标：** 将当前 Haze Glass 从拟态复用层改造成独立的光学玻璃语言，并提供 Blur、Glass / 清透、Glass / 聚焦三个可持久化选择。

**架构：** Blur 继续使用现有 Haze blur 与 Neumorphic 绘制；Glass 使用独立的角色 token、Glass surface、Glass icon button 和普通半透明 tab indicator，不再叠加拟态外阴影、内阴影、旧 GlassHighlight 或 NeumorphicActiveTab。用户只选择材质和 Glass 风格，页面只声明表面角色，底层光学参数集中在 AppGlassStyle.kt。

**技术栈：** Jetpack Compose、Material 3、Haze 2.0.0-alpha04、Preferences DataStore、Hilt/MVVM、JUnit/Truth/Robolectric、Compose UI Test、ADB gfxinfo。

---

## 文件清单与职责

**修改：**

- app/src/main/java/com/tracktosearch/ui/theme/VisualEffectMode.kt：新增 GlassVariant 和 LocalGlassVariant。
- app/src/main/java/com/tracktosearch/data/local/ThemeStorage.kt：新增 glass_variant key、StateFlow 和模式/风格原子保存。
- app/src/main/java/com/tracktosearch/ui/theme/Theme.kt：向主题树提供 Glass 风格。
- app/src/main/java/com/tracktosearch/MainActivity.kt：收集并传入 glassVariant。
- app/src/main/java/com/tracktosearch/ui/component/AppGlassStyle.kt：集中定义角色 token、清透/聚焦参数和零色散约束。
- app/src/main/java/com/tracktosearch/ui/component/AppVisualEffect.kt：保留 Blur/Glass 的 Haze 分发，并使用主题中的 Glass 风格。
- app/src/main/java/com/tracktosearch/ui/component/NeumorphicGlass.kt：保留 Blur 组件，不让 Glass 继续复用拟态绘制。
- app/src/main/java/com/tracktosearch/ui/component/GlassSurface.kt：新增无拟态阴影/高光的 Glass surface、Glass icon button、普通 Glass tab indicator。
- app/src/main/java/com/tracktosearch/ui/component/VisualSurface.kt：新增 AppVisualSurface 与 AppIconButton，按模式分发 Blur、Glass、普通内容和 Modal。
- app/src/main/java/com/tracktosearch/ui/screen/settings/SettingsViewModel.kt：暴露 glassVariant 和原子视觉选择方法。
- app/src/main/java/com/tracktosearch/ui/screen/settings/SettingsScreen.kt：开放视觉效果入口并显示当前三选一摘要。
- app/src/main/java/com/tracktosearch/ui/screen/settings/SettingsDialogs.kt：提供 Blur、Glass/清透、Glass/聚焦三项互斥选择。
- app/src/main/java/com/tracktosearch/ui/screen/main/MainScreen.kt：底部导航改为单一外层 Glass，Tab 内移除独立采样和拟态选中态。
- app/src/main/java/com/tracktosearch/ui/screen/discover/DiscoverScreen.kt、DiscoverComponents.kt、DiscoverSections.kt、DiscoverSheets.kt：按发现页真实海报密度迁移顶部操作和圆形按钮，内容卡片/Sheet 保持非 Glass。
- app/src/main/java/com/tracktosearch/ui/screen/search/SearchScreen.kt、app/src/main/java/com/tracktosearch/ui/component/GlassSearchBar.kt、app/src/main/java/com/tracktosearch/ui/screen/traktsearch/TraktSearchScreen.kt：迁移搜索框和顶部操作，结果内容不使用 Glass。
- app/src/main/java/com/tracktosearch/ui/screen/watchlist/WatchlistScreen.kt、WatchlistCategoryTabs.kt、app/src/main/java/com/tracktosearch/ui/screen/markrecord/MarkRecordScreen.kt：迁移顶部、多选和操作控件，海报内容保持普通表面。
- app/src/main/java/com/tracktosearch/ui/screen/detail/DetailScreen.kt、DetailComments.kt、DetailSeasonsSection.kt、app/src/main/java/com/tracktosearch/ui/screen/person/PersonScreen.kt、app/src/main/java/com/tracktosearch/ui/screen/douban/DoubanItemDetailScreen.kt：只迁移顶部/详情操作按钮，正文、海报、评分、评论、剧照和预览不使用 Glass。
- app/src/main/java/com/tracktosearch/ui/screen/statistics/StatisticsScreen.kt、app/src/main/java/com/tracktosearch/ui/screen/discover/DiscoverFilterScreen.kt：只迁移顶部栏和少量操作控件。
- app/src/main/java/com/tracktosearch/ui/screen/settings/SettingsComponents.kt：设置项使用普通内容表面，不将每个设置项变为 Glass。
- app/src/main/java/com/tracktosearch/ui/screen/login/ActivationLoginScreen.kt：以一个 LoginSurface 包裹登录表单，输入框不单独套 Glass。
- app/src/main/java/com/tracktosearch/ui/component/ScrollToTopButton.kt：使用统一圆形操作角色。
- app/src/main/res/values/strings.xml、values-zh/strings.xml、values-ja/strings.xml、values-ko/strings.xml：新增清透/聚焦资源并更新帮助说明。
- app/src/test/java/com/tracktosearch/ui/theme/VisualEffectModeTest.kt、ThemeTest.kt：覆盖状态解码、默认值和主题注入。
- app/src/test/java/com/tracktosearch/ui/component/NeumorphicGlassTest.kt：覆盖 Blur 未回归和 Glass 按钮交互契约。
- app/src/test/java/com/tracktosearch/ui/screen/settings/SettingsViewModelTest.kt：覆盖视觉选择的原子委托。

**创建：**

- app/src/main/java/com/tracktosearch/ui/component/GlassSurface.kt：独立 Glass 绘制组件。
- app/src/main/java/com/tracktosearch/ui/component/VisualSurface.kt：按视觉模式与表面用途分发的行为入口。
- app/src/test/java/com/tracktosearch/ui/component/GlassTokenTest.kt：纯 token 关系测试，不依赖截图。

## 任务 1：建立 Glass 风格状态模型与兼容持久化

**文件：**

- 修改：app/src/main/java/com/tracktosearch/ui/theme/VisualEffectMode.kt
- 修改：app/src/main/java/com/tracktosearch/data/local/ThemeStorage.kt
- 修改：app/src/main/java/com/tracktosearch/ui/theme/Theme.kt
- 修改：app/src/main/java/com/tracktosearch/MainActivity.kt
- 测试：app/src/test/java/com/tracktosearch/ui/theme/VisualEffectModeTest.kt
- 测试：app/src/test/java/com/tracktosearch/ui/theme/ThemeTest.kt

- [ ] **步骤 1：先编写状态解码失败测试**

在 VisualEffectModeTest.kt 增加：

~~~kotlin
@Test
fun missingGlassVariantDefaultsToClear() {
    assertThat(GlassVariant.fromStorageValue(null)).isEqualTo(GlassVariant.CLEAR)
}

@Test
fun unknownGlassVariantDefaultsToClear() {
    assertThat(GlassVariant.fromStorageValue("future_variant"))
        .isEqualTo(GlassVariant.CLEAR)
}

@Test
fun glassVariantUsesStableStorageValues() {
    assertThat(GlassVariant.CLEAR.storageValue).isEqualTo("clear")
    assertThat(GlassVariant.FOCUSED.storageValue).isEqualTo("focused")
    assertThat(GlassVariant.fromStorageValue("focused"))
        .isEqualTo(GlassVariant.FOCUSED)
}
~~~

运行：

~~~bash
./gradlew.bat :app:testDebugUnitTest --tests 'com.tracktosearch.ui.theme.VisualEffectModeTest' --no-daemon --console=plain
~~~

预期：新增测试先因 GlassVariant 不存在而失败。

- [ ] **步骤 2：实现 GlassVariant 和主题 CompositionLocal**

在 VisualEffectMode.kt 保留现有 VisualEffectMode，新增：

~~~kotlin
enum class GlassVariant(val storageValue: String) {
    CLEAR("clear"),
    FOCUSED("focused");

    companion object {
        fun fromStorageValue(value: String?): GlassVariant =
            entries.firstOrNull { it.storageValue == value } ?: CLEAR
    }
}

val LocalGlassVariant = staticCompositionLocalOf { GlassVariant.CLEAR }
~~~

在 ThemeStorage.kt 新增 KEY_GLASS_VARIANT、_glassVariant 和 glassVariant StateFlow。初始化时用 GlassVariant.fromStorageValue(prefs[KEY_GLASS_VARIANT])。新增原子 API：

~~~kotlin
suspend fun setVisualEffectSelection(
    mode: VisualEffectMode,
    glassVariant: GlassVariant
) {
    context.themeDataStore.edit { prefs ->
        prefs[KEY_VISUAL_EFFECT_MODE] = mode.storageValue
        prefs[KEY_GLASS_VARIANT] = glassVariant.storageValue
    }
    _visualEffectMode.value = mode
    _glassVariant.value = glassVariant
}
~~~

保留 setVisualEffectMode(mode) 兼容旧调用，但让它复用当前 glassVariant，确保 Blur 到 Glass 不丢失已选风格。缺省或未知 visual_effect_mode 仍回退 BLUR；缺省或未知 glass_variant 回退 CLEAR；旧 glass 记录不强制重写 DataStore。

- [ ] **步骤 3：将风格传入主题树**

扩展 TraktoSearchTheme：

~~~kotlin
fun TraktoSearchTheme(
    themeMode: String = "system",
    accentColor: MonetAccent? = null,
    visualEffectMode: VisualEffectMode = VisualEffectMode.BLUR,
    glassVariant: GlassVariant = GlassVariant.CLEAR,
    content: @Composable () -> Unit
)
~~~

在 CompositionLocalProvider 增加 LocalGlassVariant provides glassVariant。MainActivity.setContent 收集 themeStorage.glassVariant 并传入；未配置时保持 BLUR + CLEAR。

- [ ] **步骤 4：运行测试并提交**

运行：

~~~bash
./gradlew.bat :app:testDebugUnitTest --tests 'com.tracktosearch.ui.theme.VisualEffectModeTest' --tests 'com.tracktosearch.ui.theme.ThemeTest' --no-daemon --console=plain
~~~

预期：相关测试通过。随后运行 git diff --check，只暂存任务 1 文件并提交：

~~~bash
git add app/src/main/java/com/tracktosearch/ui/theme/VisualEffectMode.kt app/src/main/java/com/tracktosearch/data/local/ThemeStorage.kt app/src/main/java/com/tracktosearch/ui/theme/Theme.kt app/src/main/java/com/tracktosearch/MainActivity.kt app/src/test/java/com/tracktosearch/ui/theme/VisualEffectModeTest.kt app/src/test/java/com/tracktosearch/ui/theme/ThemeTest.kt
git diff --cached --check
git diff --cached --name-only
git commit -m "feat(theme): 增加玻璃风格持久化选择"
~~~

## 任务 2：集中 Glass token 并拆出独立绘制组件

**文件：**

- 修改：app/src/main/java/com/tracktosearch/ui/component/AppGlassStyle.kt
- 修改：app/src/main/java/com/tracktosearch/ui/component/AppVisualEffect.kt
- 修改：app/src/main/java/com/tracktosearch/ui/component/NeumorphicGlass.kt
- 创建：app/src/main/java/com/tracktosearch/ui/component/GlassSurface.kt
- 创建：app/src/main/java/com/tracktosearch/ui/component/VisualSurface.kt
- 测试：app/src/test/java/com/tracktosearch/ui/component/GlassTokenTest.kt
- 测试：app/src/test/java/com/tracktosearch/ui/component/NeumorphicGlassTest.kt

- [ ] **步骤 1：先添加 token 失败测试**

创建 GlassTokenTest.kt，以纯函数 glassToken(role, variant, isDark) 为入口：

~~~kotlin
@Test
fun focusedHasStrongerTopBarTokenThanClear() {
    val clear = glassToken(GlassSurfaceRole.TopBar, GlassVariant.CLEAR, false)
    val focused = glassToken(GlassSurfaceRole.TopBar, GlassVariant.FOCUSED, false)
    assertThat(clear.tintAlpha).isLessThan(focused.tintAlpha)
    assertThat(clear.specularIntensity).isLessThan(focused.specularIntensity)
    assertThat(clear.ambientResponse).isLessThan(focused.ambientResponse)
}

@Test
fun allFormalRolesDisableChromaticAberration() {
    GlassSurfaceRole.entries.forEach { role ->
        GlassVariant.entries.forEach { variant ->
            assertThat(glassToken(role, variant, false).chromaticAberrationStrength)
                .isEqualTo(0f)
        }
    }
}

@Test
fun pressScaleStaysWithinHazeGlassBounds() {
    val pressScale = glassToken(
        GlassSurfaceRole.CircularControl,
        GlassVariant.FOCUSED,
        false
    ).pressScale
    assertThat(pressScale).isAtLeast(0.98f)
    assertThat(pressScale).isAtMost(1f)
}
~~~

运行：

~~~bash
./gradlew.bat :app:testDebugUnitTest --tests 'com.tracktosearch.ui.component.GlassTokenTest' --no-daemon --console=plain
~~~

预期：先因角色或 token 类型不存在而失败。

- [ ] **步骤 2：实现角色 token**

在 AppGlassStyle.kt 定义：

~~~kotlin
enum class GlassSurfaceRole {
    TopBar,
    CircularControl,
    BottomNavigation,
    SearchField,
    DetailAction,
    LoginSurface
}

data class GlassToken(
    val tintAlpha: Float,
    val specularIntensity: Float,
    val ambientResponse: Float,
    val edgeSoftness: Dp,
    val surfaceProfile: SurfaceProfile,
    val chromaticAberrationStrength: Float = 0f,
    val hoverLighting: Float,
    val pressLighting: Float,
    val pressRefractionMultiplier: Float,
    val pressWhitePointDelta: Float,
    val pressScale: Float
)
~~~

实现 glassToken(role, variant, isDark) 时使用规格第一版基线：顶部栏清透 tint 0.08f/0.13f、聚焦 0.13f/0.20f；圆形控件清透 0.11f/0.15f、聚焦 0.18f/0.25f；底部导航清透 0.38f/0.25f、聚焦 0.49f/0.34f；搜索框清透 0.14f/0.18f、聚焦 0.23f/0.28f；详情操作沿用圆形控件；登录表面清透 0.42f/0.34f、聚焦 0.53f/0.43f。每组前者为浅色、后者为深色。聚焦的 specular、ambient、hover、press 参数高于清透，所有角色色散强度固定 0f。

- [ ] **步骤 3：让 AppGlassStyles 读取当前风格**

将样式入口收敛为：

~~~kotlin
@Composable
fun style(
    role: GlassSurfaceRole,
    shape: RoundedCornerShape,
    tint: Color = MaterialTheme.colorScheme.surface,
    interactive: Boolean = false
): GlassStyle
~~~

内部读取 LocalGlassVariant.current 和 isAppDarkTheme()，用 token 设置 tint、GlassOptics.Adaptive、specular、ambient、edgeSoftness、shape、surfaceProfile；始终设置 chromaticAberrationStrength(0f)。交互状态使用 token 的 lighting/refraction/white-point，按压 scale(token.pressScale) 且值不超过 1f。保留 topBar、circularControl、bottomNavigation、searchField、detailAction、loginSurface 短入口，但短入口只传角色和形状。删除 bottomNavigation 的 0.10f 色散；不再使用 bottomNavigationItem。

- [ ] **步骤 4：实现 Glass 专用绘制组件**

创建 GlassSurface.kt，提供：

~~~kotlin
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    hazeState: HazeState,
    role: GlassSurfaceRole,
    shape: RoundedCornerShape,
    sourceSelection: HazeSourceSelection = HazeSourceSelection.Behind,
    content: @Composable () -> Unit
)

@Composable
fun GlassIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 42.dp,
    hazeState: HazeState,
    role: GlassSurfaceRole = GlassSurfaceRole.CircularControl,
    interactionSource: MutableInteractionSource? = null,
    enabled: Boolean = true,
    content: @Composable () -> Unit
)
~~~

GlassSurface 的顺序固定为 clip、hazeGlass、border、content，不得调用 neumorphicOuterShadow、neumorphicInnerShadow、GlassHighlight 或 NeumorphicActiveTab。GlassIconButton 内部用 remember 补齐空 interaction source，保留 clickable、enabled、语义和原有 haptic 行为。GlassTabIndicator 只绘制普通半透明背景/边框，不采样背景。

- [ ] **步骤 5：实现按用途分发入口**

在 VisualSurface.kt 定义：

~~~kotlin
enum class VisualSurfaceKind { Glass, Content, Modal }

@Composable
fun AppVisualSurface(
    kind: VisualSurfaceKind,
    modifier: Modifier = Modifier,
    isDark: Boolean,
    shape: Shape,
    hazeState: HazeState? = null,
    glassRole: GlassSurfaceRole = GlassSurfaceRole.TopBar,
    sourceSelection: HazeSourceSelection = HazeSourceSelection.Behind,
    backgroundColor: Color,
    borderColor: Color,
    content: @Composable () -> Unit
)

@Composable
fun AppIconButton(
    onClick: () -> Unit,
    isDark: Boolean,
    modifier: Modifier = Modifier,
    size: Dp = 42.dp,
    hazeState: HazeState? = null,
    role: GlassSurfaceRole = GlassSurfaceRole.CircularControl,
    interactionSource: MutableInteractionSource? = null,
    enabled: Boolean = true,
    buttonStyle: NeumorphicIconButtonStyle = NeumorphicIconButtonStyle.Default,
    content: @Composable () -> Unit
)
~~~

分发规则固定为：Glass + GLASS 使用 GlassSurface 并透传 sourceSelection；Glass + BLUR 使用现有 NeumorphicFrostedSurface；Content + GLASS 使用无采样、无拟态阴影的普通 Box；Content + BLUR 保留现有拟态内容表面；Modal 使用 surfaceVariant 或现有 Bottom Sheet 表面并禁止 hazeGlass。AppIconButton 在 Blur 分支调用 NeumorphicIconButton，Glass 分支调用 GlassIconButton。页面不再直接传光学底层参数。

- [ ] **步骤 6：运行组件测试、编译并提交**

在 NeumorphicGlassTest.kt 保留现有禁用 Blur 按钮测试，并新增 Glass 分支的启用/禁用点击测试。运行：

~~~bash
./gradlew.bat :app:testDebugUnitTest --tests 'com.tracktosearch.ui.component.GlassTokenTest' --tests 'com.tracktosearch.ui.component.NeumorphicGlassTest' --no-daemon --console=plain
./gradlew.bat :app:compileDebugKotlin --no-daemon --console=plain
~~~

预期：测试通过、Kotlin 编译成功。只提交任务 2 文件：

~~~bash
git add app/src/main/java/com/tracktosearch/ui/component/AppGlassStyle.kt app/src/main/java/com/tracktosearch/ui/component/AppVisualEffect.kt app/src/main/java/com/tracktosearch/ui/component/NeumorphicGlass.kt app/src/main/java/com/tracktosearch/ui/component/GlassSurface.kt app/src/main/java/com/tracktosearch/ui/component/VisualSurface.kt app/src/test/java/com/tracktosearch/ui/component/GlassTokenTest.kt app/src/test/java/com/tracktosearch/ui/component/NeumorphicGlassTest.kt
git diff --cached --check
git diff --cached --name-only
git commit -m "refactor(glass): 分离玻璃与拟态材质组件"
~~~

## 任务 3：开放设置入口并接入三选一 UI

**文件：**

- 修改：app/src/main/java/com/tracktosearch/ui/screen/settings/SettingsViewModel.kt
- 修改：app/src/main/java/com/tracktosearch/ui/screen/settings/SettingsScreen.kt
- 修改：app/src/main/java/com/tracktosearch/ui/screen/settings/SettingsDialogs.kt
- 修改：app/src/main/res/values/strings.xml
- 修改：app/src/main/res/values-zh/strings.xml
- 修改：app/src/main/res/values-ja/strings.xml
- 修改：app/src/main/res/values-ko/strings.xml
- 测试：app/src/test/java/com/tracktosearch/ui/screen/settings/SettingsViewModelTest.kt

- [ ] **步骤 1：先为 ViewModel 原子委托添加失败测试**

在 SettingsViewModelTest.kt 增加：

~~~kotlin
@Test
fun setVisualEffectSelectionWritesModeAndVariantTogether() = runTest {
    viewModel.setVisualEffectSelection(VisualEffectMode.GLASS, GlassVariant.FOCUSED)
    advanceUntilIdle()

    coVerify {
        themeStorage.setVisualEffectSelection(
            VisualEffectMode.GLASS,
            GlassVariant.FOCUSED
        )
    }
}
~~~

预期：先因 ViewModel 方法不存在而失败。

- [ ] **步骤 2：实现 ViewModel 状态和保存入口**

在 SettingsViewModel 增加：

~~~kotlin
val glassVariant: StateFlow<GlassVariant> = themeStorage.glassVariant

fun setVisualEffectSelection(mode: VisualEffectMode, variant: GlassVariant) {
    viewModelScope.launch {
        themeStorage.setVisualEffectSelection(mode, variant)
    }
}
~~~

不在 ViewModel 中根据设备能力、API 或帧率覆盖用户选择。

- [ ] **步骤 3：更新对话框为三个互斥选项**

将 VisualEffectSelectionDialog 改为：

~~~kotlin
@Composable
internal fun VisualEffectSelectionDialog(
    currentMode: VisualEffectMode,
    currentVariant: GlassVariant,
    onSelection: (VisualEffectMode, GlassVariant) -> Unit,
    onDismiss: () -> Unit
)
~~~

选中条件固定为：Blur 是 currentMode == BLUR；清透是 currentMode == GLASS && currentVariant == CLEAR；聚焦是 currentMode == GLASS && currentVariant == FOCUSED。对话框仍用 AlertDialog(containerColor = MaterialTheme.colorScheme.surfaceVariant)，不使用 Glass。

- [ ] **步骤 4：启用入口并显示摘要**

在 SettingsScreen.kt 将 SHOW_VISUAL_EFFECT_ENTRY 改为 true，收集 viewModel.glassVariant，并按模式/风格显示对应资源。三项点击都调用 setVisualEffectSelection 后关闭对话框；页面不展示 Adaptive、采样、折射或色散等实现术语。

- [ ] **步骤 5：同步四种语言资源和帮助说明**

四套资源都新增同名键：

~~~xml
<string name="settings_visual_effect_glass_clear">Glass - Clear</string>
<string name="settings_visual_effect_glass_clear_desc">A lighter optical glass style with a quieter boundary</string>
<string name="settings_visual_effect_glass_focused">Glass - Focused</string>
<string name="settings_visual_effect_glass_focused_desc">A more defined glass layer for navigation and controls</string>
~~~

中文分别使用“玻璃 - 清透”“更轻薄的光学玻璃，边界存在感更低”和“玻璃 - 聚焦”“更明确的玻璃层次，适合导航与操作”；日语、韩语提供同义本地化文案。更新 help_tips_b11，说明 Blur 绑定拟态毛玻璃，Glass 提供清透/聚焦两种独立光学风格，删除“实验性、敬请期待”的过时文案。

- [ ] **步骤 6：运行设置测试、资源检查并提交**

运行：

~~~bash
./gradlew.bat :app:testDebugUnitTest --tests 'com.tracktosearch.ui.screen.settings.SettingsViewModelTest' --no-daemon --console=plain
./gradlew.bat :app:compileDebugKotlin --no-daemon --console=plain
rg -n "settings_visual_effect_glass_(clear|focused)" app/src/main/res/values*/strings.xml
~~~

预期：设置测试、资源合并和编译通过。执行 git diff --cached --check 后提交：

~~~bash
git add app/src/main/java/com/tracktosearch/ui/screen/settings/SettingsViewModel.kt app/src/main/java/com/tracktosearch/ui/screen/settings/SettingsScreen.kt app/src/main/java/com/tracktosearch/ui/screen/settings/SettingsDialogs.kt app/src/main/res/values/strings.xml app/src/main/res/values-zh/strings.xml app/src/main/res/values-ja/strings.xml app/src/main/res/values-ko/strings.xml app/src/test/java/com/tracktosearch/ui/screen/settings/SettingsViewModelTest.kt
git diff --cached --check
git diff --cached --name-only
git commit -m "feat(settings): 提供玻璃风格细分选择"
~~~

## 任务 4：迁移发现页和底部导航

**文件：**

- 修改：app/src/main/java/com/tracktosearch/ui/screen/main/MainScreen.kt
- 修改：app/src/main/java/com/tracktosearch/ui/screen/discover/DiscoverScreen.kt
- 修改：app/src/main/java/com/tracktosearch/ui/screen/discover/DiscoverComponents.kt
- 修改：app/src/main/java/com/tracktosearch/ui/screen/discover/DiscoverSections.kt
- 修改：app/src/main/java/com/tracktosearch/ui/screen/discover/DiscoverSheets.kt

- [ ] **步骤 1：整理底部导航为单一外层表面**

MainScreen.kt 保留导航外壳尺寸、位置、edge-to-edge 和 HazeSourceSelection.Behind.where { source -> source.zIndex < 1f }，将外层 NeumorphicFrostedSurface 改为 AppVisualSurface(kind = VisualSurfaceKind.Glass, glassRole = GlassSurfaceRole.BottomNavigation, sourceSelection = HazeSourceSelection.Behind.where { source -> source.zIndex < 1f })。Glass 模式下只允许这一层调用 hazeGlass；删除 NavTabItem 内部的 appVisualEffect、AppGlassStyles.bottomNavigationItem 和逐 Tab HazeInput.Sources。将 NeumorphicActiveTab 替换为 GlassTabIndicator，Blur 模式仍由分发入口使用现有拟态选中态。

- [ ] **步骤 2：保留导航交互但移除 Tab 拟态绘制**

保留 NavTabItem 的点击、头像、角标、语义、haptic 和已有布局。selectedScale 只作为外层 Compose transform；Glass 下清透使用较弱的选中/焦点反馈，聚焦允许更明确的指示，但不得重新添加单 Tab Glass 或内外阴影。选中指示层使用 primary.copy(alpha = ...) 和普通边框，不采样背景。

- [ ] **步骤 3：迁移 Discover 顶部栏和操作按钮**

保持发现页真实海报布局、横向列表、Hero 内容、评分徽章、标题、年份、观看人数和滚动行为。hazeTopBar 继续只在内容经过顶部栏时启用效果；筛选、排序及返回等圆形操作从 NeumorphicIconButton 改为 AppIconButton，Glass 分支使用 GlassSurfaceRole.CircularControl，Blur 分支保持原拟态组件。

- [ ] **步骤 4：把 Discover 内容表面和 Sheet 排除出 Glass**

发现页海报卡片、社区卡片、Hero 内容容器和 DiscoverSheets.kt 的底部 Sheet 改用 AppVisualSurface(kind = VisualSurfaceKind.Content/Modal) 或现有普通 Sheet 容器。Glass 模式下这些调用不得出现 hazeGlass、GlassHighlight、neumorphicOuterShadow 或 neumorphicInnerShadow；Blur 模式保留原有视觉。

- [ ] **步骤 5：编译并提交发现页迁移**

运行：

~~~bash
./gradlew.bat :app:compileDebugKotlin --no-daemon --console=plain
rg -n "bottomNavigationItem|NeumorphicActiveTab|appVisualEffect" app/src/main/java/com/tracktosearch/ui/screen/main/MainScreen.kt
git diff --check
~~~

预期：编译成功，导航只剩外层材质分发和普通指示层。提交：

~~~bash
git add app/src/main/java/com/tracktosearch/ui/screen/main/MainScreen.kt app/src/main/java/com/tracktosearch/ui/screen/discover/DiscoverScreen.kt app/src/main/java/com/tracktosearch/ui/screen/discover/DiscoverComponents.kt app/src/main/java/com/tracktosearch/ui/screen/discover/DiscoverSections.kt app/src/main/java/com/tracktosearch/ui/screen/discover/DiscoverSheets.kt
git diff --cached --check
git diff --cached --name-only
git commit -m "style(discover): 适配独立玻璃导航与发现页"
~~~

## 任务 5：迁移搜索、Watchlist 和标记记录场景

**文件：**

- 修改：app/src/main/java/com/tracktosearch/ui/screen/search/SearchScreen.kt
- 修改：app/src/main/java/com/tracktosearch/ui/component/GlassSearchBar.kt
- 修改：app/src/main/java/com/tracktosearch/ui/screen/traktsearch/TraktSearchScreen.kt
- 修改：app/src/main/java/com/tracktosearch/ui/screen/watchlist/WatchlistScreen.kt
- 修改：app/src/main/java/com/tracktosearch/ui/screen/watchlist/WatchlistCategoryTabs.kt
- 修改：app/src/main/java/com/tracktosearch/ui/screen/markrecord/MarkRecordScreen.kt
- 修改：app/src/main/java/com/tracktosearch/ui/component/ScrollToTopButton.kt

- [ ] **步骤 1：迁移搜索框和顶部操作区**

GlassSearchBar.kt 和 SearchScreen.kt 的搜索输入框统一使用 GlassSurfaceRole.SearchField；输入文本、清除、键盘焦点和搜索历史行为保持不变。TraktSearchScreen.kt 的搜索栏和顶部操作使用同一角色，页面只传 hazeState 和形状，不传折射、高光参数。

- [ ] **步骤 2：拆出结果内容与操作表面**

Search/Trakt Search 的结果海报、标题、评分、年份和信息卡统一使用 VisualSurfaceKind.Content；筛选、清除、滚顶等圆形按钮使用 AppIconButton。任何结果列表项不得单独创建 Glass source/effect。

- [ ] **步骤 3：迁移 Watchlist 多选和分类控件**

Watchlist 顶部栏、多选操作栏、删除/刷新/排序圆形按钮使用 TopBar、DetailAction 或 CircularControl；海报卡片和统计信息保持内容表面。WatchlistCategoryTabs.kt 选中态使用普通半透明指示层，不再为每个分类项采样 Glass。多选、批量删除、Tab 切换和滚动行为不变。

- [ ] **步骤 4：迁移 MarkRecord 和 ScrollToTop**

MarkRecordScreen.kt 顶部返回、筛选和操作按钮使用 AppIconButton；ScrollToTopButton.kt 使用 CircularControl。Blur 模式继续走原有 NeumorphicIconButton 和 Haze blur。

- [ ] **步骤 5：编译、回归测试并提交**

运行：

~~~bash
./gradlew.bat :app:compileDebugKotlin --no-daemon --console=plain
./gradlew.bat :app:testDebugUnitTest --tests 'com.tracktosearch.ui.component.NeumorphicGlassTest' --no-daemon --console=plain
rg -n "hazeGlass|bottomNavigationItem|GlassHighlight|neumorphicOuterShadow|neumorphicInnerShadow" app/src/main/java/com/tracktosearch/ui/screen/search app/src/main/java/com/tracktosearch/ui/screen/traktsearch app/src/main/java/com/tracktosearch/ui/screen/watchlist app/src/main/java/com/tracktosearch/ui/screen/markrecord
~~~

预期：编译和组件行为测试通过，结果内容区没有新增的 hazeGlass 直接调用。提交：

~~~bash
git add app/src/main/java/com/tracktosearch/ui/screen/search/SearchScreen.kt app/src/main/java/com/tracktosearch/ui/component/GlassSearchBar.kt app/src/main/java/com/tracktosearch/ui/screen/traktsearch/TraktSearchScreen.kt app/src/main/java/com/tracktosearch/ui/screen/watchlist/WatchlistScreen.kt app/src/main/java/com/tracktosearch/ui/screen/watchlist/WatchlistCategoryTabs.kt app/src/main/java/com/tracktosearch/ui/screen/markrecord/MarkRecordScreen.kt app/src/main/java/com/tracktosearch/ui/component/ScrollToTopButton.kt
git diff --cached --check
git diff --cached --name-only
git commit -m "style(search): 适配搜索与列表玻璃表面"
~~~

## 任务 6：迁移详情、人物、豆瓣详情和统计场景

**文件：**

- 修改：app/src/main/java/com/tracktosearch/ui/screen/detail/DetailScreen.kt
- 修改：app/src/main/java/com/tracktosearch/ui/screen/detail/DetailComments.kt
- 修改：app/src/main/java/com/tracktosearch/ui/screen/detail/DetailSeasonsSection.kt
- 修改：app/src/main/java/com/tracktosearch/ui/screen/person/PersonScreen.kt
- 修改：app/src/main/java/com/tracktosearch/ui/screen/douban/DoubanItemDetailScreen.kt
- 修改：app/src/main/java/com/tracktosearch/ui/screen/statistics/StatisticsScreen.kt
- 修改：app/src/main/java/com/tracktosearch/ui/screen/discover/DiscoverFilterScreen.kt

- [ ] **步骤 1：迁移详情操作按钮**

Detail、Person、Douban detail 的返回、收藏、分享、更多和季数操作按钮改用 AppIconButton。顶部栏继续使用 hazeTopBar；Glass 下只出现顶部栏和操作按钮的光学表面；Blur 下保持 NeumorphicIconButtonStyle.DetailTopBar 的颜色、阴影和尺寸。

- [ ] **步骤 2：排除内容和预览表面**

海报、标题、评分、年份、观看人数、正文、评论、季数列表、剧照网格和全屏海报预览不得调用 Glass。DetailComments.kt、DetailSeasonsSection.kt 和豆瓣详情中的内容容器使用 VisualSurfaceKind.Content/Modal，确保 Glass 下没有旧拟态装饰。

- [ ] **步骤 3：迁移 Statistics 和 DiscoverFilter**

统计页面和发现筛选页面只给顶部栏、筛选/排序等少量操作控件使用 Glass；图表、筛选项列表、对话框和 Bottom Sheet 使用普通内容/Modal 表面。保持筛选提交、重置和返回行为不变。

- [ ] **步骤 4：编译并提交**

运行：

~~~bash
./gradlew.bat :app:compileDebugKotlin --no-daemon --console=plain
rg -n "NeumorphicIconButton|NeumorphicFrostedSurface|GlassHighlight|hazeGlass" app/src/main/java/com/tracktosearch/ui/screen/detail app/src/main/java/com/tracktosearch/ui/screen/person app/src/main/java/com/tracktosearch/ui/screen/douban app/src/main/java/com/tracktosearch/ui/screen/statistics app/src/main/java/com/tracktosearch/ui/screen/discover/DiscoverFilterScreen.kt
~~~

预期：编译成功，检查结果只包含 Blur 或明确的 Content/Modal 分支。提交：

~~~bash
git add app/src/main/java/com/tracktosearch/ui/screen/detail/DetailScreen.kt app/src/main/java/com/tracktosearch/ui/screen/detail/DetailComments.kt app/src/main/java/com/tracktosearch/ui/screen/detail/DetailSeasonsSection.kt app/src/main/java/com/tracktosearch/ui/screen/person/PersonScreen.kt app/src/main/java/com/tracktosearch/ui/screen/douban/DoubanItemDetailScreen.kt app/src/main/java/com/tracktosearch/ui/screen/statistics/StatisticsScreen.kt app/src/main/java/com/tracktosearch/ui/screen/discover/DiscoverFilterScreen.kt
git diff --cached --check
git diff --cached --name-only
git commit -m "style(detail): 适配详情操作玻璃语言"
~~~

## 任务 7：迁移设置页、登录表面和剩余全局调用点

**文件：**

- 修改：app/src/main/java/com/tracktosearch/ui/screen/settings/SettingsScreen.kt
- 修改：app/src/main/java/com/tracktosearch/ui/screen/settings/SettingsComponents.kt
- 修改：app/src/main/java/com/tracktosearch/ui/screen/login/ActivationLoginScreen.kt
- 修改：任务 1-6 中仍被静态扫描发现的效果调用文件

- [ ] **步骤 1：设置页按内容用途迁移**

设置页顶部栏和返回按钮使用 TopBar/CircularControl；设置列表、开关、主题色/语言入口、缓存卡片和设置分组在 Glass 下使用普通内容表面，不为每个设置项叠加 Glass。AlertDialog、Bottom Sheet 和导入导出弹层继续使用 surfaceVariant 或普通表面。

- [ ] **步骤 2：登录页只保留一个 LoginSurface**

ActivationLoginScreen.kt 的登录表单外层使用 VisualSurfaceKind.Glass 与 GlassSurfaceRole.LoginSurface；输入框、验证码/邀请信息、按钮内部不逐层调用 appVisualEffect。登录成功、访客模式、错误提示和键盘行为保持不变。

- [ ] **步骤 3：清理旧的 Glass 直连和拟态复用**

对生产页面执行：

~~~bash
rg -n "appVisualEffect\(|hazeGlass|GlassHighlight|NeumorphicActiveTab|NeumorphicIconButton\(|NeumorphicFrostedSurface\(" app/src/main/java/com/tracktosearch/ui --glob '*.kt'
~~~

允许的结果只有 Blur 组件内部实现、AppVisualEffect.kt/GlassSurface.kt 的集中分发和明确的 Content/Modal 普通表面调用。页面不得自行传入 chromaticAberrationStrength、lightingIntensity、refractionMultiplier、whitePointDelta 或 scale。

- [ ] **步骤 4：编译、完整单测并提交**

运行：

~~~bash
./gradlew.bat :app:compileDebugKotlin --no-daemon --console=plain
./gradlew.bat :app:testDebugUnitTest --no-daemon --console=plain
~~~

预期：编译和完整 JVM 单测成功。提交：

~~~bash
git add app/src/main/java/com/tracktosearch/ui/screen/settings/SettingsScreen.kt app/src/main/java/com/tracktosearch/ui/screen/settings/SettingsComponents.kt app/src/main/java/com/tracktosearch/ui/screen/login/ActivationLoginScreen.kt
git diff --cached --check
git diff --cached --name-only
git commit -m "style(ui): 完成玻璃场景迁移与拟态隔离"
~~~

## 任务 8：运行时视觉、持久化和性能验收

**文件：**

- 修改：无业务文件；只产生本地截图、gfxinfo 或测试报告，不纳入 Git。
- 验证：任务 1-7 的源码、测试和 APK。

- [ ] **步骤 1：确认设备和构建产物**

运行：

~~~bash
adb devices
./gradlew.bat :app:assembleDebug --no-daemon --console=plain
~~~

预期：至少一个设备状态为 device，并生成 app/build/outputs/apk/debug/app-debug.apk。记录设备的 ro.build.version.release、ro.build.version.sdk、分辨率和密度；如果没有在线设备，只报告源码/编译/单测证据，不把运行时视觉或持久化标记为通过。

- [ ] **步骤 2：验证三种设置和跨重启持久化**

安装 APK 后启动 App，在设置页依次选择 Blur、Glass/清透、Glass/聚焦；每次选择后返回发现页，记录设置摘要文字和实际页面材质。选择 Glass/聚焦后杀进程、重新启动，确认仍为 Glass/聚焦；切换 Blur 再切回 Glass，确认仍为聚焦。使用 adb exec-out screencap -p 保存截图，截图和临时报告放在仓库外，不提交。

- [ ] **步骤 3：按发现页真实内容执行视觉检查**

在浅色和深色主题下分别检查发现页首屏和滚动后状态：海报、中文标题、评分、年份和观看人数清晰；顶部栏仅在内容经过其下方时形成玻璃层；底部导航只有一个外层 Glass；选中 Tab 是普通半透明指示层；横向海报列表和 Hero 阴影没有被裁切。继续检查 Search 输入/键盘、Watchlist 多选、Detail 操作按钮、Settings 切换、Login 表单，以及 Dialog/Bottom Sheet/全屏预览仍为稳定普通表面。

- [ ] **步骤 4：采集 Glass 数量和帧耗时证据**

在同一设备、同一数据和同一路径下分别运行 Blur、清透、聚焦，采集 Discover 滚动期间的：

~~~bash
adb shell dumpsys gfxinfo com.tracktosearch reset
adb shell dumpsys gfxinfo com.tracktosearch framestats > /tmp/tracktosearch-<mode>-framestats.txt
~~~

记录最大可见 Glass 表面数量、滚动时 P90 frame duration/frame overrun、底部导航持续显示时的额外开销。若需要调参，优先减少表面数量和面积，一次只修改一个 token，并重新运行任务 2 或相关页面编译与测试；不得通过运行时自动把用户选择改回 Blur。

- [ ] **步骤 5：最终检查 Git 状态和提交边界**

运行：

~~~bash
git status --short --branch
git log --oneline --decorate -12
git diff --check
~~~

预期：只包含设计提交、计划提交和任务 1-7 的逻辑提交；截图、APK、性能报告、.superpowers/brainstorm/ 和敏感配置不在提交中。只有在 Blur 默认、两种 Glass 选择、页面范围、四语言资源、单测/编译和可用设备运行证据全部具备时，才在最终交付中声明完成。

## 计划自审

- [ ] **规格覆盖度：** 规格第 2 节的非目标由任务 2、4、5、6、7 的 Content/Modal 分发和静态扫描覆盖；第 3-5 节由任务 1-2 覆盖；第 6 节页面矩阵由任务 4-7 覆盖；第 8-10 节由任务 1-8 覆盖。
- [ ] **兼容性：** 旧 glass 没有 glass_variant 时解码为 Clear；未知模式回退 Blur；切换 Blur 不清除已选 variant；设置选择使用单次 DataStore edit。
- [ ] **材质隔离：** Glass 绘制组件不引用拟态阴影、GlassHighlight 或 NeumorphicActiveTab；Blur 继续走现有实现；内容卡片和弹层不调用 hazeGlass。
- [ ] **API 约束：** 使用 GlassOptics.Adaptive、默认 HazeSampling.Default、chromaticAberrationStrength = 0f，按压 scale 不超过 1f，不在页面层暴露底层光学参数。
- [ ] **国际化：** 新增设置和帮助资源同时存在于 values/、values-zh/、values-ja/、values-ko/，并通过 rg 检查键集合。
- [ ] **验证边界：** 单元测试、编译、设备截图、重启持久化和 gfxinfo 证据分别记录，不用静态代码或构建成功替代运行时验收。

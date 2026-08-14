# Haze Glass 双风格视觉语言设计

日期：2026-08-12

状态：待用户审阅

## 1. 背景与目标

TrackToSearch 当前已经接入 Haze 2.0.0-alpha04，并同时保留 Blur 与实验性 Glass。现状是 Glass 主要复用了原有拟态组件的结构和装饰，页面之间也共用一套参数，导致 Glass 没有形成独立的视觉语言，且发现页、详情页、搜索页等不同内容密度的场景没有充分区分。

本设计的目标：

1. 保留 Blur 作为现有稳定材质，继续使用当前 Haze 模糊与拟态体系。
2. 将 Glass 重做为独立的光学玻璃语言，不再依赖拟态阴影和旧的高光装饰。
3. 在设置中提供两种 Glass 细分风格，让用户可以全局选择。
4. 由页面内部根据组件角色自动调参，避免把折射、高光、边缘柔化等技术参数暴露给用户。
5. 以发现页的真实海报密度为主要视觉基准，同时覆盖搜索、Watchlist、详情、设置和登录场景。
6. 以真实设备上的滚动、交互和重启持久化结果作为验收依据。

## 2. 范围与非目标

### 2.1 本次范围

- 全局视觉模式：Blur、Glass。
- Glass 细分风格：清透、聚焦。
- Glass 设置项的持久化、国际化和兼容读取。
- 顶部栏、底部导航、圆形操作按钮、搜索框、少量登录表面等 Glass 场景。
- Glass 参数集中管理、页面角色映射和真实设备验收。

### 2.2 非目标

- 不改变 Blur 的布局、交互、颜色、阴影、噪点或默认行为。
- 不给海报、标题、评分、观看人数和普通内容卡片增加 Glass。
- 不给对话框、Bottom Sheet、全屏海报预览增加 Glass。
- 不在本次正式风格中启用色散或 Full chromatic aberration。
- 不因为设备能力、帧率或 Haze fallback 自动覆盖用户的 Glass 选择。
- 不在未完成真实设备测量前擅自引入固定降采样或性能模式覆盖。

## 3. 设计原则

### 3.1 两套材质语言完全分离

Blur 继续绑定现有拟态组件：背景填充、内外阴影、边框、顶部高光和既有交互结构保持不变。

Glass 不使用以下拟态装饰：

- `NeumorphicFrostedSurface`
- `NeumorphicIconButton`
- `NeumorphicActiveTab`
- `neumorphicOuterShadow`
- `neumorphicInnerShadow`
- `GlassHighlight`

Glass 只表达内容采样、透明 tint、边界、光学深度、边缘响应和交互光照。共享的是行为和布局契约，不是拟态绘制层。

### 3.2 内容优先

发现页的海报颜色、评分徽章和中文信息密度是背景内容，不是玻璃装饰。Glass 表面必须让这些内容仍然可辨识：

- 海报主体保持清晰。
- 标题、评分、年份和观看人数保持原有对比度。
- 顶部和底部表面只在确实承担导航或操作职责时出现。
- 不为每张海报或每个普通卡片创建独立 Glass。

### 3.3 表面数量受控

底部导航只保留一个外层 Glass 表面。选中 Tab 使用普通半透明指示层，不再让容器和每个 Tab 同时采样。顶部栏和圆形按钮按场景启用，不把所有可点击元素都变成独立 Glass。

### 3.4 用户选择与页面适配分离

用户只选择 Blur、Glass/清透或 Glass/聚焦。页面内部根据组件角色自动选择 tint、specular、ambient response、edge softness 和交互响应；页面调用点不直接调节这些底层数值。

## 4. 用户可见状态模型

### 4.1 类型

保留现有：

```kotlin
enum class VisualEffectMode {
    BLUR,
    GLASS
}
```

新增：

```kotlin
enum class GlassVariant {
    CLEAR,
    FOCUSED
}
```

建议的存储值：

- `visual_effect_mode`: 现有 `blur`、`glass`。
- `glass_variant`: 新增 `clear`、`focused`。

### 4.2 默认与兼容

- 新用户默认 `VisualEffectMode.BLUR`。
- 现有用户保持 `BLUR` 或已有 `GLASS`，不因升级被改写。
- 旧的 `glass` 记录没有 `glass_variant` 时，在内存中按 `CLEAR` 使用，不强制重写用户配置。
- 未知 `visual_effect_mode` 仍回退到 `BLUR`。
- 未知 `glass_variant` 在 Glass 模式下回退到 `CLEAR`，不改变 `visual_effect_mode`。
- 用户从 Blur 切换到 Glass 且没有历史细分选择时使用 `CLEAR`。
- 用户在 Glass 下从 Clear 切换到 Focused，再切回 Blur，之后重新进入 Glass 时保留 Focused。
- 设备性能、API 能力和 fallback 只影响渲染实现，不覆盖持久化选择。

### 4.3 设置交互

视觉效果选择对话框提供三个可选项：

1. Blur
2. Glass - 清透
3. Glass - 聚焦

三项使用同一组互斥选择行。选择 Glass 细分项时应在一次 DataStore edit 中同时保存 `visual_effect_mode = glass` 和对应的 `glass_variant`，避免短暂出现模式与细分值不一致。

所有标题、描述和选项文本通过 `values/`、`values-zh/`、`values-ja/`、`values-ko/` 提供。设置页面只展示用户能理解的“清透”和“聚焦”，不展示 Adaptive、折射强度或采样比例等实现术语。

## 5. Glass 视觉契约

### 5.1 共同参数边界

两种正式 Glass 风格均采用：

- `GlassOptics.Adaptive`。
- Haze Glass 默认性能策略。
- `chromaticAberrationStrength = 0f`。
- 普通内容表面使用 `SurfaceProfile.Squircle` 或由组件几何决定的 `Circle`。
- 底部导航可使用 `SurfaceProfile.Lip` 表达一个整体的抬起边缘，但不叠加拟态阴影。
- 交互只使用轻微 lighting/refraction/white-point response。
- Glass 按压缩放不得大于 `1f`；按压基线为 `0.98f`，放大反馈若需要由外层 Compose transform 单独处理。

### 5.2 初始调参基线

以下为实现时的第一版基线，不是最终视觉验收值。浅色和深色主题沿用各自的 `MaterialTheme.colorScheme.surface`，表中 alpha 为起始范围，真实设备截图后只逐项调整一个变量。

| 组件角色 | 清透 | 聚焦 | 说明 |
|---|---|---|---|
| 顶部栏 tint | 浅色 0.06-0.10；深色 0.10-0.16 | 浅色 0.10-0.16；深色 0.16-0.24 | 全宽、无圆角容器；仅在内容经过其下方时形成明确玻璃层 |
| 顶部栏 specular | 0.18-0.26 | 0.28-0.38 | 不画独立高光渐变 |
| 顶部栏 ambient | 0.28-0.36 | 0.38-0.48 | 让文字从复杂海报背景中脱离 |
| 顶部栏 edge softness | 2-6dp | 4-8dp | 保持顶部边界柔和，不产生卡片感 |
| 圆形操作按钮 tint | 浅色 0.08-0.14；深色 0.12-0.18 | 浅色 0.14-0.22；深色 0.20-0.30 | 筛选、排序、返回、收藏、分享等操作 |
| 圆形操作 specular | 0.22-0.32 | 0.36-0.48 | 聚焦只强化边缘和按压，不引入色散 |
| 圆形操作 edge softness | 3-5dp | 4-7dp | 形状边界与实际圆形一致 |
| 底部导航 tint | 浅色 0.32-0.44；深色 0.20-0.30 | 浅色 0.42-0.56；深色 0.28-0.40 | 一个整体外壳，不能拆成四个独立 Glass |
| 底部导航 specular | 0.26-0.36 | 0.42-0.56 | 聚焦强化导航外壳的层次 |
| 底部导航 ambient | 0.34-0.44 | 0.46-0.58 | 选中项用普通半透明指示层表达 |
| 底部导航 edge softness | 8-12dp | 10-16dp | 配合整体胶囊形状 |
| 搜索框 tint | 浅色 0.10-0.18；深色 0.14-0.22 | 浅色 0.18-0.28；深色 0.22-0.34 | 输入框本身不再叠加拟态阴影 |
| 详情操作 tint | 浅色 0.08-0.14；深色 0.12-0.20 | 浅色 0.14-0.22；深色 0.20-0.30 | 只覆盖操作按钮，不覆盖海报和正文 |
| 登录表面 tint | 浅色 0.36-0.48；深色 0.28-0.40 | 浅色 0.46-0.60；深色 0.36-0.50 | 一个可读的表单表面，输入框保持普通透明/实色填充 |

### 5.3 交互基线

清透：

- hover/focus lighting intensity：约 `0.15-0.22`。
- press lighting intensity：约 `0.60-0.75`。
- press refraction multiplier：约 `1.02-1.04`。
- press white-point delta：约 `0.01-0.02`。
- press scale：`0.985-0.98`。

聚焦：

- hover/focus lighting intensity：约 `0.24-0.34`。
- press lighting intensity：约 `0.78-0.95`。
- press refraction multiplier：约 `1.04-1.08`。
- press white-point delta：约 `0.02-0.04`。
- press scale：`0.98`。

交互效果只提供状态反馈，不增加点击、焦点、键盘或无障碍语义；行为仍由现有 Compose modifier 提供。减少动态效果时遵循系统策略，抑制不必要的光照和变换。

## 6. 页面适配矩阵

| 场景 | Glass 使用范围 | 清透 | 聚焦 |
|---|---|---|---|
| Discover | 顶部栏、筛选/排序圆形按钮、底部导航外壳 | 顶部和导航更通透，海报保持原样 | 底部导航和选中项分层更明确 |
| Trakt Search / Search | 搜索框、必要的顶部操作区 | 搜索框轻薄，减少对结果海报的干扰 | 输入边界和按压反馈更明确 |
| Watchlist / MarkRecord | 顶部栏、多选时的操作栏 | 低 tint、弱边缘 | 多选操作栏有更强分离感 |
| Statistics / DiscoverFilter | 顶部栏和少量操作控件 | 内容区域保持干净 | 操作控件边界更清楚 |
| Detail / Person / Douban Detail | 海报上的返回、收藏、分享等圆形按钮 | 更透明、更少存在感 | 边界和交互光照更明显 |
| Settings | 顶部栏和视觉效果设置入口 | 设置列表保持普通 Material 表面 | 选择状态更明确 |
| Activation Login | 一个独立表单 Glass 表面 | 轻薄、弱边缘 | 表单容器层次更清晰，输入框不单独套 Glass |
| Dialog / Bottom Sheet / 全屏预览 | 不使用 Glass | 稳定实色或高不透明度表面 | 稳定实色或高不透明度表面 |
| 海报、标题、评分、观看人数 | 不使用 Glass | 保持原样 | 保持原样 |

Discover 的特殊规则：

- 继续使用 edge-to-edge 内容结构。
- 顶部栏在没有内容经过其下方时保持沉浸透明；内容滚动到顶部栏下方后才启用对应 Glass 层。
- 底部导航只采样其后方页面内容，保留 `HazeSourceSelection.Behind` 的 z-index 过滤，避免自采样。
- 不给每个导航 Tab 单独应用 `hazeGlass`。
- 选中 Tab 使用普通半透明胶囊指示层，不使用 `NeumorphicActiveTab`。

## 7. 代码结构与职责边界

### 7.1 状态与主题

- `VisualEffectMode.kt`：保留 Blur/Glass，新增 `GlassVariant` 和对应的 composition local。
- `ThemeStorage.kt`：新增 `glass_variant` DataStore key、StateFlow 和原子保存方法。
- `Theme.kt`：向主题树提供 `LocalVisualEffectMode` 与 `LocalGlassVariant`。
- 设置 ViewModel/Screen/Dialog：消费状态并通过 string resources 展示三项选择。

### 7.2 效果分发

`AppVisualEffect.kt` 继续作为 Haze 底层分发入口：

- Blur 分支调用现有 `hazeBlur` 和调用点的 Blur style。
- Glass 分支调用 `hazeGlass` 和当前角色的 Glass style。
- Glass 使用 `HazeSampling.Default` 或 alpha04 当前可用的默认策略；不要在分发层偷偷根据设备自动改写用户风格。

### 7.3 Glass 样式

`AppGlassStyle.kt` 按组件角色集中生成样式，至少包含：

- `topBar`
- `bottomNavigation`
- `control`
- `circularControl`
- `detailAction`
- `loginSurface`

样式内部根据 `GlassVariant` 和当前主题决定起始 token。页面只传角色、颜色来源、形状和交互源，不传折射、高光、边缘和色差数值。

### 7.4 组件分叉策略

行为和布局可以复用，但 Glass 绘制必须与拟态绘制分叉：

- Blur 继续使用现有 `Neumorphic...` 组件。
- Glass 使用不带拟态阴影和 `GlassHighlight` 的 Glass 专用表面/按钮实现。
- 必要时由一个行为入口根据 `LocalVisualEffectMode` 选择两套内部实现，但不要在 `Neumorphic...` 内部加入大量 `if (GLASS)` 分支。
- Glass 专用实现只负责玻璃边界和光学样式，点击、焦点、语义和 haptic 继续沿用原有行为契约。

## 8. 测试与验收

### 8.1 源码与单元测试

- 扩展 `VisualEffectModeTest`：默认、未知值、旧 `glass` 值兼容。
- 新增 `GlassVariant` 存储值、未知值和缺省值测试。
- 测试 Blur/Glass 切换不会丢失已选 Glass variant。
- 测试 Glass token 的清透/聚焦关系，以及两者的色差强度均为零。
- 测试设置选择同时保存模式和细分值，且 UI 文本来自四套资源。
- 运行 `git diff --check`，并对涉及 Compose 的模块执行针对性测试和编译。

### 8.2 真实设备视觉验收

在可用设备上分别安装并验证 Blur、Glass/清透、Glass/聚焦：

1. Discover 连续滚动，检查三组横向海报、评分、观看人数和底部导航。
2. Search 输入、清空、键盘显示/隐藏。
3. Watchlist 滚动、多选和 Tab 切换。
4. Detail 快速滑动，并连续点击返回、收藏、分享等按钮。
5. Settings 切换三种模式，重启 App 后确认选择保留。
6. 打开 Dialog、Bottom Sheet、全屏海报预览，确认仍为稳定表面。
7. 开启系统减少动态效果，确认 Glass 不产生明显交互动画。

截图至少覆盖浅色/深色主题和发现页首屏/滚动后状态。静态预览只能用于方向确认，不能代替 Android 运行时验收。

### 8.3 性能验收

以相同设备、相同内容和相同操作路径分别记录 Blur、清透、聚焦：

- 最大可见 Glass 表面数量。
- Discover 滚动和底部导航持续显示时的 P90 CPU frame duration / frame overrun。
- 顶部栏内容进入采样范围时的滚动稳定性。
- 按压、焦点和 Tab 切换期间的额外开销。
- 低性能设备和高分辨率设备的发热、掉帧和视觉降级。

不设脱离设备的统一性能数字；要求记录每台目标设备的证据，并确认主要流程没有持续 frame overrun 或用户可感知卡顿。若需要调性能，一次只改一个变量，优先减少 Glass 表面面积和表面数量，再考虑采样策略。

## 9. 官方依据

- Haze Glass 概览与参数：<https://chrisbanes.github.io/haze/dev/effects/glass/>
- Glass 性能指南：<https://chrisbanes.github.io/haze/dev/glass/performance/>
- Haze API 索引：<https://chrisbanes.github.io/haze/dev/api/>

依据要点：优先使用 Adaptive optics 和默认性能模式；完整屏幕工作负载后再调性能；面积、表面数量、变化中的背景、复杂光学和交互光照都会增加成本；固定参数只在有明确视觉或测量收益时使用。

## 10. 完成定义

本设计对应的实现只有在以下条件全部满足后才算完成：

- Blur 仍是默认模式且视觉回归通过。
- Glass/清透和 Glass/聚焦均可从设置选择并跨重启保留。
- Glass 路径不再使用拟态阴影、旧高光和拟态活动 Tab。
- Discover 及其他矩阵场景按角色使用 Glass，内容卡片和弹层范围保持不变。
- 四种语言资源同步。
- 针对性测试、编译和真实设备截图/性能证据齐全。
- 设计与实现改动按逻辑拆分提交，不混入预览图片、构建产物或敏感配置。

# Backdrop 场景玻璃设计

日期：2026-08-17

状态：待用户审阅

## 1. 背景与目标

TrackToSearch 已经同时接入成熟的 Haze 模糊和实验性的 Haze Glass。当前 Glass 仍由 `hazeGlass` 绘制，且部分组件继续沿用旧的拟态结构；试点页虽然已经有 Backdrop 原型，但真实组件和正式 Glass 路径尚未统一。

本设计把两种模式明确拆开：

1. 模糊模式继续使用现有 Haze 模糊和拟态组件，视觉、布局和交互保持现状。
2. 玻璃模式完全脱离 `haze-glass`，使用 `io.github.kyant0:backdrop:2.0.0` 的 `LayerBackdrop`、`drawBackdrop` 和独立的场景 token。
3. 底部导航栏、顶部栏、搜索框、圆形按钮、详情按钮分别拥有独立的 blur、lens、深度、色散、高光和投影参数。
4. 底部导航采用已确认的 A 方案：选中指示器作为独立视觉层跨 Tab 滑动；按压时只放大内容层和指示器，采样层保持稳定。
5. 试点页直接复用正式 Backdrop token 和交互实现，使试点效果可以代表真实页面。

本设计不改变网络、缓存、数据协议或页面业务行为。

## 2. 范围与非目标

### 2.1 本次范围

- 将正式 Glass 表面从 `hazeGlass` 迁移到 Backdrop。
- 保留 Blur 分支的 `hazeBlur`、`NeumorphicFrostedSurface` 和 `NeumorphicIconButton`。
- 新增 Backdrop host，负责屏幕内容采样层的生命周期和作用域传递。
- 新增按组件角色集中管理的 Backdrop token。
- 覆盖底部导航栏、顶部栏、搜索框、圆形按钮和详情按钮。
- 迁移现有 Login、Discover、Search、Watchlist、Detail、Settings 等 Glass 调用点，使玻璃模式没有 Haze Glass 旁路。
- 更新 DEBUG 玻璃引擎试点页，展示五类场景和底栏 A 动效。
- 保留现有 `VisualEffectMode`、`GlassVariant` 的持久化和设置兼容性；Glass variant 只调节强弱，不改变场景的基础 lens 数值。
- 增加 token、模式分发、交互和设置兼容测试，并完成真实设备视觉验收。

### 2.2 非目标

- 不改变 Blur 模式的视觉、布局、阴影、拟态高光或成熟 Haze 参数。
- 不给普通海报、标题、评分、观看人数或内容卡片增加 Glass。
- 不把 Dialog、Bottom Sheet、全屏海报预览改成 Glass；这些表面继续使用稳定的 Material 表面。
- 不新增第三种全局视觉模式。
- 不把 blur、折射高度、折射量等底层参数暴露给普通用户设置。
- 不用设备性能判断覆盖用户选择；性能优化按设备证据逐项调整。
- 不改变现有业务点击、导航、收藏、筛选、搜索和返回语义。

## 3. 已确认的视觉方向

### 3.1 两套材质语言

Blur 和 Glass 共享页面布局和行为契约，但不共享绘制装饰：

- Blur：继续走现有 Haze 模糊和拟态表面。
- Glass：只使用 Backdrop 采样、blur、lens、边界、高光和投影。
- Glass 不调用 `NeumorphicFrostedSurface`、`NeumorphicIconButton`、拟态内外阴影或 Haze 的 `GlassStyle`。
- Glass 的选中指示器不再嵌套第二个采样表面，而是独立的半透明填充、边框和高光层。

### 3.2 设计单位

矩阵中的数值沿用试点页和用户截图中的逻辑数值。代码内部使用 `Dp` token，在 Backdrop effect block 中统一通过当前 density 转换为 API 所需的像素值；页面调用点不自行转换，也不再向用户显示 `px` 调参控件。

### 3.3 五类场景参数

以下是已确认矩阵的第一版正式基线。`highlight` 使用“宽度 / 强度”表示；`shadow` 使用“半径 / 透明度”表示。浅色和深色主题可以共享几何和 lens 数值，只由填充、边框和投影颜色适配主题。

| 角色 | blur | refractionHeight | refractionAmount | depth | chromatic | highlight | shadow |
| --- | ---: | ---: | ---: | --- | --- | --- | --- |
| BottomNavigation | 20 | 8 | 36 | 开启 | 开启 | 1.2dp / 0.52 | 26dp / 0.38 |
| TopBar | 20 | 8 | 36 | 开启 | 开启 | 0.9dp / 0.36 | 18dp / 0.28 |
| SearchField | 14 | 8 | 26 | 开启 | 开启 | 0.9dp / 0.46 | 12dp / 0.22 |
| CircularControl | 12 | 6 | 18 | 开启 | 关闭 | 0.7dp / 0.76 | 14dp / 0.32 |
| DetailAction | 12 | 6 | 18 | 开启 | 关闭 | 0.8dp / 0.58 | 16dp / 0.28 |

补充规则：

- BottomNavigation 的高光和投影最有重量，但只允许一个外层采样表面。
- TopBar 使用同一组 lens 以保持上下导航的光学连续性，高光和投影降低以保护标题可读性。
- SearchField 的 blur 和 refractionAmount 降低，焦点态通过边界和内容层反馈表达，不改变控件尺寸。
- CircularControl 的高光最高，用于确认短促动作；色散关闭，避免小面积控件产生彩边噪声。
- DetailAction 与圆形按钮共享 lens，但高光降低，文字优先于镜面效果。
- `GlassVariant.CLEAR` 使用表中基线；`GlassVariant.FOCUSED` 只提升填充、高光和按压 lighting，在安全上限内保持表中 blur、折射和开关不变。
- `LoginSurface` 是现有兼容角色，不属于本次五类核心矩阵；迁移时使用独立的表单 Backdrop profile，优先保证输入可读性，不复用五类控件的高光强度。

## 4. 交互设计

### 4.1 底部导航 A 方案

底部导航由三层组成：

1. 页面内容采样层：由 `LayerBackdrop` 捕获底部导航后方内容。
2. 一个外层 Backdrop 玻璃栏：固定尺寸和圆角，不随按压缩放。
3. 一个独立指示器和图标内容层：指示器通过 `Animatable` + spring 在 Tab 槽位之间移动。

切换 Tab 时：

- 指示器以连续的横向 spring 位移移动到目标槽位。
- 当前图标和标签更新选中颜色与高光。
- 不创建每个 Tab 的独立 `drawBackdrop`，避免重复采样和自采样。

按压 Tab 时：

- 只对图标、标签和独立指示器应用 press scale / lighting。
- 外层 Backdrop 的采样坐标、尺寸和背景内容保持稳定。
- 交互缩放放在 Backdrop 的内容 layer block 或外层内容 transform 中，不能把承载采样的 host layer 一并缩放。
- 遵循系统减少动态效果策略；减少动态效果时保留选中态和可读的静态指示器。

### 4.2 其他组件

- TopBar：返回、筛选等图标按压时只放大图标内容，顶部玻璃层不移动。
- SearchField：获得焦点时提升边界高光和内容层对比；按压搜索动作时只响应图标和尾部操作区。
- CircularControl：按压时内容层短促放大，松开使用 spring 回弹；尺寸不变化，触控区域保持稳定。
- DetailAction：按钮文字和图标一起响应，但文字基线不能因为高光或 transform 抖动。
- 所有交互组件继续沿用现有 `MutableInteractionSource`、clickable、无障碍语义和 haptic 行为；Backdrop 只消费交互状态，不重新实现业务点击。

## 5. 组件与架构

### 5.1 Backdrop host

新增一个屏幕级 Backdrop host，职责是创建和传递采样层：

- 使用 `rememberLayerBackdrop` 创建稳定的 `LayerBackdrop`。
- 对承载真实内容的根布局使用 `Modifier.layerBackdrop(backdrop)`。
- 在 host 的 `onDraw` 中先绘制主题背景，再绘制内容，避免玻璃区域之外出现空洞。
- 通过 CompositionLocal 或明确的 surface scope 将当前 `LayerBackdrop` 提供给 Glass 组件。
- 页面只创建一个 host；局部需要隔离采样时才创建明确的子 host，并在 spec/代码中说明原因。
- 保留现有 Haze source 给 Blur 分支使用；同一页面可以同时挂载 Haze source 和 Backdrop layer，但两条绘制路径不能互相调用。

### 5.2 Backdrop surface

新增或重构 Glass 专用表面入口，建议职责分为：

- `BackdropGlassToken`：保存场景的 lens、颜色、高光、投影、交互参数。
- `BackdropGlassTokens.forRole(role, variant, theme, scene)`：集中解析角色参数。
- `BackdropGlassSurface`：调用 `drawBackdrop`，负责 shape、effect 顺序、高光、投影、边框和内容。
- `BackdropGlassIconButton`：负责圆形控件的点击区域和内容层 press transform。
- `BackdropGlassTabIndicator`：只负责普通半透明指示器和动画，不采样。
- `AppVisualSurface` / `AppIconButton`：继续作为上层统一分发入口，Blur 走旧实现，Glass 走 Backdrop 实现。

`drawBackdrop` 的 effect 顺序固定为：

1. 色彩滤镜或 vibrancy（如果角色 token 启用）。
2. `blur`。
3. `lens`。

`lens` 使用角色 token 的 `refractionHeight`、`refractionAmount`、`depthEffect` 和 `chromaticAberration`。圆角或圆形角色使用明确的 `RoundedCornerShape`，不允许把任意 Shape 静默传给需要圆角形状的 API。

### 5.3 分发与兼容

现有 `VisualEffectMode` 和 `GlassVariant` 保留：

- `BLUR`：继续使用 `hazeBlur`，保留 `HazeSampling.Adaptive` 和现有 Haze style。
- `GLASS`：只使用当前 Backdrop host 和 `BackdropGlassToken`。
- 当 Glass 没有可用 host 时，使用确定性的普通半透明填充和边框作为 fallback；fallback 不调用 `hazeGlass`。
- `appVisualEffect` 的 Glass 分支迁移为 Backdrop effect，旧的 `glassStyle: GlassStyle?` 参数和 Haze Glass transform 参数在所有调用点迁移完成后删除。
- 当所有正式 Glass 调用点清理后，移除 `haze-glass` 依赖、`dev.chrisbanes.haze.glass.*` imports 和仅服务于 Haze Glass 的样式代码。

### 5.4 试点页

`GlassEnginePilotScreen` 改为正式 token 的可视化验证页：

- 场景覆盖 BottomNavigation、TopBar、SearchField、CircularControl、DetailAction。
- Blur 模式只展示成熟 Haze 模糊。
- Glass 模式只展示 Backdrop，不再允许 Haze + Glass 的无效组合。
- 顶栏、底栏、搜索框、圆形按钮、详情按钮都显示当前固定参数和高光层级。
- 底栏提供真实的 Tab 切换和按压演示，保证用户能看到 A 方案的指示器移动和按压折射反馈。
- 调参控件仅保留 DEBUG 使用，并修改为角色 token 的临时覆盖；正式页面不得依赖试点状态。

## 6. 页面迁移矩阵

| 页面/组件 | Glass surface | Backdrop host | 备注 |
| --- | --- | --- | --- |
| Main / Discover | TopBar、BottomNavigation、CircularControl | 页面根内容 | 底栏只采样后方内容，保留 z-index 过滤语义 |
| Search / TraktSearch | TopBar、SearchField、CircularControl | 搜索结果根内容 | 输入、清空、键盘行为保持不变 |
| Watchlist | TopBar、BottomNavigation、CircularControl、DetailAction | 列表根内容 | Tab 指示器不采样 |
| Detail / Person / Douban Detail | TopBar、CircularControl、DetailAction | 详情滚动内容 | 海报和正文不包 Glass |
| Settings | TopBar、设置入口需要的操作控件 | 设置根内容 | 对话框继续 Modal 表面 |
| Activation Login | LoginSurface、已有操作控件 | 登录背景/表单 host | 输入框本身不再叠加 Haze Glass |
| Dialog / Bottom Sheet / 全屏预览 | 不迁移 | 不创建 Glass host | 保持稳定 Material surface |

迁移时优先修改统一组件入口和屏幕 host，再处理少量直接调用 `appVisualEffect` 的旧组件；不复制五套页面级实现。

## 7. 状态、国际化与兼容

- `VisualEffectMode` 的存储值和默认值不变：新用户及未知值仍回退到 Blur。
- `GlassVariant` 的存储值和未知值处理不变；Glass 未指定 variant 时回退到 Clear。
- 设置界面继续以用户可理解的 Blur、Glass 清透、Glass 聚焦展示，不暴露 Backdrop、lens、折射和高光术语。
- 如试点页或正式页面新增用户可见文字，使用 `stringResource`，同步 `values/`、`values-zh/`、`values-ja/`、`values-ko/`。
- 不修改旧 DTO、数据库或网络字段。

## 8. 测试与验收

### 8.1 单元与静态测试

- 新增或扩展 Backdrop token 测试，锁定五个角色的基础 blur、折射高度、折射量、depth 和 chromatic 值。
- 测试五个角色的高光、投影和交互参数存在且范围合法。
- 测试 Clear/Focused 只改变允许的强度字段，不改变基础 lens 表。
- 测试 Blur/Glass 分发：Blur 使用旧 Haze 路径，Glass 不构造 Haze `GlassStyle`。
- 测试未知模式、variant 和缺失 host 的 fallback。
- 对源码执行 `rg` 审计：正式 Glass 路径不存在 `hazeGlass`、`dev.chrisbanes.haze.glass` 或拟态 Glass 分支。
- 运行 `git diff --check`、针对性 Kotlin 编译和组件测试。

### 8.2 Compose/交互测试

- 底栏初始选中态正确，点击其他 Tab 后指示器目标槽位正确。
- 底栏按压只改变内容层/指示器状态，外层表面尺寸和 host 不改变。
- 顶栏、搜索框、圆形按钮和详情按钮的按压状态可观察、可恢复，点击语义不变。
- Modal 表面在两种视觉模式下仍使用稳定表面。
- 设置三项视觉选择仍能原子保存并跨重启恢复。

### 8.3 真实设备验收

在可用 Android 设备上分别验证 Blur、Glass/Clear、Glass/Focused：

1. Discover 连续滚动，观察顶部栏、底部导航、海报和评分可读性。
2. 点击底栏各 Tab，确认 A 指示器连续移动；按住图标，确认采样背景不跟着缩放。
3. Search 输入、清空、显示/隐藏键盘，观察 SearchField 的焦点高光。
4. Watchlist Tab、多选和滚动，观察顶部/底部 Glass 表面数量和层级。
5. Detail 快速滑动并点击返回、收藏、分享和详情动作。
6. Settings 切换三种选项并重启，确认状态保留。
7. 打开 Dialog、Bottom Sheet 和全屏海报预览，确认没有被迁移成 Glass。
8. 开启系统减少动态效果，确认静态指示器和可读性保留。

截图至少覆盖浅色/深色主题、Discover 首屏/滚动后、底栏三种选中态和五类试点场景。构建成功不代替设备视觉验收。

### 8.4 性能证据

用同一设备、同一内容和同一路径比较 Blur、Glass/Clear、Glass/Focused：

- 记录最大可见 Backdrop surface 数量。
- 记录 Discover 滚动和底部导航持续显示时的帧耗时/掉帧证据。
- 记录底栏切换、搜索焦点和按钮按压期间的额外开销。
- 优先通过减少 surface 数量和采样面积优化，再调整采样策略；一次只改变一个变量。

## 9. 官方依据

- Backdrop GitHub/API：<https://github.com/kyant0/backdrop>
- 项目现有试点页中的 `rememberLayerBackdrop`、`layerBackdrop`、`drawBackdrop`、`blur`、`lens`、`Highlight`、`Shadow` 用法。
- Backdrop 官方示例的 `layerBlock` 交互缩放原则，以及 LiquidBottomTabs 的独立指示器和 spring 动画结构。
- Haze 当前稳定 Blur 用法和项目已有 Haze 2.0.0-alpha04 约束，仅作为 Blur 分支的兼容依据。

## 10. 完成定义

本设计对应的实现只有在以下条件全部满足后才算完成：

- Blur 模式仍使用成熟 Haze，视觉回归通过。
- Glass 模式没有 `hazeGlass` 运行路径，正式 Glass surface 全部由 Backdrop 或普通 fallback 绘制。
- 五类角色使用独立的基础 lens、高光和交互 token，数值与本 spec 一致。
- 底栏 A 方案的选中指示器可跨 Tab 动画，按压不缩放采样层。
- 试点页和真实页面使用同一套 Backdrop token。
- Glass variant、设置选择、四语言资源和跨重启兼容不回归。
- 针对性测试、编译、真实设备截图和性能证据齐全。
- 设计与实现按逻辑拆分提交，不混入预览文件、构建产物或敏感配置。

# 「我的」页拟态玻璃化改造设计

## 1. 目标与范围

将「我的」页（`WatchlistScreen`）的视觉风格与发现页统一为 C 方案拟态 + 玻璃设计。功能结构保持不变：搜索、筛选、想看/已看切换、电影/剧集 Tab、网格列表、长按多选、进度横幅、空状态全部保留。

同时，将发现页标题栏也严格复刻为网页原型中的吸顶栏样式（含下边框白色高亮）。

## 2. 设计原则

- **不改动功能与交互**：只在现有 UI 上换肤。
- **与发现页同款**：复用 `PageBackground`、`NeumorphicFrostedSurface`、`NeumorphicIconButton` 等已有组件。
- **严格复刻原型吸顶栏**：背景、模糊、标题字号字重、图标按钮、下边框白色高亮均与 `.superpowers/glassmorphism-prototype/index.html` 一致。
- **背景连续**：4 个主页面共享同一彩色光晕画布，`currentPage` 依次为搜索=0、发现=1、我的=2、设置=3。

## 3. 页面背景

- `WatchlistScreen` 根节点外包裹 `PageBackground(currentPage = 2, pageCount = 4, isDark = isAppDarkTheme())`。
- 现有 `Scaffold(containerColor = Color.Transparent)` 保持不变，让背景透出。
- 确保 `MainScreen` 中 `PageBackground` 的 `pageCount` 为 4，且当前页索引正确传递。

## 4. 吸顶栏（严格复刻网页原型）

### 4.1 发现页标题栏同步改造

`DiscoverScreen` 的吸顶标题栏需要同步调整为与网页原型一致：

- 位置：`LazyColumn` 外部、页面顶部，sticky。
- 背景：亮色 `rgba(240,244,255,0.55)`，暗色 `#13132A55`（与底部导航一致）。
- 模糊：`HazeMaterials.thin()` + `hazeEffect`。
- 标题：28sp、FontWeight.ExtraBold、letterSpacing = (-0.5).sp，颜色与主题 onSurface 一致。
- 图标按钮：`NeumorphicIconButton(size=42.dp)`，与原型 `.icon-btn` 一致（白色 45% 透明背景、白色 65% 边框、圆 42dp、右下暗阴影 + 左上高光）。
- 下边框白色高亮：在标题栏底部叠加 1dp 白色半透明边框（亮色 alpha=0.5，暗色 alpha=0.15），作为“白色高亮”分隔线。
- 状态栏占位：保持 `statusBarsPadding()`，高度通过 `Spacer` 占位。

### 4.2 「我的」页吸顶栏

与发现页同款，内容区包含：

1. 标题行：`我的` + 右侧操作图标（统计/设置入口，若当前有）。
2. 搜索 + 筛选 + 模式切换行：
   - 搜索框：`NeumorphicFrostedSurface` 或 `GlassSearchBar` 改造为圆角 21dp、白色 55% 背景、内外阴影。
   - 筛选按钮：`NeumorphicIconButton(size=42.dp)`。
   - 想看/已看胶囊切换：与发现页 `CapsuleTabSelector` 同款凹陷容器 + 主题色渐变滑块。
3. 电影/剧集 Tab 行：
   - 保留下划线指示器样式。
   - 选中文字和指示器颜色为 `MaterialTheme.colorScheme.primary`。
   - 未选中文字为 `onSurfaceVariant`。
4. 进度横幅（豆瓣同步 / 一致性检查 / 批量移除 / TMDB 不可用）：
   - 容器改为圆角玻璃卡片（`RoundedCornerShape(12.dp)`）。
   - 保留原有语义色（primary / errorContainer / secondaryContainer）。
   - 文字和图标颜色不变。

## 5. 网格卡片

- 每个 `WatchlistPosterCard` 外包裹拟态阴影：
  - 右下暗阴影：`Color(0xFF6478B4).copy(alpha=0.12f)`，offset 4dp，blur 12dp。
  - 左上高光：`Color.White.copy(alpha=0.55f)`，offset -2dp，blur 8dp。
- 卡片圆角保持 14dp。
- 海报本身不受影响，标题和类型文字样式不变。
- 想看/已看角标、加载遮罩、多选遮罩保持现有逻辑，仅确保多选选中遮罩颜色与主题 primary 一致。

## 6. 空状态

- 空状态引导文案外层使用 `NeumorphicFrostedSurface` 圆角卡片包裹。
- 背景半透明玻璃，文字颜色保持 `onSurfaceVariant`。

## 7. 多选操作栏

- 多选模式下顶部操作栏改为玻璃毛玻璃底栏：
  - 背景：`surface.copy(alpha=0.55f)` + `hazeEffect`。
  - 返回按钮：`NeumorphicIconButton`。
  - 删除按钮保持 error 色，但容器可改为圆角玻璃按钮样式。
  - 取消按钮改为文字按钮或 outline 玻璃按钮。

## 8. ScrollToTopButton

- 与发现页一致，使用已有的 `ScrollToTopButton` 组件，确保其 `hazeState` 正确传递。

## 9. 暗色模式

| 元素 | 亮色 | 暗色 |
|------|------|------|
| 吸顶栏背景 | `Color(0xFFF0F4FF).copy(alpha=0.55f)` | `Color(0xFF13132A).copy(alpha=0.55f)` |
| 下边框 | White alpha 0.50 | White alpha 0.15 |
| 图标按钮背景 | White alpha 0.45 | White alpha 0.08 |
| 图标按钮边框 | White alpha 0.65 | White alpha 0.12 |
| 搜索栏背景 | White alpha 0.55 | White alpha 0.10 |
| 卡片暗阴影 | `#6478B4` alpha 0.12 | Black alpha 0.25 |
| 卡片高光 | White alpha 0.55 | White alpha 0.08 |

## 10. 文件改动清单

- `app/src/main/java/com/tracktosearch/ui/screen/watchlist/WatchlistScreen.kt`
  - 接入 `PageBackground`。
  - 重写顶部吸顶栏为 NeumorphicFrostedSurface + haze。
  - 搜索栏、筛选按钮、模式切换、分类 Tab 换肤。
  - 网格卡片外阴影。
  - 空状态、多选操作栏、进度横幅玻璃化。
- `app/src/main/java/com/tracktosearch/ui/screen/discover/DiscoverScreen.kt`
  - 吸顶标题栏严格复刻原型样式（背景、模糊、标题、图标按钮、下边框高亮）。
- `app/src/main/java/com/tracktosearch/ui/screen/main/MainScreen.kt`
  - 确认 `PageBackground` 的 `pageCount=4` 且当前页索引正确。
- `app/src/main/java/com/tracktosearch/ui/component/NeumorphicGlass.kt`（按需）
  - 如需新增可复用的吸顶栏组件，可提取 `NeumorphicTopBar`。

## 11. 验收标准

- [ ] 「我的」页与发现页共享连续彩色光晕背景，滑动时背景不跳跃。
- [ ] 吸顶栏严格复刻网页原型：标题 28sp ExtraBold、42dp 玻璃图标按钮、底部白色高亮分隔线。
- [ ] 搜索栏、筛选按钮、想看/已看切换条、电影/剧集 Tab 均为拟态玻璃风格。
- [ ] 网格海报卡片有 C 方案双向阴影（右下暗 + 左上高光）。
- [ ] 暗色模式下所有玻璃元素均有对应暗色样式。
- [ ] 现有功能无回归：搜索、筛选、切换 Tab、多选删除、进度横幅、空状态链接均正常。
- [ ] `installDebug` 成功，亮/暗模式截图与原型视觉一致。

# 设置页拟态玻璃化设计

**日期**：2026-07-19
**目标**：将「设置」页改造为拟态玻璃风格，与「发现」「我的」「搜索」三页统一视觉。

---

## 目标

保留现有功能、6 个 Section 分组与所有设置项的内部逻辑，**只做视觉拟态玻璃化**，并接入跨四页连续彩色光晕背景。

---

## 设计原则

1. **沿用现有结构**：6 个 Section 不增不减，组内设置项数量/层级/弹窗全部保留
2. **跨页视觉统一**：标题栏、卡片、按钮、底部导航与「发现」「我的」「搜索」同源
3. **YAGNI**：不引入新的开关/选项/分组；如需新增必须先评审
4. **保留 i18n**：所有新增/修改文字走 stringResource，四种语言同步

---

## 视觉规范

### 标题栏（topBar）

- 复用「发现/我的」标题栏实现：
  - thin 毛玻璃 + 28sp ExtraBold「设置」标题
  - 状态栏沉浸（`statusBarsPadding`）
  - 无白色高亮分隔线
  - 仅继承外层 Box 的 hazeEffect（不重复叠加避免变白）

### 页面背景

- 接入 `PageBackground(currentPage = 3)`，与四页连续光晕
- 由 `MainScreen` 统一管理，本页不单独处理

### Section 容器

- 替换原 `Surface(color = surfaceVariant)` 为 `NeumorphicFrostedSurface`
- 参数：
  - `shape = RoundedCornerShape(20.dp)`
  - `backgroundColor`:
    - 亮色：`Color.White.copy(alpha = 0.55f)`
    - 暗色：`Color.White.copy(alpha = 0.08f)`
  - `borderColor`:
    - 亮色：`Color.White.copy(alpha = 0.75f)`
    - 暗色：`Color.White.copy(alpha = 0.10f)`
  - `elevation = 4.dp`
  - `blurRadius = 16.dp`
  - `hazeStyle = HazeMaterials.thin()`

### Section 标题

- 字体：13sp Medium，主色 (`MaterialTheme.colorScheme.primary`)
- 居左，容器上下 8dp 边距，左右对齐卡片（间距 4dp）
- 替代现有 11sp labelSmall 灰字样式

### 设置项

- 容器内堆叠，项间用 1px 描线分隔：
  - 亮色：`onSurface.copy(alpha = 0.07f)`
  - 暗色：`Color.White.copy(alpha = 0.06f)`
- 单一设置项布局：
  - 左 36×36 圆角 10dp 图标块（透明背景 + 主色 12% alpha）
  - 中间标题 15sp Medium + 副标题 12sp onSurfaceVariant
  - 右侧 Switch / Tag / 箭头 / 文字
- 整体项高 56dp，可点击项按下给 primary 4% 高亮

### 账号资料区

- 单独玻璃卡（与设置项共享容器，外层 shape 一致）
- 内含：
  - 56dp 渐变圆形头像（主色 + 辅色线性渐变，alpha 0.35 投影）
  - 用户名 16sp SemiBold + 登录状态 Tag（success/warning）
  - 副标题 12sp 灰色
- 下方接 1px 分隔线，再列出 Trakt/豆瓣子项

### Switch / Tag / 退出

- Switch：复用现有 `appSwitchColors`
- 状态 Tag：
  - success: 绿色 15% 背景 + 深绿文字
  - warning: 橙色 15% 背景 + 深橙文字
- 退出登录：保留 56dp 标准项高，标题 15sp Medium 红色 (`#E91E63`)，无右侧箭头（暗示破坏性操作）

---

## Section 分组（保持现状）

1. **账号与登录**（账号区 + Trakt + 豆瓣）
2. **同步与数据**（重新同步豆瓣 / 查看失败项 / 检查状态一致性）
3. **外观**（深色模式 / 主题色 / 语言 / 默认标签）
4. **缓存管理**（缓存管理项 + 我的评分统计 + 标记记录）
5. **导入与导出**（导出 JSON / 导入 IMDb / 导入豆瓣）
6. **关于**（检查更新 / 帮助与说明 / 重新引导 / 退出登录）

---

## 组件变更

### 新增/复用组件

- `NeumorphicFrostedSurface`：已在 `NeumorphicGlass.kt` 中，本页直接复用
- 抽离 `SettingsSectionCard` 通用容器：参数 `(title: String, content: @Composable ColumnScope.() -> Unit)`，统一管理 6 个分组的标题+卡片

### 改动文件

- `app/src/main/java/com/tracktosearch/ui/screen/settings/SettingsScreen.kt`：标题栏 + PageBackground 接入 + 6 个分组重构
- `app/src/main/java/com/tracktosearch/ui/screen/settings/SettingsAccountSection.kt`：玻璃卡化（账号资料区）
- `app/src/main/java/com/tracktosearch/ui/screen/settings/SettingsCacheSection.kt`：玻璃卡化（缓存展开逻辑保留）
- `app/src/main/java/com/tracktosearch/ui/screen/settings/SettingsComponents.kt`：可能新增 `SettingsSectionCard` 组件
- `app/src/main/java/com/tracktosearch/ui/screen/main/MainScreen.kt`：确认 `PageBackground` 参数 `currentPage = 3`

### strings.xml

- 新增：`settings_section_account`、`settings_section_sync`、`settings_section_appearance`、`settings_section_cache`、`settings_section_data`、`settings_section_about`（4 语言：en/zh/ja/ko）
- 复用现有「账号与登录」「同步与数据」等（已存在）

---

## 验收标准

1. ✅ 标题栏视觉与「发现/我的」完全一致（thin 毛玻璃、28sp ExtraBold「设置」）
2. ✅ 6 个分组完整保留，所有现有功能可点击
3. ✅ 4 页背景光晕连续（搜索/发现/我的/设置）
4. ✅ 设置项可点击区域有 4% 主色高亮反馈
5. ✅ 退出登录红色文字保留
6. ✅ 亮/暗模式截图无视觉割裂
7. ✅ 4 语言 strings 完整

---

## 实施流程

1. `writing-plans` 写实施计划
2. `ai-self-loop-ui-workflow` 在 Android 上像素级复刻
3. 编译 debug + installDebug
4. 截图亮/暗模式确认
5. 提交

---

## 风险与依赖

- **风险**：Haze 模糊叠加导致背景过白（已在「我的」页验证通过，本页同源）
- **风险**：缓存管理折叠状态需保留（`rememberSaveable`），玻璃卡化后状态不丢失
- **依赖**：`NeumorphicFrostedSurface` 组件已就绪
- **依赖**：`PageBackground` 已配置 `pageCount = 4`

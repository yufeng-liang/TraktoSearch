# 自定义色调（自由调色）实现计划

> 分支：`fix/header-click-through`
> 状态：✅ 全部实现完成（编译通过）
> 日期：2026-08-20

---

## 已确认的设计决策

| # | 决策 | 选择 | 说明 |
|---|------|------|------|
| Q1 | 色域范围 | A | 只控制 primary + 相关 container，复用 scheme 生成器 |
| Q2 | 明暗主题 | A | 一个颜色，light/dark 自动派生 |
| Q3 | 控件形态 | C | 色轮 + 滑杆结合（HSV 圆盘选色 + 亮度滑杆微调） |
| Q4 | 实现方式 | B（降级） | skydoves/colorpicker-compose 1.2.0（Apache-2.0，活跃维护）；godaddy 因 Compose 2026 二进制不兼容已弃用 |
| Q5 | 保存撤销 | A | 新增 DataStore 字段 customAccentArgb: Long?，独立于 MonetAccent 枚举，提供"恢复默认" |
| Q6 | 入口位置 | A | 色调圆盘网格末尾加"自由调色"圆盘（彩虹渐变背景） |
| Q7 | 实时预览 | B | 弹窗内显示 primary/container 色块 + 按钮预览 |
| Q8 | 色值显示 | B | 只读 HEX 显示 |
| Q9 | 调色板升级 | B | monetColorScheme 升级为 Hct.from → SchemeTonalSpot 标准生成 |

---

## 调研结论摘要

**选中依赖：skydoves/colorpicker-compose（godaddy 已因兼容性弃用）**
- Maven: `com.godaddy.android.colorpicker:compose-color-picker-android:0.7.0`
- 许可: MIT
- 组件: HarmonyColorPicker（HSV 圆形色轮） + ClassicColorPicker + AlphaTile
- 风险: 停更 2 年（kotlin 1.7.20 产物），需先在项目编译自测

**备选依赖：skydoves/colorpicker-compose**
- Maven: `com.github.skydoves:colorpicker-compose:1.2.0`
- 许可: Apache-2.0，活跃维护，但无圆形色轮（滑杆风）

**备选：SmartToolFactory 抄源码自研**（Apache-2.0，选择器最全）

**调色板生成：**
- 现有 monetColorScheme（Theme.kt:116）是手写透明度方案（onPrimary 恒白、容器色 copy(alpha)）
- 项目已内嵌 `data/util/mcu`（hct/quantize/score/utils），缺 palettes/scheme 两包
- 升级路线：补 MCU 的 palettes + scheme 包 → Hct.from(seed) → SchemeTonalSpot(hct, dark, contrastLevel) → ColorScheme
- 或引入 `com.materialkolor:material-color-utilities:5.0.0`（MIT，KMP，活跃）

---

## 实现步骤（按依赖顺序）

### 步骤 1：依赖可用性验证 ✅
- [x] gradle/libs.versions.toml 添加 skydoves colorpicker（godaddy 不兼容，已降级）
- [ ] app/build.gradle.kts 引入依赖
- [ ] 编译自测（如果 kotlin/compose 版本冲突则降级 skydoves 或 SmartToolFactory 抄源码）

### 步骤 2：升级 monetColorScheme（Q9-B）✅
- [x] 新增 TonalPalette.kt，改用 Hct → SchemeTonalSpot 标准映射（或引 materialkolor 依赖）
- [ ] Theme.kt: monetColorScheme(seed, dark) 改为 Hct.from(seedArgb) → SchemeTonalSpot → ColorScheme
- [ ] 验证 14 个预设 MonetAccent 色 + 动态壁纸色的 scheme 视觉回归
- [ ] 保留品牌 background/surface 固定值

### 步骤 3：扩展主题存储（Q5-A）✅
- [x] ThemeStorage 新增 customAccentArgb + SettingsViewModel + Theme 参数
- [ ] 扩展 ThemeStorage.accentColor 联合类型：null=壁纸 / 枚举=预设 / custom=自由色
- [ ] SettingsViewModel 新增 setCustomAccentColor(argb: Long?) / clearCustomAccent()

### 步骤 4：色调圆盘加入口（Q6-A）✅
- [x] 色调圆盘网格末尾加自由调色入口（彩虹渐变圆盘）
- [ ] CUSTOM 圆盘背景：rainbow sweepGradient + 边框高亮
- [ ] CUSTOM 圆盘点击行为：打开自由调色弹窗（而非直接 onAccentSelected）

### 步骤 5：自由调色弹窗（Q3-C + Q7-B + Q8-B）✅
- [x] skydoves HsvColorPicker + BrightnessSlider + HEX + 预览 + 恢复默认
- [ ] 内容：HarmonyColorPicker（圆形色轮） + 亮度/BrightnessSlider
- [ ] HEX 只读显示
- [ ] 预览区：primary 色块 + primaryContainer 色块 + Button 样式预览
- [ ] 确定/取消按钮
- [ ] "恢复默认（跟随壁纸）"链接

### 步骤 6：接入与集成 ✅
- [x] AccentColorDialog/SettingsScreen/MainScreen 全链路接通
- [ ] TraktoSearchTheme accentColor 参数扩展支持 customAccent: Color?
- [ ] 主题入口（MainActivity/MainScreen）读取并传递 customAccent
- [ ] 全编译 + 基本功能验证

---

## 项目结构要点

- **主题入口**: `ui/theme/Theme.kt` → TraktoSearchTheme(accentColor, ...)
- **色调定义**: `ui/theme/Color.kt` → enum MonetAccent(labelResId, light, dark)
- **Scheme 生成**: `ui/theme/Theme.kt:116` → monetColorScheme(seed, dark)（将升级）
- **主题存储**: ThemeStorage（DataStore）→ accentColor / visualEffectMode / glassVariant
- **色调弹窗**: `ui/screen/settings/SettingsDialogs.kt` → AccentColorDialog（Q6 改这里）
- **设置页**: `ui/screen/settings/SettingsScreen.kt` → 触发 AccentColorDialog

---

## 潜在风险

1. **godaddy 依赖编译冲突**：kotlin 1.7.20 产物 vs 项目 kotlin 1.9+/2.0+ → 可能需要 exclude 或替换
2. **MCU 版本冲突**：项目已内嵌 data/util/mcu 的 hct/quantize/score（可能与新补的 palettes/scheme 版本不一致）
3. **HarmonyColorPicker 样式**：可能需要自定义颜色/形状以匹配项目设计风格
4. **调色板升级回归**：14 个预设 MonetAccent 色的 scheme 会变化，需全量视觉检查

---

## 文件变更预估

| 文件 | 改动类型 |
|------|----------|
| gradle/libs.versions.toml | 新增依赖版本 |
| app/build.gradle.kts | 新增依赖声明 |
| ui/theme/Color.kt | 新增 CUSTOM_ACCENT 标识 |
| ui/theme/Theme.kt | 升级 monetColorScheme + 扩展 accentColor 参数 |
| ui/theme/ThemeStorage.kt（或 DataStore） | 新增 customAccentArgb 字段 |
| ui/screen/settings/SettingsScreen.kt | 传递 customAccent |
| ui/screen/settings/SettingsDialogs.kt | 色调圆盘加入口 + 新建 CustomAccentDialog |
| data/util/mcu/ | 可能新增 palettes/scheme 包 |

---

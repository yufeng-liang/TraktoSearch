# 自定义搜索源页面化与易用性优化设计

日期：2026-08-19

## 背景与目标

当前自定义搜索源配置是设置页「搜索」分组内的 AlertDialog 弹窗（`CustomSourceEditDialog`），存在以下问题：

- 10 个字段（名称/baseUrl/apiPath/keywordParam/cloudTypes/src/解析模式/5 个 JSONPath）一次性平铺在弹窗里，空间小、滚动困难
- JSONPath 对普通用户是天书，没有任何引导
- 无模板、无分享/导入，用户只能手抄配置
- 内置源与自定义源分散在设置页不同卡片，管理不统一

目标：**弹窗 → 独立页面**，分层设计降低配置门槛（新手引导 + 高级展开），增强易用性。

## GitHub 调研结论

| 参照方案 | 思路 | 本项目借鉴 |
|---|---|---|
| 浏览器自定义搜索引擎管理（Chrome settings） | 名称+快捷字+URL 模板，极简三字段 | 基础字段最小化 |
| PanSou 插件系统 | 预设来源一键"安装"而非"配置" | 内置模板库 |
| rsins 自定义搜索引擎扩展 | 配置导出文件/文本共享传播 | 分享/导入 |
| Degoog meta-search | engine store，0 配置启动 | 模板库形态 |

## 用户分层

- **复制粘贴型**（拿到地址照着填）：模板一键填 + 分步向导引导
- **技术自建型**（自部署 API 精细调参）：高级选项可展开、JSONPath 手动编辑保留
- **发现探索型**（想接入任意 JSON API）：步骤 2 自动解析自动识别

## 页面架构：方案 A 两级导航

```
设置页「搜索」分组 →「搜索源」列表页（统一管理）→ 分步向导编辑页
```

- 列表页与编辑页均为标准页面模板：标题栏 + `Box` + `hazeSource`/`hazeEffect` 毛玻璃吸顶，`Scaffold(contentWindowInsets = WindowInsets(0,0,0,0))` 沉浸
- 设置页原「搜索」分组的 `SearchSourcesItem`/`SearchSourceCard`/`CustomSearchSourceItem`/`SearchSourceAddCard` 列表区替换为单一入口「搜索源管理」，点击进入列表页

## 列表页设计（已获批准）

- 标题栏毛玻璃吸顶，右侧两个圆形按钮：**导入（⤢）** + **新建（+）**
- **内置源区**：渐变图标块 + 名称 + URL + 解析模式彩色徽章（模板解析绿 / 需配置橙）+ 启停 Switch；内置源不可删除、不可分享
- **自定义源区**：图标 + 名称 + URL + 解析模式徽章（JSONPath 粉）+ 操作按钮（测试 ✓ / 编辑 ✎ / 分享 / 删除）+ 启停 Switch
- 底部虚线卡片「＋ 从模板添加搜索源」为常驻入口
- 空状态：无自定义源时显示提示引导从模板添加

### 内置源启停持久化

内置源（pansou/panhub/zreso）的 enabled 状态当前随设置页状态存在；统一管理后需在 `CustomSearchSourceStorage`（或同级新 DataStore key）持久化内置源启停状态，列表页与搜索行为共用同一份状态。

## 分步向导编辑页（已获批准）

3 步向导：**基本信息 → 自动解析 → 确认参数**

### 步骤 1 基本信息

- 字段：名称（必填）、API 地址（必填）
- 从模板库/导入进入时自动填充，显示绿色提示「已应用模板 XXX」

### 步骤 2 自动解析（核心）

- 用户点击「开始解析」，App 用示例关键词（`R.string.search_test_keyword`）自动尝试：
  - 关键词参数变体：`kw`、`q`、`keyword`、`query`
  - API 路径变体：`api/search`、`search`、`/api/search`（相对 baseUrl 拼接）
- 对成功响应识别 JSON 结构，生成并高亮展示：结果列表路径 / 标题路径 / 链接路径 / 网盘类型路径 / 时间路径（即 5 个 JSONPath）
- 展示识别出的关键词参数名与最终尝试成功的完整 URL
- **识别不准入口**：「识别不准？手动调整 JSONPath →」跳到手动编辑（保留现有 5 字段 JSONPath 输入）
- 全部变体失败：降级到手动配置视图（高级选项全展开，等同现有弹窗字段集）
- 该步骤完成后自动推断 `parseMode`（PanSou 模板 / Zreso 模板 / custom），可手动改

### 步骤 3 确认参数

- URL 模板预览（等宽字体）+ 关键参数汇总（解析模式/网盘类型/来源/名称）
- 「展开全部高级参数」可微调（cloudTypesParam/Value、srcParam/Value、parseMode、JSONPath）
- 底部「测试搜索源」+「保存」

### 向导流程优化

- 模板应用（参数完整）时**跳过步骤 1/2**，直接进步骤 3 确认；配置导入时同样直接进步骤 3
- 保存后回列表页，Snackbar 提示，列表项可直接测试

## 模板库（已获批准）：BottomSheet + 内置硬编码

- 列表页「+」点击弹出半屏 BottomSheet，网格布局：
  - **PanSou 公共源**（so.252035.xyz，模板解析，一键填好）
  - **PanSou 自建**（只需改地址）
  - **Zreso 资源库**
  - **空白自定义**（虚线卡片，走分步向导）
- 底部「有配置想分享？从文本导入 →」快捷通道
- 模板点击 → 参数完整模板（PanSou 公共源/Zreso 等）直接跳**步骤 3 确认参数**（名称可改）；「空白自定义」跳步骤 1 走完整向导
- 模板定义：`data/` 下硬编码常量列表（`CustomSearchSourceTemplate`：名称、描述、图标、预填字段），离线可用，新模板随版本更新

## 分享 / 导入（已获批准）

### 分享

- 入口：自定义源列表项分享按钮 + 编辑页顶部操作菜单；内置源不可分享
- 弹层显示编码配置文本 + 「复制」+「分享到其他应用」（系统分享面板）
- **格式**：`TRS-SOURCE:1:` 前缀 + Base64(JSON)，可读可校验

### 导入

- 入口：列表页 ⤢ 按钮 + 模板库底部「从文本导入」
- 流程：粘贴文本 → 校验前缀+Base64 解析 → 预览确认（名称/API 地址/解析模式/分享者）→ 确认导入保存回列表
- 解析失败：友好提示「不是有效的配置文本」
- **冲突处理**：检测到同名/同地址配置时黄色提示「同名配置已存在」，导入前可改名，避免覆盖

## 数据模型变更

`CustomSearchSource` 保持不变（字段已覆盖全部配置），新增：

```kotlin
// 分享文本编解码（新文件 ShareCodec 或并入 Storage）
fun encodeShareText(source: CustomSearchSource): String  // "TRS-SOURCE:1:" + Base64(JSON)
fun decodeShareText(text: String): CustomSearchSource?    // 校验失败返回 null

// 模板（新文件 SearchSourceTemplates）
data class SearchSourceTemplate(
    val id: String, val name: String, val description: String,
    val icon: String, val defaults: CustomSearchSource
)
val builtInTemplates: List<SearchSourceTemplate>  // 硬编码
```

持久化不变：DataStore Preferences（`custom_search_sources` / sources_json），无结构变化无需版本迁移。

## 导航与 ViewModel

- 新导航路由：`searchSources`（列表）、`searchSourceEditor`（向导，参数：sourceId 或 templateId 或 importText 模式）
- `SettingsViewModel` 的 customSources 逻辑迁移/拆分为 `SearchSourcesViewModel`（列表）与 `SearchSourceEditorViewModel`（向导，含自动探测逻辑）
- 自动探测逻辑新文件（如 `data/remote/custom/AutoProbe.kt`）：尝试参数/路径变体组合 → 复用 `CustomSearchService` 请求 → `JsonPathParser` 分析结构 → 产出候选路径

## 国际化

- 新增字符串（values/、values-zh/、values-ja/、values-ko/ 四语同步）：页面标题、步骤文案、模板名称/描述、分享/导入文案、冲突提示、自动解析状态文案等
- 现有 `settings_source_*` 系列字符串迁移复用

## 帮助页更新

新增页面后更新 App 内帮助与说明页：搜索源管理说明（模板/向导/分享/导入使用步骤）。

## 测试

- 单元测试：`ShareCodec` 编解码往返、无效文本校验、`AutoProbe` 参数/路径变体生成与 JSON 结构识别（用 PanSou/Zreso 样例响应 fixture）、内置模板预填正确性
- 现有 `CustomSearchService`/`JsonPathParser` 测试保持通过
- UI 验证：模拟器走通 列表 → 模板添加 → 向导三步 → 保存 → 测试；分享文本 → 另一实例导入 → 搜索可用

## 范围外（YAGNI）

- 云端模板库（本次内置硬编码）
- 二维码分享
- 文件导入导出
- 自定义源排序（列表页拖动）——本次不做，保留添加顺序

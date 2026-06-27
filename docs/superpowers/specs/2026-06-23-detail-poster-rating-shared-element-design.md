# 详情页视觉与交互优化设计

## 背景

详情页存在以下三个影响体验的问题：

1. **海报高度反复跳动**：右侧信息（标题、类型、日期、评分等）异步加载，导致右侧高度变化，海报因使用 `fillMaxHeight()` 跟随变化。
2. **评分交互不精确**：当前 `UserRatingBar` 直接点击提交，用户容易误触，且半星选择区域小。
3. **演职员头像无共享元素转场**：从详情页点击演职员头像进入 PersonScreen 时，目标页先显示骨架屏，共享元素找不到目标而失效。

## 目标

- 固定详情页右侧信息区域高度，使海报高度从进入页面起就保持稳定。
- 将评分改为弹窗模式，支持半星精确选择，确认后再提交。
- 修复演职员头像的共享元素转场，使点击头像后头像能平滑过渡到 PersonScreen。

---

## 1. 海报高度固定

### 方案

采用**固定每项信息高度**的方式。在 `DetailHeaderContent` 右侧信息 Column 中，为每一项关键信息设置固定占位高度，即使内容为空也保留对应空间。

### 右侧信息项固定高度规划

| 信息项 | 最小高度 | 空值处理 |
|--------|---------|---------|
| 标题 | 24.dp | 始终显示，不会为空 |
| 原名 | 18.dp | 与原标题相同时保留透明占位 |
| 类型 | 18.dp | 无类型时保留透明占位 |
| 日期/年份 | 18.dp | 无日期时保留透明占位 |
| 时长 | 18.dp | 电视剧或缺失时保留透明占位 |
| 评分区域 | 54.dp（24.dp × 2 + 间距） | 加载完成前已有骨架屏占位 |
| 用户评分行 | 28.dp | 未登录/未评分时保留占位 |
| 已看/想看按钮 | 32.dp | 始终显示 |

### 实现要点

- Row 继续保持 `height(IntrinsicSize.Max)`，使海报高度贴合右侧总高度。
- 每项使用 `Box` 或 `Text` 包裹，添加 `heightIn(min = xx.dp)` 或 `defaultMinSize(minHeight = xx.dp)`。
- 无内容时渲染等高的 `Spacer` 或透明 `Box`，不压缩高度。
- 评分区域保持现有的两行固定高度骨架屏占位。

### 预期效果

右侧信息区域从进入页面起就是固定高度，海报只会在初始数据到达时从骨架态切换一次真实图片，之后高度不再变化。

---

## 2. 评分弹窗

### 方案

将现有的 `UserRatingBar` 点击行为改为打开弹窗，弹窗内支持点击半星选择，用户点击“确定”后再提交。

### 交互流程

1. 用户点击详情页的 `UserRatingBar` 区域。
2. 弹出 `RatingDialog`，标题为“评分”。
3. 弹窗内显示 5 颗星，每颗星分为左右两半：
   - 点击左半边 → 选中 `i * 2 - 1`（半星）
   - 点击右半边 → 选中 `i * 2`（整星）
   - 再次点击当前选中的整颗星 → 取消选中（评分归零）
4. 弹窗实时显示当前分值，例如 “3.5/5” 或 “未评分”。
5. 用户点击“取消” → 关闭弹窗，不提交。
6. 用户点击“确定”：
   - 如果最终值为 `null` 或 0 → 调用 `removeRating()` 移除评分。
   - 如果最终值为 1~10 → 调用 `setRating(rating)` 提交评分。
7. 提交期间弹窗保持打开并显示加载状态，成功后自动关闭。

### 数据流

- UI 层维护临时状态 `selectedRating: Int?`。
- 打开弹窗时，用 `uiState.userRating` 初始化。
- 弹窗内只更新临时状态，不调用 ViewModel。
- 点击“确定”后调用 ViewModel 方法，避免误触提交。

### 弹窗样式

- 使用 `AlertDialog`，与现有“登录引导”、“标记已看”弹窗风格一致。
- 星标使用金色（`Color(0xFFFFC107)`），空星使用 `onSurfaceVariant.copy(alpha = 0.3f)`。
- 弹窗宽度自适应，星标大小 32~36.dp。

---

## 3. 演职员头像共享元素转场

### 问题根因

- `CastCard`（详情页）已设置 `sharedElement(key = "person-avatar-$personId")`。
- `PersonHeaderContent`（PersonScreen）也使用了相同的 key。
- 但 `PersonScreen` 进入时 `uiState.isLoading = true`，先渲染 `PersonSkeletonContent()`，共享元素找不到目标节点，导致转场失效。

### 方案

如果导航时传入了 `profileUrl`，PersonScreen 进入后立即渲染头部区域（至少包含头像），不再等待 API 返回。

### 实现要点

1. 在 `PersonScreen` 中，当 `profileUrl != null` 时：
   - 即使 `uiState.isLoading` 为 true，也直接渲染 `PersonHeaderContent`（或一个仅包含头像的头部）。
   - 头像使用传入的 `profileUrl`，并应用 `sharedElement(key = "person-avatar-$personId")`。
   - 姓名、生日、出生地、简介等其他信息先显示骨架占位。
2. API 返回后，用完整数据更新其他信息；如果头像 URL 与传入的不同，Coil 会自动替换图片。
3. 如果 `profileUrl == null`（演职员无头像），不应用共享元素转场，按原有骨架屏流程加载。

### 边界情况

- 从详情页点击头像时，`onPersonClick` 已传入 `profileUrl`，确保目标页能立即渲染。
- 从其他入口（如搜索、作品列表）进入 PersonScreen 时，如果没有头像 URL，则无转场。
- 共享元素 key 必须全局唯一且稳定，使用 `"person-avatar-$personId"` 已满足要求。

---

## 涉及文件

- `app/src/main/java/com/tracktosearch/ui/screen/detail/DetailScreen.kt`
  - 修改 `DetailHeaderContent` 右侧信息布局，固定每项高度。
  - 将 `UserRatingBar` 点击行为改为打开 `RatingDialog`。
  - 新增 `RatingDialog` composable。
- `app/src/main/java/com/tracktosearch/ui/screen/detail/DetailViewModel.kt`
  - 保持 `setRating()` / `removeRating()` 不变。
  - 不需要新增状态，弹窗状态由 UI 层管理。
- `app/src/main/java/com/tracktosearch/ui/screen/person/PersonScreen.kt`
  - 修改 `PersonScreen` 加载状态处理：有 `profileUrl` 时立即渲染头像头部。
- `app/src/main/res/values/strings.xml`
  - 新增 `detail_rating_dialog_title`、`detail_rating_confirm`、`detail_rating_cancel` 等字符串。

## 错误处理

- 评分提交失败：弹窗关闭，详情页显示原有评分不变（与现有行为一致）。
- PersonScreen API 失败：仍显示传入的头像，错误信息以 Toast 或占位文案展示（保持现有错误处理）。

## 测试建议

- 详情页进入时观察海报高度是否在信息加载过程中保持稳定。
- 测试评分弹窗的半星选择、取消评分、确定/取消流程。
- 测试有头像和无头像演职员的转场表现。
- 验证从全部演职员弹窗（`FullCastCrewSheet`）点击进入 PersonScreen 时，共享元素 key 是否一致。

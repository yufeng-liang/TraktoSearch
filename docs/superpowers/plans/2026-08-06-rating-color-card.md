# 四平台评分颜色卡片实现计划

> **面向 AI 代理的执行说明：** 使用 `subagent-driven-development`，按任务边界派发独立子任务；每个任务完成后检查 diff 和针对性测试，主线程最后做全量整合验证。

**目标：** 在详情页把 IMDb、豆瓣/Metacritic、TMDB、Rotten Tomatoes 固定展示在同一张评分卡中，评分数字按统一的 0-100 分档着色，缺失评分显示灰色 `—`，并保持现有评分来源、顺序和豆瓣条目语义。

**方案：** 新增无 UI 副作用的评分展示逻辑，分别负责 10 分制/百分制归一化和低、中、高、无评分分档。Compose 层将四个固定槽位包进单张 `Surface`，平台图标与名称使用品牌色，只有数值文本使用分档色；详情头部的加载占位沿用相同卡片尺寸。沉浸色为空时回退到主题 `surface`，卡片填充按浅色 33%、深色 20% 与白色混合。

**技术栈：** Kotlin、Jetpack Compose Material 3、Robolectric Compose UI Test、现有 `MultiRatings`/`posterDominantColor`。

---

### 任务 1：新增评分归一化和分档逻辑

**文件：**
- 创建：`app/src/main/java/com/tracktosearch/ui/screen/detail/RatingPresentation.kt`
- 创建：`app/src/test/java/com/tracktosearch/ui/screen/detail/RatingPresentationTest.kt`

- [ ] **步骤 1：先编写失败的纯逻辑测试**

覆盖以下确定行为：

```kotlin
assertThat(normalizeTenPointRating(8.0)).isEqualTo(80.0)
assertThat(normalizePercentRating("88%")).isEqualTo(88.0)
assertThat(normalizePercentRating("88/100")).isEqualTo(88.0)
assertThat(ratingBand(null)).isEqualTo(RatingBand.NONE)
assertThat(ratingBand(59.9)).isEqualTo(RatingBand.LOW)
assertThat(ratingBand(60.0)).isEqualTo(RatingBand.MEDIUM)
assertThat(ratingBand(75.0)).isEqualTo(RatingBand.HIGH)
```

- [ ] **步骤 2：运行纯逻辑测试，确认新 API 尚不存在时失败**

运行：

```bash
./gradlew :app:testDebugUnitTest --tests 'com.tracktosearch.ui.screen.detail.RatingPresentationTest' --console=plain
```

预期：FAIL，原因是展示逻辑类型或函数尚未创建。

- [ ] **步骤 3：实现最小展示逻辑**

定义 `RatingBand` 和归一化函数：10 分制乘以 10，百分制解析 `%` 或 `/100` 后限制在 0-100；空白、`N/A`、非法值返回 `null`。分档边界固定为 `<60`、`<75`、其余高分；颜色函数返回设计文档中的浅色/深色色值，无评分使用传入的 `onSurfaceVariant`。

- [ ] **步骤 4：重新运行测试并检查边界**

运行同一测试命令，预期全部通过；同时确认 `git diff --check` 无空白错误。

---

### 任务 2：实现评分卡片和加载占位

**文件：**
- 修改：`app/src/main/java/com/tracktosearch/ui/screen/detail/DetailRatingsDialog.kt`
- 修改：`app/src/main/java/com/tracktosearch/ui/screen/detail/DetailHeaderContent.kt`

- [ ] **步骤 1：保留现有评分语义并重构槽位数据**

`RatingsRow` 继续以 IMDb、豆瓣或 MTC、TMDB、RT 的顺序渲染；当评分为空时创建对应槽位并显示 `—`。`isDoubanItem=true` 且没有豆瓣公开评分时，第二槽位仍显示豆瓣 `—`，不错误回退到 MTC；普通详情没有豆瓣评分时才使用 MTC。

- [ ] **步骤 2：加入评分卡容器**

将两行各两个固定槽位放入单张 `Surface`：圆角 `12.dp`、水平内边距 `12.dp`、垂直内边距 `8.dp`、无阴影；填充色由沉浸色与白色按主题混合，边框为 `1.dp` 白色 35% 透明度。每行固定 `20.dp` 高、行间距 `4.dp`，卡片内容总高度为 `60.dp`。

- [ ] **步骤 3：应用颜色职责**

平台图标与名称保留品牌色；评分数字根据归一化值使用低/中/高分档色，`—` 使用 `onSurfaceVariant`。不修改数值格式、Locale 小数显示或用户评分控件。

- [ ] **步骤 4：同步详情头部的固定高度和加载态**

正式评分卡和骨架占位都放在 `60.dp` 固定区域，加载、缺失、网络返回不会造成标题区跳动；将 `posterDominantColor` 传给评分卡作为沉浸色。

- [ ] **步骤 5：运行受影响测试和编译**

运行：

```bash
./gradlew :app:testDebugUnitTest --tests 'com.tracktosearch.ui.screen.detail.DetailRatingsDialogTest' --console=plain
./gradlew :app:assembleDebug --console=plain
```

预期：测试和 debug APK 均成功；若工具层超时，先检查 Gradle 进程、测试 XML、报告和 APK，再判断结果。

---

### 任务 3：补齐 Compose 行为回归测试

**文件：**
- 修改：`app/src/test/java/com/tracktosearch/ui/screen/detail/DetailRatingsDialogTest.kt`

- [ ] **步骤 1：增加固定槽位和缺失占位测试**

验证全空 `MultiRatings` 仍显示 IMDb、豆瓣/适用的 MTC、TMDB、RT 四个平台及四个 `—`；验证普通详情无豆瓣评分时显示 MTC，豆瓣条目无公开评分时显示豆瓣占位且不显示 MTC。

- [ ] **步骤 2：增加评分分档覆盖**

用 5.9、6.0、7.5 的 10 分制数据覆盖低/中/高档的 UI 构建路径，并保留已有豆瓣替换、Locale 小数测试。颜色数值通过任务 1 的纯逻辑测试锁定，Compose 测试重点验证文字和布局槽位。

- [ ] **步骤 3：运行测试并检查 diff**

运行：

```bash
./gradlew :app:testDebugUnitTest --tests 'com.tracktosearch.ui.screen.detail.DetailRatingsDialogTest' --console=plain
git diff --check
```

---

### 任务 4：集成验收和提交

- [ ] **步骤 1：统一审查所有改动**

确认没有修改 `RatingsRepository`、网络接口、评分值、平台顺序或用户评分写回；确认用户现有 `AGENTS.md` 与 JVM 日志未被暂存。

- [ ] **步骤 2：运行完整验证**

```bash
./gradlew :app:testDebugUnitTest --console=plain
./gradlew :app:assembleDebug --console=plain
adb devices
adb -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk
adb -s <serial> shell cmd package resolve-activity --brief com.tracktosearch
adb -s <serial> shell am force-stop com.tracktosearch
adb -s <serial> shell am start -n <resolved-activity>
adb -s <serial> exec-out uiautomator dump /dev/tty
adb -s <serial> exec-out screencap -p > verification-artifacts/rating-card-light.png
adb -s <serial> logcat -b crash -d
```

在可切换深色主题的设备状态下再抓取一张深色截图，检查评分卡背景、四个平台顺序、缺失占位、浅色香槟金和深色柔香槟的对比度；截图只作为本地验证产物，不提交。

- [ ] **步骤 3：按改动拆分提交**

计划文档：

```bash
git add -f docs/superpowers/plans/2026-08-06-rating-color-card.md
git diff --cached --check
git diff --cached --name-only
git commit -m "docs(douban): 记录评分卡片实现计划"
```

实现通过验证后：

```bash
git add app/src/main/java/com/tracktosearch/ui/screen/detail/RatingPresentation.kt app/src/test/java/com/tracktosearch/ui/screen/detail/RatingPresentationTest.kt
git diff --cached --check
git diff --cached --name-only
git commit -m "feat(detail): 增加评分分档展示逻辑"

git add app/src/main/java/com/tracktosearch/ui/screen/detail/DetailRatingsDialog.kt app/src/main/java/com/tracktosearch/ui/screen/detail/DetailHeaderContent.kt app/src/test/java/com/tracktosearch/ui/screen/detail/DetailRatingsDialogTest.kt
git diff --cached --check
git diff --cached --name-only
git commit -m "style(detail): 优化四平台评分卡片"
```

**完成标准：** 纯逻辑边界测试、Compose 行为测试、完整 debug 单元测试和 debug 构建有实际成功输出；设备安装、启动、UI 树、浅/深色截图和 crash buffer 分别记录，不用单一证据替代其他验收项。

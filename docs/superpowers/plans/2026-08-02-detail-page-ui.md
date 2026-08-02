# 详情页 UI 优化实现计划

> **面向实现者：** 按任务顺序执行，每个逻辑改动先验证，再提交；不要混入工作区已有的 auth、截图、依赖或其他临时文件。

**目标：** 让详情页重试按钮与分享按钮统一为拟态玻璃样式，让长简介明确显示可展开入口并支持整块区域展开/收起，同时通过边框和柔和阴影提升季卡片的背景分离度。

**技术栈：** Jetpack Compose、Material 3、Haze、现有 `NeumorphicIconButton` 和 Compose UI 测试。

### 任务 1：锁定简介与按钮行为

**文件：**

- 修改：`app/src/test/java/com/tracktosearch/ui/component/NeumorphicGlassTest.kt`（若现有测试文件存在则沿用，否则在组件测试目录创建）
- 修改：`app/src/test/java/com/tracktosearch/ui/screen/detail/DetailHeaderContentTest.kt`

- [ ] 为简介短文本、超过三行文本和展开/收起点击写测试。
- [ ] 为禁用的拟态按钮写测试，确认点击不会触发回调。
- [ ] 运行对应测试，确认新增断言在实现前失败，记录失败原因。

### 任务 2：实现详情页 UI 调整

**文件：**

- 修改：`app/src/main/java/com/tracktosearch/ui/component/NeumorphicGlass.kt`
- 修改：`app/src/main/java/com/tracktosearch/ui/screen/detail/DetailScreen.kt`
- 修改：`app/src/main/java/com/tracktosearch/ui/screen/detail/DetailHeaderContent.kt`
- 修改：`app/src/main/java/com/tracktosearch/ui/screen/detail/DetailSeasonsSection.kt`
- 修改：`app/src/main/res/values/strings.xml`
- 修改：`app/src/main/res/values-zh/strings.xml`
- 修改：`app/src/main/res/values-ja/strings.xml`
- 修改：`app/src/main/res/values-ko/strings.xml`

- [ ] 给 `NeumorphicIconButton` 增加默认值为启用的 `enabled` 参数，并同步透明度与点击语义。
- [ ] 用共享按钮替换详情页顶部的重试按钮，保留同步中进度指示器和触感反馈。
- [ ] 去掉简介标题冒号，加入可测量的三行折叠提示和整块点击展开/收起。
- [ ] 为季卡片加入主题边框和低强度拟态外阴影，保持原有布局与展开行为。

### 任务 3：回归验证与提交

- [ ] 运行详情组件测试并确认通过。
- [ ] 运行 `./gradlew :app:assembleDebug`。
- [ ] 连接设备安装并进入详情页，检查重试按钮、简介和季卡片截图。
- [ ] 检查 `logcat -b crash`、`git diff --check` 和 staged 文件列表。
- [ ] 按实际改动创建 Conventional Commit。

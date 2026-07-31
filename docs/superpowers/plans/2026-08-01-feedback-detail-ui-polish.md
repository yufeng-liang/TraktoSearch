# 反馈详情页 UI 优化实施计划

> 面向执行代理：使用 `superpowers:executing-plans` 在当前会话内按顺序执行。

## 目标

将反馈详情页改造成稳定的品牌化会话布局：移除对话末尾的新建反馈按钮，将新建入口放到标题栏；让底部回复栏在多行输入、截图、上传、失败和键盘弹出时保持垂直居中；新反馈提交成功后回到反馈列表并刷新。

## 架构与技术栈

- Android Jetpack Compose + Material 3。
- 继续使用 `FeedbackViewModel` 共享详情、列表、提交和回复状态。
- 不修改反馈 API、DTO 或 Repository 数据协议。
- 新建反馈页从 `Routes.MAIN` 获取共享 ViewModel，使用同一实例刷新反馈列表。

## 任务 1：先补充列表刷新回归测试

**文件：**

- 修改：`app/src/test/java/com/tracktosearch/ui/screen/feedback/FeedbackViewModelTest.kt:45-65`

### 步骤 1：编写失败测试

在现有 `loadList success updates listState to Success` 测试之后新增测试，先返回旧列表，再返回包含新反馈的刷新列表，验证 `loadList(refresh = true)` 会替换旧列表。

```kotlin
@Test
fun `loadList refresh replaces previous items`() = runTest {
    val oldItem = FeedbackListItem("old", "BUG", "old", null, "PENDING", 1700000000L)
    val newItem = FeedbackListItem("new", "FEATURE", "new", null, "PENDING", 1700000100L)
    coEvery { feedbackRepository.getMine(any(), any()) } returnsMany listOf(
        Result.success(MineResponse(listOf(oldItem), 20, 0, 1, false)),
        Result.success(MineResponse(listOf(newItem), 20, 0, 1, false))
    )
    val viewModel = createViewModel()

    viewModel.loadList(refresh = true)
    advanceUntilIdle()
    viewModel.loadList(refresh = true)
    advanceUntilIdle()

    val state = viewModel.listState.value as FeedbackViewModel.ListState.Success
    assertThat(state.items.map { it.id }).containsExactly("new")
}
```

### 步骤 2：运行测试确认行为边界

运行：

```bash
gradlew.bat :app:testDebugUnitTest --tests "com.tracktosearch.ui.screen.feedback.FeedbackViewModelTest.loadList refresh replaces previous items"
```

预期：测试通过，证明列表刷新使用替换语义；如果实现被错误改成追加，断言会失败。

## 任务 2：重排反馈详情页

**文件：**

- 修改：`app/src/main/java/com/tracktosearch/ui/screen/feedback/FeedbackDetailScreen.kt:1-246`
- 测试：`app/src/test/java/com/tracktosearch/ui/screen/feedback/FeedbackDetailScreenTest.kt:1-14`

### 步骤 1：调整标题栏动作

在 `FeedbackDetailScreen` 的 `TopAppBar` 中加入 `actions`，使用 Material 图标 `Icons.Rounded.Add`，为图标补充本地化 `contentDescription`，并删除 `LazyColumn` 中 `key = "new_feedback"` 的文字按钮。

```kotlin
actions = {
    IconButton(onClick = onNewFeedback) {
        Icon(
            imageVector = Icons.Rounded.Add,
            contentDescription = stringResource(R.string.feedback_new)
        )
    }
}
```

验证：详情页源代码不再包含 `item(key = "new_feedback")`，标题栏仍保留返回按钮和展示 ID。

### 步骤 2：修正原始反馈截图与元信息

将原始反馈截图从正方形 `120.dp + ContentScale.Crop` 改为固定高度、纵向缩略图和 `ContentScale.Fit`，保留截图完整内容并维持点击全屏查看。将 App、设备、Trakt、豆瓣标签改为 `stringResource`，只有对应值非空时显示可选元信息。

```kotlin
AsyncImage(
    model = remember(url) { ImageRequest.Builder(context).data(url).crossfade(true).build() },
    contentDescription = stringResource(R.string.feedback_screenshot_description),
    contentScale = ContentScale.Fit,
    modifier = Modifier
        .width(112.dp)
        .height(160.dp)
        .clip(RoundedCornerShape(10.dp))
        .background(MaterialTheme.colorScheme.surface)
        .clickable { onScreenshotClick(urls, index) }
)
```

验证：截图不再通过 `size(120.dp)` 强制裁切；硬编码 `App `、`Trakt: ` 和 `${...}: $it` 标签从详情页移除。

### 步骤 3：调整对话气泡间距与稳定尺寸

保留开发者左侧、用户右侧的气泡语义，统一头像、角色名和时间的间距；限制气泡最大宽度为可用宽度的 80%，保留回复截图附件和现有 `replyId` 高亮逻辑。不要改动 `conversationReplyListItemIndex` 的索引契约。

### 步骤 4：重构回复栏布局

将 `ReplyBar` 的输入行从 `Alignment.Bottom` 改为 `Alignment.CenterVertically`，并把回复栏加上 `imePadding()` 和 `navigationBarsPadding()`。截图预览独立在输入行上方；加图标、输入框和发送按钮固定在同一水平中心线，发送按钮固定 48dp，输入框限制 1～4 行。

```kotlin
Surface(
    modifier = Modifier
        .fillMaxWidth()
        .imePadding()
        .navigationBarsPadding(),
    color = MaterialTheme.colorScheme.surface,
    tonalElevation = 2.dp
) {
    Column(
        modifier = Modifier.padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // 截图预览、失败信息和进度信息保持在输入行之外
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // 加图标、输入框、发送按钮
        }
    }
}
```

保持现有上传中/发送中禁用逻辑；错误时保留 `replyText` 和 `replyScreenshots`，成功时清空草稿并重新加载详情。CLOSED 状态继续隐藏回复栏并显示关闭提示。

### 步骤 5：补充详情页纯逻辑测试

在 `FeedbackDetailScreenTest` 中保留现有回复定位索引测试，并补充截图解析的空值、空数组和合法数组行为测试；若测试需要访问解析函数，将其改为 `internal`，不暴露给生产模块外部。

运行：

```bash
gradlew.bat :app:testDebugUnitTest --tests "com.tracktosearch.ui.screen.feedback.FeedbackDetailScreenTest"
```

预期：定位索引保持原有 `replyIndex + 2` 规则，截图解析不会因为空值或空数组导致详情页崩溃。

## 任务 3：修复新建反馈成功后的导航与刷新

**文件：**

- 修改：`app/src/main/java/com/tracktosearch/ui/navigation/AppNavigation.kt:886-922`

### 步骤 1：统一使用 MAIN 作用域 ViewModel

在 `Routes.NEW_FEEDBACK` 路由中获取与反馈列表和详情相同的 ViewModel：

```kotlin
val sharedViewModel: FeedbackViewModel = hiltViewModel(
    navController.getBackStackEntry(Routes.MAIN)
)
NewFeedbackScreen(
    onBack = { navController.popBackStack() },
    onSuccess = {
        sharedViewModel.loadList(refresh = true)
        navController.popBackStack(Routes.FEEDBACK, inclusive = false)
    },
    viewModel = sharedViewModel
)
```

保留 `FeedbackScreen` 和 `FeedbackDetailScreen` 当前的 MAIN 作用域 ViewModel，确保返回列表时列表状态立即收到刷新结果。

### 步骤 2：运行 ViewModel 回归测试

运行：

```bash
gradlew.bat :app:testDebugUnitTest --tests "com.tracktosearch.ui.screen.feedback.FeedbackViewModelTest"
```

预期：列表加载、刷新、详情加载、提交和截图上传失败测试全部通过。

## 任务 4：补齐四套本地化资源

**文件：**

- 修改：`app/src/main/res/values/strings.xml`
- 修改：`app/src/main/res/values-zh/strings.xml`
- 修改：`app/src/main/res/values-ja/strings.xml`
- 修改：`app/src/main/res/values-ko/strings.xml`

新增或复用以下资源：

- 反馈截图无障碍描述。
- App 版本、设备、Trakt、豆瓣等环境信息标签。
- 标题栏新建反馈图标的无障碍描述。

四个文件保持同名资源集合，不在 Kotlin Composable 中加入新的用户可见硬编码文字。

## 任务 5：构建与设备验证

### 步骤 1：静态检查

运行：

```bash
git diff --check
rg -n 'item\(key = "new_feedback"\)|Alignment\.Bottom|Text\("App |Text\("Trakt:' app/src/main/java/com/tracktosearch/ui/screen/feedback/FeedbackDetailScreen.kt
```

预期：详情页不再出现对话末尾的新建按钮、回复栏底部对齐和硬编码环境标签；如果 `Alignment.Bottom` 出现在其他非回复布局，需要人工确认后再排除误报。

### 步骤 2：构建

运行：

```bash
gradlew.bat assembleDebug --no-daemon --max-workers=2
```

预期：生成 `app/build/outputs/apk/debug/app-debug.apk`。

### 步骤 3：设备冒烟验证

先确认只有一个在线设备：

```bash
adb devices
```

安装并启动：

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am force-stop com.tracktosearch
adb shell monkey -p com.tracktosearch 1
```

手工检查：

1. 设置页消息图标进入消息页，点击一条消息，详情自动定位并高亮对应回复。
2. 详情标题栏右侧 `+` 进入新反馈页，提交后返回反馈列表且能看到新反馈。
3. 详情页原始截图不被强制裁切，点击可全屏查看。
4. 回复栏无截图、有截图、多行输入、上传中、发送失败时，三个控件保持垂直居中。
5. 弹出键盘时回复栏不被遮挡；关闭反馈不显示输入控件。

收集崩溃日志：

```bash
adb logcat -b crash -d
```

## 任务 6：提交逻辑改动

在所有定向测试通过后，检查暂存文件名单，不纳入用户现有修改、截图、构建产物、`git-graph` 或 `node_modules`。

```bash
git diff --cached --check
git diff --cached --name-only
git commit -m "style(反馈详情): 优化详情页布局与回复栏"
```

若导航刷新逻辑与 UI 改动可以独立验证，再单独提交：

```bash
git add app/src/main/java/com/tracktosearch/ui/navigation/AppNavigation.kt app/src/test/java/com/tracktosearch/ui/screen/feedback/FeedbackViewModelTest.kt
git diff --cached --check
git commit -m "fix(反馈导航): 修复新反馈提交返回列表"
```

## 完成标准

- 详情页不再在对话末尾显示“写新反馈”按钮。
- 标题栏右侧可以创建新反馈，提交后回到反馈列表并刷新。
- 回复栏的加图标、输入框和发送按钮垂直居中，且适配键盘和导航栏。
- 原始截图不被正方形裁切，详情页保留消息定位、高亮、回复和关闭状态行为。
- 四套语言资源完整，定向单元测试和 Debug 构建通过。

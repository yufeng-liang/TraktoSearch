# 激活庆祝与反馈入口优化实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 在 Android Compose 激活页加入首次激活后的彩色礼花庆祝，并移除新反馈页联系方式采集、增加反馈列表愿望池引导文案。

**架构：** 激活状态转变仍由 `ActivationLoginScreen` 观察，新增屏幕级 `ActivationCelebration` 只负责两份 Lottie 的播放、镜像、内倾角和自动关闭。反馈提交的 UI/ViewModel/Repository 不再接收联系方式，但 `SubmitFeedbackRequest.contact`、Worker、D1 和历史详情保持兼容；反馈列表只新增本地化辅助文本，不改变导航或缓存。

**技术栈：** Kotlin、Jetpack Compose、Material 3、Lottie Compose 6.7.1、Hilt/MVVM、Kotlin Serialization、MockWebServer、JUnit/Truth/MockK。

---

## 文件清单与职责

### 激活庆祝

- 创建：`app/src/main/java/com/tracktosearch/ui/screen/login/ActivationCelebration.kt`
  - 定义 `shouldShowActivationCelebration` 状态转变判断。
  - 加载现有 `R.raw.easter_firework`，渲染左右两份礼花，右侧镜像，左右分别向屏幕中央倾斜 `40°`。
  - 管理一次播放、2.5 秒正常结束和 3 秒兜底关闭。
- 修改：`app/src/main/java/com/tracktosearch/ui/screen/login/ActivationLoginScreen.kt`
  - 监听 `authState.activated` 的 `false -> true` 转变。
  - 把庆祝层放在激活卡片下方、主内容上方的非交互装饰层。
- 修改：`app/src/test/java/com/tracktosearch/ui/screen/login/ActivationLoginActionsTest.kt`
  - 增加状态转变判断测试，覆盖首次激活和已激活初始状态。

### 反馈提交契约

- 修改：`app/src/main/java/com/tracktosearch/ui/screen/feedback/FeedbackViewModel.kt`
  - 删除 `submit` 的 `contact` 参数，保留其它字段和截图上传流程。
- 修改：`app/src/main/java/com/tracktosearch/data/repository/FeedbackRepository.kt`
  - 删除 `submit` 的 `contact` 参数，构造请求时显式使用 `contact = null`。
- 保持：`app/src/main/java/com/tracktosearch/data/remote/feedback/FeedbackApiService.kt`
  - `SubmitFeedbackRequest.contact` 不删除，继续作为可空兼容字段。
- 修改：`app/src/test/java/com/tracktosearch/ui/screen/feedback/FeedbackViewModelTest.kt`
  - 更新新的 submit 参数顺序和 Repository Mock 参数数量。
- 修改：`app/src/test/java/com/tracktosearch/data/repository/FeedbackRepositoryTest.kt`
  - 捕获实际提交请求，断言 `contact == null`，同时保留提交成功和网络失败覆盖。

### 反馈页面与本地化

- 修改：`app/src/main/java/com/tracktosearch/ui/screen/feedback/NewFeedbackScreen.kt`
  - 删除联系方式 state、输入框和提交时的联系方式参数。
- 修改：`app/src/main/java/com/tracktosearch/ui/screen/feedback/FeedbackScreen.kt`
  - 在现有整行加号卡片之前增加 `feedback_intro` 辅助文案。
- 修改：`app/src/main/res/values/strings.xml`
- 修改：`app/src/main/res/values-zh/strings.xml`
- 修改：`app/src/main/res/values-ja/strings.xml`
- 修改：`app/src/main/res/values-ko/strings.xml`
  - 增加 `feedback_intro`。
  - 删除仅供新反馈输入框使用的 `feedback_contact` 和 `feedback_contact_placeholder`。
  - 保留 `feedback_contact_value`，供历史详情显示。

---

### 任务 1：用测试锁定激活状态转变

**文件：**
- 修改：`app/src/test/java/com/tracktosearch/ui/screen/login/ActivationLoginActionsTest.kt`
- 创建：`app/src/main/java/com/tracktosearch/ui/screen/login/ActivationCelebration.kt`

- [ ] **步骤 1：先编写状态转变失败测试**

在 `ActivationLoginActionsTest` 中增加普通 JUnit 测试，先引用尚不存在的 `shouldShowActivationCelebration`：

```kotlin
@Test
fun `only false to true activation shows celebration`() {
    assertThat(shouldShowActivationCelebration(false, true)).isTrue()
    assertThat(shouldShowActivationCelebration(true, true)).isFalse()
    assertThat(shouldShowActivationCelebration(false, false)).isFalse()
}
```

- [ ] **步骤 2：运行测试确认当前实现失败**

运行：

```bash
./gradlew.bat :app:testDebugUnitTest --tests com.tracktosearch.ui.screen.login.ActivationLoginActionsTest --console=plain --offline --no-daemon --max-workers=1
```

预期：测试编译失败，原因是 `shouldShowActivationCelebration` 尚未定义。不要在此步骤修改业务代码以外的文件。

- [ ] **步骤 3：实现最小状态判断和礼花组件**

在新文件中先实现状态判断：

```kotlin
internal fun shouldShowActivationCelebration(
    previousActivated: Boolean,
    currentActivated: Boolean
): Boolean = !previousActivated && currentActivated
```

然后实现 `ActivationCelebration`：

```kotlin
@Composable
internal fun ActivationCelebration(
    visible: Boolean,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(animationSpec = tween(180)),
        exit = fadeOut(animationSpec = tween(240)),
        modifier = modifier
    ) {
        val composition by rememberLottieComposition(
            LottieCompositionSpec.RawRes(R.raw.easter_firework)
        )
        val progress by animateLottieCompositionAsState(
            composition = composition,
            iterations = 1
        )

        LaunchedEffect(composition) {
            if (composition != null) {
                delay(2_500)
                onFinished()
            }
        }
        LaunchedEffect(Unit) {
            delay(3_000)
            onFinished()
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            LottieAnimation(
                composition = composition,
                progress = { progress },
                modifier = Modifier
                    .size(104.dp)
                    .rotate(40f)
            )
            LottieAnimation(
                composition = composition,
                progress = { progress },
                modifier = Modifier
                    .size(104.dp)
                    .graphicsLayer {
                        scaleX = -1f
                        rotationZ = -40f
                    }
            )
        }
    }
}
```

使用现有 Lottie Compose API；组件不增加点击处理，`composition == null` 时只保留 3 秒兜底关闭。

- [ ] **步骤 4：运行激活测试确认状态判断通过**

运行同一命令：

```bash
./gradlew.bat :app:testDebugUnitTest --tests com.tracktosearch.ui.screen.login.ActivationLoginActionsTest --console=plain --offline --no-daemon --max-workers=1
```

预期：`ActivationLoginActionsTest` 通过。

- [ ] **步骤 5：提交状态判断和组件**

```bash
git add app/src/main/java/com/tracktosearch/ui/screen/login/ActivationCelebration.kt app/src/test/java/com/tracktosearch/ui/screen/login/ActivationLoginActionsTest.kt
git diff --cached --check
git diff --cached --name-only
git commit -m "feat(激活页): 增加礼花庆祝组件"
```

### 任务 2：把礼花接入激活成功状态

**文件：**
- 修改：`app/src/main/java/com/tracktosearch/ui/screen/login/ActivationLoginScreen.kt`

- [ ] **步骤 1：增加页面局部庆祝状态**

在 `isActivated` 附近增加以下状态和副作用，初始已激活状态直接作为已观察状态：

```kotlin
var showCelebration by remember { mutableStateOf(false) }
var observedActivated by remember { mutableStateOf(isActivated) }

LaunchedEffect(isActivated) {
    if (shouldShowActivationCelebration(observedActivated, isActivated)) {
        showCelebration = true
    }
    observedActivated = isActivated
}
```

- [ ] **步骤 2：把组件放进现有根 Box**

在现有内容 `Column` 后、根 `Box` 结束前加入：

```kotlin
ActivationCelebration(
    visible = showCelebration,
    onFinished = { showCelebration = false },
    modifier = Modifier
        .align(Alignment.TopCenter)
        .fillMaxWidth()
        .padding(top = 174.dp)
        .zIndex(0.5f)
)
```

保持 `ActivationCard` 当前 `zIndex(1f)`，确保礼花位于卡片周边而不是覆盖卡片内容；保留现有 Trakt 登录和游客回调，不加入自动导航。

- [ ] **步骤 3：运行编译和激活测试**

运行：

```bash
./gradlew.bat :app:testDebugUnitTest --tests com.tracktosearch.ui.screen.login.ActivationLoginActionsTest --console=plain --offline --no-daemon --max-workers=1
./gradlew.bat :app:compileDebugKotlin --console=plain --offline --no-daemon --max-workers=1
```

预期：测试通过，Compose/Kotlin 编译通过；不出现 Lottie、`Alignment`、`zIndex` 或资源引用错误。

- [ ] **步骤 4：提交激活页集成**

```bash
git add app/src/main/java/com/tracktosearch/ui/screen/login/ActivationLoginScreen.kt
git diff --cached --check
git diff --cached --name-only
git commit -m "feat(激活页): 增加激活成功礼花庆祝"
```

### 任务 3：移除联系方式采集并锁定空值契约

**文件：**
- 修改：`app/src/test/java/com/tracktosearch/data/repository/FeedbackRepositoryTest.kt`
- 修改：`app/src/test/java/com/tracktosearch/ui/screen/feedback/FeedbackViewModelTest.kt`
- 修改：`app/src/main/java/com/tracktosearch/data/repository/FeedbackRepository.kt`
- 修改：`app/src/main/java/com/tracktosearch/ui/screen/feedback/FeedbackViewModel.kt`

- [ ] **步骤 1：先让 Repository 测试捕获并断言请求**

在 `FeedbackRepositoryTest` 的成功提交测试中，给匿名 API 实现增加捕获变量：

```kotlin
var submittedRequest: SubmitFeedbackRequest? = null
val api = object : TestFeedbackApi() {
    override suspend fun submit(request: SubmitFeedbackRequest): Response<FeedbackResponse<SubmitFeedbackResponse>> {
        submittedRequest = request
        return Response.success(
            FeedbackResponse("SUCCESS", "OK", "r1", SubmitFeedbackResponse("fb1", 1700000000L))
        )
    }
    // 保留当前测试中的 uploadScreenshot、getMine、getDetail 实现。
}
```

把 Repository 调用更新为不传 `contact`，并增加：

```kotlin
assertThat(submittedRequest?.contact).isNull()
```

- [ ] **步骤 2：运行反馈测试确认签名尚未更新**

运行：

```bash
./gradlew.bat :app:testDebugUnitTest --tests com.tracktosearch.data.repository.FeedbackRepositoryTest --tests com.tracktosearch.ui.screen.feedback.FeedbackViewModelTest --console=plain --offline --no-daemon --max-workers=1
```

预期：编译失败，原因是测试已经使用无 `contact` 的调用，而当前 Repository/ViewModel 签名仍需要该参数。

- [ ] **步骤 3：删除应用层提交参数，保留远端 DTO 字段**

将 Repository 签名调整为：

```kotlin
suspend fun submit(
    type: String,
    content: String,
    screenshots: List<String>,
    friendNickname: String,
    traktUsername: String?,
    doubanUsername: String?,
    appVersion: String,
    osVersion: String,
    deviceModel: String
): Result<SubmitFeedbackResponse>
```

构造请求时固定：

```kotlin
val request = SubmitFeedbackRequest(
    type = type,
    content = content,
    contact = null,
    screenshots = screenshots,
    friendNickname = friendNickname,
    traktUsername = traktUsername,
    doubanUsername = doubanUsername,
    appVersion = appVersion,
    osVersion = osVersion,
    deviceModel = deviceModel
)
```

将 ViewModel 的 `submit` 调整为：

```kotlin
fun submit(
    type: String,
    content: String,
    screenshotBytes: List<ByteArray>,
    screenshotMimeTypes: List<String>
)
```

调用 Repository 时删除 `contact = contact`，其它身份和设备字段保持当前实现。

更新 ViewModel 测试中的调用：

```kotlin
viewModel.submit("BUG", "test content", emptyList(), emptyList())
```

并把 MockK 的 `feedbackRepository.submit` 参数匹配从 10 个改为 9 个。

- [ ] **步骤 4：运行反馈测试确认契约通过**

运行：

```bash
./gradlew.bat :app:testDebugUnitTest --tests com.tracktosearch.data.repository.FeedbackRepositoryTest --tests com.tracktosearch.ui.screen.feedback.FeedbackViewModelTest --console=plain --offline --no-daemon --max-workers=1
```

预期：Repository 提交成功、网络失败、截图失败和 ViewModel 状态测试全部通过，且捕获请求的 `contact` 为 `null`。

- [ ] **步骤 5：提交反馈提交契约调整**

```bash
git add app/src/main/java/com/tracktosearch/data/repository/FeedbackRepository.kt app/src/main/java/com/tracktosearch/ui/screen/feedback/FeedbackViewModel.kt app/src/test/java/com/tracktosearch/data/repository/FeedbackRepositoryTest.kt app/src/test/java/com/tracktosearch/ui/screen/feedback/FeedbackViewModelTest.kt
git diff --cached --check
git diff --cached --name-only
git commit -m "fix(反馈表单): 移除联系方式提交"
```

### 任务 4：移除表单栏并增加愿望池文案

**文件：**
- 修改：`app/src/main/java/com/tracktosearch/ui/screen/feedback/NewFeedbackScreen.kt`
- 修改：`app/src/main/java/com/tracktosearch/ui/screen/feedback/FeedbackScreen.kt`
- 修改：`app/src/main/res/values/strings.xml`
- 修改：`app/src/main/res/values-zh/strings.xml`
- 修改：`app/src/main/res/values-ja/strings.xml`
- 修改：`app/src/main/res/values-ko/strings.xml`

- [ ] **步骤 1：删除 NewFeedbackScreen 中的联系方式 UI**

删除以下状态：

```kotlin
var contact by remember { mutableStateOf("") }
```

删除联系方式 `OutlinedTextField`，并把提交调用调整为：

```kotlin
viewModel.submit(
    type = selectedType!!,
    content = content.trim(),
    screenshotBytes = screenshots.map { it.first },
    screenshotMimeTypes = screenshots.map { it.second }
)
```

保留截图、正文、类型、提交进度和错误提示的现有布局与行为。

- [ ] **步骤 2：添加 FeedbackScreen 引导文本**

在现有 LazyColumn 的写新反馈卡片 `item` 之前添加：

```kotlin
item {
    Text(
        text = stringResource(R.string.feedback_intro),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center
    )
}
```

新增 `TextAlign` import；保留现有加号卡片的颜色、圆角、点击回调和文字。

- [ ] **步骤 3：同步四套字符串资源**

在反馈相关字符串附近新增同名 `feedback_intro`：

```xml
<!-- values/strings.xml -->
<string name="feedback_intro">I read every piece of feedback carefully. This is also your feature wish pool—maybe one day, your wish will make it into the app~</string>

<!-- values-zh/strings.xml -->
<string name="feedback_intro">每一条反馈，我都会认真读到。这里也是你的功能许愿池，说不定哪天，你许下的愿望就上线啦～</string>

<!-- values-ja/strings.xml -->
<string name="feedback_intro">届いたフィードバックは、一つひとつ私が大切に読みます。ここは機能の願いごとボックスでもあります。いつか、あなたの願いが実現するかも〜</string>

<!-- values-ko/strings.xml -->
<string name="feedback_intro">남겨 주신 피드백은 제가 하나하나 꼼꼼히 읽어요. 여기는 기능 소원함이기도 하니, 언젠가 당신의 바람이 이루어질지도 몰라요~</string>
```

删除 `feedback_contact` 和 `feedback_contact_placeholder` 四语资源；不要删除 `feedback_contact_value`，因为 `FeedbackDetailScreen` 仍然使用它展示旧数据。

- [ ] **步骤 4：做残余引用检查并编译**

运行：

```bash
rg -n --glob '*.kt' 'var contact|contact = contact|feedback_contact(?!_value)' app/src/main/java --pcre2
rg -n 'name="feedback_intro"' app/src/main/res/values*/strings.xml
./gradlew.bat :app:compileDebugKotlin --console=plain --offline --no-daemon --max-workers=1
```

预期：第一条命令无输出；第二条命令返回四个 `feedback_intro`；Kotlin 编译通过。

- [ ] **步骤 5：提交反馈页面和本地化**

```bash
git add app/src/main/java/com/tracktosearch/ui/screen/feedback/NewFeedbackScreen.kt app/src/main/java/com/tracktosearch/ui/screen/feedback/FeedbackScreen.kt app/src/main/res/values/strings.xml app/src/main/res/values-zh/strings.xml app/src/main/res/values-ja/strings.xml app/src/main/res/values-ko/strings.xml
git diff --cached --check
git diff --cached --name-only
git commit -m "style(反馈页面): 增加愿望池引导文案"
```

### 任务 5：集成验证与模拟器视觉验收

**文件：**
- 读取：前述全部修改文件和四个功能提交的 diff
- 产物：`app/build/outputs/apk/debug/app-debug.apk`（不纳入 Git）

- [ ] **步骤 1：运行完整针对性单元测试**

```bash
./gradlew.bat :app:testDebugUnitTest --tests com.tracktosearch.ui.screen.login.ActivationLoginActionsTest --tests com.tracktosearch.data.repository.FeedbackRepositoryTest --tests com.tracktosearch.ui.screen.feedback.FeedbackViewModelTest --tests com.tracktosearch.data.remote.feedback.FeedbackApiServiceTest --tests com.tracktosearch.ui.screen.feedback.FeedbackDetailScreenTest --console=plain --offline --no-daemon --max-workers=1
```

预期：所有指定测试通过，`app/build/test-results/testDebugUnitTest` 生成对应 XML，失败时先读取 XML 和 Gradle 进程状态再判断原因。

- [ ] **步骤 2：构建 debug APK**

```bash
./gradlew.bat :app:assembleDebug --console=plain --offline --no-daemon --no-configuration-cache --max-workers=1
```

预期：`app/build/outputs/apk/debug/app-debug.apk` 生成，构建日志无 Kotlin 编译、资源链接或 Lottie 资源错误。

- [ ] **步骤 3：检查 Git 变更边界**

```bash
git diff ae106c3..HEAD --check
git diff ae106c3..HEAD --name-only
git status --short --branch
```

预期：设计提交之后的四个功能提交只包含激活页、反馈页、反馈提交层、测试和四套 strings；不包含 APK、截图、`.superpowers`、`local.properties` 或其它临时文件。

- [ ] **步骤 4：在在线 Android 设备上安装并采集运行证据**

先确认并自动选择第一个在线设备：

```bash
adb devices
DEVICE_SERIAL="$(adb devices | awk 'NR > 1 && $2 == \"device\" { print $1; exit }')"
test -n "$DEVICE_SERIAL"
adb -s "$DEVICE_SERIAL" shell getprop ro.build.version.release
adb -s "$DEVICE_SERIAL" shell getprop ro.build.version.sdk
adb -s "$DEVICE_SERIAL" install -r app/build/outputs/apk/debug/app-debug.apk
```

在可用测试激活码下验证：

1. 未激活页输入邀请码并成功激活。
2. 截取礼花出现时和淡出后的截图，确认左 `+40°`、右 `-40°` 朝屏幕中央，卡片和按钮没有被遮挡。
3. 点击 Trakt 或游客入口，确认礼花层不拦截操作。
4. 强制停止并重新打开已激活页面，确认不重复播放礼花。
5. 打开反馈列表，确认愿望池文案位于加号卡片上方。
6. 打开新反馈页，确认联系方式栏不存在；提交一条反馈并确认成功返回。

同时采集：

```bash
adb -s "$DEVICE_SERIAL" shell dumpsys window | head -80
adb -s "$DEVICE_SERIAL" logcat -b crash -d
```

预期：安装成功、主 Activity 正常启动、无 crash buffer 条目；截图作为本地 QA 证据，不加入 Git。

## 计划自检

- 规格中的首次激活触发、左右 `40°`、2～3 秒淡出、已激活不播放和不自动导航均由任务 1～2 覆盖。
- 联系方式 UI 删除、应用层固定空值、远端 DTO/历史详情兼容由任务 3～4 覆盖。
- 愿望池文案位置、四语资源和现有加号卡片保留由任务 4 覆盖。
- 自动化测试、编译、APK、Git 边界和模拟器运行证据由任务 5 覆盖。
- 计划没有引入新依赖、Worker 改动或无关帮助页重构。

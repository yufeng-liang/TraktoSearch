# 简介与评论尾部展开操作实现计划

> 面向 AI 代理的工作者：必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（- [ ]）语法跟踪进度。

目标：将详情页简介和评论正文的长文本操作改为尾行右侧的主题色“展开”/“收起”，并复刻操作前的背景渐隐效果。

架构：保留详情模块内现有的共享 ExpandableText，由 DetailHeaderContent.kt 同时服务简介和 DetailComments.kt 的评论正文。使用 TextMeasurer 测量实际本地化操作文字，折叠状态二分查找正文前缀，尾部独立操作层负责右对齐和渐隐遮罩；展开状态保留完整正文并为操作区预留尾部空间。

技术栈：Kotlin、Jetpack Compose Material 3、TextMeasurer、Robolectric Compose UI Test、Gradle Android Debug 构建、ADB 截图与 logcat。

---

## 文件清单

修改：

- app/src/main/java/com/tracktosearch/ui/screen/detail/DetailHeaderContent.kt：重写 ExpandableText 的尾行测量、渐隐操作层和资源引用。
- app/src/main/res/values/strings.xml：增加英文 Expand/Collapse。
- app/src/main/res/values-zh/strings.xml：增加中文 展开/收起。
- app/src/main/res/values-ja/strings.xml：增加日文 展開/折りたたむ。
- app/src/main/res/values-ko/strings.xml：增加韩文 펼치기/접기。
- app/src/test/java/com/tracktosearch/ui/screen/detail/DetailHeaderContentTest.kt：覆盖共享组件的展开/收起行为。

不修改：

- DetailComments.kt 已经调用共享 ExpandableText，不复制逻辑，不改变评论翻译、剧透和分页。
- 其他页面的“查看全部”、季数展开和推荐入口不使用该资源键，不随本计划变化。

## 任务 1：先写失败的共享组件测试

文件：app/src/test/java/com/tracktosearch/ui/screen/detail/DetailHeaderContentTest.kt

- [ ] 步骤 1：增加长文本显示展开的测试。

在现有测试类中加入：

    @Test
    fun long_overview_shows_expand_action() {
        composeRule.setContent {
            MaterialTheme {
                ExpandableText(text = "A long overview. ".repeat(80))
            }
        }

        composeRule.onNodeWithText("Expand", substring = true).assertExists()
        composeRule.onNodeWithText("View all", substring = true).assertDoesNotExist()
    }

- [ ] 步骤 2：增加点击后显示收起的测试。

    @Test
    fun clicking_expand_changes_action_to_collapse() {
        composeRule.setContent {
            MaterialTheme {
                ExpandableText(text = "A long overview. ".repeat(80))
            }
        }

        composeRule.onNodeWithText("Expand", substring = true).performClick()
        composeRule.onNodeWithText("Collapse", substring = true).assertExists()
    }

保留短文本不显示操作的测试；测试阶段先使用固定的期望文案 Expand/Collapse，不要提前引用尚未创建的资源键，确保红灯来自行为缺失而不是资源编译错误。实现资源同步后，再把资源属性改为 detail_text_expand/detail_text_collapse。

- [ ] 步骤 3：运行红灯测试。

    ANDROID_HOME=H:/android/Sdk ANDROID_SDK_ROOT=H:/android/Sdk ./gradlew.bat :app:testDebugUnitTest --tests com.tracktosearch.ui.screen.detail.DetailHeaderContentTest --no-daemon --console=plain

预期：任务正常编译和运行，但新增测试因当前只有 View all/Collapse 文案而失败；不接受测试编译错误或测试运行器错误作为红灯。

## 任务 2：实现尾行测量和主题色操作层

文件：app/src/main/java/com/tracktosearch/ui/screen/detail/DetailHeaderContent.kt

- [ ] 步骤 1：把 ExpandableText 的资源读取改为 detail_text_expand 和 detail_text_collapse；同步把 buildOverviewDisplayText、findCollapsedPrefixLength 的参数名从 showAllLabel 改成 expandLabel。

- [ ] 步骤 2：保留“完整文本测量 + 二分查找前缀”的结构，去掉候选字符串中的 ...，并为渐隐区域增加参数：

    internal fun findCollapsedPrefixLength(
        text: String,
        expandLabel: String,
        style: TextStyle,
        textMeasurer: TextMeasurer,
        maxWidthPx: Int,
        maxLines: Int,
        fadeWidthPx: Int = 0
    ): Int? {
        if (maxWidthPx <= 0) return null
        val fullLayout = textMeasurer.measure(
            text = AnnotatedString(text),
            style = style,
            maxLines = Int.MAX_VALUE,
            overflow = TextOverflow.Clip,
            constraints = Constraints(maxWidth = maxWidthPx)
        )
        if (fullLayout.lineCount <= maxLines) return null

        val candidateWidth = (maxWidthPx - fadeWidthPx).coerceAtLeast(1)
        val suffix = " $expandLabel"
        var low = 0
        var high = text.length
        var bestLength = 0
        while (low <= high) {
            val candidateLength = (low + high) / 2
            val candidate = text.take(candidateLength).trimEnd() + suffix
            val layout = textMeasurer.measure(
                text = AnnotatedString(candidate),
                style = style,
                maxLines = Int.MAX_VALUE,
                overflow = TextOverflow.Clip,
                constraints = Constraints(maxWidth = candidateWidth)
            )
            if (layout.lineCount <= maxLines) {
                bestLength = candidateLength
                low = candidateLength + 1
            } else {
                high = candidateLength - 1
            }
        }
        return bestLength
    }

- [ ] 步骤 3：在 BoxWithConstraints 中用正文样式测量展开/收起实际像素宽度；折叠正文只显示前缀，展开正文显示完整文本。操作层使用 Alignment.BottomEnd，宽度为操作文字宽度加渐隐宽度，操作文字使用 MaterialTheme.colorScheme.primary，左侧渐变终点使用 MaterialTheme.colorScheme.background。外层继续使用当前 clickable 切换状态。

- [ ] 步骤 4：展开状态测量完整正文最后一行。如果最后一行会进入操作区，则给正文增加操作区尾部约束后重新换行，再定位收起操作；不能让操作层覆盖正文。操作文字必须作为独立 Text 节点保留，便于 UI 测试和无障碍识别。

- [ ] 步骤 5：运行源码检查。

    git diff --check
    rg -n 'detail_overview_show_all|detail_overview_collapse|\.\.\. ' app/src/main/java/com/tracktosearch/ui/screen/detail/DetailHeaderContent.kt

预期：无空白错误；DetailHeaderContent.kt 不再引用旧资源或拼接三个点。

## 任务 3：同步四套本地化资源

文件：

- app/src/main/res/values/strings.xml
- app/src/main/res/values-zh/strings.xml
- app/src/main/res/values-ja/strings.xml
- app/src/main/res/values-ko/strings.xml

- [ ] 步骤 1：在详情页资源附近新增同名键：

    <string name="detail_text_expand">Expand</string>
    <string name="detail_text_collapse">Collapse</string>

中文为 展开/收起，日文为 展開/折りたたむ，韩文为 펼치기/접기。

- [ ] 步骤 2：删除仅由 ExpandableText 使用的 detail_overview_show_all/detail_overview_collapse，并检查引用：

    rg -n 'detail_overview_show_all|detail_overview_collapse|detail_text_expand|detail_text_collapse' app/src/main app/src/test

预期：旧键无引用；新键存在于四个语言文件、ExpandableText 和相关测试。

## 任务 4：运行绿灯测试并验证简介/评论共享调用

- [ ] 步骤 1：运行详情组件测试。

    ANDROID_HOME=H:/android/Sdk ANDROID_SDK_ROOT=H:/android/Sdk ./gradlew.bat :app:testDebugUnitTest --tests com.tracktosearch.ui.screen.detail.DetailHeaderContentTest --no-daemon --console=plain

预期：测试全部通过，短文本无操作，长文本显示 Expand，点击后显示 Collapse。

- [ ] 步骤 2：检查两个调用方仍共享同一实现。

    rg -n -C 2 'ExpandableText\(text = displayText\)|ExpandableText\(text = uiState\.overview\)|detail_text_expand' app/src/main/java/com/tracktosearch/ui/screen/detail

预期：评论 CommentItem 和详情简介都调用 ExpandableText，没有第二套展开逻辑。

- [ ] 步骤 3：运行详情 ViewModel 回归测试。

    ANDROID_HOME=H:/android/Sdk ANDROID_SDK_ROOT=H:/android/Sdk ./gradlew.bat :app:testDebugUnitTest --tests com.tracktosearch.ui.screen.detail.DetailViewModelTest --tests com.tracktosearch.ui.screen.detail.DetailViewModelSupplementTest --no-daemon --console=plain

预期：详情数据、评论翻译和分页测试通过。

## 任务 5：构建和 Android 视觉验收

- [ ] 步骤 1：构建 Debug APK。

    ANDROID_HOME=H:/android/Sdk ANDROID_SDK_ROOT=H:/android/Sdk ./gradlew.bat :app:assembleDebug --no-daemon --console=plain

预期：退出码为 0，生成 app/build/outputs/apk/debug/app-debug.apk。

- [ ] 步骤 2：运行 adb devices；只有存在状态为 device 的目标时继续安装。没有在线设备时记录该事实，不把构建结果当作视觉验收。

- [ ] 步骤 3：在线设备执行安装、启动、UI 树、截图和 crash 日志采集：

    adb -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk
    adb -s <serial> shell am start -n com.tracktosearch/.MainActivity
    adb -s <serial> exec-out uiautomator dump /dev/tty > /tmp/tracktosearch-detail-expand.xml
    adb -s <serial> exec-out screencap -p > /tmp/tracktosearch-detail-expand.png
    adb -s <serial> logcat -b crash -d

检查简介和评论的折叠态、展开态：操作文字使用主题色、在尾行右侧、渐隐连续且没有遮挡正文；截图和日志不得加入 Git。

## 任务 6：检查差异并提交功能改动

- [ ] 步骤 1：运行 git diff --check、git status --short、git diff --stat，并限定查看以下 6 个路径：DetailHeaderContent.kt、四套 strings.xml、DetailHeaderContentTest.kt。确认没有 APK、截图、日志或临时文件。

- [ ] 步骤 2：验证通过后显式暂存并检查：

    git add -- app/src/main/java/com/tracktosearch/ui/screen/detail/DetailHeaderContent.kt app/src/main/res/values/strings.xml app/src/main/res/values-zh/strings.xml app/src/main/res/values-ja/strings.xml app/src/main/res/values-ko/strings.xml app/src/test/java/com/tracktosearch/ui/screen/detail/DetailHeaderContentTest.kt
    git diff --cached --check
    git diff --cached --name-only
    git commit -m 'style(detail): 优化简介评论展开操作'

预期：暂存列表只包含上述 6 个路径，提交信息符合项目 Conventional Commits 中文约定。

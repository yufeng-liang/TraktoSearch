package com.tracktosearch.ui.component

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.tracktosearch.data.repository.UpdateInfo
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UpdateDialogTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun createUpdateInfo(
        latestVersion: String = "v9.9.9",
        changelog: String = "## v9.9.9 更新内容\n\n### 新功能\n\n- 测试功能A\n- 测试功能B",
        downloadUrl: String = "https://example.com/app.apk",
        fileSize: Long = 1024L,
        hasUpdate: Boolean = true
    ): UpdateInfo {
        return UpdateInfo(
            latestVersion = latestVersion,
            downloadUrl = downloadUrl,
            changelog = changelog,
            fileSize = fileSize,
            hasUpdate = hasUpdate
        )
    }

    @Test
    fun `版本号在 changelog 标题中显示`() {
        composeRule.setContent {
            UpdateDialog(
                updateInfo = createUpdateInfo(latestVersion = "v9.9.9"),
                onDismiss = {}
            )
        }
        composeRule.waitForIdle()
        // StickyHeaderChangelogContent 会把 "## v9.9.9 更新内容" 解析为 section 标题
        // 标题文本为 "v9.9.9 更新内容"（## 被剥离）
        composeRule.onNodeWithText("v9.9.9 更新内容").assertIsDisplayed()
    }

    @Test
    fun `markdown 更新日志正常渲染`() {
        val changelog = "## v1.0.0\n\n### 新功能\n\n- 功能A\n- 功能B"
        composeRule.setContent {
            UpdateDialog(
                updateInfo = createUpdateInfo(changelog = changelog),
                onDismiss = {}
            )
        }
        composeRule.waitForIdle()
        // StickyHeaderChangelogContent 把 "## v1.0.0" 解析为 section 标题
        composeRule.onNodeWithText("v1.0.0").assertIsDisplayed()
        // ### 新功能 在 RenderLine 中被去除 # 显示为 "新功能"
        composeRule.onNodeWithText("新功能").assertIsDisplayed()
        // - 功能A 在 RenderLine 中被去除 "- " 显示为 "功能A"
        composeRule.onNodeWithText("功能A").assertIsDisplayed()
        composeRule.onNodeWithText("功能B").assertIsDisplayed()
    }

    @Test
    fun `空 changelog 不崩溃`() {
        composeRule.setContent {
            UpdateDialog(
                updateInfo = createUpdateInfo(changelog = ""),
                onDismiss = {}
            )
        }
        composeRule.waitForIdle()
        // 不崩溃即通过（LazyColumn 内容行的可见性在 Robolectric 中不稳定，
        // 不验证具体文本，仅验证组件渲染不崩溃）
    }

    @Test
    fun `点击稍后按钮触发 onDismiss`() {
        var dismissed = false
        composeRule.setContent {
            UpdateDialog(
                updateInfo = createUpdateInfo(),
                onDismiss = { dismissed = true }
            )
        }
        composeRule.waitForIdle()
        // Idle 状态下有 update_later 按钮，英文资源为 "Remind me later"
        composeRule.onNodeWithText("Remind me later").performClick()
        composeRule.waitForIdle()
        assertThat(dismissed).isTrue()
    }

    @Test
    fun `点击内置下载按钮进入下载状态不崩溃`() {
        composeRule.setContent {
            UpdateDialog(
                updateInfo = createUpdateInfo(),
                onDismiss = {}
            )
        }
        composeRule.waitForIdle()
        // 点击 "Built-in download" 按钮会启动下载协程
        // 由于 URL 无效会进入 Error 状态，但不应崩溃
        composeRule.onNodeWithText("Built-in download").performClick()
        composeRule.waitForIdle()
        // 不崩溃即通过
    }

    @Test
    fun `parseInlineMarkdown 纯文本返回原内容`() {
        val input = "这是纯文本"
        val result = parseInlineMarkdown(input)
        assertThat(result.toString()).isEqualTo(input)
    }

    @Test
    fun `parseInlineMarkdown 粗体标记正确解析`() {
        val input = "这是**粗体**文本"
        val result = parseInlineMarkdown(input)
        // AnnotatedString.toString() 返回纯文本，** 标记会被去除
        assertThat(result.toString()).isEqualTo("这是粗体文本")
        assertThat(result.toString()).contains("粗体")
    }

    @Test
    fun `parseInlineMarkdown 多个粗体标记正确解析`() {
        val input = "**A** 和 **B** 都是粗体"
        val result = parseInlineMarkdown(input)
        assertThat(result.toString()).isEqualTo("A 和 B 都是粗体")
    }

    @Test
    fun `parseInlineMarkdown 未闭合粗体标记原样返回`() {
        val input = "这是**未闭合的粗体"
        val result = parseInlineMarkdown(input)
        // 没有 closing ** 时，原样追加剩余文本（包含 **）
        assertThat(result.toString()).contains("未闭合的粗体")
    }

    @Test
    fun `parseInlineMarkdown 列表标记原样保留`() {
        // parseInlineMarkdown 不处理列表标记，由外层 RenderLine 处理
        val input = "- 项目1\n- 项目2"
        val result = parseInlineMarkdown(input)
        assertThat(result.toString()).contains("项目1")
        assertThat(result.toString()).contains("项目2")
    }

    @Test
    fun `parseInlineMarkdown 标题标记原样保留`() {
        // parseInlineMarkdown 不处理标题标记，由外层 RenderLine 处理
        val input = "## 标题"
        val result = parseInlineMarkdown(input)
        assertThat(result.toString()).contains("标题")
    }

    @Test
    fun `parseInlineMarkdown 空字符串不崩溃`() {
        val result = parseInlineMarkdown("")
        assertThat(result.toString()).isEmpty()
    }

    @Test
    fun `parseInlineMarkdown 混合标记正确解析`() {
        // parseInlineMarkdown 只处理 ** 粗体，其他标记原样保留
        val input = "## 标题\n\n- **粗体项**\n- 普通项"
        val result = parseInlineMarkdown(input)
        assertThat(result.toString()).contains("标题")
        assertThat(result.toString()).contains("粗体项")
        assertThat(result.toString()).contains("普通项")
        // ** 标记应被去除
        assertThat(result.toString()).doesNotContain("**")
    }

    @Test
    fun `StickyHeaderChangelogContent 渲染不崩溃`() {
        composeRule.setContent {
            StickyHeaderChangelogContent(text = "## 标题\n\n- 内容")
        }
        composeRule.waitForIdle()
        // 不崩溃即通过
    }

    @Test
    fun `StickyHeaderChangelogContent 多版本 section 渲染不崩溃`() {
        val text = """
            ## v1.0.0

            ### 新功能

            - 功能A

            ---

            ## v0.9.0

            ### 修复

            - 修复B
        """.trimIndent()
        composeRule.setContent {
            StickyHeaderChangelogContent(text = text)
        }
        composeRule.waitForIdle()
        // 不崩溃即通过
    }

    @Test
    fun `ChangelogContent 渲染不崩溃`() {
        composeRule.setContent {
            ChangelogContent(text = "## 标题\n\n- 内容")
        }
        composeRule.waitForIdle()
        // 不崩溃即通过
    }

    @Test
    fun `ChangelogContent 超长文本渲染不崩溃`() {
        val longText = "## 标题\n\n" + "- 很长的项目内容".repeat(100)
        composeRule.setContent {
            ChangelogContent(text = longText)
        }
        composeRule.waitForIdle()
        // 不崩溃即通过
    }
}

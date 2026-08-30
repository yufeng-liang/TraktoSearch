package com.tracktosearch.ui.component

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
    fun `parseInlineMarkdown 粗体标记正确解析`() {
        val input = "这是**粗体**文本"
        val result = parseInlineMarkdown(input)
        // AnnotatedString.toString() 返回纯文本，** 标记会被去除
        assertThat(result.toString()).isEqualTo("这是粗体文本")
        assertThat(result.toString()).contains("粗体")
    }
}

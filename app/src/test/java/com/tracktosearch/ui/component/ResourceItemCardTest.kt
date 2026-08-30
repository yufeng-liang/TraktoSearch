package com.tracktosearch.ui.component

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.tracktosearch.data.remote.dto.DiskType
import com.tracktosearch.data.remote.dto.ResourceItem
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ResourceItemCardTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun createResourceItem(): ResourceItem {
        return ResourceItem(
            name = "测试资源",
            diskType = DiskType.BAIDU,
            fileSize = "1GB",
            fileDate = "",
            fileCount = 0,
            status = "",
            url = "https://example.com",
            source = "pansou"
        )
    }

    @Test
    fun `点击卡片触发 onClick`() {
        var clicked = false
        composeRule.setContent {
            ResourceItemCard(
                item = createResourceItem(),
                onClick = { clicked = true }
            )
        }
        composeRule.onNodeWithText("测试资源").performClick()
        composeRule.waitForIdle()
        assertThat(clicked).isTrue()
    }

    @Test
    fun `长按触发 onLongClick`() {
        var longClicked = false
        composeRule.setContent {
            ResourceItemCard(
                item = createResourceItem(),
                onClick = {},
                onLongClick = { longClicked = true }
            )
        }
        composeRule.onNodeWithText("测试资源").performTouchInput { longClick() }
        composeRule.waitForIdle()
        assertThat(longClicked).isTrue()
    }
}

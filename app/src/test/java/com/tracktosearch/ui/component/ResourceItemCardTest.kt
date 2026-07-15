package com.tracktosearch.ui.component

import androidx.compose.ui.test.assertIsDisplayed
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

    private fun createResourceItem(
        name: String = "测试资源",
        diskType: DiskType = DiskType.BAIDU,
        source: String = "pansou",
        fileCount: Int = 0,
        fileDate: String = ""
    ): ResourceItem {
        return ResourceItem(
            name = name,
            diskType = diskType,
            fileSize = "1GB",
            fileDate = fileDate,
            fileCount = fileCount,
            status = "",
            url = "https://example.com",
            source = source
        )
    }

    @Test
    fun `name 正常显示`() {
        composeRule.setContent {
            ResourceItemCard(
                item = createResourceItem(name = "测试电影资源"),
                onClick = {}
            )
        }
        composeRule.onNodeWithText("测试电影资源").assertIsDisplayed()
    }

    @Test
    fun `diskType 正常显示`() {
        composeRule.setContent {
            ResourceItemCard(
                item = createResourceItem(diskType = DiskType.ALI),
                onClick = {}
            )
        }
        // Robolectric 默认加载 values/(英文)，DiskType.ALI -> "Ali"
        composeRule.onNodeWithText("Ali").assertIsDisplayed()
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
    fun `isViewed_true 时显示已查看状态`() {
        composeRule.setContent {
            ResourceItemCard(
                item = createResourceItem(),
                isViewed = true,
                onClick = {}
            )
        }
        composeRule.waitForIdle()
        // Robolectric 默认加载 values/(英文)，resource_viewed -> "Viewed"
        composeRule.onNodeWithText("Viewed").assertIsDisplayed()
    }

    @Test
    fun `isViewed_false 时正常显示`() {
        composeRule.setContent {
            ResourceItemCard(
                item = createResourceItem(),
                isViewed = false,
                onClick = {}
            )
        }
        composeRule.onNodeWithText("测试资源").assertIsDisplayed()
    }

    @Test
    fun `index 参数不影响渲染`() {
        composeRule.setContent {
            ResourceItemCard(
                item = createResourceItem(),
                onClick = {},
                index = 99
            )
        }
        composeRule.onNodeWithText("测试资源").assertIsDisplayed()
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

    @Test
    fun `onLongClick 为 null 时长按不崩溃`() {
        composeRule.setContent {
            ResourceItemCard(
                item = createResourceItem(),
                onClick = {},
                onLongClick = null
            )
        }
        composeRule.onNodeWithText("测试资源").performTouchInput { longClick() }
        composeRule.waitForIdle()
        // 不崩溃即通过
    }
}

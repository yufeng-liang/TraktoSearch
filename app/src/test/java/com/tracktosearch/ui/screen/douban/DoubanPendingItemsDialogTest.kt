package com.tracktosearch.ui.screen.douban

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 豆瓣续传选项的真实 Compose 点击行为测试。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class DoubanPendingItemsDialogTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `点击继续同步只触发继续回调`() {
        val dismissCalls = AtomicInteger(0)
        val continueCalls = AtomicInteger(0)
        val fullSyncCalls = AtomicInteger(0)

        composeRule.setContent {
            MaterialTheme {
                DoubanPendingItemsDialog(
                    pendingCount = 246,
                    onDismiss = { dismissCalls.incrementAndGet() },
                    onContinue = { continueCalls.incrementAndGet() },
                    onFullSync = { fullSyncCalls.incrementAndGet() }
                )
            }
        }

        composeRule.onNodeWithText("Continue Sync").performClick()

        assertThat(continueCalls.get()).isEqualTo(1)
        assertThat(fullSyncCalls.get()).isEqualTo(0)
        assertThat(dismissCalls.get()).isEqualTo(0)
    }

    @Test
    fun `点击完整同步只触发完整同步回调`() {
        val dismissCalls = AtomicInteger(0)
        val continueCalls = AtomicInteger(0)
        val fullSyncCalls = AtomicInteger(0)

        composeRule.setContent {
            MaterialTheme {
                DoubanPendingItemsDialog(
                    pendingCount = 246,
                    onDismiss = { dismissCalls.incrementAndGet() },
                    onContinue = { continueCalls.incrementAndGet() },
                    onFullSync = { fullSyncCalls.incrementAndGet() }
                )
            }
        }

        composeRule.onNodeWithText("Full Sync").performClick()

        assertThat(fullSyncCalls.get()).isEqualTo(1)
        assertThat(continueCalls.get()).isEqualTo(0)
        assertThat(dismissCalls.get()).isEqualTo(0)
    }

    @Test
    fun `点击取消只触发关闭回调`() {
        val dismissCalls = AtomicInteger(0)
        val continueCalls = AtomicInteger(0)
        val fullSyncCalls = AtomicInteger(0)

        composeRule.setContent {
            MaterialTheme {
                DoubanPendingItemsDialog(
                    pendingCount = 246,
                    onDismiss = { dismissCalls.incrementAndGet() },
                    onContinue = { continueCalls.incrementAndGet() },
                    onFullSync = { fullSyncCalls.incrementAndGet() }
                )
            }
        }

        composeRule.onNodeWithText("Cancel").performClick()

        assertThat(dismissCalls.get()).isEqualTo(1)
        assertThat(continueCalls.get()).isEqualTo(0)
        assertThat(fullSyncCalls.get()).isEqualTo(0)
    }
    @Test
    fun `discard pending data requires confirmation`() {
        val discardCalls = AtomicInteger(0)
        composeRule.setContent {
            MaterialTheme {
                DoubanPendingItemsDialogWithDiscard(
                    pendingCount = 12,
                    onDismiss = {},
                    onContinue = {},
                    onFullSync = {},
                    onDiscardPending = { discardCalls.incrementAndGet() }
                )
            }
        }

        composeRule.onNodeWithText("Discard pending data").performClick()
        assertThat(discardCalls.get()).isEqualTo(0)
        composeRule.onNodeWithText("Discard").performClick()
        assertThat(discardCalls.get()).isEqualTo(1)
    }
}

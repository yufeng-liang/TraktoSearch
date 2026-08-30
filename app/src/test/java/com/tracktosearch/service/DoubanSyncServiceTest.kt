package com.tracktosearch.service

import android.app.Notification
import com.google.common.truth.Truth.assertThat
import com.tracktosearch.data.remote.douban.DoubanMarkStatus
import com.tracktosearch.data.repository.DoubanSyncPreviewItem
import com.tracktosearch.data.repository.DoubanSyncProgress
import com.tracktosearch.data.repository.DoubanSyncQueueItem
import com.tracktosearch.data.repository.DoubanSyncStage
import com.tracktosearch.data.repository.DoubanSyncSubStage
import com.tracktosearch.data.repository.compactLabelRes
import com.tracktosearch.data.repository.labelRes
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DoubanSyncServiceTest {

    private val context = RuntimeEnvironment.getApplication()

    @Test
    fun `上传阶段和六个子阶段使用专用资源`() {
        assertThat(context.getString(DoubanSyncStage.UPLOADING.labelRes()))
            .isEqualTo("Uploading data")
        assertThat(context.getString(DoubanSyncStage.UPLOADING.compactLabelRes()))
            .isEqualTo("Upload")

        val expectedLabels = mapOf(
            DoubanSyncSubStage.PREPARING_UPLOAD to "Preparing upload",
            DoubanSyncSubStage.CHECKING_CONSISTENCY to "Checking consistency",
            DoubanSyncSubStage.UPLOADING_PERSONAL_DATA to "Uploading personal data",
            DoubanSyncSubStage.UPLOADING_FAILURES to "Uploading failures",
            DoubanSyncSubStage.UPLOADING_DETAILS to "Uploading details",
            DoubanSyncSubStage.FILLING_MEDIA_TYPE to "Filling media type"
        )
        expectedLabels.forEach { (subStage, expectedLabel) ->
            assertThat(context.getString(requireNotNull(subStage.labelRes())))
                .isEqualTo(expectedLabel)
        }
    }

    @Test
    fun `运行态通知从当前处理队列取值而不回退最近条目`() {
        val notification = buildNotification(
            DoubanSyncProgress(
                isRunning = true,
                current = 1,
                total = 5,
                stage = DoubanSyncStage.PARSING_DATA,
                subStage = DoubanSyncSubStage.FETCHING_DETAIL,
                recentItems = listOf(preview("old", "Old history")),
                processingItems = listOf(DoubanSyncQueueItem("live", "Live processing"))
            )
        )

        val expandedText = notification.extras
            .getCharSequence(Notification.EXTRA_SUB_TEXT)
            .toString()
        assertThat(expandedText).contains("Current: Live processing")
        assertThat(expandedText).doesNotContain("Old history")
        assertThat(expandedText).doesNotContain("Latest:")
    }

    private fun buildNotification(progress: DoubanSyncProgress): Notification {
        return buildDoubanSyncNotification(context, progress)
    }

    private fun preview(id: String, title: String) = DoubanSyncPreviewItem(
        doubanId = id,
        title = title,
        status = DoubanMarkStatus.WISH,
        rating = 4,
        markedAt = "2024-01-01"
    )
}

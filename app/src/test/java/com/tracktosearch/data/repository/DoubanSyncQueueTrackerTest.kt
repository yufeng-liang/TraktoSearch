package com.tracktosearch.data.repository

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DoubanSyncQueueTrackerTest {

    @Test
    fun `初始快照把所有条目放入待处理队列`() {
        val tracker = DoubanSyncQueueTracker(
            listOf(
                item("id-1"),
                item("id-2"),
                item("id-3")
            )
        )

        val snapshot = tracker.snapshot()

        assertThat(snapshot.processingItems).isEmpty()
        assertThat(snapshot.pendingItems.map { it.doubanId })
            .containsExactly("id-1", "id-2", "id-3")
            .inOrder()
        assertThat(snapshot.pendingItemCount).isEqualTo(3)
    }

    @Test
    fun `队列按真实 doubanId 移动并限制展示数量`() {
        val tracker = DoubanSyncQueueTracker(
            (1..8).map { item("id-$it", "条目-$it") }
        )

        tracker.start("id-2")
        tracker.start("id-1")

        val processingSnapshot = tracker.snapshot()
        assertThat(processingSnapshot.processingItems.map { it.doubanId })
            .containsExactly("id-1", "id-2")
            .inOrder()

        tracker.complete("id-2")

        val snapshot = tracker.snapshot()
        assertThat(snapshot.processingItems.map { it.doubanId })
            .containsExactly("id-1")
            .inOrder()
        assertThat(snapshot.pendingItems.map { it.doubanId })
            .containsExactly("id-3", "id-4", "id-5", "id-6", "id-7")
            .inOrder()
        assertThat(snapshot.pendingItemCount).isEqualTo(6)
    }

    @Test
    fun `重复开始和完成不会增加或恢复队列数量`() {
        val tracker = DoubanSyncQueueTracker(
            listOf(item("id-1"), item("id-2"))
        )

        tracker.start("id-1")
        tracker.start("id-1")
        assertThat(tracker.snapshot().processingItems.map { it.doubanId })
            .containsExactly("id-1")
            .inOrder()
        assertThat(tracker.snapshot().pendingItemCount).isEqualTo(1)

        tracker.complete("id-1")
        tracker.complete("id-1")
        val snapshot = tracker.snapshot()
        assertThat(snapshot.processingItems).isEmpty()
        assertThat(snapshot.pendingItems.map { it.doubanId })
            .containsExactly("id-2")
            .inOrder()
        assertThat(snapshot.pendingItemCount).isEqualTo(1)
    }

    @Test
    fun `上传子阶段归入上传主阶段`() {
        val uploadingStages = listOf(
            DoubanSyncSubStage.PREPARING_UPLOAD,
            DoubanSyncSubStage.CHECKING_CONSISTENCY,
            DoubanSyncSubStage.UPLOADING_PERSONAL_DATA,
            DoubanSyncSubStage.UPLOADING_FAILURES,
            DoubanSyncSubStage.UPLOADING_DETAILS,
            DoubanSyncSubStage.FILLING_MEDIA_TYPE
        )

        uploadingStages.forEach { subStage ->
            assertThat(stageFromSubStage(subStage)).isEqualTo(DoubanSyncStage.UPLOADING)
        }
    }

    private fun item(id: String, title: String = id) = DoubanSyncQueueItem(id, title)
}

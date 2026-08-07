package com.tracktosearch.data.repository

import com.google.common.truth.Truth.assertThat
import com.tracktosearch.data.remote.douban.DelayInfo
import com.tracktosearch.data.remote.douban.DelayType
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

class DoubanSyncProgressPublisherTest {

    @Test
    fun lateBatchCallbackCannotOverwriteNewerProgressState() {
        val progress = MutableStateFlow(
            DoubanSyncProgress(
                isRunning = true,
                total = 10,
                stage = DoubanSyncStage.PARSING_DATA
            )
        )
        val publisher = DoubanSyncProgressPublisher(progress, isCancelled = { false })
        val callback = publisher.createBatchProgressCallback(
            tracker = DoubanBatchProgressTracker(total = 10),
            recentFailures = ArrayDeque<DoubanSyncFailure>()
        )

        callback(5, "Trakt 查询", 1, "较新的条目", null)
        callback(4, "详情页", 1, "迟到的条目", null)

        assertThat(progress.value.current).isEqualTo(5)
        assertThat(progress.value.subPhase).isEqualTo("Trakt 查询")
        assertThat(progress.value.currentTitle).isEqualTo("较新的条目")
        assertThat(progress.value.cacheHitCount).isEqualTo(1)
    }

    @Test
    fun skippedItemsKeepCloudPrefetchStageAndProgressBaseline() {
        val progress = MutableStateFlow(
            DoubanSyncProgress(
                isRunning = true,
                total = 10,
                stage = DoubanSyncStage.PARSING_DATA
            )
        )
        val publisher = DoubanSyncProgressPublisher(progress, isCancelled = { false })
        val callback = publisher.createBatchProgressCallback(
            tracker = DoubanBatchProgressTracker(total = 10),
            recentFailures = ArrayDeque<DoubanSyncFailure>()
        )

        callback(3, "断点续传跳过", 0, null, null)
        callback(0, DoubanSyncSubStage.PULLING_CLOUD.name, 0, null, null)

        assertThat(progress.value.current).isEqualTo(3)
        assertThat(progress.value.subStage).isEqualTo(DoubanSyncSubStage.PULLING_CLOUD)
        assertThat(progress.value.subPhase).isEqualTo(DoubanSyncSubStage.PULLING_CLOUD.name)
    }

    @Test
    fun finalProgressUsesCancellationStateReadInsidePublisher() {
        val cancelled = AtomicBoolean(false)
        val progress = MutableStateFlow(
            DoubanSyncProgress(
                isRunning = true,
                current = 7,
                total = 10,
                stage = DoubanSyncStage.PARSING_DATA
            )
        )
        val publisher = DoubanSyncProgressPublisher(progress, isCancelled = cancelled::get)

        cancelled.set(true)
        publisher.publishFinal { current, wasCancelled ->
            current.copy(
                isRunning = false,
                isComplete = true,
                stage = if (wasCancelled) {
                    DoubanSyncStage.CANCELLING
                } else {
                    DoubanSyncStage.COMPLETED
                },
                phase = if (wasCancelled) "已取消" else "同步完成",
                isCancelling = current.isCancelling || wasCancelled
            )
        }

        assertThat(progress.value.current).isEqualTo(7)
        assertThat(progress.value.stage).isEqualTo(DoubanSyncStage.CANCELLING)
        assertThat(progress.value.phase).isEqualTo("已取消")
        assertThat(progress.value.isCancelling).isTrue()
    }

    @Test
    fun cancellationPreventsLateBatchAndDelayEventsFromRestoringRunningState() {
        val progress = MutableStateFlow(
            DoubanSyncProgress(
                isRunning = true,
                total = 10,
                stage = DoubanSyncStage.PARSING_DATA
            )
        )
        val publisher = DoubanSyncProgressPublisher(progress, isCancelled = { true })
        val callback = publisher.createBatchProgressCallback(
            tracker = DoubanBatchProgressTracker(total = 10),
            recentFailures = ArrayDeque<DoubanSyncFailure>()
        )

        publisher.publishCancelling()
        callback(5, "Trakt 查询", 0, "不应显示", null)
        publisher.publishDelay(
            DelayInfo(
                type = DelayType.DOUBAN_DETAIL_CRAWL,
                totalSeconds = 3,
                startMs = 1_000L
            )
        )

        assertThat(progress.value.isCancelling).isTrue()
        assertThat(progress.value.stage).isEqualTo(DoubanSyncStage.CANCELLING)
        assertThat(progress.value.phase).isEqualTo("正在取消...")
        assertThat(progress.value.current).isEqualTo(0)
        assertThat(progress.value.currentTitle).isNull()
        assertThat(progress.value.delayInfo).isNull()
    }

    @Test
    fun `批次回调标题为空时使用活动队列首项`() {
        val progress = MutableStateFlow(
            DoubanSyncProgress(
                isRunning = true,
                total = 10,
                stage = DoubanSyncStage.PARSING_DATA
            )
        )
        val publisher = DoubanSyncProgressPublisher(progress, isCancelled = { false })
        publisher.publishQueue(
            DoubanSyncQueueSnapshot(
                processingItems = listOf(
                    DoubanSyncQueueItem("id-2", "正在处理二"),
                    DoubanSyncQueueItem("id-1", "正在处理一")
                ),
                pendingItems = listOf(DoubanSyncQueueItem("id-3", "待处理三")),
                pendingItemCount = 4
            )
        )

        val callback = publisher.createBatchProgressCallback(
            tracker = DoubanBatchProgressTracker(total = 10),
            recentFailures = ArrayDeque<DoubanSyncFailure>()
        )
        callback(1, "详情页", 0, null, null)

        assertThat(progress.value.currentTitle).isEqualTo("正在处理二")
        assertThat(progress.value.processingItems.map { it.doubanId })
            .containsExactly("id-2", "id-1")
            .inOrder()
        assertThat(progress.value.pendingItemCount).isEqualTo(4)
    }

    @Test
    fun `发布阶段更新上传主阶段和子阶段`() {
        val progress = MutableStateFlow(
            DoubanSyncProgress(isRunning = true, stage = DoubanSyncStage.PARSING_DATA)
        )
        val publisher = DoubanSyncProgressPublisher(progress, isCancelled = { false })

        publisher.publishStage(
            stage = DoubanSyncStage.UPLOADING,
            subStage = DoubanSyncSubStage.PREPARING_UPLOAD,
            phase = "准备上传"
        )

        assertThat(progress.value.stage).isEqualTo(DoubanSyncStage.UPLOADING)
        assertThat(progress.value.subStage).isEqualTo(DoubanSyncSubStage.PREPARING_UPLOAD)
        assertThat(progress.value.current).isEqualTo(0)
        assertThat(progress.value.total).isEqualTo(0)
        assertThat(progress.value.etaSeconds).isEqualTo(DoubanSyncEtaEstimator.UNKNOWN_ETA_SECONDS)
        assertThat(progress.value.currentTitle).isNull()
        assertThat(progress.value.phase).isEqualTo("准备上传")
    }

    @Test
    fun `清理队列会移除活动项和待处理项`() {
        val progress = MutableStateFlow(DoubanSyncProgress(isRunning = true))
        val publisher = DoubanSyncProgressPublisher(progress, isCancelled = { false })
        publisher.publishQueue(
            DoubanSyncQueueSnapshot(
                processingItems = listOf(DoubanSyncQueueItem("id-1", "条目一")),
                pendingItems = listOf(DoubanSyncQueueItem("id-2", "条目二")),
                pendingItemCount = 1
            ),
            currentTitle = "条目一"
        )

        publisher.clearQueue()

        assertThat(progress.value.processingItems).isEmpty()
        assertThat(progress.value.pendingItems).isEmpty()
        assertThat(progress.value.pendingItemCount).isEqualTo(0)
        assertThat(progress.value.currentTitle).isNull()
    }

    @Test
    fun `取消后拒绝迟到的队列和阶段更新`() {
        val progress = MutableStateFlow(
            DoubanSyncProgress(
                isRunning = true,
                stage = DoubanSyncStage.PARSING_DATA,
                processingItems = listOf(DoubanSyncQueueItem("id-1", "条目一")),
                pendingItems = listOf(DoubanSyncQueueItem("id-2", "条目二")),
                pendingItemCount = 1,
                currentTitle = "条目一"
            )
        )
        val publisher = DoubanSyncProgressPublisher(progress, isCancelled = { false })

        publisher.publishCancelling()
        publisher.publishQueue(
            DoubanSyncQueueSnapshot(
                processingItems = listOf(DoubanSyncQueueItem("id-3", "迟到条目")),
                pendingItemCount = 0
            ),
            currentTitle = "迟到条目"
        )
        publisher.publishStage(
            stage = DoubanSyncStage.UPLOADING,
            subStage = DoubanSyncSubStage.UPLOADING_DETAILS,
            phase = "迟到上传"
        )

        assertThat(progress.value.stage).isEqualTo(DoubanSyncStage.CANCELLING)
        assertThat(progress.value.phase).isEqualTo("正在取消...")
        assertThat(progress.value.processingItems).isEmpty()
        assertThat(progress.value.pendingItems).isEmpty()
        assertThat(progress.value.pendingItemCount).isEqualTo(0)
        assertThat(progress.value.currentTitle).isNull()
    }
}

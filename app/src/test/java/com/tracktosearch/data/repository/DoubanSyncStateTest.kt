package com.tracktosearch.data.repository

import com.google.common.truth.Truth.assertThat
import com.tracktosearch.data.remote.douban.DoubanMarkStatus
import org.junit.Test

class DoubanSyncStateTest {

    @Test
    fun `最近预览跨页面去重并限制为五条`() {
        val buffer = DoubanSyncPreviewBuffer(maxSize = 5)

        buffer.addAll(
            listOf(
                preview("1", "旧一"),
                preview("2", "旧二"),
                preview("3", "旧三")
            )
        )
        buffer.addAll(
            listOf(
                preview("3", "新三"),
                preview("4", "新四"),
                preview("5", "新五"),
                preview("6", "新六")
            )
        )

        assertThat(buffer.snapshot().map { it.doubanId })
            .containsExactly("6", "5", "4", "3", "2")
            .inOrder()
        assertThat(buffer.snapshot().first { it.doubanId == "3" }.title).isEqualTo("新三")
    }

    @Test
    fun `相同豆瓣条目在不同状态下分别保留`() {
        val buffer = DoubanSyncPreviewBuffer(maxSize = 5)

        buffer.addAll(listOf(preview("same", "想看", DoubanMarkStatus.WISH)))
        buffer.addAll(listOf(preview("same", "看过", DoubanMarkStatus.COLLECT)))

        assertThat(buffer.snapshot().map { it.status })
            .containsExactly(DoubanMarkStatus.COLLECT, DoubanMarkStatus.WISH)
            .inOrder()
    }

    @Test
    fun `同步阶段只有稳定终态标识`() {
        assertThat(DoubanSyncStage.entries.filter { it.isTerminal })
            .containsExactly(
                DoubanSyncStage.COMPLETED,
                DoubanSyncStage.LOGIN_REQUIRED,
                DoubanSyncStage.CANCELLING,
                DoubanSyncStage.FAILED
            )
    }

    @Test
    fun `列表子阶段映射到获取列表主阶段`() {
        assertThat(stageFromSubStage(DoubanSyncSubStage.FETCHING_WISH_LIST))
            .isEqualTo(DoubanSyncStage.FETCHING_LIST)
        assertThat(stageFromSubStage(DoubanSyncSubStage.FETCHING_COLLECT_LIST))
            .isEqualTo(DoubanSyncStage.FETCHING_LIST)
        assertThat(stageFromSubStage(DoubanSyncSubStage.FETCHING_DETAIL))
            .isEqualTo(DoubanSyncStage.PARSING_DATA)
        assertThat(stageFromSubStage(DoubanSyncSubStage.WRITING_TARGET))
            .isEqualTo(DoubanSyncStage.UPDATING_LIST)
    }

    private fun preview(
        id: String,
        title: String = id,
        status: DoubanMarkStatus = DoubanMarkStatus.WISH
    ) = DoubanSyncPreviewItem(
        doubanId = id,
        title = title,
        status = status,
        rating = 4,
        markedAt = "2024-01-01"
    )
}

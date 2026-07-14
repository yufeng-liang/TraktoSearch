package com.tracktosearch.data.repository

import com.google.common.truth.Truth.assertThat
import com.tracktosearch.data.local.db.DoubanSyncFailureEntity
import com.tracktosearch.data.remote.douban.DoubanMarkItem
import com.tracktosearch.data.remote.douban.DoubanMarkStatus
import org.junit.Test

class DoubanSyncFailureTest {

    @Test
    fun fromEntity_toEntity_roundTripPreservesFields() {
        val original = DoubanSyncFailure(
            doubanId = "123456",
            title = "盗梦空间",
            posterUrl = "https://img.example.com/p.jpg",
            rating = 5,
            comment = "层层梦境",
            markedAt = "2024-01-15",
            doubanUrl = "https://book.douban.com/subject/123456/",
            status = DoubanMarkStatus.COLLECT,
            failureReason = FailureReason.TRAKT_WRITE_FAILED,
            failedAt = 1700000000L,
            updatedAt = 1700000500L,
            attemptCount = 3,
            mediaType = "movie",
            mediaTypeCleared = true,
            subtitle = "Inception"
        )

        val roundTripped = DoubanSyncFailure.fromEntity(original.toEntity())

        assertThat(roundTripped.doubanId).isEqualTo(original.doubanId)
        assertThat(roundTripped.title).isEqualTo(original.title)
        assertThat(roundTripped.posterUrl).isEqualTo(original.posterUrl)
        assertThat(roundTripped.rating).isEqualTo(original.rating)
        assertThat(roundTripped.comment).isEqualTo(original.comment)
        assertThat(roundTripped.markedAt).isEqualTo(original.markedAt)
        assertThat(roundTripped.doubanUrl).isEqualTo(original.doubanUrl)
        assertThat(roundTripped.status).isEqualTo(original.status)
        assertThat(roundTripped.failureReason).isEqualTo(original.failureReason)
        assertThat(roundTripped.failedAt).isEqualTo(original.failedAt)
        assertThat(roundTripped.updatedAt).isEqualTo(original.updatedAt)
        assertThat(roundTripped.attemptCount).isEqualTo(original.attemptCount)
        assertThat(roundTripped.mediaType).isEqualTo(original.mediaType)
        assertThat(roundTripped.mediaTypeCleared).isEqualTo(original.mediaTypeCleared)
        assertThat(roundTripped.subtitle).isEqualTo(original.subtitle)
    }

    @Test
    fun toEntity_serializesStatusAndFailureReason() {
        val failure = DoubanSyncFailure(
            doubanId = "1",
            title = "t",
            posterUrl = null,
            rating = null,
            comment = null,
            markedAt = "2024-01-01",
            doubanUrl = "u",
            status = DoubanMarkStatus.WISH,
            failureReason = FailureReason.NO_IMDB_ID,
            failedAt = 100L
        )

        val entity = failure.toEntity()

        assertThat(entity.status).isEqualTo("wish")
        assertThat(entity.failureReason).isEqualTo("NO_IMDB_ID")
    }

    @Test
    fun fromMarkItem_mapsFieldsCorrectly() {
        val item = DoubanMarkItem(
            doubanId = "999",
            title = "星际穿越",
            rating = 4,
            comment = "震撼",
            markedAt = "2024-05-20",
            doubanUrl = "https://book.douban.com/subject/999/",
            posterUrl = "https://img.example.com/x.jpg"
        )

        val failure = DoubanSyncFailure.fromMarkItem(
            item = item,
            status = DoubanMarkStatus.WISH,
            reason = FailureReason.DETAIL_FETCH_FAILED
        )

        assertThat(failure.doubanId).isEqualTo("999")
        assertThat(failure.title).isEqualTo("星际穿越")
        assertThat(failure.posterUrl).isEqualTo("https://img.example.com/x.jpg")
        assertThat(failure.rating).isEqualTo(4)
        assertThat(failure.comment).isEqualTo("震撼")
        assertThat(failure.markedAt).isEqualTo("2024-05-20")
        assertThat(failure.doubanUrl).isEqualTo("https://book.douban.com/subject/999/")
        assertThat(failure.status).isEqualTo(DoubanMarkStatus.WISH)
        assertThat(failure.failureReason).isEqualTo(FailureReason.DETAIL_FETCH_FAILED)
        // failedAt 来自 System.currentTimeMillis(),只验证被设置了
        assertThat(failure.failedAt).isGreaterThan(0L)
        // 默认值
        assertThat(failure.updatedAt).isEqualTo(0L)
        assertThat(failure.attemptCount).isEqualTo(0)
        assertThat(failure.mediaType).isNull()
        assertThat(failure.mediaTypeCleared).isFalse()
        assertThat(failure.subtitle).isNull()
    }

    @Test
    fun toMarkItem_mapsFieldsCorrectly() {
        val failure = DoubanSyncFailure(
            doubanId = "42",
            title = "信条",
            posterUrl = "https://img.example.com/t.jpg",
            rating = 3,
            comment = "时间逆转",
            markedAt = "2024-08-08",
            doubanUrl = "https://book.douban.com/subject/42/",
            status = DoubanMarkStatus.COLLECT,
            failureReason = FailureReason.TRAKT_NOT_FOUND,
            failedAt = 200L,
            updatedAt = 300L,
            attemptCount = 2,
            mediaType = "movie",
            mediaTypeCleared = false,
            subtitle = "Tenet"
        )

        val item = failure.toMarkItem()

        assertThat(item.doubanId).isEqualTo("42")
        assertThat(item.title).isEqualTo("信条")
        assertThat(item.rating).isEqualTo(3)
        assertThat(item.comment).isEqualTo("时间逆转")
        assertThat(item.markedAt).isEqualTo("2024-08-08")
        assertThat(item.doubanUrl).isEqualTo("https://book.douban.com/subject/42/")
        assertThat(item.posterUrl).isEqualTo("https://img.example.com/t.jpg")
    }

    @Test
    fun mediaTypeCleared_defaultsToFalse() {
        val failure = DoubanSyncFailure(
            doubanId = "1",
            title = "t",
            posterUrl = null,
            rating = null,
            comment = null,
            markedAt = "2024-01-01",
            doubanUrl = "u",
            status = DoubanMarkStatus.WISH,
            failureReason = FailureReason.NO_IMDB_ID,
            failedAt = 0L
        )

        assertThat(failure.mediaTypeCleared).isFalse()
    }

    @Test
    fun failureReason_fromString_validName_returnsCorrectEnum() {
        assertThat(FailureReason.fromString("NO_IMDB_ID"))
            .isEqualTo(FailureReason.NO_IMDB_ID)
    }

    @Test
    fun failureReason_fromString_validDisplayKey_returnsCorrectEnum() {
        assertThat(FailureReason.fromString("no_imdb_id"))
            .isEqualTo(FailureReason.NO_IMDB_ID)
    }

    @Test
    fun failureReason_fromString_null_returnsDefault() {
        // 源码容错:未知值降级为 DETAIL_FETCH_FAILED
        assertThat(FailureReason.fromString(null))
            .isEqualTo(FailureReason.DETAIL_FETCH_FAILED)
    }

    @Test
    fun failureReason_fromString_unknownValue_returnsDefault() {
        // 源码容错:未知值降级为 DETAIL_FETCH_FAILED
        assertThat(FailureReason.fromString("未知值"))
            .isEqualTo(FailureReason.DETAIL_FETCH_FAILED)
    }

    @Test
    fun failureReason_allValuesRoundTripByName() {
        // 验证所有枚举值都能通过 name 反序列化(等价于 entity 中存储的 FailureReason.name)
        FailureReason.entries.forEach { reason ->
            val parsed = FailureReason.fromString(reason.name)
            assertThat(parsed).isEqualTo(reason)
        }
    }

    @Test
    fun failureReason_allValuesRoundTripByDisplayKey() {
        // 验证所有枚举值都能通过 displayKey 反序列化
        FailureReason.entries.forEach { reason ->
            val parsed = FailureReason.fromString(reason.displayKey)
            assertThat(parsed).isEqualTo(reason)
        }
    }

    @Test
    fun failureReason_recoverableFlags_areCorrect() {
        // 不可恢复(默认跳过)
        assertThat(FailureReason.NO_IMDB_ID.recoverable).isFalse()
        assertThat(FailureReason.TRAKT_NOT_FOUND.recoverable).isFalse()

        // 可恢复(默认重试)
        assertThat(FailureReason.DETAIL_FETCH_FAILED.recoverable).isTrue()
        assertThat(FailureReason.TRAKT_WRITE_TIMEOUT.recoverable).isTrue()
        assertThat(FailureReason.TRAKT_WRITE_FAILED.recoverable).isTrue()
    }
}

package com.tracktosearch.data.remote.douban.dto

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.junit.Test

/**
 * 豆瓣短评 DTO 的字段映射测试。
 *
 * 只验证 JSON 键与字段的对应关系，不依赖 Android/Hilt 运行时，可在纯 JVM 下运行。
 * 重点是 vote_count 缺失时必须保持 null：UI 侧「0 与缺失都不显示点赞数」据此成立。
 */
class DoubanRexxarInterestDtoTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun voteCount_isMappedFromSnakeCaseKey() {
        val dto = json.decodeFromString(
            DoubanRexxarInterestDto.serializer(),
            """{"id":"1","comment":"很好","create_time":"2024-01-02 03:04:05","vote_count":26473,"rating":{"value":4.0,"max":5},"user":{"name":"观众"}}"""
        )

        assertThat(dto.voteCount).isEqualTo(26473)
        assertThat(dto.createTime).isEqualTo("2024-01-02 03:04:05")
        assertThat(dto.comment).isEqualTo("很好")
    }

    @Test
    fun voteCount_missingKeyStaysNull() {
        val dto = json.decodeFromString(
            DoubanRexxarInterestDto.serializer(),
            """{"id":"1","comment":"很好"}"""
        )

        assertThat(dto.voteCount).isNull()
    }
}

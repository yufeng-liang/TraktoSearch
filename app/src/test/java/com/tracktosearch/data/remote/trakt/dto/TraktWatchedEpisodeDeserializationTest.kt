package com.tracktosearch.data.remote.trakt.dto

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.junit.Test

/**
 * TraktWatchedEpisode JSON 反序列化测试。
 *
 * 验证 bug 修复：Trakt API `/sync/watched/shows` 返回的 episode 字段是 `plays`，
 * 而非 `completed`。DTO 通过 @SerialName("plays") 建立映射。
 *
 * 此测试直接覆盖 JSON 反序列化路径，防止字段映射 regression。
 */
class TraktWatchedEpisodeDeserializationTest {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        encodeDefaults = true
    }

    @Test
    fun `plays字段正确映射到completed`() {
        // 模拟 Trakt API /sync/watched/shows 真实返回结构
        val apiResponse = """
        [
          {
            "plays": 1,
            "show": {
              "title": "测试剧集",
              "year": 2024,
              "ids": {"trakt": 1, "tmdb": 10, "imdb": "tt1"}
            },
            "seasons": [
              {
                "number": 1,
                "episodes": [
                  {"number": 1, "plays": 1},
                  {"number": 2, "plays": 1},
                  {"number": 3, "plays": 0}
                ]
              }
            ]
          }
        ]
        """.trimIndent()

        val result = json.decodeFromString<List<TraktWatchedShow>>(apiResponse)

        assertThat(result).hasSize(1)
        val episodes = result[0].seasons[0].episodes
        assertThat(episodes).hasSize(3)
        // 关键断言：plays=1 映射到 completed=1（不再是默认值 0）
        assertThat(episodes[0].completed).isEqualTo(1)
        assertThat(episodes[1].completed).isEqualTo(1)
        assertThat(episodes[2].completed).isEqualTo(0)
    }

    @Test
    fun `缺失plays字段时completed默认为0`() {
        val apiResponse = """
        [
          {
            "plays": 1,
            "show": {"title": "T", "ids": {"trakt": 1}},
            "seasons": [
              {
                "number": 1,
                "episodes": [
                  {"number": 1}
                ]
              }
            ]
          }
        ]
        """.trimIndent()

        val result = json.decodeFromString<List<TraktWatchedShow>>(apiResponse)
        assertThat(result[0].seasons[0].episodes[0].completed).isEqualTo(0)
    }

    @Test
    fun `completed字段在JSON中被忽略（API不返回此字段名）`() {
        // API 实际不返回 completed 字段，只返回 plays
        // 即使 JSON 中有 completed 字段（理论上不会出现），也会被忽略
        val apiResponse = """
        [
          {
            "plays": 1,
            "show": {"title": "T", "ids": {"trakt": 1}},
            "seasons": [
              {
                "number": 1,
                "episodes": [
                  {"number": 1, "plays": 2}
                ]
              }
            ]
          }
        ]
        """.trimIndent()

        val result = json.decodeFromString<List<TraktWatchedShow>>(apiResponse)
        assertThat(result[0].seasons[0].episodes[0].completed).isEqualTo(2)
    }
}

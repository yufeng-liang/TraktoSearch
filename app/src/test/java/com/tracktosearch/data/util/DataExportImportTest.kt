package com.tracktosearch.data.util

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.junit.Test

class DataExportImportTest {

    // ==================== exportToJson 测试 ====================

    @Test
    fun exportToJson_emptyLists_returnsValidJson() {
        val json = DataExportImport.exportToJson(
            watchlistMovies = emptyList(),
            watchlistShows = emptyList(),
            historyMovies = emptyList(),
            historyShows = emptyList()
        )
        assertThat(json).isNotEmpty()
        assertThat(json).contains("watchlistMovies")
        assertThat(json).contains("watchlistShows")
        assertThat(json).contains("historyMovies")
        assertThat(json).contains("historyShows")

        // 解析回来验证空数组
        val parsed = Json.decodeFromString<ExportData>(json)
        assertThat(parsed.watchlistMovies).isEmpty()
        assertThat(parsed.watchlistShows).isEmpty()
        assertThat(parsed.historyMovies).isEmpty()
        assertThat(parsed.historyShows).isEmpty()
    }

    @Test
    fun exportToJson_withData_isRoundTripConsistent() {
        val watchlistMovies = listOf(
            ExportItem(
                title = "Inception",
                tmdbId = 27205,
                imdbId = "tt1375666",
                watchedAt = "2024-01-01"
            )
        )
        val historyShows = listOf(
            ExportItem(
                title = "Breaking Bad",
                tmdbId = 1396,
                imdbId = "tt0903747",
                watchedAt = "2024-02-15"
            )
        )
        val json = DataExportImport.exportToJson(
            watchlistMovies = watchlistMovies,
            watchlistShows = emptyList(),
            historyMovies = emptyList(),
            historyShows = historyShows
        )

        val parsed = Json.decodeFromString<ExportData>(json)
        assertThat(parsed.watchlistMovies).hasSize(1)
        assertThat(parsed.historyShows).hasSize(1)

        val movie = parsed.watchlistMovies.first()
        assertThat(movie.title).isEqualTo("Inception")
        assertThat(movie.tmdbId).isEqualTo(27205)
        assertThat(movie.imdbId).isEqualTo("tt1375666")
        assertThat(movie.watchedAt).isEqualTo("2024-01-01")

        val show = parsed.historyShows.first()
        assertThat(show.title).isEqualTo("Breaking Bad")
        assertThat(show.tmdbId).isEqualTo(1396)
        assertThat(show.imdbId).isEqualTo("tt0903747")
        assertThat(show.watchedAt).isEqualTo("2024-02-15")
    }

    // ==================== parseImdbCsv 测试 ====================

    @Test
    fun parseImdbCsv_standardCsv_parsesCorrectly() {
        val csv = """
            Position,Const,Created,Modified,Description,Title,URL,Title Type
            1,tt0111161,2024-01-15,,Desc,The Shawshank Redemption,https://example.com,movie
            2,tt0903747,2024-04-01,,Desc,Breaking Bad,https://example.com,tvSeries
        """.trimIndent()
        val result = DataExportImport.parseImdbCsv(csv)

        assertThat(result).isInstanceOf(ParseResult.Success::class.java)
        val items = (result as ParseResult.Success).items
        assertThat(items).hasSize(2)

        val movie = items[0]
        assertThat(movie.title).isEqualTo("The Shawshank Redemption")
        assertThat(movie.watchedAt).isEqualTo("2024-01-15")
        assertThat(movie.mediaType).isEqualTo("movie")

        val show = items[1]
        assertThat(show.title).isEqualTo("Breaking Bad")
        assertThat(show.watchedAt).isEqualTo("2024-04-01")
        assertThat(show.mediaType).isEqualTo("show")
    }

    @Test
    fun parseImdbCsv_quotedFieldWithComma_parsesAsSingleField() {
        val csv = """
            Position,Const,Created,Modified,Description,Title,URL,Title Type
            1,tt0068646,2024-02-20,,,"The Godfather, Part II",https://example.com,movie
        """.trimIndent()
        val result = DataExportImport.parseImdbCsv(csv)

        assertThat(result).isInstanceOf(ParseResult.Success::class.java)
        val items = (result as ParseResult.Success).items
        assertThat(items).hasSize(1)
        // 引号内逗号不应分割字段
        assertThat(items[0].title).isEqualTo("The Godfather, Part II")
        assertThat(items[0].mediaType).isEqualTo("movie")
    }

    @Test
    fun parseImdbCsv_quotedFieldWithNewline_parsesAsSingleField() {
        // 引号内换行不分割（使用 \n 显式构造，避免 trimIndent 干扰）
        val csv = "Position,Const,Created,Modified,Description,Title,URL,Title Type\n" +
            "1,tt0468569,2024-03-10,,,\"The Dark\nKnight\",https://example.com,movie"
        val result = DataExportImport.parseImdbCsv(csv)

        assertThat(result).isInstanceOf(ParseResult.Success::class.java)
        val items = (result as ParseResult.Success).items
        assertThat(items).hasSize(1)
        // 引号内换行应作为标题的一部分
        assertThat(items[0].title).isEqualTo("The Dark\nKnight")
        assertThat(items[0].watchedAt).isEqualTo("2024-03-10")
    }

    @Test
    fun parseImdbCsv_fixtureFile_parsesAllRowsCorrectly() {
        val csv = javaClass.getResourceAsStream("/fixtures/imdb/ratings.csv")
            ?.bufferedReader()?.use { it.readText() }
            ?.replace("\r\n", "\n")
            ?.replace('\r', '\n')
            ?: error("fixture 文件未找到: /fixtures/imdb/ratings.csv")
        val result = DataExportImport.parseImdbCsv(csv)

        assertThat(result).isInstanceOf(ParseResult.Success::class.java)
        val items = (result as ParseResult.Success).items
        // fixture 含 4 行数据
        assertThat(items).hasSize(4)

        // 标准行
        assertThat(items[0].title).isEqualTo("The Shawshank Redemption")
        assertThat(items[0].mediaType).isEqualTo("movie")

        // 引号内逗号
        assertThat(items[1].title).isEqualTo("The Godfather, Part II")

        // 引号内换行
        assertThat(items[2].title).isEqualTo("The Dark\nKnight")

        // TV 系列 → show
        assertThat(items[3].title).isEqualTo("Breaking Bad")
        assertThat(items[3].mediaType).isEqualTo("show")
    }

    @Test
    fun parseImdbCsv_missingRequiredColumn_returnsError() {
        // 缺少 Title、Created、Title Type 中的某些列
        val csv = "Position,Const,Year\n1,tt0111161,1994\n"
        val result = DataExportImport.parseImdbCsv(csv)

        assertThat(result).isEqualTo(ParseResult.MissingRequiredColumns)
    }

    @Test
    fun parseImdbCsv_emptyContent_returnsEmptyOrError() {
        val result = DataExportImport.parseImdbCsv("")
        // 空字符串 → 行数 < 2 → Empty
        assertThat(result).isEqualTo(ParseResult.Empty)
    }

    @Test
    fun parseImdbCsv_malformedFormat_doesNotCrash() {
        // 格式错误：仅逗号、无有效表头
        val csv1 = ",,,,"
        val result1 = DataExportImport.parseImdbCsv(csv1)
        assertThat(result1).isInstanceOf(ParseResult::class.java)

        // 格式错误：纯文本非 CSV
        val csv2 = "this is not a csv file"
        val result2 = DataExportImport.parseImdbCsv(csv2)
        assertThat(result2).isInstanceOf(ParseResult::class.java)

        // 格式错误：未闭合的引号（不应崩溃）
        val csv3 = "title,created,title type\n\"unclosed quote,movie"
        val result3 = DataExportImport.parseImdbCsv(csv3)
        assertThat(result3).isInstanceOf(ParseResult::class.java)
    }
}

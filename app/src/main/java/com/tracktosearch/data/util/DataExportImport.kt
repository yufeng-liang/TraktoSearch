package com.tracktosearch.data.util

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Serializable
data class ExportData(
    val exportDate: String,
    val watchlistMovies: List<ExportItem>,
    val watchlistShows: List<ExportItem>,
    val historyMovies: List<ExportItem>,
    val historyShows: List<ExportItem>
)

@Serializable
data class ExportItem(
    val title: String,
    val tmdbId: Int? = null,
    val imdbId: String? = null,
    val watchedAt: String? = null
)

data class ImportItem(
    val title: String,
    val watchedAt: String? = null,
    val source: String,
    val mediaType: String? = null  // "movie" or "show", null means unknown
)

/**
 * IMDb CSV 解析结果。
 * - [Success] 解析成功,返回 [ImportItem] 列表
 * - [MissingRequiredColumns] 缺少 IMDb 必填列(Title、Created、Title Type)
 * - [Empty] CSV 为空或没有数据行
 * - [Error] 其他解析异常
 */
sealed class ParseResult {
    data class Success(val items: List<ImportItem>) : ParseResult()
    object MissingRequiredColumns : ParseResult()
    object Empty : ParseResult()
    data class Error(val message: String) : ParseResult()
}

object DataExportImport {

    private val json = Json {
        prettyPrint = true
        encodeDefaults = false
        ignoreUnknownKeys = true
    }

    fun exportToJson(
        watchlistMovies: List<ExportItem>,
        watchlistShows: List<ExportItem>,
        historyMovies: List<ExportItem>,
        historyShows: List<ExportItem>
    ): String {
        val data = ExportData(
            exportDate = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                .format(Date()),
            watchlistMovies = watchlistMovies,
            watchlistShows = watchlistShows,
            historyMovies = historyMovies,
            historyShows = historyShows
        )
        return json.encodeToString(data)
    }

    /**
     * 解析 IMDb CSV 格式（想看列表导出）。
     * 表头示例: Position,Const,Created,Modified,Description,Title,URL,Title Type,...
     * Title Type 值: movie, tvSeries, tvMiniSeries, short, tvMovie, video, etc.
     *
     * 必填列: Title、Created、Title Type,任一缺失返回 [ParseResult.MissingRequiredColumns]。
     */
    fun parseImdbCsv(csvContent: String): ParseResult {
        return try {
            val rows = parseCsv(csvContent)
            if (rows.size < 2) return ParseResult.Empty

            val header = rows.first().map { it.trim().lowercase() }
            // IMDb CSV 必填列:Title、Created、Title Type
            val requiredCols = listOf("title", "created", "title type")
            if (!requiredCols.all { col -> header.any { it == col } }) {
                return ParseResult.MissingRequiredColumns
            }

            val dataRows = rows.drop(1)
            if (dataRows.isEmpty()) return ParseResult.Empty

            val titleIdx = header.indexOfFirst { it == "title" }
            val createdIdx = header.indexOfFirst { it == "created" }
            val titleTypeIdx = header.indexOfFirst { it == "title type" }

            val items = dataRows.mapNotNull { row ->
                if (titleIdx >= row.size) return@mapNotNull null
                val title = row[titleIdx].trim()
                if (title.isEmpty()) return@mapNotNull null
                val watchedAt = if (createdIdx >= 0 && createdIdx < row.size) {
                    row[createdIdx].trim().ifEmpty { null }
                } else null
                val mediaType = if (titleTypeIdx >= 0 && titleTypeIdx < row.size) {
                    val titleType = row[titleTypeIdx].trim().lowercase()
                    when {
                        titleType.contains("tvseries") || titleType.contains("tvminiseries") -> "show"
                        titleType.contains("movie") || titleType.contains("short") || titleType.contains("video") -> "movie"
                        else -> null
                    }
                } else null
                ImportItem(
                    title = title,
                    watchedAt = watchedAt,
                    source = "IMDb",
                    mediaType = mediaType
                )
            }
            ParseResult.Success(items)
        } catch (e: Exception) {
            ParseResult.Error(e.message ?: "Unknown error")
        }
    }

    /**
     * 通用 CSV 解析器，支持带引号的字段（引号内可包含逗号和换行）。
     */
    private fun parseCsv(content: String): List<List<String>> {
        val result = mutableListOf<List<String>>()
        val currentField = StringBuilder()
        val currentRow = mutableListOf<String>()
        var inQuotes = false
        var i = 0
        while (i < content.length) {
            val c = content[i]
            when {
                c == '"' && inQuotes && i + 1 < content.length && content[i + 1] == '"' -> {
                    currentField.append('"')
                    i += 2
                    continue
                }
                c == '"' -> inQuotes = !inQuotes
                c == ',' && !inQuotes -> {
                    currentRow.add(currentField.toString())
                    currentField.clear()
                }
                (c == '\n' || c == '\r') && !inQuotes -> {
                    if (c == '\r' && i + 1 < content.length && content[i + 1] == '\n') i++
                    currentRow.add(currentField.toString())
                    currentField.clear()
                    if (currentRow.any { it.isNotBlank() }) result.add(currentRow.toList())
                    currentRow.clear()
                }
                else -> currentField.append(c)
            }
            i++
        }
        if (currentField.isNotEmpty() || currentRow.isNotEmpty()) {
            currentRow.add(currentField.toString())
            if (currentRow.any { it.isNotBlank() }) result.add(currentRow.toList())
        }
        return result
    }
}

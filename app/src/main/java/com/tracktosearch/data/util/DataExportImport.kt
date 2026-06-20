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
    val source: String
)

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

    fun exportToCsv(movies: List<ExportItem>, shows: List<ExportItem>): String {
        val sb = StringBuilder()
        sb.appendLine("Type,Title,IMDb ID,TMDB ID,Watched At")
        movies.forEach { item ->
            sb.appendLine(buildCsvRow("Movie", item))
        }
        shows.forEach { item ->
            sb.appendLine(buildCsvRow("Show", item))
        }
        return sb.toString()
    }

    private fun buildCsvRow(type: String, item: ExportItem): String {
        return listOf(
            type,
            csvEscape(item.title),
            csvEscape(item.imdbId ?: ""),
            item.tmdbId?.toString() ?: "",
            csvEscape(item.watchedAt ?: "")
        ).joinToString(",")
    }

    private fun csvEscape(value: String): String {
        return if (value.contains(',') || value.contains('"') || value.contains('\n')) {
            "\"${value.replace("\"", "\"\"")}\""
        } else {
            value
        }
    }

    /**
     * 解析 Letterboxd CSV 格式。
     * 支持两种格式：
     * - 想看列表: Position,Name,Year,Letterboxd URI,Tags
     * - 观影日记: Date,Name,Year,Letterboxd URI,Rating,Rewatch,...
     */
    fun parseLetterboxdCsv(csvContent: String): List<ImportItem> {
        val rows = parseCsv(csvContent)
        if (rows.size < 2) return emptyList()

        val header = rows.first()
        val dataRows = rows.drop(1)

        val nameIdx = header.indexOfFirst { it.equals("Name", ignoreCase = true) }
        val dateIdx = header.indexOfFirst { it.equals("Date", ignoreCase = true) }

        if (nameIdx < 0) return emptyList()

        return dataRows.mapNotNull { row ->
            if (nameIdx >= row.size) return@mapNotNull null
            val title = row[nameIdx].trim()
            if (title.isEmpty()) return@mapNotNull null
            val watchedAt = if (dateIdx >= 0 && dateIdx < row.size) {
                row[dateIdx].trim().ifEmpty { null }
            } else null
            ImportItem(
                title = title,
                watchedAt = watchedAt,
                source = "Letterboxd"
            )
        }
    }

    /**
     * 解析 IMDb CSV 格式（想看列表导出）。
     * 表头示例: Position,Const,Created,Modified,Description,Title,URL,Title Type,...
     */
    fun parseImdbCsv(csvContent: String): List<ImportItem> {
        val rows = parseCsv(csvContent)
        if (rows.size < 2) return emptyList()

        val header = rows.first()
        val dataRows = rows.drop(1)

        val titleIdx = header.indexOfFirst { it.equals("Title", ignoreCase = true) }
        val createdIdx = header.indexOfFirst { it.equals("Created", ignoreCase = true) }

        if (titleIdx < 0) return emptyList()

        return dataRows.mapNotNull { row ->
            if (titleIdx >= row.size) return@mapNotNull null
            val title = row[titleIdx].trim()
            if (title.isEmpty()) return@mapNotNull null
            val watchedAt = if (createdIdx >= 0 && createdIdx < row.size) {
                row[createdIdx].trim().ifEmpty { null }
            } else null
            ImportItem(
                title = title,
                watchedAt = watchedAt,
                source = "IMDb"
            )
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

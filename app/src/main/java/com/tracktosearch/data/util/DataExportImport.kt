package com.tracktosearch.data.util

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.security.MessageDigest

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

/**
 * 导出文件验签结果。
 * - [Valid] 签名有效,返回解析后的 [ExportData]
 * - [InvalidSignature] 签名不匹配,文件可能被篡改
 * - [NoSignature] 文件不包含签名(旧版格式或未签名)
 * - [ParseError] JSON 解析失败
 */
sealed class VerifyResult {
    data class Valid(val data: ExportData) : VerifyResult()
    object InvalidSignature : VerifyResult()
    object NoSignature : VerifyResult()
    data class ParseError(val message: String) : VerifyResult()
}

object DataExportImport {

    private val json = Json {
        prettyPrint = true
        encodeDefaults = false
        ignoreUnknownKeys = true
    }

    // HMAC-SHA256 密钥:SHA-256 摘要取前 32 字节
    // 注:key 打包进 App,反编译能看到,但导出签名主要防用户误编辑导致数据损坏,
    // 非真正防恶意篡改(开源客户端无法做到)。后续可迁移到服务端签名。
    private const val HMAC_KEY = "TraktToSearch_ExportIntegrity_2026"
    private const val SIGNATURE_SEPARATOR = "\n---HMAC-SHA256---\n"

    private val hmacKey: SecretKeySpec by lazy {
        val digest = MessageDigest.getInstance("SHA-256").digest(HMAC_KEY.toByteArray(Charsets.UTF_8))
        SecretKeySpec(digest, "HmacSHA256")
    }

    /** 计算 content 的 HMAC-SHA256 签名(hex) */
    fun signExport(content: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(hmacKey)
        val raw = mac.doFinal(content.toByteArray(Charsets.UTF_8))
        return raw.joinToString("") { "%02x".format(it) }
    }

    /** 验证 content 与 signature 是否匹配(常量时间比较) */
    fun verifySignature(content: String, signature: String): Boolean {
        val expected = signExport(content)
        // 使用 Java 标准库的常量时间比较，避免长度早期返回泄露信息
        return MessageDigest.isEqual(
            expected.toByteArray(Charsets.UTF_8),
            signature.toByteArray(Charsets.UTF_8)
        )
    }

    /** 导出 JSON 并附加 HMAC 签名(双段格式:JSON + 分隔符 + 签名) */
    fun exportToJsonWithSignature(
        watchlistMovies: List<ExportItem>,
        watchlistShows: List<ExportItem>,
        historyMovies: List<ExportItem>,
        historyShows: List<ExportItem>
    ): String {
        val content = exportToJson(watchlistMovies, watchlistShows, historyMovies, historyShows)
        val signature = signExport(content)
        return content + SIGNATURE_SEPARATOR + signature
    }

    /**
     * 验证导出文件内容并解析数据。
     * 支持带签名(双段格式)和不带签名(纯 JSON)的文件。
     */
    fun verifyExportFile(fileContent: String): VerifyResult {
        val separatorIndex = fileContent.indexOf(SIGNATURE_SEPARATOR)
        return if (separatorIndex >= 0) {
            // 带签名的文件
            val content = fileContent.substring(0, separatorIndex)
            val signature = fileContent.substring(separatorIndex + SIGNATURE_SEPARATOR.length).trim()
            if (verifySignature(content, signature)) {
                try {
                    VerifyResult.Valid(json.decodeFromString<ExportData>(content))
                } catch (e: Exception) {
                    VerifyResult.ParseError(e.message ?: "Unknown error")
                }
            } else {
                VerifyResult.InvalidSignature
            }
        } else {
            // 不带签名的文件(旧版或未签名)
            try {
                VerifyResult.Valid(json.decodeFromString<ExportData>(fileContent.trim()))
            } catch (e: Exception) {
                VerifyResult.ParseError(e.message ?: "Unknown error")
            }
        }
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
                        // tv 前缀统一归 SHOW: tvSeries/tvMiniSeries/tvSpecial/tvEpisode/tvShort/tvPilot/tvMovie 等
                        titleType.startsWith("tv") -> "show"
                        titleType.contains("movie") || titleType.contains("short") || titleType.contains("video") -> "movie"
                        else -> null  // 其他类型(podcastSeries 等)跳过
                    }
                } else null
                ImportItem(
                    title = title,
                    watchedAt = watchedAt,
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

package com.tracktosearch.data.remote.custom

import android.content.Context
import com.tracktosearch.R
import com.tracktosearch.data.local.CustomSearchSource
import com.tracktosearch.data.remote.dto.DiskType
import com.tracktosearch.data.remote.dto.ResourceItem
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * 自定义搜索源服务：动态创建 HTTP 请求，解析响应
 */
@Singleton
class CustomSearchService @Inject constructor(
    @Named("custom_search") private val okHttpClient: OkHttpClient,
    @ApplicationContext private val context: Context
) {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 测试搜索源连接：用示例关键字发送请求，返回解析到的资源数量或错误信息
     */
    suspend fun testSource(source: CustomSearchSource, keyword: String? = null): TestResult {
        val actualKeyword = keyword ?: context.getString(R.string.search_test_keyword)
        return try {
            val items = search(source, actualKeyword)
            if (items.isNotEmpty()) {
                TestResult.Success(count = items.size, sampleName = items.first().name)
            } else {
                TestResult.Success(count = 0, sampleName = null)
            }
        } catch (e: Exception) {
            TestResult.Error(e.message ?: context.getString(R.string.error_unknown))
        }
    }

    /**
     * 搜索：根据自定义源配置发送请求并解析响应
     */
    suspend fun search(source: CustomSearchSource, keyword: String): List<ResourceItem> = withContext(Dispatchers.IO) {
        val url = buildUrl(source, keyword)
        val request = Request.Builder()
            .url(url)
            .addHeader("User-Agent", com.tracktosearch.di.NetworkModule.USER_AGENT)
            .addHeader("Referer", source.baseUrl)
            .build()

        val response = okHttpClient.newCall(request).execute()
        if (!response.isSuccessful) {
            response.close()
            return@withContext emptyList()
        }

        val body = response.body?.string() ?: return@withContext emptyList()
        val jsonElement = json.parseToJsonElement(body)

        parseResponse(source, jsonElement, keyword)
    }

    /**
     * 探测一次请求，区分失败原因（自动探测用）。
     *
     * [fetchRaw] 把「连不上」「返回的不是 JSON」都压成 null，编辑页无法据此给出有用的建议，
     * 所以这里返回带原因的结果；[fetchRaw] 保留给只关心成功值的调用方。
     */
    suspend fun probeRaw(source: CustomSearchSource, keyword: String): ProbeFetch =
        withContext(Dispatchers.IO) {
            val body = try {
                val request = Request.Builder()
                    .url(buildUrl(source, keyword))
                    .addHeader("User-Agent", com.tracktosearch.di.NetworkModule.USER_AGENT)
                    .addHeader("Referer", source.baseUrl)
                    .build()
                okHttpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@withContext ProbeFetch.Unreachable
                    }
                    response.body?.string()
                }
            } catch (e: Exception) {
                // 超时、DNS 失败、非法 URL 都算「连不上」：对用户的下一步动作是同一件事
                return@withContext ProbeFetch.Unreachable
            } ?: return@withContext ProbeFetch.Unreachable

            try {
                ProbeFetch.Json(json.parseToJsonElement(body))
            } catch (e: Exception) {
                ProbeFetch.NotJson
            }
        }

    /** 探测请求的结果：连不上 / 不是 JSON / 拿到 JSON */
    sealed interface ProbeFetch {
        data object Unreachable : ProbeFetch
        data object NotJson : ProbeFetch
        data class Json(val root: JsonElement) : ProbeFetch
    }

    /**
     * 获取原始响应 JSON（自动探测用）：请求失败或非 2xx 返回 null
     */
    suspend fun fetchRaw(source: CustomSearchSource, keyword: String): JsonElement? = withContext(Dispatchers.IO) {
        try {
            val url = buildUrl(source, keyword)
            val request = Request.Builder()
                .url(url)
                .addHeader("User-Agent", com.tracktosearch.di.NetworkModule.USER_AGENT)
                .addHeader("Referer", source.baseUrl)
                .build()
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body?.string() ?: return@withContext null
                json.parseToJsonElement(body)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun buildUrl(source: CustomSearchSource, keyword: String): String {
        val base = source.baseUrl.trimEnd('/')
        val path = source.apiPath.trimStart('/')
        val params = mutableListOf("${source.keywordParam}=${java.net.URLEncoder.encode(keyword, "UTF-8")}")
        if (!source.cloudTypesParam.isNullOrEmpty() && !source.cloudTypesValue.isNullOrEmpty()) {
            params.add("${source.cloudTypesParam}=${source.cloudTypesValue}")
        }
        if (!source.srcParam.isNullOrEmpty() && !source.srcValue.isNullOrEmpty()) {
            params.add("${source.srcParam}=${source.srcValue}")
        }
        return "$base/$path?${params.joinToString("&")}"
    }

    private fun parseResponse(source: CustomSearchSource, root: JsonElement, keyword: String): List<ResourceItem> {
        return when (source.parseMode) {
            "pansou_template" -> parsePanSouTemplate(root, keyword, source.id)
            "zreso_template" -> parseZresoTemplate(root, keyword, source.id)
            "custom" -> parseCustom(root, source, keyword)
            else -> emptyList()
        }
    }

    /** PanSou 模板解析：data.merged_by_type 是一个 map，每个 key 是网盘类型，value 是链接数组 */
    private fun parsePanSouTemplate(root: JsonElement, keyword: String, sourceId: String): List<ResourceItem> {
        val results = mutableListOf<ResourceItem>()
        val data = root.jsonObject["data"] ?: return emptyList()
        val mergedByType = data.jsonObject["merged_by_type"] ?: return emptyList()

        for ((typeKey, linksElement) in mergedByType.jsonObject) {
            val diskType = mapPanSouType(typeKey) ?: continue
            if (linksElement is JsonArray) {
                for (linkElement in linksElement) {
                    val linkObj = linkElement.jsonObject
                    val note = linkObj["note"]?.jsonPrimitive?.contentOrNull ?: ""
                    val url = linkObj["url"]?.jsonPrimitive?.contentOrNull ?: ""
                    val datetime = linkObj["datetime"]?.jsonPrimitive?.contentOrNull ?: ""
                    if (url.isNotEmpty()) {
                        results.add(ResourceItem(
                            name = note.ifBlank { keyword },
                            diskType = diskType,
                            fileSize = "",
                            fileDate = datetime,
                            fileCount = 1,
                            url = url,
                            source = sourceId
                        ))
                    }
                }
            }
        }
        return results
    }

    /** Zreso 模板解析：data.results 是数组，每个元素有 title、datetime、date、links */
    private fun parseZresoTemplate(root: JsonElement, keyword: String, sourceId: String): List<ResourceItem> {
        val results = mutableListOf<ResourceItem>()
        val data = root.jsonObject["data"] ?: return emptyList()
        val resultsArray = data.jsonObject["results"] ?: return emptyList()
        if (resultsArray !is JsonArray) return emptyList()

        for (resultElement in resultsArray) {
            val resultObj = resultElement.jsonObject
            val title = resultObj["title"]?.jsonPrimitive?.contentOrNull ?: keyword
            val datetime = resultObj["datetime"]?.jsonPrimitive?.contentOrNull ?: ""
            val date = resultObj["date"]?.jsonPrimitive?.contentOrNull ?: ""
            val linksElement = resultObj["links"]
            if (linksElement is JsonArray && linksElement.isNotEmpty()) {
                val firstLink = linksElement.getOrNull(0)?.jsonObject ?: continue
                val url = firstLink["url"]?.jsonPrimitive?.contentOrNull ?: ""
                val type = firstLink["type"]?.jsonPrimitive?.contentOrNull ?: ""
                val fullUrl = if (url.startsWith("http")) url else "https://zreso.cn$url"
                if (url.isNotEmpty()) {
                    results.add(ResourceItem(
                        name = title,
                        diskType = mapZresoType(type),
                        fileSize = "",
                        fileDate = datetime.ifBlank { date },
                        fileCount = linksElement.size,
                        url = fullUrl,
                        source = sourceId
                    ))
                }
            }
        }
        return results
    }

    /** 自定义 JSONPath 解析 */
    private fun parseCustom(root: JsonElement, source: CustomSearchSource, keyword: String): List<ResourceItem> {
        val listPath = source.listPath ?: return emptyList()
        val items = JsonPathParser.extractList(root, listPath)
        val results = mutableListOf<ResourceItem>()

        for (item in items) {
            val name = source.namePath?.let { JsonPathParser.extractString(item, it) } ?: keyword
            val url = source.urlPath?.let { JsonPathParser.extractString(item, it) } ?: ""
            val diskTypeStr = source.diskTypePath?.let { JsonPathParser.extractString(item, it) } ?: ""
            val date = source.datePath?.let { JsonPathParser.extractString(item, it) } ?: ""

            if (url.isNotEmpty()) {
                results.add(ResourceItem(
                    name = name.ifBlank { keyword },
                    diskType = mapPanSouType(diskTypeStr) ?: mapZresoType(diskTypeStr),
                    fileSize = "",
                    fileDate = date,
                    fileCount = 1,
                    url = url,
                    source = source.id
                ))
            }
        }
        return results
    }

    private fun mapPanSouType(type: String): DiskType? = when (type.lowercase()) {
        "quark" -> DiskType.QUARK
        "baidu" -> DiskType.BAIDU
        "aliyun", "ali" -> DiskType.ALI
        "xunlei" -> DiskType.XUNLEI
        "uc" -> DiskType.UC
        "115" -> DiskType.ONEONEFIVE
        "magnet" -> DiskType.MAGNET
        else -> null
    }

    private fun mapZresoType(type: String): DiskType = when (type.lowercase()) {
        "quark" -> DiskType.QUARK
        "baidu" -> DiskType.BAIDU
        "aliyun", "ali" -> DiskType.ALI
        "xunlei" -> DiskType.XUNLEI
        "uc" -> DiskType.UC
        "115" -> DiskType.ONEONEFIVE
        "magnet" -> DiskType.MAGNET
        else -> DiskType.OTHER
    }

    sealed class TestResult {
        data class Success(val count: Int, val sampleName: String?) : TestResult()
        data class Error(val message: String) : TestResult()
    }
}

package com.tracktosearch.data.remote.yunso.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 云搜（yunso.net）开放搜索接口响应。
 *
 * 服务端返回的是 PHP 风格大驼峰 + 下划线混用键名（`Data`、`Query_result_Total`），
 * 与项目其它网盘源的全小写 JSON 不同，故这里逐个显式 @SerialName 映射。
 */
@Serializable
data class YunsoResponse(
    @SerialName("Data") val items: List<YunsoItem>? = null,
    @SerialName("Query_result_Total") val total: Int = 0,
    @SerialName("Query_result_limit") val limit: Int = 0,
    @SerialName("Searchmode") val searchMode: Int = 0,
    @SerialName("code") val code: Int = 0,
    @SerialName("msg") val message: String = ""
)

/**
 * 单条结果。[cloudName] 是网盘中文名（夸克/百度/阿里/迅雷/UC/115），
 * [addTimeMillis] 是字符串形式的毫秒时间戳，[password] 为提取码（常为空）。
 */
@Serializable
data class YunsoItem(
    @SerialName("ScrName") val name: String = "",
    @SerialName("Scrurl") val url: String = "",
    @SerialName("Scrpass") val password: String = "",
    @SerialName("addtime") val addTimeMillis: String = "",
    @SerialName("Scrurlname") val cloudName: String = ""
)

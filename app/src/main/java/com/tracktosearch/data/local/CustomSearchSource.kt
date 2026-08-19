package com.tracktosearch.data.local

import kotlinx.serialization.Serializable

@Serializable
data class CustomSearchSource(
    val id: String,
    val name: String,
    val baseUrl: String,        // e.g., "https://so.252035.xyz/"
    val apiPath: String,        // e.g., "api/search"
    val keywordParam: String,   // e.g., "kw"
    val cloudTypesParam: String? = null, // e.g., "cloud_types"
    val cloudTypesValue: String? = null, // e.g., "quark,baidu,aliyun,xunlei,uc,115"
    val srcParam: String? = null,      // e.g., "src"
    val srcValue: String? = null,      // e.g., "all"
    val enabled: Boolean = true,
    // Parsing mode: "pansou_template" | "zreso_template" | "custom"
    val parseMode: String = "pansou_template",
    // JSONPath expressions (only used when parseMode == "custom")
    val listPath: String? = null,     // e.g., "$.data.results"
    val namePath: String? = null,     // e.g., "$.title"
    val urlPath: String? = null,      // e.g., "$.url"
    val diskTypePath: String? = null, // e.g., "$.type"
    val datePath: String? = null      // e.g., "$.datetime"
)

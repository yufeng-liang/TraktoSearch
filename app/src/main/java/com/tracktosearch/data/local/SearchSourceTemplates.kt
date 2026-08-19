package com.tracktosearch.data.local

/**
 * 内置搜索源模板：用户点选后自动填充配置。
 */
data class SearchSourceTemplate(
    val id: String,
    val name: String,
    val description: String,
    val icon: String,
    /** true 表示点击后走完整分步向导（空白自定义）；false 表示参数完整，直接进确认步骤 */
    val wizardMode: Boolean,
    val defaults: CustomSearchSource
)

object SearchSourceTemplates {

    val all: List<SearchSourceTemplate> = listOf(
        SearchSourceTemplate(
            id = "pansou_public",
            name = "PanSou 公共源",
            description = "公共盘搜 API，模板解析，一键填好",
            icon = "🔍",
            wizardMode = false,
            defaults = CustomSearchSource(
                id = "", // 保存时生成
                name = "PanSou 公共源",
                baseUrl = "https://so.252035.xyz/",
                apiPath = "api/search",
                keywordParam = "kw",
                cloudTypesParam = "cloud_types",
                cloudTypesValue = "quark,baidu,aliyun,xunlei,uc,115",
                srcParam = "src",
                srcValue = "all",
                enabled = true,
                parseMode = "pansou_template"
            )
        ),
        SearchSourceTemplate(
            id = "pansou_self",
            name = "PanSou 自建",
            description = "自部署 PanSou API，只需改地址",
            icon = "⚙️",
            wizardMode = false,
            defaults = CustomSearchSource(
                id = "",
                name = "PanSou 自建",
                baseUrl = "http://127.0.0.1:8888/",
                apiPath = "api/search",
                keywordParam = "kw",
                cloudTypesParam = "cloud_types",
                cloudTypesValue = "quark,baidu,aliyun,xunlei,uc,115",
                srcParam = "src",
                srcValue = "all",
                enabled = true,
                parseMode = "pansou_template"
            )
        ),
        SearchSourceTemplate(
            id = "zreso",
            name = "Zreso 资源库",
            description = "zreso.cn 模板解析",
            icon = "📦",
            wizardMode = false,
            defaults = CustomSearchSource(
                id = "",
                name = "Zreso 资源库",
                baseUrl = "https://zreso.cn/",
                apiPath = "api/search",
                keywordParam = "kw",
                enabled = true,
                parseMode = "zreso_template"
            )
        ),
        SearchSourceTemplate(
            id = "blank",
            name = "空白自定义",
            description = "走分步向导，支持任意 JSON API",
            icon = "＋",
            wizardMode = true,
            defaults = CustomSearchSource(
                id = "",
                name = "",
                baseUrl = "",
                apiPath = "api/search",
                keywordParam = "kw",
                cloudTypesParam = "cloud_types",
                cloudTypesValue = "quark,baidu,aliyun,xunlei,uc,115",
                srcParam = "src",
                srcValue = "all",
                enabled = true,
                parseMode = "pansou_template"
            )
        )
    )

    fun byId(id: String): SearchSourceTemplate? = all.find { it.id == id }

    /** 空白模板：新建向导入口 */
    val blank: SearchSourceTemplate get() = byId("blank")!!
}

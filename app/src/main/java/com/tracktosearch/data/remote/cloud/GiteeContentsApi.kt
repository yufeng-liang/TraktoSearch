package com.tracktosearch.data.remote.cloud

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Gitee Contents API:用于豆瓣失败项的云端同步。
 *
 * 路径:failures/{hash}.json,hash = SHA256(doubanUserId).substring(0,16)
 * 仓库:yufeng-liang/meta-data(私有)
 *
 * - 创建新文件:POST /repos/{owner}/{repo}/contents/{path},不传 sha
 * - 更新已有文件:PUT /repos/{owner}/{repo}/contents/{path},必传 sha
 * - 下载:GET /repos/{owner}/{repo}/contents/{path},返回 content(base64)+ sha
 * - 404 表示云端无该用户数据
 *
 * 注意:Gitee API 在文件不存在时可能返回 200 + `[]`(空数组)而非 404,
 * 因此 getFileContent 返回 [JsonElement],由调用方判断是对象还是数组。
 *
 * 重要:Gitee API 区分新建(POST)和更新(PUT)两个端点。
 * PUT 在文件不存在时会报 "sha is missing","sha is empty"。
 * 必须先用 POST 创建,再在文件已存在时用 PUT 更新。
 *
 * 鉴权:请求统一走网关代理 /api/gitee/,由 auth-worker 注入 GITEE_ACCESS_TOKEN,
 * 客户端无需自带 token,也不再通过 URL query 参数或 header 携带。
 */
interface GiteeContentsApi {

    /**
     * 获取文件内容(下载)。
     * @param owner 仓库 owner
     * @param repo 仓库名
     * @param path 文件路径(如 failures/abc123.json),注意必须用 encoded=true 避免 / 被 URL 编码
     * @param ref 分支(默认 master)
     * @return [JsonElement]:文件存在时是 JsonObject(含 content/sha),不存在时可能是 JsonArray(`[]`)
     */
    @GET("repos/{owner}/{repo}/contents/{path}")
    suspend fun getFileContent(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Path(value = "path", encoded = true) path: String,
        @Query("ref") ref: String = "master"
    ): Response<JsonElement>

    /**
     * 创建新文件(文件不存在时调用)。POST 请求,不传 sha。
     * @param owner 仓库 owner
     * @param repo 仓库名
     * @param path 文件路径(如 failures/abc123.json),注意必须用 encoded=true 避免 / 被 URL 编码
     * @param body 请求体(content 为 base64 编码,message 为 commit message,不传 sha)
     */
    @POST("repos/{owner}/{repo}/contents/{path}")
    suspend fun createFileContent(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Path(value = "path", encoded = true) path: String,
        @Body body: GiteeContentRequest
    ): Response<GiteeContentUpdateResponse>

    /**
     * 更新已有文件(文件已存在时调用)。PUT 请求,必传 sha。
     * @param owner 仓库 owner
     * @param repo 仓库名
     * @param path 文件路径(如 failures/abc123.json),注意必须用 encoded=true 避免 / 被 URL 编码
     * @param body 请求体(content 为 base64 编码,message 为 commit message,sha 必传)
     */
    @PUT("repos/{owner}/{repo}/contents/{path}")
    suspend fun putFileContent(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Path(value = "path", encoded = true) path: String,
        @Body body: GiteeContentRequest
    ): Response<GiteeContentUpdateResponse>
}

@Serializable
data class GiteeContentResponse(
    val content: String? = null,       // base64 编码的文件内容
    val sha: String? = null,            // 文件 SHA(更新时需要)
    val name: String? = null,
    val path: String? = null,
    val type: String? = null
)

/**
 * 上传请求体。
 *
 * 重要:sha 字段在文件不存在时必须不传(传 null 会被 Gitee 报 "sha is empty")。
 * 因此本类配合 `Json { encodeDefaults = false }` 使用,使 sha=null 时不出现在 JSON 中。
 */
@Serializable
data class GiteeContentRequest(
    val content: String,        // base64 编码的文件内容
    val message: String,         // commit message
    val branch: String = "master",
    val sha: String? = null      // 更新已有文件时必传;创建新文件时不传
)

@Serializable
data class GiteeContentUpdateResponse(
    val content: GiteeContentResponse? = null,
    val commit: GiteeCommit? = null
)

@Serializable
data class GiteeCommit(
    val sha: String? = null,
    val message: String? = null
)

package com.tracktosearch.data.remote.update

import retrofit2.http.GET
import retrofit2.http.Path

interface GitHubUpdateApiService {
    @GET("repos/{owner}/{repo}/releases/latest")
    suspend fun getLatestRelease(
        @Path("owner") owner: String,
        @Path("repo") repo: String
    ): GitHubRelease
}

interface GiteeUpdateApiService {
    // Gitee 没有 /releases/latest 端点，用列表取第一个（direction=desc 获取最新）
    @GET("repos/{owner}/{repo}/releases?per_page=1&direction=desc")
    suspend fun getLatestRelease(
        @Path("owner") owner: String,
        @Path("repo") repo: String
    ): List<GiteeRelease>
}

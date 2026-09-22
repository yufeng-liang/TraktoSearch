package com.tracktosearch.data.remote.update

import retrofit2.http.GET
import retrofit2.http.Path

interface GitHubUpdateApiService {
    @GET("repos/{owner}/{repo}/releases/latest")
    suspend fun getLatestRelease(
        @Path("owner") owner: String,
        @Path("repo") repo: String
    ): GitHubRelease

    @GET("repos/{owner}/{repo}/releases?per_page=100")
    suspend fun getAllReleases(
        @Path("owner") owner: String,
        @Path("repo") repo: String
    ): List<GitHubRelease>
}

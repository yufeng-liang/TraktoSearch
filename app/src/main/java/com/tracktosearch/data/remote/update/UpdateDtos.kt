package com.tracktosearch.data.remote.update

import kotlinx.serialization.Serializable

@Serializable
data class GitHubRelease(
    val tag_name: String = "",
    val name: String = "",
    val body: String = "",
    val assets: List<GitHubAsset> = emptyList()
)

@Serializable
data class GitHubAsset(
    val name: String = "",
    val browser_download_url: String = "",
    val size: Long = 0
)

@Serializable
data class GiteeRelease(
    val tag_name: String = "",
    val name: String = "",
    val body: String = "",
    val assets: List<GiteeAsset> = emptyList()
)

@Serializable
data class GiteeAsset(
    val name: String = "",
    val browser_download_url: String = "",
    val size: Long = 0
)

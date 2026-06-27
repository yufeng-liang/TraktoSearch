package com.tracktosearch.data.remote.panhub

data class PanHubConfig(
    val concurrency: Int = 4,
    val timeoutMs: Int = 5000,
    val enabledPlugins: Set<String> = PanHubPlugin.entries.map { it.id }.toSet(),
    val enabledChannels: Set<String> = PanHubChannel.entries.map { it.id }.toSet()
) {
    companion object {
        const val CONCURRENCY_MIN = 1
        const val CONCURRENCY_MAX = 16
        const val CONCURRENCY_DEFAULT = 4
        const val TIMEOUT_DEFAULT = 5000
    }
}

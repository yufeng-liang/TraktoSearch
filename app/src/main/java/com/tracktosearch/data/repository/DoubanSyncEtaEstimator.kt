package com.tracktosearch.data.repository

private const val NANOS_PER_MILLISECOND = 1_000_000L
private const val MILLIS_PER_SECOND = 1_000L

/** 为豆瓣同步提供可替换的时间源，生产环境使用单调时钟，测试可注入固定时间。 */
fun interface DoubanSyncTimeProvider {
    fun nowMs(): Long
}

/**
 * 根据同步进度的最近样本估算剩余秒数。
 *
 * 估算器只依赖 Kotlin/JVM 标准库，不依赖 Android。调用方负责传入已统一计入跳过项的 total。
 */
class DoubanSyncEtaEstimator(
    private val timeProvider: DoubanSyncTimeProvider = DoubanSyncTimeProvider {
        System.nanoTime() / NANOS_PER_MILLISECOND
    }
) {

    companion object {
        const val UNKNOWN_ETA_SECONDS = -1L
        const val MIN_COMPLETED_ITEMS = 5
        const val SAMPLE_WINDOW_SIZE = 5
    }

    private data class Sample(
        val progress: Int,
        val timestampMs: Long
    )

    private val samples = java.util.ArrayDeque<Sample>(SAMPLE_WINDOW_SIZE)
    private var lastAcceptedProgress: Int? = null
    private var lastAcceptedTimestampMs: Long? = null
    private var lastEtaSeconds = UNKNOWN_ETA_SECONDS

    /**
     * 记录一次进度并返回预计剩余秒数；样本不足或无有效吞吐时返回 -1。
     * 完成后清空本轮状态，便于同一个实例开始下一轮同步。
     */
    fun update(current: Int, total: Int, isComplete: Boolean = false): Long {
        if (isComplete || (total > 0 && current >= total)) {
            reset()
            return 0L
        }

        if (current < 0 || total <= 0) return lastEtaSeconds

        val previousProgress = lastAcceptedProgress
        if (previousProgress != null) {
            if (current < previousProgress || current == previousProgress) {
                return lastEtaSeconds
            }
        }

        val nowMs = timeProvider.nowMs()
        val previousTimestampMs = lastAcceptedTimestampMs
        if (previousTimestampMs != null && nowMs < previousTimestampMs) {
            return lastEtaSeconds
        }

        samples.addLast(Sample(current, nowMs))
        while (samples.size > SAMPLE_WINDOW_SIZE) {
            samples.removeFirst()
        }
        lastAcceptedProgress = current
        lastAcceptedTimestampMs = nowMs

        lastEtaSeconds = calculateEta(current, total)
        return lastEtaSeconds
    }

    /** 清除当前同步轮次的样本。 */
    fun reset() {
        samples.clear()
        lastAcceptedProgress = null
        lastAcceptedTimestampMs = null
        lastEtaSeconds = UNKNOWN_ETA_SECONDS
    }

    private fun calculateEta(current: Int, total: Int): Long {
        if (current < MIN_COMPLETED_ITEMS || samples.size < 2) {
            return UNKNOWN_ETA_SECONDS
        }

        val first = samples.first
        val latest = samples.last
        val progressDelta = latest.progress - first.progress
        val elapsedMs = latest.timestampMs - first.timestampMs
        val remaining = total - current
        if (progressDelta <= 0 || elapsedMs <= 0 || remaining <= 0) {
            return if (remaining <= 0) 0L else UNKNOWN_ETA_SECONDS
        }

        val numerator = remaining.toLong() * elapsedMs
        val denominator = progressDelta.toLong() * MILLIS_PER_SECOND
        return ceilDivide(numerator, denominator)
    }

    private fun ceilDivide(numerator: Long, denominator: Long): Long {
        return numerator / denominator + if (numerator % denominator == 0L) 0L else 1L
    }
}

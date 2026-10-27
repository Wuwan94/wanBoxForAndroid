package io.wanbox.domain

import io.wanbox.contracts.dto.TrafficPoint
import io.wanbox.contracts.dto.TrafficReport
import io.wanbox.contracts.dto.TrafficSnapshot
import java.util.Locale

// ---------------- UC-STAT-01 流量曲线 ----------------

/**
 * 聚合与单位换算都是纯函数：给定采样点与窗口，产出分桶序列。
 * 分桶：day → 每小时；week / month → 每天。未知窗口按 day 处理，避免界面传错就崩。
 */
object TrafficAggregator {

    private const val HOUR_MS = 3_600_000L
    private const val DAY_MS = 86_400_000L

    fun bucketSize(window: String): Long = when (window.lowercase()) {
        "day" -> HOUR_MS
        "week", "month" -> DAY_MS
        else -> HOUR_MS
    }

    fun report(samples: List<TrafficSnapshot>, window: String): TrafficReport {
        val size = bucketSize(window)
        val buckets = sortedMapOf<Long, LongArray>()
        for (sample in samples) {
            val key = sample.since / size * size
            val acc = buckets.getOrPut(key) { LongArray(2) }
            acc[0] += sample.rxBytes
            acc[1] += sample.txBytes
        }
        val points = buckets.map { TrafficPoint(it.key, it.value[0], it.value[1]) }
        val totalRx = points.sumOf { it.rxBytes }
        val totalTx = points.sumOf { it.txBytes }
        return TrafficReport(
            points = points,
            totalRx = totalRx,
            totalTx = totalTx,
            peakBucket = points.maxByOrNull { it.rxBytes + it.txBytes },
            unit = ByteFormat.human(totalRx + totalTx),
        )
    }
}

/** UC-STAT-02 清零 */
suspend fun AppCore.resetTraffic(profileIds: List<Long>) {
    traffic.reset(profileIds)
    logger.info("core", "traffic reset for ${if (profileIds.isEmpty()) "all" else profileIds.joinToString()}")
    publish()
}

suspend fun AppCore.trafficReport(window: String, profileId: Long? = null): TrafficReport {
    val samples = traffic.series(window, profileId)
    return TrafficAggregator.report(samples, window)
}

/** 1024 进制，保留一位小数。固定 Locale.US，避免区域设置影响数字分隔符。 */
object ByteFormat {
    private val UNITS = arrayOf("B", "KB", "MB", "GB", "TB")

    fun human(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        var value = bytes.toDouble()
        var unit = 0
        while (value >= 1024 && unit < UNITS.lastIndex) {
            value /= 1024
            unit++
        }
        return String.format(Locale.US, "%.1f %s", value, UNITS[unit])
    }
}

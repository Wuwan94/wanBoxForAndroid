// @author 雾晚
package io.nekohasekai.sagernet.bg

/** Pure scheduling policy; keeps each subscription's own due time. @author 雾晚 */
object SubscriptionSchedule {
    data class Entry(val intervalMinutes: Int, val lastUpdatedSeconds: Int)
    data class Plan(val intervalMinutes: Long, val initialDelaySeconds: Long)
    fun plan(entries: List<Entry>, nowSeconds: Long): Plan? {
        if (entries.isEmpty()) return null
        fun Entry.interval() = intervalMinutes.toLong().coerceAtLeast(15)
        return Plan(entries.minOf { it.interval() }, entries.minOf {
            (it.lastUpdatedSeconds.toLong() + it.interval() * 60 - nowSeconds).coerceAtLeast(0)
        })
    }

    /**
     * B2/O5: whether this subscription is due for a catch-up update at [nowSeconds].
     * Never updated (lastUpdated <= 0) counts as due. The interval is clamped to the
     * WorkManager minimum of 15 minutes, mirroring [plan]; there is no 60-second
     * truncation anywhere in this calculation.
     */
    fun isDue(entry: Entry, nowSeconds: Long): Boolean {
        if (entry.lastUpdatedSeconds <= 0) return true
        val intervalSeconds = entry.intervalMinutes.toLong().coerceAtLeast(15) * 60L
        return nowSeconds - entry.lastUpdatedSeconds.toLong() >= intervalSeconds
    }
}

// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package io.nekohasekai.sagernet.bg

/** Protocol read views cached independently for the short UI polling window. */
internal enum class RootModuleReadQuery { STATUS, DASHBOARD }

/**
 * Small synchronized TTL cache; the injected clock is monotonic in production and
 * makes cache expiry deterministic in unit tests.
 */
internal class RootModuleStatusCache(
    private val ttlMs: Long,
    private val nowMs: () -> Long,
) {
    private data class Entry(val value: RootModuleClient.Status, val storedAtMs: Long)
    private val entries = mutableMapOf<RootModuleReadQuery, Entry>()
    private var generation = 0L

    init { require(ttlMs > 0) }

    @Synchronized
    fun get(query: RootModuleReadQuery): RootModuleClient.Status? {
        val entry = entries[query] ?: return null
        val age = nowMs() - entry.storedAtMs
        if (age < 0 || age >= ttlMs) {
            entries.remove(query)
            return null
        }
        return entry.value
    }

    /**
     * True when an entry exists but is past its TTL. Unlike [get] this never
     * evicts: a surviving stale entry is the demand signal the read aggregator
     * in RootModuleClient uses to decide that refreshing this view alongside
     * its sibling is worthwhile. A backwards clock step counts as stale,
     * matching [get].
     */
    @Synchronized
    fun hasStaleEntry(query: RootModuleReadQuery): Boolean {
        val entry = entries[query] ?: return false
        val age = nowMs() - entry.storedAtMs
        return age < 0 || age >= ttlMs
    }

    @Synchronized
    fun generation(): Long = generation

    /** A read started before invalidation must never repopulate the cache afterward. */
    @Synchronized
    fun putIfGeneration(query: RootModuleReadQuery, value: RootModuleClient.Status, expectedGeneration: Long): Boolean {
        if (generation != expectedGeneration) return false
        entries[query] = Entry(value, nowMs())
        return true
    }

    @Synchronized
    fun invalidate() {
        entries.clear()
        generation++
    }
}

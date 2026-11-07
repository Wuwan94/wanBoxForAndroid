package io.nekohasekai.sagernet.ui

import io.nekohasekai.sagernet.database.DataStore

/**
 * Values read on every node-card bind / click. DataStore reads are synchronous SQLite
 * queries (no cross-process cache), so the node list uses this view-scoped snapshot instead
 * of reading once per bound card. Refreshed on IO from ConfigurationFragment.onResume
 * (the settings that change these values live on other screens).
 */
object ProfileListPrefs {
    private data class Snapshot(val gridLayout: Boolean, val haptic: Boolean, val columnCount: Int)
    @Volatile private var snapshot = Snapshot(false, true, 2)
    val gridLayout get() = snapshot.gridLayout
    val haptic get() = snapshot.haptic
    val columnCount get() = snapshot.columnCount

    /** Call on IO; this is a view snapshot, not a cross-process storage cache. */
    fun refresh() {
        snapshot = Snapshot(
            DataStore.groupLayoutMode == 1,
            DataStore.hapticFeedback,
            DataStore.nodeColumnCount.coerceIn(2, 3),
        )
    }
}

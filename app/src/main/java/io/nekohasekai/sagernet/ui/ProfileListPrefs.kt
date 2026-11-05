package io.nekohasekai.sagernet.ui

import io.nekohasekai.sagernet.database.DataStore

/**
 * phase2: values read on every node-card bind / click. DataStore reads are synchronous SQLite
 * queries (no cache, see audit 3.3 #4/#5), so the node list reads them once per (re)layout here
 * instead of once per bound card. Refreshed when the list layout is (re)created and from
 * ConfigurationFragment.onResume (the settings that change them live on other screens).
 */
object ProfileListPrefs {
    @Volatile var gridLayout: Boolean = false
        private set
    @Volatile var haptic: Boolean = true
        private set

    fun refresh() {
        gridLayout = DataStore.groupLayoutMode == 1
        haptic = DataStore.hapticFeedback
    }
}

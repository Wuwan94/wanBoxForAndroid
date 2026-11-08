// @author 雾晚
package io.nekohasekai.sagernet.ui

/**
 * What the node list must do after [ProfileListPrefs] takes a new snapshot.
 * Kept free of Android and DataStore so the decision is JVM-testable.
 */
enum class ProfileListPrefsChange {
    /** Nothing a bound card shows changed. */
    NONE,

    /** Same layout, but bound cards show stale content (e.g. the address line): rebind them. */
    REBIND,

    /** Grid/list mode or column count changed: rebuild the layout manager, which also rebinds. */
    RELAYOUT;

    companion object {
        fun between(
            oldGrid: Boolean, oldColumns: Int, oldAlwaysShowAddress: Boolean,
            newGrid: Boolean, newColumns: Int, newAlwaysShowAddress: Boolean,
        ): ProfileListPrefsChange = when {
            oldGrid != newGrid || oldColumns != newColumns -> RELAYOUT
            oldAlwaysShowAddress != newAlwaysShowAddress -> REBIND
            // Haptics are read on click, never drawn, so they need no rebind.
            else -> NONE
        }
    }
}

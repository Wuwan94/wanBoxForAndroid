// SPDX-License-Identifier: GPL-3.0-or-later
package io.nekohasekai.sagernet.bg

/** UI-only state. The checkbox itself stays on the last confirmed value. */
internal class AutoStartTapState {
    private var pending: Boolean? = null
    var generation: Long = 0
        private set

    fun next(frameworkValue: Boolean): Boolean {
        val target = pending?.not() ?: frameworkValue
        pending = target
        generation++
        return target
    }

    fun finish(requestGeneration: Long): Boolean {
        if (generation != requestGeneration) return false
        pending = null
        return true
    }
}

package io.nekohasekai.sagernet.bg

/** Pure diagnostic projection. Labels and local time rendering belong to the UI. */
object ModuleDiagnostics {
    enum class Kind { HEALTH, EXIT_REASON, EXIT_CODE, EXIT_TIME, RETRY_COUNT, EVENT }

    data class Line(val kind: Kind, val value: String = "", val epochSeconds: Long = 0L)

    fun format(status: RootModuleClient.Status): List<Line> = buildList {
        if (status.health.isNotBlank()) add(Line(Kind.HEALTH, status.health))
        if (status.lastReason.isNotBlank()) add(Line(Kind.EXIT_REASON, status.lastReason))
        if (status.lastExit.isNotBlank()) add(Line(Kind.EXIT_CODE, status.lastExit))
        if (status.lastExitAt > 0L) add(Line(Kind.EXIT_TIME, epochSeconds = status.lastExitAt))
        if (status.retryCount > 0) add(Line(Kind.RETRY_COUNT, status.retryCount.toString()))
        status.events.withIndex().asSequence().filter { it.value.code.isNotBlank() }
            .sortedWith(compareByDescending<IndexedValue<RootModuleClient.ModuleEvent>> { it.value.time }
                .thenByDescending { it.index }).take(5).forEach { (_, event) ->
                add(Line(Kind.EVENT, event.code, event.time.takeIf { time -> time > 0L } ?: 0L))
            }
    }
}

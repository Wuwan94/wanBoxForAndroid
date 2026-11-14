// @author 雾晚
package io.nekohasekai.sagernet.bg

/** Transition states are not toggle targets: wait for the current start/stop operation to settle. */
internal fun isTransitionalServiceState(state: BaseService.State?): Boolean =
    state == BaseService.State.Connecting || state == BaseService.State.Stopping

internal enum class ServiceToggleAction { START, STOP, RESUME, IGNORE }

/** Preserve profile-target behavior while refusing to invert an in-flight transition. */
internal fun serviceToggleAction(
    state: BaseService.State,
    requestedProfileId: Long,
    currentProfileId: Long,
): ServiceToggleAction = when {
    isTransitionalServiceState(state) -> ServiceToggleAction.IGNORE
    state == BaseService.State.Suspended -> ServiceToggleAction.RESUME
    state.canStop && (requestedProfileId == -1L || requestedProfileId == currentProfileId) ->
        ServiceToggleAction.STOP
    else -> ServiceToggleAction.START
}

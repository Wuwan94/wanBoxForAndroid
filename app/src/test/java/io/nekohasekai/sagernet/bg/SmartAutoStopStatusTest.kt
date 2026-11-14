package io.nekohasekai.sagernet.bg

import org.junit.Assert.*
import org.junit.Test

class SmartAutoStopStatusTest {
    @Test fun suspendedIsDistinctAndHasNoConnectedStats() {
        val status = RootModuleClient.parseResponse("""{"schemaVersion":1,"ok":true,"state":{"phase":"suspended","stats":{"tx":100}}}""")
        assertEquals(BaseService.State.Suspended, status.state)
        assertFalse(status.connected)
        assertNull(status.stats)
        assertNull(status.telemetry)
    }
    @Test fun suspendedToggleResumesRegardlessOfRequestedProfile() {
        assertEquals(ServiceToggleAction.RESUME, serviceToggleAction(BaseService.State.Suspended, -1, 1))
        assertEquals(ServiceToggleAction.RESUME, serviceToggleAction(BaseService.State.Suspended, 2, 1))
        assertEquals(ServiceToggleAction.STOP, serviceToggleAction(BaseService.State.Connected, -1, 1))
        assertEquals(ServiceToggleAction.START, serviceToggleAction(BaseService.State.Stopped, -1, 1))
    }
}

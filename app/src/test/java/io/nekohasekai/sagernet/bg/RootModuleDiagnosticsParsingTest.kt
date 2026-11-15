package io.nekohasekai.sagernet.bg

import org.junit.Assert.assertEquals
import org.junit.Test

class RootModuleDiagnosticsParsingTest {
    @Test fun parsesExitTimeAsEpochSecondsWithoutIntTruncation() {
        val status = RootModuleClient.parseResponse("""{"schemaVersion":1,"ok":true,"state":{"phase":"stopped","lastExitAt":4102444800}}""")
        assertEquals(4102444800L, status.lastExitAt)
    }

    @Test fun missingExitTimeDefaultsToZeroForOlderModules() {
        val status = RootModuleClient.parseResponse("""{"schemaVersion":1,"ok":true,"state":{"phase":"stopped"}}""")
        assertEquals(0L, status.lastExitAt)
    }
}

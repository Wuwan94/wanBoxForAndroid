// @author 雾晚
package io.nekohasekai.sagernet.bg.proto

import io.nekohasekai.sagernet.bg.RootModuleClient
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

class RunningProfileTagResolverTest {
    @Test
    fun resolvesOnlyFromTheCurrentRunningSnapshot() = runTest {
        var reads = 0
        val resolver = RunningProfileTagResolver {
            reads++
            status(profileTags = mapOf(10L to "active-tag", 11L to "candidate-tag"))
        }

        assertEquals("candidate-tag", resolver.tagFor(11))
        assertEquals("active-tag", resolver.tagFor(10))
        assertEquals(2, reads) // fresh root snapshot per probe; never reuse a stale generation map
    }

    @Test
    fun rejectsCandidatesOutsideTheRunningProfileTagMap() = runTest {
        val resolver = RunningProfileTagResolver {
            status(profileTags = mapOf(10L to "active-tag"))
        }
        assertThrows(ProfileNotInRunningConfigException::class.java) {
            kotlinx.coroutines.runBlocking { resolver.tagFor(99) }
        }
    }

    @Test
    fun refusesToProbeWhenTheRootModuleIsNotConnected() = runTest {
        val resolver = RunningProfileTagResolver {
            status(phase = "stopped", profileTags = mapOf(10L to "active-tag"))
        }
        assertThrows(IOException::class.java) {
            kotlinx.coroutines.runBlocking { resolver.tagFor(10) }
        }
    }

    @Test
    fun parsesOnlyValidProfileIdsAndTagsFromTheReadOnlyCliResponse() {
        val parsed = RootModuleClient.parseResponse(
            """{"schemaVersion":1,"ok":true,"state":{"phase":"connected","revision":"r1","runningRevision":"r1","profileId":10,"profileName":"active","profileTags":{"10":"active-tag","11":"candidate-tag","0":"zero","-1":"negative","12":""}}}""",
        )
        assertEquals(mapOf(10L to "active-tag", 11L to "candidate-tag"), parsed.profileTags)
    }

    private fun status(
        phase: String = "connected",
        profileTags: Map<Long, String>,
    ) = RootModuleClient.Status(
        phase = phase,
        revision = "r1",
        runningRevision = "r1",
        profileId = 10,
        profileName = "test-profile",
        stats = null,
        profileTags = profileTags,
    )
}

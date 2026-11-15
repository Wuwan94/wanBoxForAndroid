package io.nekohasekai.sagernet.bg.proto

import org.junit.Assert.assertEquals
import org.junit.Test

class PolicyMemberNameResolverTest {
    @Test fun snapshotDisplayNameTakesPrecedenceOverMatchingGroupTag() {
        val resolver = PolicyMemberNameResolver(
            mapOf(7L to "shared-tag"),
            mapOf(7L to "Tokyo"),
            setOf("shared-tag"), "Direct", "Block",
        )
        assertEquals("Tokyo", resolver.name("shared-tag"))
    }

    @Test fun resolvesOnlySnapshotTagsAndRetainsGroupsBuiltinsAndUnknownMembers() {
        val resolver = PolicyMemberNameResolver(
            mapOf(7L to "runtime-tag", 8L to "missing-profile"),
            mapOf(7L to "Tokyo", 9L to "not-running"),
            setOf("proxy", "direct"), "Direct", "Block",
        )
        assertEquals("Tokyo", resolver.name("runtime-tag"))
        assertEquals("proxy", resolver.name("proxy"))
        assertEquals("direct", resolver.name("direct")) // Actual group identity takes precedence.
        assertEquals("Block", resolver.name("block"))
        assertEquals("missing-profile", resolver.name("missing-profile"))
        assertEquals("not-running", resolver.name("not-running"))
        assertEquals("unknown", resolver.name("unknown"))
        assertEquals("Direct", PolicyMemberNameResolver(emptyMap(), emptyMap(), emptySet(), "Direct", "Block").name("direct"))
    }
}

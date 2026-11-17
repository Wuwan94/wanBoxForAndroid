package io.nekohasekai.sagernet

import io.nekohasekai.sagernet.group.editor.*
import org.junit.Assert.*
import org.junit.Test

/** ProxyEntity.groupId is the single membership truth; a saved draft only orders it. */
class GroupMembershipTest {
    private val params = UrlTestParameters("", 600, 100, "30m", false)
    private fun draft(vararg members: Long) = GroupDraft(10, "香港", GroupMode.SELECTOR, members.toList(), params)
    private val basic = GroupMembership.Target(10, subscription = false, ungrouped = false)
    private val subscription = GroupMembership.Target(10, subscription = true, ungrouped = false)

    @Test fun savedOrderFollowsOwnershipAndAppendsNewSubscriptionNodes() {
        // Saved order 3,1,2; node 2 moved to another group; nodes 4,5 arrived by subscription update.
        assertEquals(listOf(3L, 1L, 4L, 5L), GroupMembership.effectiveOrder(listOf(3, 1, 2), listOf(1, 3, 4, 5)))
        assertEquals(listOf(1L, 3L), GroupMembership.effectiveOrder(emptyList(), listOf(1, 3)))
    }

    @Test fun reconciledDraftUsesCanonicalNameAndHasNoOverrideWithoutOwnedNodes() {
        val reconciled = GroupMembership.reconcile(draft(3, 1).copy(name = "stale"), "香港", listOf(1, 3, 4))!!
        assertEquals("香港", reconciled.name)
        assertEquals(listOf(3L, 1L, 4L), reconciled.members)
        assertNull(GroupMembership.reconcile(draft(3, 1), "香港", emptyList()))
    }

    @Test fun basicGroupSaveMovesAddedNodesInAndRemovedNodesToUngrouped() {
        val owners = mapOf(1L to 10L, 2L to 10L, 7L to 20L)
        val sync = GroupMembership.plan(draft(7, 1), basic, owners, ungroupedId = 1)
        assertTrue(sync.issues.isEmpty())
        assertEquals(mapOf(7L to 10L, 2L to 1L), sync.moves)
    }

    @Test fun ordinarySaveRejectsSubscriptionOwnershipWithoutAnyMoves() {
        val sync = GroupMembership.plan(draft(7), basic, mapOf(1L to 10L, 7L to 20L),
            ungroupedId = 1, subscriptionGroupIds = setOf(20))
        assertTrue("Subscription ownership must be locked", sync.issues.any { it.code == "subscription_member_locked" })
        assertTrue("No partial moves after rejection", sync.moves.isEmpty())
    }

    @Test fun newGroupTakesOwnershipOfEveryMember() {
        val sync = GroupMembership.plan(draft(7, 8), null, mapOf(7L to 20L, 8L to 1L), ungroupedId = 1)
        assertTrue(sync.issues.isEmpty())
        assertEquals(mapOf(7L to 10L, 8L to 10L), sync.moves)
    }

    @Test fun subscriptionGroupAllowsReorderButRejectsForeignOrDroppedNodes() {
        val owners = mapOf(1L to 10L, 2L to 10L, 7L to 20L)
        assertEquals(GroupMembership.Sync(emptyMap(), emptyList()),
            GroupMembership.plan(draft(2, 1), subscription, owners, ungroupedId = 1))
        val added = GroupMembership.plan(draft(2, 1, 7), subscription, owners, ungroupedId = 1)
        assertEquals(listOf(GroupEditor.Issue("members[2]", "subscription_member_locked", EntityId.Node(7))), added.issues)
        val dropped = GroupMembership.plan(draft(1), subscription, owners, ungroupedId = 1)
        assertEquals(listOf(GroupEditor.Issue("members", "subscription_member_locked", EntityId.Node(2))), dropped.issues)
        assertTrue(dropped.moves.isEmpty())
    }

    @Test fun ungroupedNodesHaveNowhereToGoWhenDropped() {
        val ungrouped = GroupMembership.Target(1, subscription = false, ungrouped = true)
        val sync = GroupMembership.plan(draft(5).copy(id = 1), ungrouped, mapOf(5L to 1L, 6L to 1L), ungroupedId = 1)
        assertEquals(listOf(GroupEditor.Issue("members", "ungrouped_member_locked", EntityId.Node(6))), sync.issues)
        assertTrue(GroupMembership.plan(draft(1, 2), basic, mapOf(1L to 10L, 2L to 10L, 3L to 10L), ungroupedId = null)
            .issues.any { it.code == "ungrouped_member_locked" })
    }

    /** Edit+save -> subscription update -> cross-group move: database count and built members agree. */
    @Test fun countRuntimeAndExportMembersStayEqualAfterSaveUpdateAndMove() {
        val owners = mutableMapOf(1L to 10L, 2L to 10L, 3L to 10L, 7L to 20L)
        // 1. Save: order 3,1,7 (adds 7 from another group, drops 2).
        val saved = draft(3, 1, 7)
        GroupMembership.plan(saved, basic, owners, ungroupedId = 1).also { assertTrue(it.issues.isEmpty()) }
            .moves.forEach { (node, group) -> owners[node] = group }
        // 2. Subscription update inserts node 9 into group 10 (RawUpdater only writes groupId).
        owners[9L] = 10L
        // 3. Node 1 is moved to group 20 from the node page.
        owners[1L] = 20L
        val owned = owners.filterValues { it == 10L }.keys.sorted()
        val effective = GroupMembership.reconcile(saved, "香港", owned)!!
        assertEquals(listOf(3L, 7L, 9L), effective.members)
        val tags = mapOf(1L to "n1", 2L to "n2", 3L to "n3", 7L to "n7", 9L to "n9")
        val config = com.google.gson.JsonParser.parseString(
            """{"outbounds":[{"tag":"n1"},{"tag":"n2"},{"tag":"n3"},{"tag":"n7"},{"tag":"n9"}]}""").asJsonObject
        fun members(json: com.google.gson.JsonObject) = json["outbounds"].asJsonArray.map { it.asJsonObject }
            .first { it["tag"].asString == "香港" }["outbounds"].asJsonArray.map { it.asString }
        // The database count (countByGroup) equals the built selector members; the export build
        // patches this same reconciled draft (covered end-to-end by GroupEditorPersistenceTest).
        val runtime = members(GroupEditor.patchOutbounds(config, effective, "香港", tags, ""))
        assertEquals(owned.size, runtime.size)
        assertEquals(listOf("n3", "n7", "n9"), runtime)
    }
}

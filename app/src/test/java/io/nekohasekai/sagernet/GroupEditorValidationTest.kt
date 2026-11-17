package io.nekohasekai.sagernet

import io.nekohasekai.sagernet.group.editor.*
import org.junit.Assert.*
import org.junit.Test

class GroupEditorValidationTest {
    private fun draft() = GroupDraft(1, "Work", GroupMode.URL_TEST, listOf(2, 3),
        UrlTestParameters("", 600, 100, "30m", false))
    private val nodes = setOf(EntityId.Node(2), EntityId.Node(3))
    private fun errors(draft: GroupDraft, tags: Set<String> = emptySet()) =
        GroupEditor.validate(draft, nodes, emptyMap(), tags)

    @Test fun identityIsTrimmedNonEmptyUniqueAndLockedAfterCreation() {
        assertEquals("Work", draft().copy(name = "  Work  ").normalized().name)
        assertTrue(errors(draft().copy(name = " \t")).any { it.field == "name" })
        assertTrue(errors(draft(), setOf("Work")).any { it.field == "name" })
        assertEquals("identity_locked", GroupEditor.identityError(draft(), draft().copy(name = "Other")))
        assertEquals("identity_locked", GroupEditor.identityError(draft(), draft().copy(mode = GroupMode.SELECTOR)))
        assertNull(GroupEditor.identityError(draft(), draft().copy(members = listOf(3, 2))))
    }

    @Test fun urlTestParametersHaveExactValuesAndAcceptInheritedUrlAndCoreDurations() {
        assertTrue(errors(draft()).isEmpty())
        for (duration in listOf("30m", "1800s", "2h30m", "30m0.1s")) {
            assertTrue(duration, errors(draft().copy(parameters = draft().parameters.copy(idleTimeout = duration))).isEmpty())
        }
        for (url in listOf("ftp://example.test", "http://", "https://a b", "https:opaque")) {
            assertTrue(url, errors(draft().copy(parameters = draft().parameters.copy(url = url))).any { it.field == "url" })
        }
        assertTrue(errors(draft().copy(parameters = draft().parameters.copy(intervalSeconds = 0))).any { it.field == "interval" })
        assertTrue(errors(draft().copy(parameters = draft().parameters.copy(toleranceMs = -1))).any { it.field == "tolerance" })
        for (duration in listOf("-1s", "garbage", "", "1d", "999999999999999999999h")) {
            assertTrue(duration, errors(draft().copy(parameters = draft().parameters.copy(idleTimeout = duration))).any { it.field == "idleTimeout" })
        }
    }

    @Test fun membersKeepOrderRejectEmptyDuplicatesAndMissingEntities() {
        assertEquals(listOf(3L, 2L), draft().copy(members = listOf(3, 2)).normalized().members)
        assertEquals("members", errors(draft().copy(members = emptyList())).first().field)
        assertTrue(errors(draft().copy(members = listOf(2, 2))).any { it.field == "members[1]" })
        assertTrue(errors(draft().copy(members = listOf(99))).any { it.field == "members[0]" && it.code == "missing_entity" })
    }

    @Test fun graphUsesTypedStableIdsAndLocatesTheBackEdgeMember() {
        val graph: Map<EntityId, List<EntityId>> = mapOf(EntityId.Node(2) to listOf(EntityId.Node(3)),
            EntityId.Node(3) to listOf(EntityId.Group(1)))
        val issues = GroupEditor.validate(draft(), nodes, graph, emptySet())
        assertTrue(issues.any { it.code == "cycle" && it.member == EntityId.Group(1) })
        // Node 1 and group 1 belong to different namespaces.
        assertTrue(GroupEditor.validate(draft().copy(members = listOf(1)),
            setOf(EntityId.Node(1)), emptyMap(), emptySet()).isEmpty())
    }

    @Test fun outboundPatchChangesOnlyOutboundsAndRetainsMemberAndParameterValues() {
        val input = """{"route":{"rules":[{"outbound":"proxy"}],"final":"proxy"},"dns":{"servers":[]},"outbounds":[{"type":"selector","tag":"proxy","outbounds":["g-2","g-3"]},{"type":"socks","tag":"g-2"},{"type":"socks","tag":"g-3"}]}"""
        val before = com.google.gson.JsonParser.parseString(input).asJsonObject
        val changed = GroupEditor.patchOutbounds(before, draft().copy(members = listOf(3, 2)),
            "proxy", mapOf(2L to "g-2", 3L to "g-3"), "https://global.test")
        assertEquals(before["route"], changed["route"])
        assertEquals(before["dns"], changed["dns"])
        val group = changed["outbounds"].asJsonArray[0].asJsonObject
        assertEquals("urltest", group["type"].asString)
        assertEquals("proxy", group["tag"].asString)
        assertEquals("g-3", group["outbounds"].asJsonArray[0].asString)
        assertEquals("https://global.test", group["url"].asString)
        assertEquals("600s", group["interval"].asString)
        assertEquals(100, group["tolerance"].asInt)
        assertEquals("30m", group["idle_timeout"].asString)
        assertFalse(group["interrupt_exist_connections"].asBoolean)
        assertEquals("selector", before["outbounds"].asJsonArray[0].asJsonObject["type"].asString)
    }

    @Test fun patchPreservesEffectiveTunAndUnappliedDnsSettings() {
        val baseline = com.google.gson.JsonParser.parseString(
            """{"inbounds":[{"type":"tun","tag":"active-tun"}],"dns":{"final":"effective-dns"},"outbounds":[{"tag":"g-2"},{"tag":"g-3"}]}""").asJsonObject
        val after = GroupEditor.patchOutbounds(baseline, draft(), "Work",
            mapOf(2L to "g-2", 3L to "g-3"), "https://global.test")
        assertEquals(baseline["inbounds"], after["inbounds"])
        assertEquals(baseline["dns"], after["dns"])
        assertEquals("Work", after["outbounds"].asJsonArray.last().asJsonObject["tag"].asString)
    }

    @Test fun missingNestedTypedGroupIsLocatedAtMemberBeforeCapture() {
        val issues = GroupEditor.validate(draft(), nodes,
            mapOf(EntityId.Node(2) to listOf(EntityId.Group(3))), emptySet())
        assertTrue(issues.any { it.field == "members[0]" && it.member == EntityId.Group(3) && it.code == "missing_entity" })
    }

    @Test fun wholeGraphReferenceCheckDistinguishesMissingTargetGroupFromSameNumberNode() {
        val issues = GroupEditor.referenceIssues(setOf(EntityId.Node(3), EntityId.Node(2)),
            mapOf(EntityId.Node(2) to listOf(EntityId.Group(3))))
        assertEquals(listOf(GroupEditor.Issue("node[2].references[0]", "missing_entity", EntityId.Group(3))), issues)
    }

    @Test fun duplicateTagsAndUnavailableGeneratedMembersAreRejected() {
        for (json in listOf(
            """{"outbounds":[{"tag":"proxy"},{"tag":"proxy"}]}""",
            """{"outbounds":[{"tag":"proxy"}]}""")) {
            try {
                GroupEditor.patchOutbounds(com.google.gson.JsonParser.parseString(json).asJsonObject,
                    draft(), "proxy", mapOf(2L to "g-2", 3L to "g-3"), "https://global.test")
                fail("invalid outbound topology accepted")
            } catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun candidateSnapshotUsesSelectedMainProfileTag() {
        val baseline = com.google.gson.JsonParser.parseString("""{"config":{"outbounds":[{"tag":"proxy","type":"selector","default":"a","outbounds":["a","b"]},{"tag":"a","type":"socks"},{"tag":"b","type":"socks"}]},"files":{}}""").asJsonObject
        baseline.getAsJsonObject("files").addProperty("files/profile_tags.json",
            java.util.Base64.getEncoder().encodeToString("""{"2":{"tag":"a"},"3":{"tag":"b"}}""".toByteArray()))
        val patched = GroupEditor.patchSnapshot(baseline, draft().copy(mode = GroupMode.SELECTOR),
            "proxy", "", selectedProfileId = 3L)
        assertEquals("b", patched["config"].asJsonObject["outbounds"].asJsonArray[0].asJsonObject["default"].asString)
        assertEquals("a", baseline["config"].asJsonObject["outbounds"].asJsonArray[0].asJsonObject["default"].asString)
    }

    @Test fun selectorKeepsExplicitCurrentSelectionInsteadOfFirstMember() {
        val before = com.google.gson.JsonParser.parseString("""{"outbounds":[{"tag":"proxy","type":"selector","default":"b","outbounds":["a","b"]},{"tag":"a","type":"socks"},{"tag":"b","type":"socks"}]}""").asJsonObject
        val selector = draft().copy(mode = GroupMode.SELECTOR)
        fun patch(selected: String? = null) = GroupEditor.patchOutbounds(before, selector, "proxy",
            mapOf(2L to "a", 3L to "b"), "", selected)["outbounds"].asJsonArray[0].asJsonObject
        assertEquals("b", patch()["default"].asString)
        assertEquals("a", patch("a")["default"].asString)
        // N3: the main group's selection left the members -> explicit fallback to the first member.
        assertEquals("a", patch("removed")["default"].asString)
        val removed = GroupEditor.patchOutbounds(before, selector.copy(members = listOf(2)), "proxy",
            mapOf(2L to "a", 3L to "b"), "")["outbounds"].asJsonArray[0].asJsonObject
        assertFalse(removed.has("default"))
        assertEquals("b", before["outbounds"].asJsonArray[0].asJsonObject["default"].asString)
    }

    @Test fun mainGroupSelectionOutsideMembersFallsBackExplicitlyAndSyncsSelection() {
        val before = com.google.gson.JsonParser.parseString("""{"outbounds":[{"tag":"proxy","type":"selector","default":"b","outbounds":["a","b"]},{"tag":"a","type":"socks"},{"tag":"b","type":"socks"}]}""").asJsonObject
        val selector = draft().copy(mode = GroupMode.SELECTOR, members = listOf(2))
        val main = GroupEditor.patchOutbounds(before, selector, "proxy", mapOf(2L to "a", 3L to "b"), "", "b")
        assertEquals("a", main["outbounds"].asJsonArray[0].asJsonObject["default"].asString)
        // The selection follows only when it is the main group and the node left the members.
        assertEquals(2L, GroupEditor.selectionAfterApply(selector, selectedProfileId = 3, mainGroupId = 1))
        assertNull(GroupEditor.selectionAfterApply(selector, selectedProfileId = 2, mainGroupId = 1))
        assertNull(GroupEditor.selectionAfterApply(selector, selectedProfileId = 3, mainGroupId = 9))
        // A dropped selection would otherwise move the main group to wherever the node went.
        assertEquals(2L, GroupEditor.selectionAfterApply(draft().copy(mode = GroupMode.URL_TEST, members = listOf(2)),
            selectedProfileId = 3, mainGroupId = 1))
    }

    @Test fun nodeTagCollisionFailsClosedWithoutMutatingInput() {
        val before = com.google.gson.JsonParser.parseString("""{"outbounds":[{"tag":"Work","type":"socks"},{"tag":"a","type":"socks"}]}""").asJsonObject
        assertEquals("duplicate_tag", assertThrows(IllegalArgumentException::class.java) {
            GroupEditor.patchOutbounds(before, draft().copy(members = listOf(2)), "Work", mapOf(2L to "a"), "")
        }.message)
        assertEquals("socks", before["outbounds"].asJsonArray[0].asJsonObject["type"].asString)
    }

    @Test fun editorCannotBypassUrlTestStabilityLimits() {
        val params = draft().parameters.copy(intervalSeconds = 1, idleTimeout = "2m", interruptExisting = true)
        val issues = errors(draft().copy(parameters = params))
        assertTrue(issues.any { it.code == "minimum_10_seconds" })
        assertTrue(issues.any { it.code == "minimum_30_minutes" })
        assertTrue(issues.any { it.code == "interrupt_locked_off" })
        val before = com.google.gson.JsonParser.parseString("""{"outbounds":[{"tag":"a","type":"socks"},{"tag":"b","type":"socks"}]}""").asJsonObject
        val out = GroupEditor.patchOutbounds(before, draft().copy(parameters = params), "Work",
            mapOf(2L to "a", 3L to "b"), "")["outbounds"].asJsonArray.last().asJsonObject
        assertEquals("10s", out["interval"].asString)
        assertEquals("30m", out["idle_timeout"].asString)
        assertFalse(out["interrupt_exist_connections"].asBoolean)
        for (duration in listOf("0", "1ns", "1.5s", "29m59s"))
            assertTrue(errors(draft().copy(parameters = params.copy(intervalSeconds = 600,
                idleTimeout = duration, interruptExisting = false))).any { it.field == "idleTimeout" })
    }

    @Test fun deletingNodesPrunesOnlySelectedIdsAndRemovesEmptyOverride() {
        assertEquals(listOf(3L), withoutDeletedMembers(draft(), setOf(2))!!.members)
        assertEquals(draft(), withoutDeletedMembers(draft(), setOf(99)))
        assertNull(withoutDeletedMembers(draft(), setOf(2, 3)))
    }
}

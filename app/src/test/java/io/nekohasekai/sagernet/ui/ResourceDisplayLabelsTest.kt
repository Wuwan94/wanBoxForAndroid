package io.nekohasekai.sagernet.ui

import io.nekohasekai.sagernet.GroupType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Batch 4b display-layer identity rules (OpenSpec: 统一资源页与稳定身份）.
 * Expected values come from the spec: duplicate names are disambiguated by a
 * source suffix only; when name and source both collide, the entity ID is
 * added. Labels are keyed by stable entity ID, never by display name.
 */
class ResourceDisplayLabelsTest {

    private fun item(id: Long, name: String, source: String) =
        ResourceDisplayLabels.Item(id, name, source)

    @Test
    fun uniqueNamesStayBare() {
        val labels = ResourceDisplayLabels.labels(
            listOf(
                item(1L, "香港 01", "订阅"),
                item(2L, "自建节点", "本地"),
            )
        )
        assertEquals("香港 01", labels[1L])
        assertEquals("自建节点", labels[2L])
    }

    @Test
    fun duplicateNamesAcrossSourcesGetSourceSuffixOnly() {
        val labels = ResourceDisplayLabels.labels(
            listOf(
                item(1L, "主力", "本地"),
                item(2L, "主力", "订阅"),
                item(3L, "备用", "订阅"),
            )
        )
        assertEquals("主力 (本地)", labels[1L])
        assertEquals("主力 (订阅)", labels[2L])
        assertEquals("备用", labels[3L])
        assertFalse(labels.getValue(1L).contains("#"))
        assertFalse(labels.getValue(2L).contains("#"))
    }

    @Test
    fun duplicateNameAndSourceGetEntityIdSuffix() {
        val labels = ResourceDisplayLabels.labels(
            listOf(
                item(99125L, "同名节点", "机场 A"),
                item(99126L, "同名节点", "机场 A"),
            )
        )
        assertEquals("同名节点 (机场 A #99125)", labels[99125L])
        assertEquals("同名节点 (机场 A #99126)", labels[99126L])
    }

    @Test
    fun labelsAreKeyedByStableIdNotName() {
        val labels = ResourceDisplayLabels.labels(
            listOf(
                item(7L, "重名", "订阅"),
                item(9L, "重名", "订阅"),
            )
        )
        assertEquals(setOf(7L, 9L), labels.keys)
        assertEquals(2, labels.values.toSet().size)
    }

    @Test
    fun renameKeepsIdsAndUnrelatedLabelsStable() {
        val before = ResourceDisplayLabels.labels(
            listOf(
                item(1L, "甲", "本地"),
                item(2L, "乙", "订阅"),
                item(3L, "丙", "订阅"),
            )
        )
        val after = ResourceDisplayLabels.labels(
            listOf(
                item(1L, "甲", "本地"),
                item(2L, "乙改名", "订阅"),
                item(3L, "丙", "订阅"),
            )
        )
        assertEquals(before.keys, after.keys)
        assertEquals(before[1L], after[1L])
        assertEquals(before[3L], after[3L])
        assertEquals("乙改名", after[2L])
        assertTrue(after.values.none { it.contains("乙 (") })
    }

    @Test
    fun plannerSingleOnlyMatchesCurrentSubscription() {
        val groups = listOf(
            ResourceDisplayLabels.GroupRef(1L, GroupType.BASIC),
            ResourceDisplayLabels.GroupRef(2L, GroupType.SUBSCRIPTION),
        )
        assertEquals(
            listOf(2L),
            ResourceDisplayLabels.planUpdateTargets(
                groups, ResourceDisplayLabels.UpdateScope.SINGLE,
                currentGroupId = 2L, selectedGroupIds = emptySet(),
            )
        )
        assertEquals(
            emptyList<Long>(),
            ResourceDisplayLabels.planUpdateTargets(
                groups, ResourceDisplayLabels.UpdateScope.SINGLE,
                currentGroupId = 1L, selectedGroupIds = emptySet(),
            )
        )
    }

    @Test
    fun plannerSelectedFiltersToSubscriptionsPreservingGroupOrder() {
        val groups = listOf(
            ResourceDisplayLabels.GroupRef(5L, GroupType.SUBSCRIPTION),
            ResourceDisplayLabels.GroupRef(6L, GroupType.BASIC),
            ResourceDisplayLabels.GroupRef(7L, GroupType.SUBSCRIPTION),
        )
        assertEquals(
            listOf(5L, 7L),
            ResourceDisplayLabels.planUpdateTargets(
                groups, ResourceDisplayLabels.UpdateScope.SELECTED,
                currentGroupId = 0L, selectedGroupIds = setOf(7L, 6L, 5L),
            )
        )
    }

    @Test
    fun plannerAllReturnsOnlySubscriptions() {
        val groups = listOf(
            ResourceDisplayLabels.GroupRef(1L, GroupType.BASIC),
            ResourceDisplayLabels.GroupRef(2L, GroupType.SUBSCRIPTION),
            ResourceDisplayLabels.GroupRef(3L, GroupType.SUBSCRIPTION),
        )
        assertEquals(
            listOf(2L, 3L),
            ResourceDisplayLabels.planUpdateTargets(
                groups, ResourceDisplayLabels.UpdateScope.ALL,
                currentGroupId = 0L, selectedGroupIds = emptySet(),
            )
        )
    }

    @Test
    fun plannerNeverIncludesLocalGroupsInAnyScope() {
        val groups = listOf(
            ResourceDisplayLabels.GroupRef(1L, GroupType.BASIC),
            ResourceDisplayLabels.GroupRef(2L, GroupType.BASIC),
        )
        for (scope in ResourceDisplayLabels.UpdateScope.values()) {
            assertEquals(
                emptyList<Long>(),
                ResourceDisplayLabels.planUpdateTargets(
                    groups, scope,
                    currentGroupId = 1L, selectedGroupIds = setOf(1L, 2L),
                )
            )
        }
    }
}

package io.nekohasekai.sagernet.ui

import androidx.recyclerview.widget.RecyclerView
import org.junit.Assert.*
import org.junit.Test

class ConfigurationFragmentPagerIdTest {

    @Test
    fun allGroupsSentinelNeverEmitsNoId() {
        val stableId = ConfigurationFragment.pagerStableIdOf(ConfigurationFragment.ALL_GROUPS_SENTINEL_ID)
        assertNotEquals("Stable ID for ALL tab must not collide with RecyclerView.NO_ID", RecyclerView.NO_ID, stableId)
        assertEquals(ConfigurationFragment.PAGER_STABLE_ALL_GROUPS_ID, stableId)
        assertEquals(Long.MIN_VALUE, stableId)
    }

    @Test
    fun realGroupIdsArePreservedWithoutModification() {
        val testIds = listOf(1L, 2L, 1000L, 99202L, 99207L, Long.MAX_VALUE)
        for (id in testIds) {
            assertEquals("Real group ID $id must map to itself", id, ConfigurationFragment.pagerStableIdOf(id))
        }
    }

    @Test
    fun mixedGroupListProducesUniqueStableIds() {
        val groupIds = listOf(
            ConfigurationFragment.ALL_GROUPS_SENTINEL_ID,
            99202L,
            99203L,
            99204L,
            99205L,
            99206L,
            99207L
        )
        val stableIds = groupIds.map { ConfigurationFragment.pagerStableIdOf(it) }

        assertEquals(groupIds.size, stableIds.toSet().size)
        assertFalse("No item may map to RecyclerView.NO_ID", stableIds.contains(RecyclerView.NO_ID))
    }

    @Test
    fun containsItemResolutionMatchesLifecycle() {
        val activeGroupIds = mutableListOf(
            ConfigurationFragment.ALL_GROUPS_SENTINEL_ID,
            99202L,
            99203L
        )

        fun containsItem(itemId: Long): Boolean {
            return activeGroupIds.any { ConfigurationFragment.pagerStableIdOf(it) == itemId }
        }

        // 验证 ALL 组能通过 stableId 找到，且原始 -1L 不再被识别为有效 item
        assertTrue(containsItem(ConfigurationFragment.PAGER_STABLE_ALL_GROUPS_ID))
        assertFalse(containsItem(RecyclerView.NO_ID))

        // 验证真实组
        assertTrue(containsItem(99202L))
        assertTrue(containsItem(99203L))

        // 验证移除真实组后返回 false
        activeGroupIds.remove(99202L)
        assertFalse(containsItem(99202L))
        assertTrue(containsItem(99203L))
    }

    @Test
    fun fragmentStateAdapterTagContract() {
        val allGroupId = ConfigurationFragment.ALL_GROUPS_SENTINEL_ID
        val expectedTag = "f" + ConfigurationFragment.pagerStableIdOf(allGroupId)
        assertEquals("f${Long.MIN_VALUE}", expectedTag)
        assertNotEquals("f-1", expectedTag)

        val realGroupId = 99202L
        assertEquals("f99202", "f" + ConfigurationFragment.pagerStableIdOf(realGroupId))
    }
}

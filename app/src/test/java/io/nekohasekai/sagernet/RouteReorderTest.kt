// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package io.nekohasekai.sagernet

import io.nekohasekai.sagernet.database.RuleEntity
import io.nekohasekai.sagernet.ui.reorderRuleItems
import org.junit.Assert.*
import org.junit.Test

/** B1/O2 路由拖拽排序测试：上游 8.7 提取的 reorderRuleItems。@author 雾晚 */
class RouteReorderTest {

    private fun rules(n: Int) = MutableList(n) { i ->
        RuleEntity(id = (i + 1).toLong(), userOrder = i.toLong())
    }

    @Test fun moveDownRotatesOrders() {
        val list = rules(5)
        val changed = reorderRuleItems(list, 2, 4)
        assertEquals(listOf(1L, 3L, 4L, 2L, 5L), list.map { it.id })
        // userOrder 跟随位置走：位置 i 的元素 userOrder == i
        assertEquals(listOf(0L, 1L, 2L, 3L, 4L), list.map { it.userOrder })
        assertEquals(3, changed.size)
    }

    @Test fun moveUpRotatesOrders() {
        val list = rules(5)
        val changed = reorderRuleItems(list, 5, 2)
        assertEquals(listOf(1L, 5L, 2L, 3L, 4L), list.map { it.id })
        assertEquals(listOf(0L, 1L, 2L, 3L, 4L), list.map { it.userOrder })
        assertEquals(4, changed.size)
    }

    @Test fun moveToSamePositionIsNoop() {
        val list = rules(3)
        assertTrue(reorderRuleItems(list, 2, 2).isEmpty())
        assertEquals(listOf(1L, 2L, 3L), list.map { it.id })
        assertEquals(listOf(0L, 1L, 2L), list.map { it.userOrder })
    }

    @Test fun outOfBoundsIsNoop() {
        val list = rules(3)
        assertTrue(reorderRuleItems(list, 0, 2).isEmpty())
        assertTrue(reorderRuleItems(list, 2, 0).isEmpty())
        assertTrue(reorderRuleItems(list, 4, 2).isEmpty())
        assertTrue(reorderRuleItems(list, 2, 4).isEmpty())
        assertEquals(listOf(1L, 2L, 3L), list.map { it.id })
        assertEquals(listOf(0L, 1L, 2L), list.map { it.userOrder })
    }
}

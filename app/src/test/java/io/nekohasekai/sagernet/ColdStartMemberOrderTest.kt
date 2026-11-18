// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package io.nekohasekai.sagernet

import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.fmt.sortBalancerMembersForColdStart
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** B1/O3 冷启动预排序测试：健康低延迟在前、失败在后。@author 雾晚 */
class ColdStartMemberOrderTest {

    private fun member(id: Long, status: Int, ping: Int) =
        ProxyEntity(id = id, status = status, ping = ping)

    @Test fun healthyLowPingFirstFailedLast() {
        val sorted = sortBalancerMembersForColdStart(
            listOf(
                member(1, 3, 50),    // 失败（低延迟也不应排前）
                member(2, 0, 0),     // 未测
                member(3, 1, 900),   // 健康慢
                member(4, 1, 80),    // 健康快
                member(5, 2, 30),    // 不支持
                member(6, 0, 120),   // 未测但有延迟记录
            )
        ).map { it.id }
        assertEquals(listOf(4L, 3L, 6L, 2L, 1L, 5L), sorted)
    }

    // B4/O1：排序是纯顺序（order-only），不判断种子新鲜度。ProxyEntity
    // 无探针时间戳字段，Kotlin 侧无法区分“刚测的 900ms”与“几天前的
    // 900ms”；健康大延迟仍按延迟排在未测之前，过期判定在 Go 侧
    // URLTestGroup.freshHistory（3*interval 窗口）执行，此处不得另造判定。
    @Test fun sortDoesNotJudgeFreshness() {
        val sorted = sortBalancerMembersForColdStart(
            listOf(
                member(1, 0, 0),      // 未测
                member(2, 1, 99999),  // 健康但延迟极大（可能是过期缓存）
            )
        ).map { it.id }
        assertEquals(listOf(2L, 1L), sorted)
    }

    @Test fun singleOrEmptyListUnchanged() {
        assertEquals(emptyList<ProxyEntity>(), sortBalancerMembersForColdStart(emptyList()))
        val one = listOf(member(1, 1, 10))
        assertEquals(one, sortBalancerMembersForColdStart(one))
    }

    @Test fun buildChainAppliesSortForLeastPingOnly() {
        val source = File("src/main/java/io/nekohasekai/sagernet/fmt/ConfigBuilder.kt").readText()
        assertTrue(source.contains("sortBalancerMembersForColdStart(memberEntities)"))
        assertTrue(source.contains("BalancerBean.STRATEGY_LEAST_PING"))
        // 成员顺序进入 urltest 出站的是排序后的列表（B7c 拆成两步：先由排序后
        // 列表建 (id, tag) 对照，再由对照取 tag 列表，顺序不变）。
        assertTrue(source.contains("val memberTagPairs = sortedMemberEntities.mapNotNull"))
        assertTrue(source.contains("val memberTags = memberTagPairs.map { it.second }"))
    }
}

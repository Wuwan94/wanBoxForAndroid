package io.wanbox.tests

import io.wanbox.contracts.dto.SubscriptionInfo
import io.wanbox.contracts.dto.SubscriptionUpdate
import io.wanbox.domain.BackoffPolicy
import io.wanbox.domain.SubscriptionPolicy
import io.wanbox.domain.addSubscription
import io.wanbox.domain.importProfiles
import io.wanbox.domain.removeSubscription
import io.wanbox.domain.runDueSubscriptions
import io.wanbox.domain.setUpdateWhenConnectedOnly
import io.wanbox.domain.updateSubscription
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** 用例基线 UC-SUB-01 ~ 04 + 到期 / 退避纯函数 */
class SubscriptionTest {

    private fun sub(
        id: Long,
        lastUpdated: Long = 0L,
        interval: Int = 60,
        auto: Boolean = true,
    ) = SubscriptionInfo(id, "组$id", "https://$id.example/sub", lastUpdated, auto, interval)

    @Test
    fun `UC-SUB-01 添加订阅立即拉取一次`() {
        runBlocking {
            val h = Harness()
            h.subs.enqueue(SubscriptionUpdate(1, added = 3))

            val info = h.core.addSubscription("https://a.example/sub", "机场A")

            assertEquals(1L, info.groupId)
            assertEquals(listOf(1L to true), h.subs.updateCalls, "首次拉取必须以用户身份触发")
            assertEquals(3, h.profiles.count, "拉取到的节点应入组")
            assertEquals(1_000L, info.lastUpdated)
        }
    }

    @Test
    fun `UC-SUB-01 首次拉取失败仍保留订阅并记退避`() {
        runBlocking {
            val h = Harness()
            h.subs.enqueue(SubscriptionUpdate(1, error = "HTTP 500"))

            val info = h.core.addSubscription("https://a.example/sub", "机场A")

            assertEquals(1, info.consecutiveFailures)
            assertEquals("HTTP 500", info.lastError)
            assertEquals(61_000L, info.nextRetryAt, "失败后应推迟 1 分钟重试")
            assertEquals(0, h.profiles.count)
        }
    }

    @Test
    fun `UC-SUB-02 更新成功写入节点`() {
        runBlocking {
            val h = Harness(now = 120_000L, initialSubs = listOf(sub(1, interval = 1)))
            h.subs.enqueue(SubscriptionUpdate(1, added = 2, updated = 1))

            val result = h.core.updateSubscription(1)

            assertTrue(result.ok)
            assertEquals(2, h.profiles.count)
            assertEquals(0, h.subs.all.first().consecutiveFailures)
        }
    }

    @Test
    fun `UC-SUB-02 更新失败不丢旧数据并进入退避`() {
        runBlocking {
            val h = Harness(now = 120_000L, initialSubs = listOf(sub(1, interval = 1)))
            h.subs.enqueue(SubscriptionUpdate(1, added = 2))
            h.core.updateSubscription(1)
            assertEquals(2, h.profiles.count)

            h.clock.advance(80_000L)
            h.subs.enqueue(SubscriptionUpdate(1, error = "HTTP 500"))
            val failed = h.core.updateSubscription(1)

            assertFalse(failed.ok)
            assertEquals(2, h.profiles.count, "失败时旧节点必须原样保留")

            val info = h.subs.all.first()
            assertEquals(1, info.consecutiveFailures)
            assertEquals(260_000L, info.nextRetryAt)
            assertFalse(SubscriptionPolicy.isDue(info, 200_000L), "退避窗口内不该再拉")
            assertFalse(SubscriptionPolicy.isDue(info, 259_999L))
            assertTrue(SubscriptionPolicy.isDue(info, 260_000L), "退避到点后应允许重试")
        }
    }

    @Test
    fun `UC-SUB-02 未到期时手动传 byUser=false 不发起请求`() {
        runBlocking {
            val h = Harness(now = 120_000L, initialSubs = listOf(sub(1, lastUpdated = 120_000L, interval = 60)))

            val result = h.core.updateSubscription(1, byUser = false)

            assertFalse(result.ok)
            assertTrue(result.error!!.contains("未到期"))
            assertTrue(h.subs.updateCalls.isEmpty(), "未到期不应触发拉取")
        }
    }

    @Test
    fun `UC-SUB-02 用户主动刷新可以绕过到期判断`() {
        runBlocking {
            val h = Harness(now = 120_000L, initialSubs = listOf(sub(1, lastUpdated = 120_000L, interval = 60)))
            h.subs.enqueue(SubscriptionUpdate(1, added = 1))

            val result = h.core.updateSubscription(1, byUser = true)

            assertTrue(result.ok)
            assertEquals(1, h.subs.updateCalls.size)
        }
    }

    @Test
    fun `UC-SUB-02 订阅不存在时直接返回失败`() {
        runBlocking {
            val h = Harness()
            val result = h.core.updateSubscription(404L)
            assertFalse(result.ok)
            assertTrue(h.subs.updateCalls.isEmpty())
        }
    }

    @Test
    fun `UC-SUB-03 只更新已到期的 autoUpdate 组`() {
        runBlocking {
            val h = Harness(
                now = 7_200_000L,
                initialSubs = listOf(
                    sub(1, lastUpdated = 0L, interval = 60),
                    sub(2, lastUpdated = 7_000_000L, interval = 60),
                    sub(3, lastUpdated = 0L, interval = 60, auto = false),
                ),
            )
            h.subs.enqueue(SubscriptionUpdate(1, added = 1))

            val results = h.core.runDueSubscriptions()

            assertEquals(1, results.size)
            assertEquals(listOf(1L), h.subs.updateCalls.map { it.first })
            assertEquals(false, h.subs.updateCalls.first().second, "定时任务不算用户触发")
        }
    }

    @Test
    fun `UC-SUB-03 只连更模式在未连接时整体跳过`() {
        runBlocking {
            val h = Harness(now = 7_200_000L, initialSubs = listOf(sub(1)))
            h.core.setUpdateWhenConnectedOnly(true)

            val results = h.core.runDueSubscriptions()

            assertTrue(results.isEmpty())
            assertTrue(h.subs.updateCalls.isEmpty())
            assertTrue(h.logger.contains("only-when-connected"))
        }
    }

    @Test
    fun `UC-SUB-03 只连更模式在已连接时照常更新`() {
        runBlocking {
            val h = Harness(now = 7_200_000L, initialSubs = listOf(sub(1)))
            h.core.setUpdateWhenConnectedOnly(true)
            h.core.importProfiles(Links.shadowsocks())
            h.core.connect()
            h.subs.enqueue(SubscriptionUpdate(1, added = 1))

            val results = h.core.runDueSubscriptions()

            assertEquals(1, results.size)
        }
    }

    @Test
    fun `UC-SUB-04 删除订阅连带删掉组内节点`() {
        runBlocking {
            val h = Harness(initialSubs = listOf(sub(1), sub(2)))
            h.core.importProfiles(Links.shadowsocks(host = "g1.example"), groupId = 1L)
            h.core.importProfiles(Links.shadowsocks(host = "g2.example"), groupId = 2L)

            h.core.removeSubscription(1L)

            assertEquals(1, h.subs.all.size)
            assertEquals(1, h.profiles.count, "组 1 的节点应一并删除")
            assertEquals(2L, h.profiles.current.first().groupId)
        }
    }

    @Test
    fun `退避是指数增长且有上限`() {
        assertEquals(0L, BackoffPolicy.nextDelay(0))
        assertEquals(60_000L, BackoffPolicy.nextDelay(1))
        assertEquals(120_000L, BackoffPolicy.nextDelay(2))
        assertEquals(240_000L, BackoffPolicy.nextDelay(3))
        assertEquals(BackoffPolicy.MAX_MS, BackoffPolicy.nextDelay(20))
    }

    @Test
    fun `到期判定边界：恰好等于间隔算到期`() {
        val info = sub(1, lastUpdated = 1_000L, interval = 1)
        assertFalse(SubscriptionPolicy.isDue(info, 60_999L))
        assertTrue(SubscriptionPolicy.isDue(info, 61_000L))
    }

    @Test
    fun `间隔为 0 时按 1 分钟兜底，避免每轮都拉`() {
        val info = SubscriptionInfo(1, "组", "u", 0L, true, 0)
        assertFalse(SubscriptionPolicy.isDue(info, 59_999L))
        assertTrue(SubscriptionPolicy.isDue(info, 60_000L))
    }
}

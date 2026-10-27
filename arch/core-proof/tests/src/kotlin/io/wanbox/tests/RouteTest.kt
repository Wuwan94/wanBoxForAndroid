package io.wanbox.tests

import io.wanbox.contracts.dto.RouteRule
import io.wanbox.domain.deleteRouteRules
import io.wanbox.domain.reorderRoutes
import io.wanbox.domain.saveRouteRule
import io.wanbox.domain.setPerAppProxy
import io.wanbox.domain.setRouteEnabled
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** 用例基线 UC-ROUTE-01/02、UC-APP-01 */
class RouteTest {

    @Test
    fun `UC-ROUTE-01 写入新规则`() {
        runBlocking {
            val h = Harness()
            val rules = h.core.saveRouteRule(RouteRule(1, true, "geosite:cn", 7L, 0))

            assertEquals(1, rules.size)
            assertEquals("geosite:cn", rules.first().match)
            assertEquals(7L, rules.first().outboundProfileId)
            assertEquals(0, h.core.snapshot.value.profiles.size, "写规则不应产生节点")
        }
    }

    @Test
    fun `UC-ROUTE-01 同 id 再写入是更新而不是追加`() {
        runBlocking {
            val h = Harness()
            h.core.saveRouteRule(RouteRule(1, true, "geosite:cn", 7L, 0))
            val rules = h.core.saveRouteRule(RouteRule(1, true, "geosite:geolocation-!cn", 8L, 0))

            assertEquals(1, rules.size)
            assertEquals("geosite:geolocation-!cn", rules.first().match)
        }
    }

    @Test
    fun `UC-ROUTE-01 删除规则`() {
        runBlocking {
            val h = Harness()
            h.core.saveRouteRule(RouteRule(1, true, "a", null, 0))
            h.core.saveRouteRule(RouteRule(2, true, "b", null, 1))

            val left = h.core.deleteRouteRules(listOf(1L))

            assertEquals(1, left.size)
            assertEquals(2L, left.first().id)
        }
    }

    @Test
    fun `UC-ROUTE-02 排序后 order 重新编号`() {
        runBlocking {
            val h = Harness()
            h.core.saveRouteRule(RouteRule(1, true, "a", null, 0))
            h.core.saveRouteRule(RouteRule(2, true, "b", null, 1))
            h.core.saveRouteRule(RouteRule(3, true, "c", null, 2))

            val rules = h.core.reorderRoutes(listOf(3L, 1L, 2L))

            assertEquals(listOf(3L, 1L, 2L), rules.map { it.id })
            assertEquals(listOf(0, 1, 2), rules.map { it.order }, "order 必须连续重排")
        }
    }

    @Test
    fun `UC-ROUTE-02 批量启停`() {
        runBlocking {
            val h = Harness()
            h.core.saveRouteRule(RouteRule(1, true, "a", null, 0))
            h.core.saveRouteRule(RouteRule(2, true, "b", null, 1))

            val rules = h.core.setRouteEnabled(listOf(1L, 2L), false)

            assertTrue(rules.all { !it.enabled })

            val back = h.core.setRouteEnabled(listOf(2L), true)
            assertFalse(back.first { it.id == 1L }.enabled)
            assertTrue(back.first { it.id == 2L }.enabled)
        }
    }

    @Test
    fun `UC-APP-01 选择集归一化并持久化`() {
        runBlocking {
            val h = Harness()

            val selected = h.core.setPerAppProxy(
                listOf(" com.tencent.mm ", "com.example.app", "com.tencent.mm", "  "),
                "include",
            )

            assertEquals(listOf("com.example.app", "com.tencent.mm"), selected)
            assertEquals(selected, h.routes.selected)
            assertEquals("include", h.routes.perAppMode)
            assertEquals("include", h.core.snapshot.value.settings.perAppMode)
        }
    }

    @Test
    fun `UC-APP-01 未知模式拒绝写入`() {
        runBlocking {
            val h = Harness()
            var thrown: Throwable? = null
            try {
                h.core.setPerAppProxy(listOf("a"), "blacklist")
            } catch (e: Throwable) {
                thrown = e
            }
            assertTrue(thrown is IllegalArgumentException, "应拒绝未知模式")
            assertTrue(h.routes.selected.isEmpty(), "拒绝时不得写入任何选择集")
        }
    }
}

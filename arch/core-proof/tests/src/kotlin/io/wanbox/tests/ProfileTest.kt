package io.wanbox.tests

import io.wanbox.contracts.dto.ConnectionState
import io.wanbox.contracts.dto.ProfileSpec
import io.wanbox.contracts.dto.RouteRule
import io.wanbox.domain.cloneProfile
import io.wanbox.domain.createProfile
import io.wanbox.domain.deleteProfiles
import io.wanbox.domain.importProfiles
import io.wanbox.domain.reorderProfiles
import io.wanbox.domain.saveRouteRule
import io.wanbox.domain.updateProfile
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** 用例基线 UC-PROF-01 ~ 06 */
class ProfileTest {

    @Test
    fun `UC-PROF-01 导入三种协议并收集非法行`() {
        runBlocking {
            val h = Harness()
            val text = listOf(
                Links.shadowsocks(),
                Links.vmess(),
                Links.trojan(),
                "not-a-link",
                "",
                "# 注释行",
            ).joinToString("\n")

            val result = h.core.importProfiles(text)

            assertEquals(3, result.imported)
            assertEquals(1, result.errors.size, "只有 not-a-link 应报错")
            assertEquals(
                setOf("shadowsocks", "vmess", "trojan"),
                h.core.snapshot.value.profiles.map { it.protocol }.toSet(),
            )
            assertEquals("香港01", h.core.snapshot.value.profiles.first { it.protocol == "shadowsocks" }.name)
        }
    }

    @Test
    fun `UC-PROF-01 同批次重复链接只保留一条`() {
        runBlocking {
            val h = Harness()
            val link = Links.shadowsocks()

            val result = h.core.importProfiles(listOf(link, link, link).joinToString("\n"))

            assertEquals(1, result.imported)
            assertEquals(2, result.skipped)
            assertEquals(1, h.profiles.count)
        }
    }

    @Test
    fun `UC-PROF-01 再次导入同一链接不覆盖`() {
        runBlocking {
            val h = Harness()
            val link = Links.shadowsocks()
            h.core.importProfiles(link)

            val again = h.core.importProfiles(link)

            assertEquals(0, again.imported)
            assertEquals(1, again.skipped)
            assertEquals(1, h.profiles.count, "重复项不新增也不覆盖")
        }
    }

    @Test
    fun `UC-PROF-01 同机同端口不同密码不算重复`() {
        runBlocking {
            val h = Harness()
            val a = Links.base64Url("aes-256-gcm:pass-a").let { "ss://$it@same.example:8388#A" }
            val b = Links.base64Url("aes-256-gcm:pass-b").let { "ss://$it@same.example:8388#B" }

            val result = h.core.importProfiles(listOf(a, b).joinToString("\n"))

            assertEquals(2, result.imported, "凭据不同应视为不同节点")
        }
    }

    @Test
    fun `UC-PROF-01 不同主机同端口不算重复`() {
        runBlocking {
            val h = Harness()
            val text = listOf(
                Links.shadowsocks(host = "a.example"),
                Links.shadowsocks(host = "b.example"),
            ).joinToString("\n")

            val result = h.core.importProfiles(text)

            assertEquals(2, result.imported)
        }
    }

    @Test
    fun `UC-PROF-02 手动新建节点`() {
        runBlocking {
            val h = Harness()
            val created = h.core.createProfile(
                ProfileSpec("trojan", "手动", mapOf("server" to "t.example", "port" to "443", "password" to "x"))
            )

            assertEquals("t.example", created.server)
            assertEquals(443, created.port)
            assertEquals(1, h.core.snapshot.value.profiles.size)
        }
    }

    @Test
    fun `UC-PROF-03 编辑节点合并字段`() {
        runBlocking {
            val h = Harness()
            h.core.importProfiles(Links.shadowsocks())
            val id = h.core.snapshot.value.profiles.first().id

            val updated = h.core.updateProfile(id, mapOf("name" to "改名后", "password" to "new-pass"))

            assertEquals("改名后", updated.name)
            assertEquals("hk1.example", updated.server, "未提供的字段应保持原值")
        }
    }

    @Test
    fun `UC-PROF-03 编辑当前节点会触发重连`() {
        runBlocking {
            val h = Harness()
            h.core.importProfiles(Links.shadowsocks())
            val id = h.core.snapshot.value.profiles.first().id
            h.core.connect()

            h.core.updateProfile(id, mapOf("name" to "热改"))

            assertEquals(1, h.traffic.persistCalls, "重连前应断开并落库一次")
            assertTrue(h.core.connection.value is ConnectionState.Connected, "重连后应回到 Connected")
        }
    }

    @Test
    fun `UC-PROF-04 删除当前节点先断开并清空规则引用`() {
        runBlocking {
            val h = Harness()
            h.core.importProfiles(listOf(Links.shadowsocks(), Links.vmess()).joinToString("\n"))
            val target = h.core.snapshot.value.profiles.first()
            h.core.saveRouteRule(RouteRule(1, true, "geosite:cn", target.id, 0))
            h.core.connect(target.id)

            val deleted = h.core.deleteProfiles(listOf(target.id))

            assertEquals(1, deleted)
            assertTrue(h.core.connection.value is ConnectionState.Idle, "删当前节点必须先断开")
            assertNull(h.routes.all.first().outboundProfileId, "规则不得悬挂到已删节点")
            assertEquals(1, h.core.snapshot.value.profiles.size)
        }
    }

    @Test
    fun `UC-PROF-05 克隆加副本后缀`() {
        runBlocking {
            val h = Harness()
            h.core.importProfiles(Links.shadowsocks(name = "香港01"))
            val id = h.core.snapshot.value.profiles.first().id

            val clone = h.core.cloneProfile(id)

            assertEquals("香港01 (副本)", clone.name)
            assertEquals(2, h.profiles.count)
        }
    }

    @Test
    fun `UC-PROF-06 拖拽排序并持久化`() {
        runBlocking {
            val h = Harness()
            h.core.importProfiles(
                listOf(
                    Links.shadowsocks(host = "a.example", name = "A"),
                    Links.vmess(host = "b.example", name = "B"),
                    Links.trojan(host = "c.example", name = "C"),
                ).joinToString("\n")
            )
            val ids = h.core.snapshot.value.profiles.map { it.id }
            val last = ids.last()

            val order = h.core.reorderProfiles(last, 0)

            assertEquals(last, order.first())
            assertEquals(last, h.profiles.current.first().id, "顺序应已落库")
            assertEquals(3, order.size)
        }
    }

    @Test
    fun `UC-PROF-06 越界索引被收敛到末尾`() {
        runBlocking {
            val h = Harness()
            h.core.importProfiles(listOf(Links.shadowsocks(), Links.vmess()).joinToString("\n"))
            val first = h.core.snapshot.value.profiles.first().id

            val order = h.core.reorderProfiles(first, 99)

            assertEquals(first, order.last())
        }
    }

    @Test
    fun `快照里的节点来自仓储而不是界面`() {
        runBlocking {
            val h = Harness()
            h.core.importProfiles(Links.shadowsocks())
            assertEquals(h.profiles.count, h.core.snapshot.value.profiles.size)
            assertNotNull(h.core.snapshot.value.profiles.first().id)
        }
    }
}

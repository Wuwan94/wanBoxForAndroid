package io.wanbox.tests

import io.wanbox.contracts.dto.ConnectionState
import io.wanbox.domain.importProfiles
import io.wanbox.domain.selectProfile
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** 用例基线 UC-CONN-01/02/03、UC-SYS-01/03 */
class ConnectionTest {

    @Test
    fun `UC-CONN-01 连接写入选中节点并刷新三个平台入口`() {
        runBlocking {
            val h = Harness()
            h.core.importProfiles(Links.shadowsocks())

            val state = h.core.connect()

            assertTrue(state is ConnectionState.Connected, "应进入 Connected，实得 $state")
            val snap = h.core.snapshot.value
            assertEquals(snap.profiles.first().id, snap.settings.selectedProfileId, "选中节点应写入设置")
            assertSame(h.ports.widgets.last(), h.ports.tiles.last(), "小组件与磁贴应是同一份快照")
            assertSame(h.ports.notifications.last(), h.ports.widgets.last(), "通知也必须是同一份")
            assertSame(h.core.snapshot.value, h.ports.widgets.last(), "快照即核心持有的那一份")
        }
    }

    @Test
    fun `UC-CONN-01 无节点时进入 Failed 且不可重试`() {
        runBlocking {
            val h = Harness()
            val state = h.core.connect()
            assertTrue(state is ConnectionState.Failed && !state.retryable, "实得 $state")
        }
    }

    @Test
    fun `UC-CONN-02 断开回到 Idle 并把流量落库`() {
        runBlocking {
            val h = Harness()
            h.core.importProfiles(Links.shadowsocks())
            h.core.connect()

            val state = h.core.disconnect()

            assertTrue(state is ConnectionState.Idle)
            assertEquals(1, h.traffic.persistCalls, "断开必须落库一次")
            assertTrue(h.ports.tiles.last().connection is ConnectionState.Idle)
        }
    }

    @Test
    fun `UC-CONN-03 热切换不落 Idle 且只换节点`() {
        runBlocking {
            val h = Harness()
            h.core.importProfiles(listOf(Links.shadowsocks(), Links.vmess()).joinToString("\n"))
            h.core.connect()
            val target = h.core.snapshot.value.profiles.first { it.server == "jp1.example" }.id

            val state = h.core.switchTo(target)

            assertTrue(state is ConnectionState.Connected)
            assertEquals(target, (state as ConnectionState.Connected).profileId)
            assertEquals(target, h.core.snapshot.value.settings.selectedProfileId)
            assertEquals(0, h.traffic.persistCalls, "热切换不应触发断开落库")
        }
    }

    @Test
    fun `UC-CONN-03 切到不存在的节点进入 Failed`() {
        runBlocking {
            val h = Harness()
            h.core.importProfiles(Links.shadowsocks())
            h.core.connect()

            val state = h.core.switchTo(999L)

            assertTrue(state is ConnectionState.Failed, "实得 $state")
        }
    }

    @Test
    fun `UC-CONN-03 selectProfile 未连接时等价于连接`() {
        runBlocking {
            val h = Harness()
            h.core.importProfiles(Links.shadowsocks())
            val id = h.core.snapshot.value.profiles.first().id

            val state = h.core.selectProfile(id)

            assertTrue(state is ConnectionState.Connected)
        }
    }

    @Test
    fun `UC-SYS-01 拒绝 VPN 权限时保持 Idle 且不推快照`() {
        runBlocking {
            val h = Harness()
            h.core.importProfiles(Links.shadowsocks())
            val pushed = h.ports.notifications.size
            h.permissions.vpn = false

            val state = h.core.connect()

            assertTrue(state is ConnectionState.Idle, "实得 $state")
            assertEquals(pushed, h.ports.notifications.size, "拒绝权限时不应再推任何连接态")
            assertTrue(h.logger.contains("vpn permission"))
        }
    }

    @Test
    fun `UC-SYS-03 磁贴与小组件走同一条用例`() {
        runBlocking {
            val h = Harness()
            h.core.importProfiles(Links.shadowsocks())

            h.core.onPlatformAction("toggle")
            assertTrue(h.core.connection.value is ConnectionState.Connected, "toggle 应连上")

            h.core.onPlatformAction("toggle")
            assertTrue(h.core.connection.value is ConnectionState.Idle, "再 toggle 应断开")

            assertSame(h.ports.tiles.last(), h.ports.widgets.last())
        }
    }

    @Test
    fun `UC-SYS-03 未知动作只记日志不改状态`() {
        runBlocking {
            val h = Harness()
            h.core.importProfiles(Links.shadowsocks())
            h.core.connect()

            h.core.onPlatformAction("reboot-everything")

            assertTrue(h.core.connection.value is ConnectionState.Connected)
            assertTrue(h.logger.contains("unknown platform action"))
        }
    }
}

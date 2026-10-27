package io.wanbox.tests

import io.wanbox.contracts.dto.ConnectionState
import io.wanbox.contracts.dto.RootModuleInfo
import io.wanbox.contracts.dto.SettingsPatch
import io.wanbox.domain.RootCommandPolicy
import io.wanbox.domain.importProfiles
import io.wanbox.domain.installRootModule
import io.wanbox.domain.rootCommand
import io.wanbox.domain.rootModuleVersion
import io.wanbox.domain.setAutoStart
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** 用例基线 UC-SYS-02、UC-ROOT-01 */
class PlatformTest {

    @Test
    fun `UC-SYS-02 关闭自启时开机保持 Idle`() {
        runBlocking {
            val h = Harness()
            h.core.importProfiles(Links.shadowsocks())
            h.core.setAutoStart(false)

            val state = h.core.onBoot()

            assertTrue(state is ConnectionState.Idle)
            assertEquals(0, h.traffic.persistCalls)
        }
    }

    @Test
    fun `UC-SYS-02 开启自启且有选中节点时自动连接`() {
        runBlocking {
            val h = Harness()
            h.core.importProfiles(Links.shadowsocks())
            h.core.connect()
            h.core.disconnect()
            h.core.setAutoStart(true)

            val state = h.core.onBoot()

            assertTrue(state is ConnectionState.Connected, "实得 $state")
        }
    }

    @Test
    fun `UC-SYS-02 自启但未选节点时保持 Idle`() {
        runBlocking {
            val h = Harness()
            h.core.setAutoStart(true)

            val state = h.core.onBoot()

            assertTrue(state is ConnectionState.Idle)
            assertTrue(h.logger.contains("no selected profile"))
        }
    }

    @Test
    fun `UC-SYS-02 选中节点已被删除时不崩溃，落 Failed`() {
        runBlocking {
            val h = Harness()
            h.settings.update(SettingsPatch(selectedProfileId = 99L))
            h.core.setAutoStart(true)

            val state = h.core.onBoot()

            assertTrue(state is ConnectionState.Failed, "实得 $state")
        }
    }

    @Test
    fun `UC-ROOT-01 白名单命令才放行`() {
        runBlocking {
            val h = Harness()

            assertTrue(h.core.rootCommand("cat /data/adb/modules/wanbox/module.prop") != null)
            assertTrue(h.core.rootCommand("/data/adb/modules/wanbox/wanbox-bridge --status") != null)
            assertTrue(h.core.rootCommand("ls -l /data/adb/modules/wanbox") != null)
            assertEquals(3, h.root.executed.size)
        }
    }

    @Test
    fun `UC-ROOT-01 注入与越权一律拒绝且不落到适配层`() {
        runBlocking {
            val h = Harness()

            assertNull(h.core.rootCommand("cat /data/adb/modules/wanbox/module.prop; rm -rf /"))
            assertNull(h.core.rootCommand("cat /data/adb/modules/wanbox/module.prop | sh"))
            assertNull(h.core.rootCommand("cat /data/adb/modules/wanbox/../../etc/passwd"))
            assertNull(h.core.rootCommand("rm -rf /data/adb/modules/wanbox"))
            assertNull(h.core.rootCommand("cat /data/adb/modules/wanbox/module.prop && reboot"))
            assertNull(h.core.rootCommand(""))
            assertTrue(h.root.executed.isEmpty(), "被拒的命令不得下发")
            assertTrue(h.logger.contains("root command rejected"))
        }
    }

    @Test
    fun `命令白名单是纯函数`() {
        assertTrue(RootCommandPolicy.isAllowed("/data/adb/modules/wanbox/wanbox-bridge --restart"))
        assertFalse(RootCommandPolicy.isAllowed("/data/adb/modules/wanbox/wanbox-bridge --exec"))
        assertFalse(RootCommandPolicy.isAllowed("cat /data/adb/modules/wanbox/module.prop\nreboot"))
    }

    @Test
    fun `UC-ROOT-01 安装成功与失败都能回报`() {
        runBlocking {
            val h = Harness()
            val ok = h.core.installRootModule("bundle")
            assertEquals("1.2.3", ok.version)

            h.root.installResult = RootModuleInfo(false, null, "签名校验失败")
            val failed = h.core.installRootModule("bad-bundle")
            assertFalse(failed.installed)
            assertEquals("签名校验失败", failed.error)
        }
    }

    @Test
    fun `UC-ROOT-01 可查询已装版本`() {
        runBlocking {
            val h = Harness()
            assertEquals("1.2.3", h.core.rootModuleVersion())
        }
    }
}

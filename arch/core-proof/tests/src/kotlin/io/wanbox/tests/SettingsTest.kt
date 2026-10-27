package io.wanbox.tests

import io.wanbox.contracts.dto.ConnectionState
import io.wanbox.domain.SettingsMigration
import io.wanbox.domain.importProfiles
import io.wanbox.domain.migrateSettings
import io.wanbox.domain.setAutoStart
import io.wanbox.domain.setProxyMode
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** 用例基线 UC-NET-01、UC-DATA-04 */
class SettingsTest {

    @Test
    fun `UC-NET-01 连接中切换模式会重连`() {
        runBlocking {
            val h = Harness()
            h.core.importProfiles(Links.shadowsocks())
            h.core.connect()

            val snapshot = h.core.setProxyMode("tun")

            assertEquals("tun", snapshot.proxyMode)
            assertEquals(1, h.traffic.persistCalls, "重连必须先断开落库")
            assertTrue(h.core.connection.value is ConnectionState.Connected, "重连后应回到已连接")
        }
    }

    @Test
    fun `UC-NET-01 未连接时切换模式不触发连接`() {
        runBlocking {
            val h = Harness()
            h.core.importProfiles(Links.shadowsocks())

            val snapshot = h.core.setProxyMode("proxy")

            assertEquals("proxy", snapshot.proxyMode)
            assertTrue(h.core.connection.value is ConnectionState.Idle)
            assertEquals(0, h.traffic.persistCalls)
        }
    }

    @Test
    fun `UC-NET-01 未知模式被拒绝`() {
        runBlocking {
            val h = Harness()
            var thrown: Throwable? = null
            try {
                h.core.setProxyMode("tproxy")
            } catch (e: Throwable) {
                thrown = e
            }
            assertTrue(thrown is IllegalArgumentException)
            assertEquals("vpn", h.settings.value.proxyMode, "拒绝时设置不得改动")
        }
    }

    @Test
    fun `UC-DATA-04 v3_0_10 的旧键能迁移到新结构`() {
        runBlocking {
            val h = Harness()
            // v3.0.10 的 prefs 片段（键名取自 DataStore 的历史命名）
            val legacy = mapOf(
                "proxy_mode" to "proxy",
                "pref_selected_profile" to "42",
                "per_app_mode" to "exclude",
                "auto_start" to "1",
                "clash_api" to "yes",
            )

            val migrated = h.core.migrateSettings(legacy)

            assertEquals(5, migrated)
            val snap = h.settings.value
            assertEquals("proxy", snap.proxyMode)
            assertEquals(42L, snap.selectedProfileId)
            assertEquals("exclude", snap.perAppMode)
            assertTrue(snap.autoStart)
            assertTrue(snap.clashApiEnabled)
            assertEquals(1, h.settings.replaceCalls, "迁移应一次性原子替换")
        }
    }

    @Test
    fun `UC-DATA-04 迁移不丢旧版本没写过的字段`() {
        runBlocking {
            val h = Harness()
            h.core.importProfiles(Links.shadowsocks())
            h.core.connect()
            val selected = h.settings.value.selectedProfileId
            assertEquals(1L, selected, "先确认已选中一个节点")

            h.core.migrateSettings(mapOf("proxy_mode" to "tun"))

            assertEquals(selected, h.settings.value.selectedProfileId, "选中节点不能被迁移抹掉")
            assertEquals("tun", h.settings.value.proxyMode)
        }
    }

    @Test
    fun `UC-DATA-04 非法值整体回滚，原设置一个字不改`() {
        runBlocking {
            val h = Harness()
            h.core.setAutoStart(true)
            val before = h.settings.value

            val migrated = h.core.migrateSettings(
                mapOf("proxy_mode" to "bogus", "auto_start" to "0", "per_app_mode" to "off")
            )

            assertEquals(0, migrated)
            assertEquals(before, h.settings.value, "校验失败必须保持原设置")
            assertEquals(0, h.settings.replaceCalls, "失败时不得调用 replace")
            assertTrue(h.logger.contains("migration aborted"))
        }
    }

    @Test
    fun `UC-DATA-04 非法 selectedProfileId 也算校验失败`() {
        runBlocking {
            val h = Harness()
            val before = h.settings.value

            val migrated = h.core.migrateSettings(mapOf("pref_selected_profile" to "not-a-number"))

            assertEquals(0, migrated)
            assertEquals(before, h.settings.value)
            assertEquals(0, h.settings.replaceCalls)
        }
    }

    @Test
    fun `UC-DATA-04 未知键被忽略而不是报错`() {
        val result = SettingsMigration.migrate(mapOf("totally_unknown" to "x", "proxy_mode" to "tun"))
        assertEquals(1, result.migratedKeys)
        assertTrue(result.ok)
        assertEquals("tun", result.values["proxyMode"])
    }

    @Test
    fun `UC-DATA-04 缺省键不出现在迁移结果里`() {
        val result = SettingsMigration.migrate(mapOf("proxy_mode" to "vpn"))
        assertTrue(result.ok)
        assertEquals(setOf("proxyMode"), result.values.keys, "没写的键不应被臆造，否则会覆盖现值")
    }

    @Test
    fun `布尔迁移兼容 true_1_yes_on`() {
        assertTrue(SettingsMigration.toBool("true"))
        assertTrue(SettingsMigration.toBool("1"))
        assertTrue(SettingsMigration.toBool("YES"))
        assertTrue(SettingsMigration.toBool("on"))
        assertFalse(SettingsMigration.toBool("0"))
        assertFalse(SettingsMigration.toBool(null))
    }
}

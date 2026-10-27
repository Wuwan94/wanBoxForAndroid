package io.wanbox.tests

import io.wanbox.contracts.dto.StorageState
import io.wanbox.domain.checkAndRecoverStorage
import io.wanbox.domain.exportConfig
import io.wanbox.domain.exportLogs
import io.wanbox.domain.importBackup
import io.wanbox.domain.importProfiles
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** 用例基线 UC-DATA-01/02/03、UC-LOG-01 */
class DataTest {

    @Test
    fun `UC-DATA-01 导出带文件头并可选择是否含私有信息`() {
        runBlocking {
            val h = Harness()
            h.core.importProfiles(Links.shadowsocks())

            val withPrivate = h.core.exportConfig(includePrivate = true)
            val text = String(withPrivate.bytes, Charsets.UTF_8)

            assertTrue(text.startsWith("wanbox-backup"))
            assertTrue(text.contains("group\t"), "含私有信息时应带分组")
            assertTrue(withPrivate.fileName.contains("private"))
        }
    }

    @Test
    fun `UC-DATA-01 导出的内容能被 UC-DATA-02 读回`() {
        runBlocking {
            val h = Harness()
            h.core.importProfiles(listOf(Links.shadowsocks(), Links.vmess()).joinToString("\n"))
            val exported = String(h.core.exportConfig(true).bytes, Charsets.UTF_8)

            val imported = h.core.importBackup(exported)

            assertTrue(imported.ok, "导出的文件必须能被自己读回")
            assertEquals(2, imported.imported)
            assertEquals(4, h.profiles.count, "原 2 条 + 导入 2 条")
        }
    }

    @Test
    fun `UC-DATA-02 损坏文件被拒绝且不写库`() {
        runBlocking {
            val h = Harness()
            h.core.importProfiles(Links.shadowsocks())
            val before = h.profiles.count

            val result = h.core.importBackup("this is not a backup")

            assertFalse(result.ok)
            assertTrue(result.errors.first().contains("文件头"))
            assertEquals(before, h.profiles.count, "校验失败时不得改动现有数据")
            assertEquals(0, h.files.importCalls, "连导入函数都不该被调用")
        }
    }

    @Test
    fun `UC-DATA-03 存储健康时不触发恢复`() {
        runBlocking {
            val h = Harness()
            assertTrue(h.core.checkAndRecoverStorage())
            assertEquals(0, h.storage.recoverCalls)
        }
    }

    @Test
    fun `UC-DATA-03 损坏且可恢复`() {
        runBlocking {
            val h = Harness()
            h.storage.state = StorageState.Corrupted

            assertTrue(h.core.checkAndRecoverStorage())
            assertEquals(1, h.storage.recoverCalls)
            assertEquals(StorageState.Healthy, h.storage.state)
        }
    }

    @Test
    fun `UC-DATA-03 损坏且恢复失败时上报 false`() {
        runBlocking {
            val h = Harness()
            h.storage.state = StorageState.Corrupted
            h.storage.recoverable = false

            assertFalse(h.core.checkAndRecoverStorage())
            assertEquals(StorageState.Corrupted, h.storage.state)
        }
    }

    @Test
    fun `UC-LOG-01 导出日志必须脱敏`() {
        runBlocking {
            val h = Harness()

            val export = h.core.exportLogs("info")

            assertFalse(export.content.contains("hunter2"), "password 值不得出现在导出内容里")
            assertFalse(export.content.contains("abc.def.ghi"), "Bearer 值不得出现")
            assertFalse(export.content.contains("plain-token-value"), "token 值不得出现")
            assertFalse(export.content.contains("11111111-2222-3333-4444-555555555555"), "uuid 不得出现")
            assertTrue(export.redactedCount >= 4, "四条敏感信息都应命中，实得 ${export.redactedCount}")
        }
    }

    @Test
    fun `UC-LOG-01 脱敏保留可读上下文`() {
        runBlocking {
            val h = Harness()
            val export = h.core.exportLogs("debug")
            assertTrue(export.content.contains("connected profile=1"), "非敏感内容应原样保留")
            assertTrue(export.content.contains("password=***"))
            assertEquals("wanbox-log-debug.txt", export.fileName)
        }
    }

    @Test
    fun `日志脱敏对空文本安全`() {
        val (out, hits) = io.wanbox.domain.LogRedactor.redact("")
        assertEquals("", out)
        assertEquals(0, hits)
    }
}

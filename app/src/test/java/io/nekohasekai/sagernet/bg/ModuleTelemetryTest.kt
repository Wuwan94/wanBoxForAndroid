// @author 雾晚
package io.nekohasekai.sagernet.bg

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/**
 * ModuleTelemetry 解析与 UI 契约回归测试。
 * 验证：JSON 解析、未知与真实 0 区分、断开清理。
 */
class ModuleTelemetryTest {

    @Test
    fun `parse valid telemetry`() {
        val json = JSONObject().apply {
            put("vmRssBytes", 104857600L) // 100MB
            put("uptimeSeconds", 3600L)
            put("txBytes", 1000L)
            put("rxBytes", 2000L)
            put("connectionCount", 5)
        }
        val t = ModuleTelemetry.fromJson(json)
        assertNotNull(t)
        assertEquals(104857600L, t!!.vmRSSBytes)
        assertEquals(3600L, t.uptimeSeconds)
        assertEquals(5, t.connectionCount)
        assertTrue(t.hasData())
    }

    @Test
    fun `parse missing fields as unknown`() {
        val json = JSONObject() // 空对象
        val t = ModuleTelemetry.fromJson(json)
        assertNotNull(t)
        assertEquals(0L, t!!.vmRSSBytes)
        assertEquals(0L, t.uptimeSeconds)
        assertNull(t.connectionCount) // null 表示未知，不伪造 0
        assertFalse(t.hasData())
    }

    @Test
    fun `distinguish unknown from real zero`() {
        // 未知：字段缺失 -> null
        val unknown = ModuleTelemetry.fromJson(JSONObject())
        assertNull(unknown!!.connectionCount)
        
        // 真实 0：字段存在且为 0 -> 0（非 null）
        val realZero = ModuleTelemetry.fromJson(JSONObject().apply {
            put("connectionCount", 0)
        })
        assertNotNull(realZero!!.connectionCount)
        assertEquals(0, realZero.connectionCount)
    }

    @Test
    fun `parse null returns null`() {
        assertNull(ModuleTelemetry.fromJson(null))
    }

    @Test
    fun `hasData with only uptime`() {
        val t = ModuleTelemetry.fromJson(JSONObject().apply {
            put("uptimeSeconds", 60L)
        })
        assertTrue(t!!.hasData()) // 有运行时长即有数据
    }

    @Test
    fun `ui contract - unknown shows dash`() {
        // UI 契约：未知时显示"—"，不显示 0
        val unknown = ModuleTelemetry.fromJson(JSONObject())
        val displayRss = if (unknown!!.vmRSSBytes > 0) "100MB" else "—"
        assertEquals("—", displayRss)
        
        val displayCount = unknown.connectionCount?.let { "$it 个" } ?: "—"
        assertEquals("—", displayCount)
    }

    @Test
    fun `ui contract - real zero shows zero`() {
        // UI 契约：真实 0 显示"0"，与未知区分
        val realZero = ModuleTelemetry.fromJson(JSONObject().apply {
            put("connectionCount", 0)
        })
        val display = realZero!!.connectionCount?.let { "$it 个" } ?: "—"
        assertEquals("0 个", display)
    }
}

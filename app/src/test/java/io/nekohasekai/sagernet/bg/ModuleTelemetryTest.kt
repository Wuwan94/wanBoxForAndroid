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
        assertEquals(1000L, t.txBytes)
        assertEquals(2000L, t.rxBytes)
        assertEquals(5, t.connectionCount)
        assertTrue(t.hasData())
    }

    @Test
    fun `parse null tx rx as unknown`() {
        // tx/rx 为 null 表示未知，不用 0 冒充
        val json = JSONObject().apply {
            put("vmRssBytes", 104857600L)
            put("uptimeSeconds", 3600L)
            put("txBytes", JSONObject.NULL)
            put("rxBytes", JSONObject.NULL)
        }
        val t = ModuleTelemetry.fromJson(json)
        assertNotNull(t)
        assertNull(t!!.txBytes)
        assertNull(t.rxBytes)
    }

    @Test
    fun `reject negative values`() {
        // 负值视为未知
        val json = JSONObject().apply {
            put("vmRssBytes", -100L)
            put("uptimeSeconds", -10L)
            put("connectionCount", -5)
            put("txBytes", -1L)
        }
        val t = ModuleTelemetry.fromJson(json)
        assertNotNull(t)
        assertNull("负内存必须为未知 null", t!!.vmRSSBytes)
        assertNull("负时长必须为未知 null", t.uptimeSeconds)
        assertNull("负连接数必须为未知 null", t.connectionCount)
        assertNull(t.txBytes)
    }

    @Test
    fun `parse missing fields as unknown`() {
        val json = JSONObject() // 空对象
        val t = ModuleTelemetry.fromJson(json)
        assertNotNull(t)
        assertNull("缺失物理内存为未知 null", t!!.vmRSSBytes)
        assertNull("缺失运行时长为未知 null", t.uptimeSeconds)
        assertNull("缺失连接数为未知 null，不伪造 0", t.connectionCount)
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
        val displayRss = if ((unknown!!.vmRSSBytes ?: 0L) > 0) "100MB" else "—"
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

    @Test
    fun `reject overflow and large illegal values`() {
        // 超出 Int 范围的大数不能溢出截断为有效值或 0
        val overflowJson = JSONObject().apply {
            put("connectionCount", 100000000000000L)
            put("vmRssBytes", -99999999999L)
        }
        val t = ModuleTelemetry.fromJson(overflowJson)
        assertNotNull(t)
        assertNull("溢出大数必须判定为 null 未知，严禁截断或溢出为 0", t!!.connectionCount)
        assertNull("负物理内存必须判定为 null 未知", t.vmRSSBytes)

        val maxIntJson = JSONObject().apply {
            put("connectionCount", Int.MAX_VALUE.toLong())
        }
        val tMax = ModuleTelemetry.fromJson(maxIntJson)
        assertEquals(Int.MAX_VALUE, tMax!!.connectionCount)

        val beyondMaxJson = JSONObject().apply {
            put("connectionCount", Int.MAX_VALUE.toLong() + 1L)
        }
        val tBeyond = ModuleTelemetry.fromJson(beyondMaxJson)
        assertNull(tBeyond!!.connectionCount)
    }

    @Test
    fun `reject floating point and truncation traps`() {
        // 小数不能被截断冒充真实 0 或整数
        val floatJson = JSONObject().apply {
            put("connectionCount", 0.5)
            put("vmRssBytes", 0.9)
            put("uptimeSeconds", 0.1)
        }
        val t = ModuleTelemetry.fromJson(floatJson)
        assertNotNull(t)
        assertNull("浮点小数值不能被截断为有效连接数 0", t!!.connectionCount)
        assertNull("浮点内存值不能被截断为有效内存", t.vmRSSBytes)
        assertNull("浮点时长不能被截断为有效运行时间", t.uptimeSeconds)
    }

    @Test
    fun `zero memory is treated as unknown while zero uptime and zero connection are preserved`() {
        // 运行中核心不可能为 0 字节物理内存；但启动瞬间 uptime 可以为 0，空闲状态连接数也可以为真实 0
        val zeroMetricsJson = JSONObject().apply {
            put("vmRssBytes", 0L)
            put("uptimeSeconds", 0L)
            put("connectionCount", 0)
            put("txBytes", 0L)
            put("rxBytes", 0L)
        }
        val t = ModuleTelemetry.fromJson(zeroMetricsJson)
        assertNotNull(t)
        assertNull("0 字节物理内存不合常理，必须视为未知", t!!.vmRSSBytes)
        assertEquals(0L, t.uptimeSeconds)
        assertEquals(0, t.connectionCount)
        assertEquals(0L, t.txBytes)
        assertEquals(0L, t.rxBytes)
        assertTrue(t.hasData())
    }
}

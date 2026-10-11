// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package io.nekohasekai.sagernet.bg

import org.json.JSONObject
import kotlinx.coroutines.CancellationException

/**
 * @author 雾晚：模块端遥测数据。
 * 对应 rootmodule/runtime.go 中的 Telemetry 结构体。
 * 未知字段为 null，UI 显示"—"，不伪造数据。
 * 区分未知 (null) 与真实 0。
 */
data class ModuleTelemetry(
    val vmRSSBytes: Long = 0L,
    val uptimeSeconds: Long = 0L,
    val txBytes: Long? = null,
    val rxBytes: Long? = null,
    val connectionCount: Int? = null
) {
    companion object {
        fun fromJson(json: JSONObject?): ModuleTelemetry? {
            if (json == null) return null
            return try {
                // @author 雾晚：负值/溢出校验，非法值视为未知
                fun validLong(value: Long): Long? = if (value < 0) null else value
                fun validInt(value: Int): Int? = if (value < 0) null else value

                val vmRss = json.optLong("vmRssBytes", 0L).let { if (it < 0) 0L else it }
                val uptime = json.optLong("uptimeSeconds", 0L).let { if (it < 0) 0L else it }
                // tx/rx：缺失或 null 为未知，不用 0 冒充
                val tx = if (json.isNull("txBytes")) null else validLong(json.optLong("txBytes", -1))
                val rx = if (json.isNull("rxBytes")) null else validLong(json.optLong("rxBytes", -1))
                // connectionCount：isNull 检查，optInt 对 null 返回 0 是陷阱
                val conn = if (json.isNull("connectionCount")) null
                    else validInt(json.optInt("connectionCount", -1))

                ModuleTelemetry(
                    vmRSSBytes = vmRss,
                    uptimeSeconds = uptime,
                    txBytes = tx,
                    rxBytes = rx,
                    connectionCount = conn
                )
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                null
            }
        }
    }

    /** 是否有有效数据 */
    fun hasData(): Boolean = vmRSSBytes > 0 || uptimeSeconds > 0
}
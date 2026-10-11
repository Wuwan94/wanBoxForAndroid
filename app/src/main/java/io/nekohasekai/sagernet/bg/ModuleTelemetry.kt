// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package io.nekohasekai.sagernet.bg

import org.json.JSONObject
import kotlinx.coroutines.CancellationException

/**
 * @author 雾晚：模块端遥测数据。
 * 对应 rootmodule/runtime.go 中的 Telemetry 结构体。
 * 未知字段为 null/0，UI 显示"—"，不伪造数据。
 */
data class ModuleTelemetry(
    val vmRSSBytes: Long = 0L,
    val uptimeSeconds: Long = 0L,
    val txBytes: Long = 0L,
    val rxBytes: Long = 0L,
    val connectionCount: Int? = null
) {
    companion object {
        fun fromJson(json: JSONObject?): ModuleTelemetry? {
            if (json == null) return null
            return try {
                ModuleTelemetry(
                    vmRSSBytes = json.optLong("vmRssBytes", 0L),
                    uptimeSeconds = json.optLong("uptimeSeconds", 0L),
                    txBytes = json.optLong("txBytes", 0L),
                    rxBytes = json.optLong("rxBytes", 0L),
                    connectionCount = if (json.has("connectionCount")) 
                        json.optInt("connectionCount") else null
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
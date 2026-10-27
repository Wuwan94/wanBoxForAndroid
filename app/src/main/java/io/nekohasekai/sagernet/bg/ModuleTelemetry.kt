// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package io.nekohasekai.sagernet.bg

import org.json.JSONObject
import kotlinx.coroutines.CancellationException
import io.nekohasekai.sagernet.aidl.SpeedDisplayData

/**
 * @author 雾晚：模块端遥测数据。
 * 对应 rootmodule/runtime.go 中的 Telemetry 结构体。
 * 未知字段为 null，UI 显示"—"，不伪造数据。
 * 区分未知 (null) 与真实 0。
 */
data class ModuleTelemetry(
    val vmRSSBytes: Long? = null,
    val uptimeSeconds: Long? = null,
    val txBytes: Long? = null,
    val rxBytes: Long? = null,
    val connectionCount: Int? = null
) {
    companion object {
        fun fromJson(json: JSONObject?): ModuleTelemetry? {
            if (json == null) return null
            return try {
                fun parseValidLong(key: String): Long? {
                    if (json.isNull(key) || !json.has(key)) return null
                    val raw = json.opt(key) ?: return null
                    return when (raw) {
                        is Long -> if (raw >= 0L) raw else null
                        is Int -> if (raw >= 0) raw.toLong() else null
                        is Short -> if (raw >= 0) raw.toLong() else null
                        is Byte -> if (raw >= 0) raw.toLong() else null
                        is String -> raw.toLongOrNull()?.takeIf { it >= 0L }
                        else -> null
                    }
                }

                fun parsePositiveLong(key: String): Long? = parseValidLong(key)?.takeIf { it > 0L }

                fun parseValidInt(key: String): Int? {
                    if (json.isNull(key) || !json.has(key)) return null
                    val raw = json.opt(key) ?: return null
                    return when (raw) {
                        is Int -> if (raw >= 0) raw else null
                        is Short -> if (raw >= 0) raw.toInt() else null
                        is Byte -> if (raw >= 0) raw.toInt() else null
                        is Long -> if (raw in 0L..Int.MAX_VALUE.toLong()) raw.toInt() else null
                        is String -> raw.toIntOrNull()?.takeIf { it >= 0 }
                        else -> null
                    }
                }

                // vmRssBytes：物理内存必须为正，缺失、null、负值、非整数或<=0均视为未知（null）
                val vmRss = parsePositiveLong("vmRssBytes")

                // uptimeSeconds：非负有效，缺失、null、负值或非法类型视为未知（null）
                val uptime = parseValidLong("uptimeSeconds")

                // tx/rx：缺失或 null 为未知，不用 0 冒充
                val tx = parseValidLong("txBytes")
                val rx = parseValidLong("rxBytes")

                // connectionCount：严格限制在 [0, Int.MAX_VALUE]，防止大数溢出或浮点截断导致错误归零
                val conn = parseValidInt("connectionCount")

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
    fun hasData(): Boolean = (vmRSSBytes ?: 0L) > 0L || (uptimeSeconds ?: -1L) >= 0L || connectionCount != null
}

/**
 * UI 遥测会话状态跟踪器：
 * 保证服务脱离 Connected 时立即清除上一周期残留样本；
 * 处于非 Connected 状态时拒绝任何迟到的旧回调；
 * 重新进入 Connected 状态时保持全新状态，直至收到当前代次的新采样。
 */
class TelemetrySession(
    initialState: BaseService.State = BaseService.State.Idle
) {
    var serviceState: BaseService.State = initialState
        private set
    var connectedSince: Long = 0L
        private set
    var lastSpeedStats: SpeedDisplayData? = null
        private set

    fun onStateChanged(newState: BaseService.State, elapsedRealtime: Long): Boolean {
        val wasConnected = serviceState == BaseService.State.Connected
        serviceState = newState
        if (newState == BaseService.State.Connected) {
            if (!wasConnected) {
                lastSpeedStats = null
                connectedSince = elapsedRealtime
            }
        } else {
            connectedSince = 0L
            lastSpeedStats = null
        }
        return wasConnected != (newState == BaseService.State.Connected)
    }

    fun onSpeedUpdate(stats: SpeedDisplayData): Boolean {
        if (serviceState == BaseService.State.Connected) {
            lastSpeedStats = stats
            return true
        }
        return false
    }

    fun onDisconnect() {
        onStateChanged(BaseService.State.Idle, 0L)
    }
}

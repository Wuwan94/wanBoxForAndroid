// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package io.nekohasekai.sagernet.core.contracts

// ============================ 节点与选择 ============================

enum class ProfileType {
    SHADOWSOCKS, SHADOWSOCKS_R, VMESS, VLESS, TROJAN, SOCKS, HTTP,
    HYSTERIA, TUIC, WIREGUARD, SSH, CONFIG, UNKNOWN,
}

data class ProfileSummary(
    val id: Long,
    val name: String,
    val type: ProfileType,
    val groupId: Long,
)

/** 当前选中项。state 真值在模块侧，本 DTO 只是读回结果。 */
data class Selection(val profileId: Long, val groupId: Long?)

/** 平台无关的 URI 表示，避免 core 依赖 android.net.Uri。 */
data class PlatformUri(val scheme: String, val authority: String?, val path: String)

data class ImportResult(val imported: Int, val skipped: Int, val errors: List<String>)

// ============================ 模块管理面 ============================

data class ModuleStatus(
    val running: Boolean,
    val revision: String?,
    val selection: Selection?,
    /** eBPF / TUN，对应 fmt/RootInboundBackend 的两条路径。 */
    val backend: String?,
    /** 模块 store/ 是否就绪（真值可用）。 */
    val storeReady: Boolean,
)

data class ModuleLogLine(val level: String, val message: String)

enum class DataUpdateAction { PREPARE, FINISH, ROLLBACK }

/** 错误码沿用 RootModuleClient.safeError 的 [a-z_]{1,80} 约定。 */
class ModuleError(val code: String) : Exception(code)

sealed interface ModuleEvent {
    data class StateChanged(val status: ModuleStatus) : ModuleEvent
    data class Traffic(val snapshot: TrafficSnapshot) : ModuleEvent
    data class SelectionChanged(val selection: Selection) : ModuleEvent
    data class Failed(val error: ModuleError) : ModuleEvent
}

// ============================ 设置与会话 ============================

enum class ServiceState { IDLE, STARTING, RUNNING, STOPPING, FAILED }

/** 取代 DataStore 把偏好、会话、服务句柄三合一的角色。 */
data class SessionState(
    val currentProfileId: Long?,
    val selectedProxyId: Long?,
    val selectedGroupId: Long,
    val serviceState: ServiceState,
    val mixedInboundAuthed: Boolean,
)

data class AppSettings(
    val autoStart: Boolean,
    val clashApiEnabled: Boolean,
    val proxyMode: String,
    val perAppMode: String,
)

// ============================ 流量与探测 ============================

data class TrafficData(val rx: Long, val tx: Long)

data class TrafficSnapshot(val total: TrafficData, val perUid: Map<Int, TrafficData>)

data class SpeedSnapshot(val rxBytesPerSecond: Long, val txBytesPerSecond: Long)

data class NodeRef(val profileId: Long)

enum class SpeedTestDirection { DOWNLOAD, UPLOAD }

data class SpeedTestOutcome(val ok: Boolean, val mbps: Double?, val errorCode: String?)

data class UrlTestResult(val ok: Boolean, val latencyMs: Int?, val errorCode: String?)

data class LandingIpResult(val ip: String?, val errorCode: String?)

data class SubscriptionResult(
    val updated: Int,
    val failed: Int,
    val skipped: Int,
    val errors: List<ModuleError>,
)

// ============================ 用例返回 ============================

/** 显式成功/失败，避免把异常当控制流（对应禁止项：不许把 !! 搬到别处）。 */
sealed interface CoreResult<out T> {
    data class Success<T>(val value: T) : CoreResult<T>
    data class Failure(val code: String, val message: String) : CoreResult<Nothing>
}

/** 界面与平台端口订阅的唯一只读快照。 */
data class CoreSnapshot(
    val status: ModuleStatus?,
    val selection: Selection?,
    val traffic: TrafficSnapshot?,
    val lastErrorCode: String?,
)

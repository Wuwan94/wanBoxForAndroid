// :core:contracts/dto —— 模块对外暴露的唯一 DTO 目录。
// 约束：全部不可变；不含 Android / UI 类型（无 Context/Uri/Bitmap/Parcelable）；时间一律 epoch millis。
package io.wanbox.contracts.dto

// ---------------- 节点 ----------------

data class ProfileSummary(
    val id: Long,
    val groupId: Long?,
    val name: String,
    val protocol: String,
    val server: String,
    val port: Int,
    val latencyMs: Int?,          // null = 未测
    val enabled: Boolean,
)

/** 新建/编辑的输入。fields 按协议自定义，核心只认 server / port / 凭据三项做去重。 */
data class ProfileSpec(
    val protocol: String,
    val name: String,
    val fields: Map<String, String>,
    val groupId: Long? = null,
) {
    val server: String get() = fields["server"].orEmpty()
    val port: Int get() = fields["port"]?.toIntOrNull() ?: 0

    /** 去重键：协议 + 主机 + 端口 + 凭据。同机同端口但密码不同，视为不同节点。 */
    fun dedupeKey(): String {
        val credential = fields["password"] ?: fields["uuid"] ?: fields["username"] ?: ""
        return "${protocol.lowercase()}|${server.lowercase()}|$port|$credential"
    }
}

// ---------------- 连接 ----------------

sealed interface ConnectionState {
    data object Idle : ConnectionState
    data class Connecting(val profileId: Long?, val since: Long) : ConnectionState
    data class Connected(val profileId: Long, val since: Long) : ConnectionState
    data class Failed(val reason: String, val retryable: Boolean) : ConnectionState
    data class Stopping(val since: Long) : ConnectionState
}

// ---------------- 订阅 ----------------

data class SubscriptionInfo(
    val groupId: Long,
    val name: String,
    val url: String,
    val lastUpdated: Long,
    val autoUpdate: Boolean,
    val updateIntervalMinutes: Int,
    val consecutiveFailures: Int = 0,
    val lastError: String? = null,
    val nextRetryAt: Long = 0L,   // 0 = 无退避
)

/** 一次订阅更新的结果。error != null 时视为失败，旧数据必须保持不动。 */
data class SubscriptionUpdate(
    val groupId: Long,
    val added: Int = 0,
    val removed: Int = 0,
    val updated: Int = 0,
    val error: String? = null,
) {
    val ok: Boolean get() = error == null
    val total: Int get() = added + removed + updated

    companion object {
        fun skipped(groupId: Long, reason: String) = SubscriptionUpdate(groupId, error = reason)
    }
}

// ---------------- 路由 / 设置 ----------------

data class RouteRule(
    val id: Long,
    val enabled: Boolean,
    val match: String,
    val outboundProfileId: Long?,
    val order: Int,
)

data class SettingsSnapshot(
    val selectedProfileId: Long?,
    val proxyMode: String,                 // vpn | proxy | tun
    val perAppMode: String,                // off | include | exclude
    val autoStart: Boolean,
    val clashApiEnabled: Boolean,
    val updateWhenConnectedOnly: Boolean = false,
)

/** 局部更新：只改非 null 字段。用类型化 patch 代替 Map，避免字符串键拼错。 */
data class SettingsPatch(
    val selectedProfileId: Long? = null,
    val proxyMode: String? = null,
    val perAppMode: String? = null,
    val autoStart: Boolean? = null,
    val clashApiEnabled: Boolean? = null,
    val updateWhenConnectedOnly: Boolean? = null,
)

// ---------------- 流量 ----------------

data class TrafficSnapshot(
    val profileId: Long?,
    val rxBytes: Long,
    val txBytes: Long,
    val since: Long,
)

data class TrafficPoint(val bucketStart: Long, val rxBytes: Long, val txBytes: Long)

data class TrafficReport(
    val points: List<TrafficPoint>,
    val totalRx: Long,
    val totalTx: Long,
    val peakBucket: TrafficPoint?,
    val unit: String,
)

// ---------------- 导入 / 导出 ----------------

data class ImportResult(val imported: Int, val skipped: Int, val errors: List<String>) {
    val ok: Boolean get() = errors.isEmpty()
}

data class ExportBundle(val fileName: String, val mimeType: String, val bytes: ByteArray) {
    override fun equals(other: Any?): Boolean =
        other is ExportBundle && fileName == other.fileName &&
            mimeType == other.mimeType && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = 31 * (31 * fileName.hashCode() + mimeType.hashCode()) + bytes.contentHashCode()
}

data class BackupPreview(val valid: Boolean, val profiles: Int, val groups: Int, val error: String? = null)

data class LogExport(val fileName: String, val content: String, val redactedCount: Int)

// ---------------- 平台 ----------------

enum class StorageState { Healthy, Corrupted }

data class RootModuleInfo(val installed: Boolean, val version: String? = null, val error: String? = null)

// ---------------- 快照 ----------------

/** 界面订阅的唯一只读快照。UI 不持有业务状态，只渲染这一份。 */
data class AppSnapshot(
    val connection: ConnectionState,
    val profiles: List<ProfileSummary>,
    val subscriptions: List<SubscriptionInfo>,
    val traffic: TrafficSnapshot,
    val settings: SettingsSnapshot,
) {
    val connected: Boolean get() = connection is ConnectionState.Connected
    val currentProfileId: Long? get() = (connection as? ConnectionState.Connected)?.profileId
}

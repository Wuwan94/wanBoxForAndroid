// :core:contracts/Contracts.kt —— 模块对外唯一接口文件。
// 依赖纪律：除 kotlinx-coroutines-core（Flow，非 UI/平台库）外不允许任何依赖。
// 所有外部能力（文件/网络/存储/系统服务）在此声明，实现由 :adapters:* 提供；核心不认具体实现。
package io.wanbox.contracts

import io.wanbox.contracts.dto.AppSnapshot
import io.wanbox.contracts.dto.BackupPreview
import io.wanbox.contracts.dto.ConnectionState
import io.wanbox.contracts.dto.ExportBundle
import io.wanbox.contracts.dto.ImportResult
import io.wanbox.contracts.dto.LogExport
import io.wanbox.contracts.dto.ProfileSpec
import io.wanbox.contracts.dto.ProfileSummary
import io.wanbox.contracts.dto.RootModuleInfo
import io.wanbox.contracts.dto.RouteRule
import io.wanbox.contracts.dto.SettingsPatch
import io.wanbox.contracts.dto.SettingsSnapshot
import io.wanbox.contracts.dto.StorageState
import io.wanbox.contracts.dto.SubscriptionInfo
import io.wanbox.contracts.dto.SubscriptionUpdate
import io.wanbox.contracts.dto.TrafficSnapshot
import kotlinx.coroutines.flow.Flow

// ============================ 仓储 ============================

interface ProfileRepository {
    fun observe(): Flow<List<ProfileSummary>>
    suspend fun get(id: Long): ProfileSummary?
    suspend fun create(spec: ProfileSpec): ProfileSummary
    suspend fun update(id: Long, spec: ProfileSpec): ProfileSummary
    suspend fun delete(ids: List<Long>): Int
    suspend fun clone(id: Long): ProfileSummary
    suspend fun reorder(ids: List<Long>)
    /** 组内节点批量删除（删订阅时用），返回删除条数 */
    suspend fun deleteByGroup(groupId: Long): Int
}

interface SubscriptionGateway {
    fun observe(): Flow<List<SubscriptionInfo>>
    suspend fun add(url: String, name: String, autoUpdate: Boolean, at: Long): SubscriptionInfo
    /** 拉取并解析；失败时 error != null，且不得改动既有节点 */
    suspend fun update(groupId: Long, byUser: Boolean, at: Long): SubscriptionUpdate
    suspend fun remove(groupId: Long)
    /** 记录本次结果：成功清空退避，失败累加失败次数与下次重试时间 */
    suspend fun recordResult(groupId: Long, error: String?, nextRetryAt: Long, at: Long)
}

interface RouteRepository {
    fun rules(): Flow<List<RouteRule>>
    suspend fun upsert(rule: RouteRule)
    suspend fun delete(ids: List<Long>)
    suspend fun reorder(ids: List<Long>)
    suspend fun setEnabled(ids: List<Long>, enabled: Boolean)
    suspend fun selectedPackages(): List<String>
    suspend fun setPerApp(packages: List<String>, mode: String)
    /** 节点被删除后清掉指向它的出站引用，返回受影响规则数 */
    suspend fun clearOutbound(profileIds: List<Long>): Int
}

interface SettingsStore {
    fun snapshot(): Flow<SettingsSnapshot>
    suspend fun update(patch: SettingsPatch)
    /** 原子替换整份设置（迁移与导入用），失败不得留下半份数据 */
    suspend fun replace(snapshot: SettingsSnapshot)
}

// ============================ 端口 ============================

interface ConnectionController {
    val state: Flow<ConnectionState>
    suspend fun connect(profileId: Long? = null): ConnectionState
    suspend fun disconnect()
    suspend fun switchTo(profileId: Long)
}

interface TrafficMeter {
    fun observe(): Flow<TrafficSnapshot>
    suspend fun series(window: String, profileId: Long?): List<TrafficSnapshot>
    suspend fun reset(profileIds: List<Long>)
    /** 断开时把本次会话流量落库（UC-CONN-02） */
    suspend fun persist()
}

interface FileGateway {
    suspend fun preview(source: String): BackupPreview
    suspend fun importFrom(source: String): ImportResult
    suspend fun export(includePrivate: Boolean): ExportBundle
}

interface LogGateway {
    /** 返回原始日志文本（未脱敏）。脱敏规则属核心，由 domain 施加。 */
    suspend fun export(level: String): LogExport
}

interface RootModuleGateway {
    /** 只执行核心已经过白名单校验的命令 */
    suspend fun exec(command: String): String
    suspend fun install(bundle: String): RootModuleInfo
    suspend fun installedVersion(): String?
}

interface StorageHealthPort {
    suspend fun check(): StorageState
    suspend fun recover(): Boolean
}

interface AssetGateway {
    suspend fun ensureAssets(): Boolean
    suspend fun iconFor(profile: ProfileSummary): ByteArray?
}

interface PermissionGate {
    suspend fun requireVpn(): Boolean
    suspend fun requireNotifications(): Boolean
}

/** UI 之外的平台呈现：小组件、磁贴、通知，全部由同一份快照驱动 */
interface PlatformPorts {
    suspend fun renderWidget(snapshot: AppSnapshot)
    suspend fun renderTile(snapshot: AppSnapshot)
    suspend fun renderNotification(snapshot: AppSnapshot)
}

interface Clock { fun now(): Long }

interface Logger {
    fun info(tag: String, msg: String)
    fun warn(tag: String, msg: String, error: Throwable? = null)
}

// ============================ 用例签名（domain 对外） ============================

interface UseCase<in I, out O> { suspend operator fun invoke(input: I): O }

data class ToggleInput(val desired: Boolean, val profileId: Long? = null)

interface ToggleConnection : UseCase<ToggleInput, ConnectionState>
interface SelectProfile : UseCase<Long, ConnectionState>
interface ImportProfiles : UseCase<String, ImportResult>
interface UpdateSubscription : UseCase<Long, ImportResult>
interface ExportConfig : UseCase<Boolean, ExportBundle>
interface ResetTraffic : UseCase<List<Long>, Unit>

package io.wanbox.domain

import io.wanbox.contracts.Clock
import io.wanbox.contracts.FileGateway
import io.wanbox.contracts.LogGateway
import io.wanbox.contracts.Logger
import io.wanbox.contracts.PermissionGate
import io.wanbox.contracts.PlatformPorts
import io.wanbox.contracts.ProfileRepository
import io.wanbox.contracts.RootModuleGateway
import io.wanbox.contracts.RouteRepository
import io.wanbox.contracts.SettingsStore
import io.wanbox.contracts.StorageHealthPort
import io.wanbox.contracts.SubscriptionGateway
import io.wanbox.contracts.TrafficMeter
import io.wanbox.contracts.dto.AppSnapshot
import io.wanbox.contracts.dto.ConnectionState
import io.wanbox.contracts.dto.SettingsPatch
import io.wanbox.contracts.dto.SettingsSnapshot
import io.wanbox.contracts.dto.TrafficSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

/**
 * 领域核心：唯一状态源 + 用例入口。
 *
 * 依赖纪律：只依赖 :core:contracts，不出现任何 Android / UI 类型。
 * 所有外部能力（存储、网络、系统服务、权限、呈现）都从构造参数注入，核心不认具体实现。
 *
 * 用例实现分散在同包的其他文件里（ProfileUseCases / SubscriptionUseCases / ...），
 * 通过 internal 成员访问依赖，保持每个文件职责单一。
 */
class AppCore(
    internal val profiles: ProfileRepository,
    internal val subscriptions: SubscriptionGateway,
    internal val routes: RouteRepository,
    internal val settings: SettingsStore,
    internal val traffic: TrafficMeter,
    internal val files: FileGateway,
    internal val logs: LogGateway,
    internal val root: RootModuleGateway,
    internal val storage: StorageHealthPort,
    internal val permissions: PermissionGate,
    internal val ports: PlatformPorts,
    internal val clock: Clock,
    internal val logger: Logger,
) {
    internal val connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val connection: StateFlow<ConnectionState> = connectionState.asStateFlow()

    internal val snapshotState = MutableStateFlow(defaultSnapshot())
    val snapshot: StateFlow<AppSnapshot> = snapshotState.asStateFlow()

    // ---------------- UC-CONN-01 / 02 / 03 ----------------

    suspend fun toggleConnection(desired: Boolean, profileId: Long? = null): ConnectionState =
        if (desired) connect(profileId) else disconnect()

    /**
     * UC-CONN-01 连接。
     * 副作用：修改选中节点、通知/磁贴/小组件刷新（统一在 publish 内完成）。
     * UC-SYS-01：VPN 权限未授予时保持 Idle，不进入 Failed——用户拒绝不是错误状态。
     */
    suspend fun connect(profileId: Long? = null): ConnectionState {
        val current = connectionState.value
        if (current is ConnectionState.Connected && (profileId == null || profileId == current.profileId)) {
            return current
        }
        if (current is ConnectionState.Connecting) return current

        if (!permissions.requireVpn()) {
            logger.warn("core", "connect denied: vpn permission not granted")
            return connectionState.value
        }

        val target = profileId
            ?: settings.snapshot().first().selectedProfileId
            ?: profiles.observe().first().firstOrNull()?.id
            ?: return fail("没有可选节点", retryable = false)

        connectionState.value = ConnectionState.Connecting(target, clock.now())
        publish()

        val profile = profiles.get(target) ?: return fail("节点不存在", retryable = false)
        settings.update(SettingsPatch(selectedProfileId = profile.id))
        connectionState.value = ConnectionState.Connected(profile.id, clock.now())
        logger.info("core", "connected profile=${profile.id} ${profile.name}")
        publish()
        return connectionState.value
    }

    /** UC-CONN-02 断开。副作用：本次会话流量落库；通知移除、磁贴回到 Idle。 */
    suspend fun disconnect(): ConnectionState {
        if (connectionState.value is ConnectionState.Idle) return connectionState.value
        connectionState.value = ConnectionState.Stopping(clock.now())
        publish()
        traffic.persist()
        connectionState.value = ConnectionState.Idle
        logger.info("core", "disconnected, traffic persisted")
        publish()
        return connectionState.value
    }

    /** UC-CONN-03 热切换：连接中换节点不落 Idle，只改当前节点。 */
    suspend fun switchTo(profileId: Long): ConnectionState {
        if (profiles.get(profileId) == null) return fail("节点不存在", retryable = false)
        if (connectionState.value is ConnectionState.Connected) {
            connectionState.value = ConnectionState.Connected(profileId, clock.now())
            settings.update(SettingsPatch(selectedProfileId = profileId))
            logger.info("core", "hot switch to profile=$profileId")
            publish()
            return connectionState.value
        }
        return connect(profileId)
    }

    internal suspend fun fail(reason: String, retryable: Boolean): ConnectionState {
        connectionState.value = ConnectionState.Failed(reason, retryable)
        logger.warn("core", "connect failed: $reason")
        publish()
        return connectionState.value
    }

    // ---------------- UC-SYS-02 / 03 ----------------

    /** UC-SYS-02 开机自启：只看设置，不碰系统 API。 */
    suspend fun onBoot(): ConnectionState {
        val snap = settings.snapshot().first()
        if (!snap.autoStart) {
            logger.info("core", "boot: autostart disabled")
            publish()
            return connectionState.value
        }
        val selected = snap.selectedProfileId
        if (selected == null) {
            logger.info("core", "boot: no selected profile, stay idle")
            publish()
            return connectionState.value
        }
        return connect(selected)
    }

    /**
     * UC-SYS-03 小组件 / 磁贴点击。
     * 入口在 UI 之外，但必须走同一条用例，产出同一份快照。
     */
    suspend fun onPlatformAction(action: String, profileId: Long? = null): ConnectionState = when (action) {
        "toggle" -> toggleConnection(connectionState.value !is ConnectionState.Connected, profileId)
        "connect" -> connect(profileId)
        "disconnect" -> disconnect()
        "switch" -> {
            val target = profileId ?: settings.snapshot().first().selectedProfileId
            if (target == null) fail("没有可选节点", retryable = false) else switchTo(target)
        }
        else -> {
            logger.warn("core", "unknown platform action: $action")
            connectionState.value
        }
    }

    // ---------------- 单一状态源 ----------------

    /** 状态变化后重组快照，一次性推给 UI / 通知 / 磁贴 / 小组件（同一对象）。 */
    suspend fun publish() {
        val snap = AppSnapshot(
            connection = connectionState.value,
            profiles = profiles.observe().first(),
            subscriptions = subscriptions.observe().first(),
            traffic = traffic.observe().first(),
            settings = settings.snapshot().first(),
        )
        snapshotState.value = snap
        ports.renderNotification(snap)
        ports.renderTile(snap)
        ports.renderWidget(snap)
    }

    private fun defaultSnapshot() = AppSnapshot(
        connection = ConnectionState.Idle,
        profiles = emptyList(),
        subscriptions = emptyList(),
        traffic = TrafficSnapshot(null, 0, 0, 0),
        settings = SettingsSnapshot(null, "vpn", "off", false, false),
    )
}

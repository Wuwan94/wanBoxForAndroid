package io.wanbox.adapters.memory

import io.wanbox.contracts.Clock
import io.wanbox.contracts.Logger
import io.wanbox.contracts.PermissionGate
import io.wanbox.contracts.PlatformPorts
import io.wanbox.contracts.dto.AppSnapshot
import io.wanbox.contracts.dto.ConnectionState

/** 记录每次呈现调用，用来断言"三个入口拿到的是同一份快照"。 */
class RecordingPlatformPorts : PlatformPorts {

    val widgets = mutableListOf<AppSnapshot>()
    val tiles = mutableListOf<AppSnapshot>()
    val notifications = mutableListOf<AppSnapshot>()

    override suspend fun renderWidget(snapshot: AppSnapshot) { widgets += snapshot }
    override suspend fun renderTile(snapshot: AppSnapshot) { tiles += snapshot }
    override suspend fun renderNotification(snapshot: AppSnapshot) { notifications += snapshot }

    val last: AppSnapshot? get() = notifications.lastOrNull()
    val lastConnection: ConnectionState? get() = last?.connection
}

class FixedClock(private var value: Long) : Clock {
    override fun now(): Long = value
    fun advance(ms: Long) { value += ms }
    fun set(ms: Long) { value = ms }
}

class BufferLogger : Logger {
    val lines = mutableListOf<String>()
    override fun info(tag: String, msg: String) { lines += "I/$tag $msg" }
    override fun warn(tag: String, msg: String, error: Throwable?) { lines += "W/$tag $msg" }
    fun contains(keyword: String): Boolean = lines.any { it.contains(keyword) }
}

/** 权限门：默认全给，测试里可逐项收回。 */
class FakePermissions(
    var vpn: Boolean = true,
    var notifications: Boolean = true,
) : PermissionGate {
    var vpnRequests: Int = 0
        private set

    override suspend fun requireVpn(): Boolean { vpnRequests++; return vpn }
    override suspend fun requireNotifications(): Boolean = notifications
}

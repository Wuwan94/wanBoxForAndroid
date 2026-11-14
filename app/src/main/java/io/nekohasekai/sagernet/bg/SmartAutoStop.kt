package io.nekohasekai.sagernet.bg

import java.net.InetAddress

object SmartAutoStopRuleParser {
    fun parse(text: String): Result<List<String>> = runCatching {
        val entries = text.split(Regex("[,;，;\\n\\r]+")).map { it.trim() }.filter { it.isNotEmpty() }
        require(entries.size <= 5) { "too_many_rules" }
        entries.map { raw ->
            val gateway = raw.startsWith("gateway:", true)
            val value = if (gateway) raw.substring(8) else raw
            require(value.isNotBlank() && !value.any { it.isWhitespace() }) { "invalid_rule" }
            val host = value.substringBefore('/')
            require(host.matches(Regex("[0-9A-Fa-f:.]+"))) { "invalid_rule" }
            require(':' in host || (host.split('.').size == 4 && host.split('.').all { it.matches(Regex("[0-9]{1,3}")) && it.toInt() in 0..255 })) { "invalid_rule" }
            require('%' !in host) { "invalid_rule" }
            val isIpv6 = ':' in host
            InetAddress.getByName(host) // Validate the numeric literal; mapped IPv6 may normalize to IPv4.
            if ('/' in value) {
                val bits = value.substringAfter('/').toIntOrNull()
                require(bits != null && bits in 0..if (isIpv6) 128 else 32) { "invalid_cidr" }
            } else require(value == host) { "invalid_rule" }
            (if (gateway) "gateway:" else "") + value.lowercase()
        }
    }
}

/** Enabling requires actionable notifications on every supported Android version. */
enum class NotificationGate { AVAILABLE, REQUEST_PERMISSION, OPEN_SETTINGS }

object SmartAutoStopNotificationGate {
    fun evaluate(sdk: Int, runtimeGranted: Boolean, appEnabled: Boolean, channelEnabled: Boolean): NotificationGate = when {
        sdk >= 33 && !runtimeGranted -> NotificationGate.REQUEST_PERMISSION
        !appEnabled || !channelEnabled -> NotificationGate.OPEN_SETTINGS
        else -> NotificationGate.AVAILABLE
    }
}

object SmartAutoStopAndroidUser {
    // Android assigns each user/profile a range of 100000 UIDs. Use the public
    // Process.myUid() rather than the hidden UserHandle.myUserId/getIdentifier.
    fun fromUid(uid: Int): Int {
        require(uid >= 0)
        return uid / 100000
    }
}

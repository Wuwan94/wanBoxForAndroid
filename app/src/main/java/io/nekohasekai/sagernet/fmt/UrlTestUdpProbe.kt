// SPDX-License-Identifier: GPL-3.0-or-later
package io.nekohasekai.sagernet.fmt

import com.google.common.net.InetAddresses

/**
 * 自管 UDP 回显端点只属于正在运行的正式配置：导出配置和一次性测速配置
 * 都不得携带它（否则导出文件会把探测目标泄漏给接收方，一次性测速也会
 * 凭空多出探测流量）。这是 buildConfig 里唯一的判定点，单列出来便于回归。
 */
internal fun effectiveUdpProbeServer(forTest: Boolean, forExport: Boolean, configured: String): String? =
    if (!forTest && !forExport) configured else null

/** Numeric IP and port only: no DNS lookup, scoped address or implicit probe service. */
internal fun normalizeUdpProbeServer(input: String): String? {
    val endpoint = input.trim()
    val colon = endpoint.lastIndexOf(':')
    if (colon <= 0) return null
    val portText = endpoint.substring(colon + 1)
    if (portText.isEmpty() || portText.any { it !in '0'..'9' }) return null
    val port = portText.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
    val address = endpoint.substring(0, colon)
    val bracketed = address.startsWith('[') && address.endsWith(']')
    val host = if (bracketed) address.substring(1, address.length - 1) else address
    if ('%' in host || (':' in host) != bracketed || !InetAddresses.isInetAddress(host)) return null
    return if (bracketed) "[$host]:$port" else "$host:$port"
}

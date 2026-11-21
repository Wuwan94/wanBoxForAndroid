// SPDX-License-Identifier: GPL-3.0-or-later
package io.nekohasekai.sagernet.fmt

import com.google.common.net.InetAddresses

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

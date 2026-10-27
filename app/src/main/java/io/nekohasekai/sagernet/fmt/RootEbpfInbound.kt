// @author 雾晚
package io.nekohasekai.sagernet.fmt

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import moe.matsuri.nb4a.SingBoxOptions.Inbound

internal data class RootEbpfUidFilter(
    val includeUids: List<Int>? = null,
    val excludeUids: List<Int>? = null,
)

/** Mirrors the existing root-TUN UID policy without changing its allowlist/bypass meaning. */
internal fun rootEbpfUidFilter(
    proxyApps: Boolean,
    bypass: Boolean,
    selectedUids: List<Int>,
    appUid: Int,
    rootUidPolicy: Int,
): RootEbpfUidFilter {
    if (!proxyApps) {
        return if (rootUidPolicy == 2) RootEbpfUidFilter(excludeUids = listOf(0)) else RootEbpfUidFilter()
    }
    val selected = selectedUids.distinct()
    if (bypass) {
        val excluded = (selected + if (rootUidPolicy == 2) listOf(0) else emptyList()).distinct()
        return RootEbpfUidFilter(excludeUids = excluded.takeIf { it.isNotEmpty() })
    }
    val included = (selected + appUid + if (rootUidPolicy == 1) listOf(0) else emptyList()).distinct()
    return RootEbpfUidFilter(includeUids = included)
}

/**
 * Build an eBPF inbound as generic sing-box JSON: the app's generated option model predates
 * the NetProxy fork's eBPF schema, while the managed Android core validates the same fields.
 */
internal fun buildRootEbpfInbound(
    includeUids: List<Int>?,
    excludeUids: List<Int>?,
    ipv6: Boolean,
    bypassPrivateAddress: Boolean,
): Inbound {
    val local = JsonObject().apply {
        addProperty("enabled", true)
        addProperty("data_plane", "cgroup")
        addProperty("dns_mode", "respect_policy")
        addProperty("ipv6", ipv6)
        addProperty("bypass_private_address", bypassPrivateAddress)
        includeUids?.takeIf { it.isNotEmpty() }?.let { uids ->
            add("include_uid", JsonArray().apply { uids.forEach(::add) })
        }
        excludeUids?.takeIf { it.isNotEmpty() }?.let { uids ->
            add("exclude_uid", JsonArray().apply { uids.forEach(::add) })
        }
    }
    val config = JsonObject().apply {
        add("network", JsonArray().apply { add("tcp"); add("udp") })
        addProperty("udp_timeout", "5m")
        add("local", local)
        add("shared", JsonObject().apply { addProperty("enabled", false) })
    }
    return Inbound().apply {
        type = "ebpf"
        // Keep the logical inbound tag stable so existing route rules remain valid.
        tag = "tun-in"
        _hack_custom_config = config.toString()
    }
}

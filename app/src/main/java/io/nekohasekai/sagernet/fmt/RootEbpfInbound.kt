// @author 雾晚
package io.nekohasekai.sagernet.fmt

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import moe.matsuri.nb4a.SingBoxOptions.Inbound

internal data class RootEbpfUidFilter(
    val includeUids: List<Int>? = null,
    val excludeUids: List<Int>? = null,
    // B6（N5）：空白名单护栏。白名单模式下解析后 UID 与原生 include 均为空时，
    // 运行时只设 exclude_uid_range=["0:4294967294"]，不生成空 include。
    val excludeUidRange: List<String>? = null,
)

/** B6（N5）：空白名单护栏的范围值（与 rootmodule/per_user_apps.go 一致）。 */
internal const val EXCLUDE_ALL_UID_RANGE = "0:4294967294"

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
    // B6（N5）：白名单为空时护栏生效——排除全部有效 UID，不生成空 include，
    // 也不把管理器自身 UID 当作唯一的 include（空名单即不代理任何应用）。
    if (selected.isEmpty()) {
        return RootEbpfUidFilter(excludeUidRange = listOf(EXCLUDE_ALL_UID_RANGE))
    }
    val included = (selected + appUid + if (rootUidPolicy == 1) listOf(0) else emptyList()).distinct()
    return RootEbpfUidFilter(includeUids = included)
}

/**
 * Build an eBPF inbound as generic sing-box JSON: the app's generated option model predates
 * the NetProxy fork's eBPF schema, while the managed Android core validates the same fields.
 *
 * B6（N4）：shared 路径只在显式开启时启用（默认关闭）；启用时 interface MUST
 * 含至少一个真实下游接口（Root 侧校验，loopback 不得入选），data_plane 为
 * socket_assign（默认）或 packet_rewrite，dns_mode 默认 hijack，
 * default_interface 默认 false。开启前 MUST 过 eBPF 能力预检（Root apply 门禁）。
 * B6（N5）：excludeUidRange 为空白名单护栏（见 rootEbpfUidFilter）。
 */
internal data class RootEbpfSharedConfig(
    val enabled: Boolean = false,
    val interfaces: List<String> = emptyList(),
    val dataPlane: String = "socket_assign",
    val dnsMode: String = "hijack",
    val defaultInterface: Boolean = false,
    val udpTimeout: String = "5m",
    val includeSourceCidr: List<String> = emptyList(),
    val excludeSourceCidr: List<String> = emptyList(),
    val includeMacAddress: List<String> = emptyList(),
    val excludeMacAddress: List<String> = emptyList(),
)

internal fun buildRootEbpfInbound(
    includeUids: List<Int>?,
    excludeUids: List<Int>?,
    ipv6: Boolean,
    bypassPrivateAddress: Boolean,
    excludeUidRange: List<String>? = null,
    shared: RootEbpfSharedConfig = RootEbpfSharedConfig(),
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
        excludeUidRange?.takeIf { it.isNotEmpty() }?.let { ranges ->
            add("exclude_uid_range", JsonArray().apply { ranges.forEach(::add) })
        }
    }
    val sharedObj = JsonObject().apply {
        // 共享路径与本机路径独立开关；仅 eBPF 共享路径可接管共享流量。
        // 本开关不创建热点、DHCP、NAT 或 IPv6 RA（见设置页文案）。
        addProperty("enabled", shared.enabled)
        if (shared.enabled) {
            add("interface", JsonArray().apply { shared.interfaces.forEach(::add) })
            addProperty("data_plane", shared.dataPlane)
            addProperty("dns_mode", shared.dnsMode)
            addProperty("default_interface", shared.defaultInterface)
            addProperty("udp_timeout", shared.udpTimeout)
            fun addList(key: String, values: List<String>) {
                if (values.isNotEmpty()) add(key, JsonArray().apply { values.forEach(::add) })
            }
            addList("include_source_cidr", shared.includeSourceCidr)
            addList("exclude_source_cidr", shared.excludeSourceCidr)
            addList("include_mac_address", shared.includeMacAddress)
            addList("exclude_mac_address", shared.excludeMacAddress)
        }
    }
    val config = JsonObject().apply {
        add("network", JsonArray().apply { add("tcp"); add("udp") })
        addProperty("udp_timeout", "5m")
        add("local", local)
        add("shared", sharedObj)
    }
    return Inbound().apply {
        type = "ebpf"
        // Keep the logical inbound tag stable so existing route rules remain valid.
        tag = "tun-in"
        _hack_custom_config = config.toString()
    }
}

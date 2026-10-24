// @author 雾晚
package io.nekohasekai.sagernet.fmt

import android.widget.Toast
import io.nekohasekai.sagernet.*
import io.nekohasekai.sagernet.bg.TunAddresses
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.TYPE_CONFIG
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.fmt.ConfigBuildResult.IndexEntity
import io.nekohasekai.sagernet.fmt.hysteria.HysteriaBean
import io.nekohasekai.sagernet.fmt.hysteria.buildSingBoxOutboundHysteriaBean
import io.nekohasekai.sagernet.fmt.internal.ChainBean
import io.nekohasekai.sagernet.fmt.internal.BalancerBean
import io.nekohasekai.sagernet.fmt.shadowsocks.ShadowsocksBean
import io.nekohasekai.sagernet.fmt.shadowsocks.buildSingBoxOutboundShadowsocksBean
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.fmt.socks.buildSingBoxOutboundSocksBean
import io.nekohasekai.sagernet.fmt.ssh.SSHBean
import io.nekohasekai.sagernet.fmt.ssh.buildSingBoxOutboundSSHBean
import io.nekohasekai.sagernet.fmt.tuic.TuicBean
import io.nekohasekai.sagernet.fmt.tuic.buildSingBoxOutboundTuicBean
import io.nekohasekai.sagernet.fmt.juicity.JuicityBean
import io.nekohasekai.sagernet.fmt.juicity.buildSingBoxOutboundJuicityBean
import io.nekohasekai.sagernet.fmt.v2ray.StandardV2RayBean
import io.nekohasekai.sagernet.fmt.v2ray.buildSingBoxOutboundStandardV2RayBean
import io.nekohasekai.sagernet.fmt.shadowsocksr.ShadowsocksRBean
import io.nekohasekai.sagernet.fmt.shadowsocksr.buildSingBoxOutboundShadowsocksRBean
import io.nekohasekai.sagernet.fmt.snell.SnellBean
import io.nekohasekai.sagernet.fmt.snell.buildSingBoxOutboundSnellBean
import io.nekohasekai.sagernet.fmt.wireguard.WireGuardBean
import io.nekohasekai.sagernet.fmt.wireguard.buildSingBoxEndpointWireGuardBean
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.isIpAddress
import io.nekohasekai.sagernet.ktx.isIpAddressV6
import io.nekohasekai.sagernet.ktx.unwrapIPV6Host
import io.nekohasekai.sagernet.ktx.mkPort
import io.nekohasekai.sagernet.ktx.runOnMainDispatcher
import io.nekohasekai.sagernet.utils.PackageCache
import io.nekohasekai.sagernet.route.AppRouteIdentity
import io.nekohasekai.sagernet.route.InputMethodRouteMigration
import io.nekohasekai.sagernet.route.ROOT_APP_UID_DOMAIN_OUTBOUND_ROUTE_MARKER
import io.nekohasekai.sagernet.route.rootAppUIDDomainOutboundRouteInput
import io.nekohasekai.sagernet.route.ROOT_APP_UID_RULESET_OUTBOUND_MARKER
import io.nekohasekai.sagernet.route.rootAppUIDRuleSetOutboundInput
import io.nekohasekai.sagernet.route.ROOT_APP_UID_DOMAIN_RULESET_OUTBOUND_MARKER
import io.nekohasekai.sagernet.route.rootAppUIDDomainRuleSetOutboundInput
import moe.matsuri.nb4a.*
import moe.matsuri.nb4a.SingBoxOptions.*
import moe.matsuri.nb4a.plugin.Plugins
import moe.matsuri.nb4a.proxy.anytls.AnyTLSBean
import moe.matsuri.nb4a.proxy.anytls.buildSingBoxOutboundAnyTLSBean
import moe.matsuri.nb4a.proxy.config.ConfigBean
import moe.matsuri.nb4a.proxy.shadowtls.ShadowTLSBean
import moe.matsuri.nb4a.proxy.shadowtls.buildSingBoxOutboundShadowTLSBean
import moe.matsuri.nb4a.utils.JavaUtil.gson
import moe.matsuri.nb4a.utils.Util
import moe.matsuri.nb4a.utils.listByLineOrComma
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
// @author 雾晚
import io.nekohasekai.sagernet.route.RouteRuleEditor
import io.nekohasekai.sagernet.route.ROOT_IP_REJECT_ROUTE_MARKER
import io.nekohasekai.sagernet.route.rootIPRejectRouteInput
import io.nekohasekai.sagernet.route.RouteRulePriority
import io.nekohasekai.sagernet.route.ROOT_APP_UID_ROUTE_MARKER
import io.nekohasekai.sagernet.route.ROOT_APP_IDENTITY_DNS_RULE_MARKER
import io.nekohasekai.sagernet.route.rootAppIdentityDNSRuleInput
import io.nekohasekai.sagernet.route.ROOT_PORT_REJECT_ROUTE_MARKER
import io.nekohasekai.sagernet.route.ROOT_NETWORK_REJECT_ROUTE_MARKER
import io.nekohasekai.sagernet.route.rootNetworkRejectRouteInput
import io.nekohasekai.sagernet.route.ROOT_NETWORK_PORT_REJECT_ROUTE_MARKER
import io.nekohasekai.sagernet.route.rootNetworkPortRejectRouteInput
import io.nekohasekai.sagernet.route.rootPortRejectRouteInput
import io.nekohasekai.sagernet.route.ROOT_SOURCE_PORT_REJECT_ROUTE_MARKER
import io.nekohasekai.sagernet.route.rootSourcePortRejectRouteInput
import io.nekohasekai.sagernet.route.ROOT_PROTOCOL_REJECT_ROUTE_MARKER
import io.nekohasekai.sagernet.route.rootProtocolRejectRouteInput
import io.nekohasekai.sagernet.route.ROOT_SOURCE_IP_REJECT_ROUTE_MARKER
import io.nekohasekai.sagernet.route.rootSourceIPRejectRouteInput
import io.nekohasekai.sagernet.route.ROOT_SOURCE_DESTINATION_IP_REJECT_ROUTE_MARKER
import io.nekohasekai.sagernet.route.rootSourceDestinationIPRejectRouteInput
import io.nekohasekai.sagernet.route.ROOT_DOMAIN_REJECT_ROUTE_MARKER
import io.nekohasekai.sagernet.route.rootDomainRejectRouteInput
import io.nekohasekai.sagernet.route.ROOT_DOMAIN_SUFFIX_REJECT_ROUTE_MARKER
import io.nekohasekai.sagernet.route.rootDomainSuffixRejectRouteInput
import io.nekohasekai.sagernet.route.ROOT_DOMAIN_KEYWORD_REJECT_ROUTE_MARKER
import io.nekohasekai.sagernet.route.ROOT_IP_OUTBOUND_ROUTE_MARKER
import io.nekohasekai.sagernet.route.rootDomainKeywordRejectRouteInput

const val TAG_MIXED = "mixed-in"

const val TAG_PROXY = "proxy"
const val TAG_DIRECT = "direct"
const val TAG_BYPASS = "bypass"
const val TAG_BLOCK = "block"
const val TAG_FRAGMENT = "fragment"
const val TAG_DNS_HOSTS = "dns-hosts"

const val LOCALHOST = "127.0.0.1"

// Only types backed by the target sing-box endpoint registry belong here. Keeping this
// whitelist explicit prevents a user-supplied legacy/custom outbound from being silently
// reinterpreted as an endpoint.
private val ENDPOINT_TYPES = setOf("wireguard")

private fun SingBoxOption.isGeneratedEndpoint(): Boolean {
    return this is Endpoint && type in ENDPOINT_TYPES
}

internal fun SingBoxOption.detourTo(nextTag: String) {
    if (this is Endpoint_WireGuardOptions) {
        val effectiveOptions = asMap()
        val listenPort = when (val value = effectiveOptions["listen_port"]) {
            is Number -> value.toInt()
            else -> value?.toString()?.toIntOrNull() ?: 0
        }
        if (listenPort > 0) {
            val endpointTag = effectiveOptions["tag"]?.toString()?.takeIf { it.isNotBlank() }
                ?: "<untagged>"
            throw IllegalArgumentException(
                "WireGuard endpoint '$endpointTag' cannot detour to '$nextTag' while " +
                    "listen_port is enabled; set listen_port to 0 or use WireGuard only " +
                    "in a chain position that does not require another hop."
            )
        }

        // sing-box exposes endpoints through OutboundManager, so selectors, URL tests and
        // other dialers can reference this tag directly. WireGuard also embeds DialerOptions,
        // allowing its own outbound connection to follow the existing T4A chain direction.
        detour = nextTag
        return
    }

    _hack_config_map["detour"] = nextTag
}

internal data class ChainHopTag(
    val tag: String,
    val reused: Boolean,
)

internal fun resolveChainHopTag(
    profileId: Long,
    proposedTag: String,
    needGlobal: Boolean,
    globalOutbounds: MutableMap<Long, String>,
): ChainHopTag {
    if (!needGlobal) return ChainHopTag(proposedTag, reused = false)

    val existingTag = globalOutbounds[profileId]
    if (existingTag != null) return ChainHopTag(existingTag, reused = true)

    globalOutbounds[profileId] = proposedTag
    return ChainHopTag(proposedTag, reused = false)
}

internal fun RouteOptions.ensureMainRouteFinal(mainProxyTag: String) {
    if (final_.isNullOrBlank()) final_ = mainProxyTag
}

internal fun buildSelectorOutbound(defaultTag: String?, memberTags: List<String>, customTag: String? = null) =
    Outbound_SelectorOptions().apply {
        type = "selector"
        tag = customTag?.takeIf { it.isNotBlank() } ?: TAG_PROXY
        default_ = defaultTag
        // Endpoint tags are valid outbound references in sing-box 1.13; keep them as direct
        // group members instead of wrapping WireGuard in a removed outbound.
        outbounds = memberTags
    }

internal fun buildLoadBalanceOutbound(
    memberTags: List<String>,
    strategy: String? = null,
    testUrl: String? = null,
    intervalSec: Long? = null,
    toleranceMs: Int? = null,
    idleTimeoutStr: String? = null,
    interruptExist: Boolean? = null,
    customTag: String? = null
) =
    Outbound_SelectorOptions().apply {
        type = "loadbalance"
        tag = customTag?.takeIf { it.isNotBlank() } ?: TAG_PROXY
        outbounds = memberTags
        this.strategy = strategy ?: "round_robin"
        url = testUrl?.takeIf { it.isNotBlank() }
            ?: runCatching { DataStore.connectionTestURL }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: "https://www.gstatic.com/generate_204"
        val iv = (intervalSec?.takeIf { it > 0 } ?: 300L).coerceAtLeast(10L)
        interval = "${iv}s"
        tolerance = toleranceMs?.takeIf { it >= 0 } ?: 300
        idle_timeout = idleTimeoutStr?.takeIf { it.isNotBlank() } ?: "${iv}s"
        interrupt_exist_connections = interruptExist ?: false
    }

internal fun buildUrlTestOutbound(
    memberTags: List<String>,
    testUrl: String? = null,
    intervalSec: Long? = null,
    toleranceMs: Int? = null,
    idleTimeoutStr: String? = null,
    interruptExist: Boolean? = null,
    customTag: String? = null
) =
    Outbound_URLTestOptions().apply {
        type = "urltest"
        tag = customTag?.takeIf { it.isNotBlank() } ?: TAG_PROXY
        outbounds = memberTags
        url = testUrl?.takeIf { it.isNotBlank() }
            ?: "http://connectivitycheck.gstatic.com/generate_204"
        val iv = (intervalSec?.takeIf { it > 0 } ?: 600L).coerceAtLeast(10L)
        interval = "${iv}s"
        // @author 雾晚: saved settings override the quieter defaults for new groups.
        tolerance = toleranceMs?.takeIf { it >= 0 } ?: 100
        // Enforce minimum 10-minute idle_timeout regardless of memory mode.
        // This guarantees Telegram file uploads (which can take many minutes) are never
        // interrupted by the kernel reclaiming an "idle" leastPing connection pool.
        // DO NOT lower this threshold for any memory optimization — violates stability contract.
        val rawIdleTimeout = idleTimeoutStr?.takeIf { it.isNotBlank() }
        if (rawIdleTimeout != null) {
            val parsed = if (rawIdleTimeout.all { c -> c.isDigit() }) "${rawIdleTimeout}s" else rawIdleTimeout
            // Parse minutes from string like "3m", "5m", "10m", "30m"; enforce minimum 30m
            val minSecs = 30 * 60
            val parsedSecs = when {
                parsed.endsWith("m") -> (parsed.dropLast(1).toLongOrNull() ?: 0L) * 60
                parsed.endsWith("s") -> parsed.dropLast(1).toLongOrNull() ?: 0L
                else -> 0L
            }
            idle_timeout = if (parsedSecs < minSecs) "30m" else parsed
        } else {
            idle_timeout = "30m"
        }
        // CRITICAL: Never interrupt active connections on node switch.
        // interrupt_exist_connections=true would instantly kill Telegram upload streams on any
        // 5ms latency fluctuation. This must always be false — it is NOT overridden by memory mode.
        interrupt_exist_connections = false
    }

private fun endpointTag(value: Any?): String? {
    return (value as? Map<*, *>)?.get("tag")?.toString()?.takeIf { it.isNotBlank() }
}

private fun mergeEndpointList(
    existing: List<*>, incoming: List<*>, prependNew: Boolean = false
): MutableList<Any?> {
    val result = existing.toMutableList()
    val additions = mutableListOf<Any?>()

    incoming.forEach { endpoint ->
        val tag = endpointTag(endpoint)
        val existingIndex = tag?.let { candidate ->
            result.indexOfFirst { endpointTag(it) == candidate }
        } ?: -1
        val additionIndex = tag?.let { candidate ->
            additions.indexOfFirst { endpointTag(it) == candidate }
        } ?: -1

        when {
            existingIndex >= 0 -> result[existingIndex] = endpoint
            additionIndex >= 0 -> additions[additionIndex] = endpoint
            else -> additions.add(endpoint)
        }
    }

    if (prependNew) result.addAll(0, additions) else result.addAll(additions)
    return result
}

@Suppress("UNCHECKED_CAST")
private fun mergeRootConfig(dst: MutableMap<String, Any?>, json: String) {
    if (json.isBlank()) return
    val source = gson.fromJson(json, dst.javaClass) as? Map<String, Any?> ?: return
    val remaining = source.toMutableMap()

    // Root custom config precedence is automatic < global < selected profile. For endpoints,
    // a later non-empty tag replaces the earlier object in place; distinct/untagged objects
    // coexist. The existing +key/key+ list extension syntax remains prepend/append respectively.
    val replacement = remaining.remove("endpoints")
    val prepended = remaining.remove("+endpoints")
    val appended = remaining.remove("endpoints+")
    Util.mergeMap(dst, remaining)

    fun merge(value: Any?, prependNew: Boolean = false) {
        if (value !is List<*>) {
            if (value != null) dst["endpoints"] = value
            return
        }
        val current = dst["endpoints"] as? List<*> ?: emptyList<Any?>()
        dst["endpoints"] = mergeEndpointList(current, value, prependNew)
    }

    merge(replacement)
    merge(prepended, prependNew = true)
    merge(appended)
}

internal fun finalizeRootConfig(
    options: MyOptions,
    globalCustomConfig: String = "",
    profileCustomConfig: String = "",
): MutableMap<String, Any?> {
    val generatedEndpoints = options.outbounds.orEmpty().filter { it.isGeneratedEndpoint() }
    generatedEndpoints
        .filterIsInstance<Endpoint_WireGuardOptions>()
        .filter { endpoint -> endpoint.asMap()["detour"]?.toString().isNullOrBlank() }
        .forEach { endpoint -> endpoint.detourTo(TAG_DIRECT) }
    options.endpoints = options.endpoints.orEmpty() + generatedEndpoints.map { it as Endpoint }
    options.outbounds = options.outbounds.orEmpty().filterNot { it.isGeneratedEndpoint() }

    val configMap = options.asMap()
    mergeRootConfig(configMap, globalCustomConfig)
    mergeRootConfig(configMap, profileCustomConfig)
    return configMap
}

class ConfigBuildResult(
    var config: String,
    var externalIndex: List<IndexEntity>,
    var mainEntId: Long,
    var trafficMap: Map<String, List<ProxyEntity>>,
    var profileTagMap: Map<Long, String>,
    val selectorGroupId: Long,
    val balancerMemberMap: Map<Long, List<Long>> = emptyMap(),
) {
    data class IndexEntity(var chain: LinkedHashMap<Int, ProxyEntity>)
}

private fun sanitizeDnsEntry(value: String): String {
    return value.filterNot { it.isISOControl() }.trim()
}

private fun parseDnsHosts(value: String): Map<String, List<String>> {
    val hosts = linkedMapOf<String, MutableList<String>>()
    value.lineSequence().forEach { line ->
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("#")) return@forEach
        val tokens = trimmed.split("\\s+".toRegex())
        if (tokens.size < 2) return@forEach
        val domain = tokens.first()
        val addresses = tokens.drop(1).filter { it.isIpAddress() }
        if (addresses.isEmpty()) return@forEach
        hosts.getOrPut(domain) { mutableListOf() }.addAll(addresses)
    }
    return hosts.mapValues { (_, addresses) -> addresses.distinct() }
}

private fun serverHostOf(bean: AbstractBean): String? {
    val fallback = bean.serverAddress?.takeIf { it.isNotBlank() }
    if (bean is ConfigBean) {
        return try {
            val map = gson.fromJson(bean.config, mutableMapOf<String, Any>().javaClass)
            map["server"]?.toString()?.takeIf { it.isNotBlank() } ?: fallback
        } catch (_: Exception) {
            fallback
        }
    }
    return fallback
}

fun clashModeStringOf(dashboardMode: Int): String = when (dashboardMode) {
    1 -> "global"
    2 -> "direct"
    else -> "rule"
}

enum class RootInboundBackend { EBPF, TUN }

fun buildConfig(
    proxy: ProxyEntity, forTest: Boolean = false, forExport: Boolean = false,
    rootInboundBackend: RootInboundBackend = RootInboundBackend.TUN
): ConfigBuildResult {
    // @author 雾晚: persist preview.3's choice as an ordinary routing rule before reading rules.
    if (!forTest && !forExport) InputMethodRouteMigration.migrate()

    if (proxy.type == TYPE_CONFIG) {
        val bean = proxy.requireBean() as ConfigBean
        if (bean.type == 0) {
            val tagProxy = proxy.displayName()
            return ConfigBuildResult(
                bean.config,
                listOf(),
                proxy.id, //
                mapOf(tagProxy to listOf(proxy)), //
                mapOf(proxy.id to tagProxy), //
                -1L
            )
        }
    }

    val trafficMap = HashMap<String, List<ProxyEntity>>()
    val tagMap = HashMap<Long, String>()
    val balancerMemberMap = HashMap<Long, List<Long>>()
    val globalOutbounds = HashMap<Long, String>()
    val readableNames = mutableSetOf(TAG_DIRECT, TAG_BYPASS, TAG_BLOCK, TAG_FRAGMENT, TAG_MIXED, TAG_PROXY)
    val group = SagerDatabase.groupDao.getById(proxy.groupId)
    val groupTag = group?.name?.trim()?.takeIf { it.isNotBlank() } ?: TAG_PROXY
    readableNames.add(groupTag)

    fun ProxyEntity.resolveChainInternal(): MutableList<ProxyEntity> {
        val bean = requireBean()
        if (bean is ChainBean) {
            val beans = SagerDatabase.proxyDao.getEntities(bean.proxies)
            val beansMap = beans.associateBy { it.id }
            val beanList = ArrayList<ProxyEntity>()
            for (proxyId in bean.proxies) {
                val item = beansMap[proxyId] ?: continue
                beanList.addAll(item.resolveChainInternal())
            }
            return beanList.asReversed()
        }
        return mutableListOf(this)
    }

    fun readableTag(name_: String): String {
        var name = name_
        var count = 0
        while (!readableNames.add(name)) {
            count++
            name = "$name_-$count"
        }
        return name
    }

    fun ProxyEntity.resolveChain(useGroupFront: Boolean = true, useGroupLanding: Boolean = true): MutableList<ProxyEntity> {
        val thisGroup = SagerDatabase.groupDao.getById(groupId)
        val frontProxy = if (useGroupFront) thisGroup?.frontProxy?.let { SagerDatabase.proxyDao.getById(it) } else null
        val landingProxy = if (useGroupLanding) thisGroup?.landingProxy?.let { SagerDatabase.proxyDao.getById(it) } else null
        val list = resolveChainInternal()
        if (frontProxy != null) {
            list.add(frontProxy)
        }
        if (landingProxy != null) {
            list.add(0, landingProxy)
        }
        return list
    }

    val extraRules = if (forTest) listOf() else RouteRulePriority.order(SagerDatabase.rulesDao.enabledRules())
    val extraProxies =
        if (forTest) mapOf() else SagerDatabase.proxyDao.getEntities(extraRules.mapNotNull { rule ->
            rule.outbound.takeIf { it > 0 && it != proxy.id }
        }.toHashSet().toList()).associateBy { it.id }
    // @author 雾晚: hot-switch: module configs always carry a top-level selector
    // (TAG_PROXY) over the group's nodes, so switching nodes only moves the
    // selector via the runtime Clash API instead of restarting the core.
    // forTest/forExport keep the previous behavior.
    val buildSelector = !forTest && !forExport
    val isGroupUrlTest = group?.let { DataStore.isGroupUrlTest(it.id) } == true
    val isGroupLoadBalance = group?.let { DataStore.isGroupLoadBalance(it.id) } == true
    val useAutoSelect = !forTest && !forExport && isGroupUrlTest
    val useLoadBalance = !forTest && !forExport && isGroupLoadBalance
    val userDNSRuleList = mutableListOf<DNSRule_DefaultOptions>()
    val domainListDNSDirectForce = mutableListOf<String>()
    val bypassDNSBeans = hashSetOf<AbstractBean>()
    val perGroupResolver = HashMap<Long, String>()
    val perGroupServerHosts = HashMap<Long, MutableSet<String>>()
    val hostResolvers = HashMap<String, MutableSet<String>>()
    val nonCustomFinalHosts = hashSetOf<String>()
    val groupCache = HashMap<Long, ProxyGroup?>()
    val isRootTun = !forTest
    // The managed root-module runtime uses the fork's eBPF inbound. Keep the old
    // TUN builder as an explicit safe fallback and for exported/test configs.
    val useEbpfRootInbound = !forTest && !forExport && rootInboundBackend == RootInboundBackend.EBPF
    val useTun = !forTest && !useEbpfRootInbound
    val bind = if (!forTest && DataStore.allowAccess) "0.0.0.0" else LOCALHOST
    val remoteDns = DataStore.remoteDns.split("\n")
        .mapNotNull { dns -> dns.trim().takeIf { it.isNotBlank() && !it.startsWith("#") } }
    val directDNS = DataStore.directDns.split("\n")
        .mapNotNull { dns -> dns.trim().takeIf { it.isNotBlank() && !it.startsWith("#") } }
    val dnsHosts by lazy { parseDnsHosts(DataStore.dnsHosts) }
    val enableDnsRouting = DataStore.enableDnsRouting
    val useFakeDns = DataStore.enableFakeDns && !forTest
    // sing-box 1.13 已移除 sniff_override_destination（sniff 规则动作不再覆盖目标地址），
    // trafficSniffing 退化为开关语义（>0 即启用）。
    val needSniff = DataStore.trafficSniffing > 0
    val externalIndexMap = ArrayList<IndexEntity>()
    // 测速配置必须与正式连接一致（对齐 husi）：沿用用户的 IPv6 模式。
    // 曾强制 ENABLE——测速拨号的协议族选择与真实路径不同，
    // v6 不通的网络里测速假 err（节点实际可用），反之假成功。
    val ipv6Mode = DataStore.ipv6Mode

    fun genDomainStrategy(noAsIs: Boolean): String {
        return when {
            !noAsIs -> ""
            ipv6Mode == IPv6Mode.DISABLE -> "ipv4_only"
            ipv6Mode == IPv6Mode.PREFER -> "prefer_ipv6"
            ipv6Mode == IPv6Mode.ONLY -> "ipv6_only"
            else -> "prefer_ipv4"
        }
    }

    // 旧 fork 的 "hosts" DNS 地址 = 系统解析器；官方内核无此 scheme，对应 "local"
    // （官方 legacy 升级会把裸 "hosts" 误判为 UDP 服务器域名，静默失败）。
    // 仅用于 dns-direct / 订阅 resolver 等"本就应本机直解"的场景，远程 DNS 禁止走此函数。
    fun normalizeDnsAddress(address: String): String = if (address == "hosts") "local" else address

    // 远程 DNS 必须由节点代访问（配合下方 detour=当前节点），绝不能归一化为本机直解的 local：
    // 官方内核下 local/hosts/fakeip 都是本机解析占位符（Android 上 local 走平台接口经物理网卡
    // 直连系统 DNS），用作远程即 DNS 泄露，与 fork 时代 hosts 语义不对齐。
    // 统一回退为公共 DoH 并 Toast 提示用户修改设置。
    fun normalizeRemoteDnsAddress(address: String): String {
        return when (address) {
            "hosts", "local", "localhost", "fakeip" -> {
                runOnMainDispatcher {
                    Toast.makeText(
                        SagerNet.application,
                        "Warning: \"$address\" is not supported as remote DNS and has been replaced with https://8.8.8.8/dns-query. Please update your remote DNS setting.",
                        Toast.LENGTH_LONG
                    ).show()
                }
                "https://8.8.8.8/dns-query"
            }

            else -> address
        }
    }

    fun extractDnsTargets(dnsList: List<String>, isRemote: Boolean): Pair<Set<String>, Set<String>> {
        val domains = mutableSetOf<String>()
        val ips = mutableSetOf<String>()

        if (isRemote) {
            domains.addAll(
                listOf(
                    "dns.google",
                    "cloudflare-dns.com",
                    "one.one.one.one",
                    "dns.quad9.net",
                    "dns.opendns.com"
                )
            )
            ips.addAll(
                listOf(
                    "8.8.8.8", "8.8.4.4",
                    "1.1.1.1", "1.0.0.1",
                    "9.9.9.9", "149.112.112.112",
                    "208.67.222.222", "208.67.220.220",
                    "2001:4860:4860::8888", "2001:4860:4860::8844",
                    "2606:4700:4700::1111", "2606:4700:4700::1001",
                    "2620:fe::fe", "2620:fe::9",
                    "2620:119:35::35", "2620:119:53::53"
                )
            )
        } else {
            domains.addAll(
                listOf(
                    "dns.alidns.com",
                    "doh.pub",
                    "dot.pub",
                    "doh.360.cn",
                    "dot.360.cn"
                )
            )
            ips.addAll(
                listOf(
                    "223.5.5.5", "223.6.6.6",
                    "119.29.29.29", "1.12.12.12", "120.53.53.53",
                    "114.114.114.114", "114.114.115.115",
                    "180.76.76.76",
                    "1.2.4.8", "210.2.4.8",
                    "2400:3200::1", "2400:3200:baba::1"
                )
            )
        }

        dnsList.forEach { raw ->
            val trimmed = raw.trim()
            if (trimmed.isBlank() || trimmed.startsWith("#")) return@forEach
            if (trimmed == "local" || trimmed == "hosts" || trimmed == "fakeip") return@forEach

            val host: String? = if (trimmed.contains("://")) {
                val uri = runCatching { java.net.URI(trimmed) }.getOrNull()
                uri?.host ?: trimmed.substringAfter("://").substringBefore("/").substringBefore(":")
            } else {
                val rawNoPath = trimmed.substringBefore("/")
                if (rawNoPath.startsWith("[") && rawNoPath.contains("]")) {
                    rawNoPath.substringAfter("[").substringBefore("]")
                } else if (rawNoPath.contains(":") && rawNoPath.indexOf(":") == rawNoPath.lastIndexOf(":")) {
                    rawNoPath.substringBefore(":")
                } else {
                    rawNoPath
                }
            }

            val cleanHost = host?.trim()?.unwrapIPV6Host()?.lowercase()
            if (!cleanHost.isNullOrBlank()) {
                if (cleanHost.isIpAddress()) {
                    ips.add(cleanHost)
                } else {
                    domains.add(cleanHost)
                }
            }
        }

        val formattedCidrs = ips.mapNotNull { ip ->
            val clean = ip.trim().unwrapIPV6Host()
            if (ipv6Mode == IPv6Mode.DISABLE && clean.isIpAddressV6()) return@mapNotNull null
            if (ipv6Mode == IPv6Mode.ONLY && !clean.isIpAddressV6()) return@mapNotNull null
            if (clean.contains("/")) clean
            else if (clean.isIpAddressV6()) "$clean/128"
            else "$clean/32"
        }.toSet()

        return Pair(domains, formattedCidrs)
    }

    fun buildDnsServer(
        address: String,
        tag: String,
        detour: String? = null,
        domainResolver: String? = null,
        domainStrategy: String? = null
    ): DNSServerOptions {
        val trimmed = address.trim()
        if (trimmed == "local" || trimmed == "hosts") {
            return DNSServerOptions().apply {
                this.type = "local"
                this.tag = tag
                this.detour = detour
            }
        }
        if (trimmed.startsWith("https://", ignoreCase = true) || trimmed.startsWith("http://", ignoreCase = true)) {
            val uri = runCatching { java.net.URI(trimmed) }.getOrNull()
            val host = uri?.host ?: trimmed.removePrefix("https://").removePrefix("http://").substringBefore("/").substringBefore(":")
            val port = if (uri != null && uri.port != -1) uri.port else 443
            val path = if (uri != null && !uri.rawPath.isNullOrEmpty()) uri.rawPath else "/dns-query"
            return DNSServerOptions().apply {
                this.type = "https"
                this.tag = tag
                this.server = host
                this.server_port = port
                this.path = path
                this.detour = detour
                if (!host.isIpAddress()) {
                    this.domain_resolver = domainResolver
                    this.domain_strategy = domainStrategy
                }
            }
        }
        if (trimmed.startsWith("h3://", ignoreCase = true)) {
            val uri = runCatching { java.net.URI(trimmed) }.getOrNull()
            val host = uri?.host ?: trimmed.removePrefix("h3://").substringBefore("/").substringBefore(":")
            val port = if (uri != null && uri.port != -1) uri.port else 443
            val path = if (uri != null && !uri.rawPath.isNullOrEmpty()) uri.rawPath else "/dns-query"
            return DNSServerOptions().apply {
                this.type = "h3"
                this.tag = tag
                this.server = host
                this.server_port = port
                this.path = path
                this.detour = detour
                if (!host.isIpAddress()) {
                    this.domain_resolver = domainResolver
                    this.domain_strategy = domainStrategy
                }
            }
        }
        if (trimmed.startsWith("tls://", ignoreCase = true)) {
            val uri = runCatching { java.net.URI(trimmed) }.getOrNull()
            val host = uri?.host ?: trimmed.removePrefix("tls://").substringBefore("/").substringBefore(":")
            val port = if (uri != null && uri.port != -1) uri.port else 853
            return DNSServerOptions().apply {
                this.type = "tls"
                this.tag = tag
                this.server = host
                this.server_port = port
                this.detour = detour
                if (!host.isIpAddress()) {
                    this.domain_resolver = domainResolver
                    this.domain_strategy = domainStrategy
                }
            }
        }
        if (trimmed.startsWith("quic://", ignoreCase = true)) {
            val uri = runCatching { java.net.URI(trimmed) }.getOrNull()
            val host = uri?.host ?: trimmed.removePrefix("quic://").substringBefore("/").substringBefore(":")
            val port = if (uri != null && uri.port != -1) uri.port else 853
            return DNSServerOptions().apply {
                this.type = "quic"
                this.tag = tag
                this.server = host
                this.server_port = port
                this.detour = detour
                if (!host.isIpAddress()) {
                    this.domain_resolver = domainResolver
                    this.domain_strategy = domainStrategy
                }
            }
        }
        if (trimmed.startsWith("tcp://", ignoreCase = true)) {
            val uri = runCatching { java.net.URI(trimmed) }.getOrNull()
            val host = uri?.host ?: trimmed.removePrefix("tcp://").substringBefore("/").substringBefore(":")
            val port = if (uri != null && uri.port != -1) uri.port else 53
            return DNSServerOptions().apply {
                this.type = "tcp"
                this.tag = tag
                this.server = host
                this.server_port = port
                this.detour = detour
                if (!host.isIpAddress()) {
                    this.domain_resolver = domainResolver
                    this.domain_strategy = domainStrategy
                }
            }
        }
        val raw = if (trimmed.startsWith("udp://", ignoreCase = true)) trimmed.removePrefix("udp://") else trimmed
        val host: String
        val port: Int
        if (raw.startsWith("[") && raw.contains("]")) {
            host = raw.substringAfter("[").substringBefore("]")
            val after = raw.substringAfter("]")
            port = if (after.startsWith(":") && after.length > 1) after.substring(1).toIntOrNull() ?: 53 else 53
        } else if (raw.contains(":") && raw.indexOf(":") == raw.lastIndexOf(":")) {
            host = raw.substringBefore(":")
            port = raw.substringAfter(":").toIntOrNull() ?: 53
        } else {
            host = raw
            port = 53
        }
        return DNSServerOptions().apply {
            this.type = "udp"
            this.tag = tag
            this.server = host
            this.server_port = port
            this.detour = detour
            if (!host.isIpAddress()) {
                this.domain_resolver = domainResolver
                this.domain_strategy = domainStrategy
            }
        }
    }

    return MyOptions().apply {
        // forTest 不配 experimental：Go 侧 NewTestSingBoxInstance 不注册
        // PlatformLogWriter，官方内核据此不再强制创建 CacheFile/ClashServer
        // （官方 box.go 的 needCacheFile/needClashAPI 分支），测速完全不产生
        // cache.db——曾因此引发主进程并发共享 bbolt 文件损坏闪退，现从根上移除。
        if (!forTest) {
            experimental = ExperimentalOptions().apply {
                cache_file = CacheFile().apply {
                    enabled = true
                    path = "../cache/cache.db"
                    store_fakeip = true
                }

                // @author 雾晚: hot-switch: module configs (!forTest && !forExport) always
                // expose the Clash API on loopback so wanboxctl can move the top-level
                // selector at runtime (node select) without restarting the core.
                // The secret is stable (DataStore.clashApiSecret) so snapshot bytes stay
                // identical when nothing changed.
                if (!forExport || DataStore.enableClashAPI || DataStore.allowAccess) {
                    clash_api = ClashAPIOptions().apply {
                        external_controller = "127.0.0.1:9090"
                        secret = DataStore.clashApiSecret
                        external_ui = "../files/yacd"
                        if (!forExport) default_mode = clashModeStringOf(DataStore.dashboardMode)
                    }
                }
            }
        }

        log = LogOptions().apply {
            level = when (DataStore.logLevel) {
                0 -> "panic"
                1 -> "warn"
                2 -> "info"
                3 -> "debug"
                4 -> "trace"
                else -> "info"
            }
        }

        dns = DNSOptions().apply {
            servers = mutableListOf()
            rules = mutableListOf()
            independent_cache = true
        }

        fun autoDnsDomainStrategy(s: String, isProxied: Boolean = false): String? {
            if (ipv6Mode == IPv6Mode.DISABLE) {
                return "ipv4_only"
            }
            if (ipv6Mode == IPv6Mode.ONLY) {
                return "ipv6_only"
            }
            if (s.isNotEmpty()) {
                return s
            }
            return when (ipv6Mode) {
                IPv6Mode.DISABLE -> "ipv4_only"
                IPv6Mode.ENABLE -> "prefer_ipv4"
                IPv6Mode.PREFER -> if (isProxied) "prefer_ipv4" else "prefer_ipv6"
                IPv6Mode.ONLY -> "ipv6_only"
                else -> null
            }
        }

        inbounds = mutableListOf()

        if (!forTest) {
            if (useEbpfRootInbound) {
                val selectedUids = if (DataStore.proxyApps) {
                    PackageCache.awaitLoadSync()
                    DataStore.individual.lineSequence()
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                        .flatMap { PackageCache.uidsForPackage(it) }
                        .filter { it >= 1000 && it != SagerNet.application.applicationInfo.uid }
                        .distinct()
                        .toList()
                } else emptyList()
                val uidFilter = rootEbpfUidFilter(
                    proxyApps = DataStore.proxyApps,
                    bypass = DataStore.bypass,
                    selectedUids = selectedUids,
                    appUid = SagerNet.application.applicationInfo.uid,
                    rootUidPolicy = DataStore.rootUidPolicy,
                )
                inbounds.add(buildRootEbpfInbound(
                    includeUids = uidFilter.includeUids,
                    excludeUids = uidFilter.excludeUids,
                    ipv6 = ipv6Mode != IPv6Mode.DISABLE,
                    bypassPrivateAddress = DataStore.bypassLan,
                ))
            }
            if (useTun) inbounds.add(Inbound_TunOptions().apply {
                type = "tun"
                tag = "tun-in"
                interface_name = "tun0"
                                                /* preview.17: 仅使用 sing-box 原生默认 TUN 栈，不再输出 stack 字段（旧 gVisor/System/Mixed 模式已移除，DataStore 层已迁移为 SING_TUN） */ stack = null
                mtu = DataStore.mtu
                auto_route = true
                auto_redirect = if (isRootTun) true else null
                strict_route = DataStore.strictRoute
                // @author 雾晚: Root TUN has no VpnService.Builder app filter. Apply
                // the same selected-app UIDs here so system traffic is not captured
                // unexpectedly when the user selected an allowlist in VPN settings.
                if (isRootTun && DataStore.proxyApps) {
                    PackageCache.awaitLoadSync()
                    val selectedUids = DataStore.individual.lineSequence()
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                        // @author 雾晚: include secondary users' UIDs so the app filter
                        // covers cloned/dual apps as well.
                        .flatMap { PackageCache.uidsForPackage(it) }
                        .filter { it >= 1000 && it != SagerNet.application.applicationInfo.uid }
                        .distinct()
                        .toList()
                                        /* preview.17: Root 进程 (UID 0) 独立策略 */ val rootUidExtra = when (DataStore.rootUidPolicy) { 1 -> listOf(0) else -> emptyList() }; if (DataStore.bypass) { val excludeList = selectedUids.toMutableList(); if (DataStore.rootUidPolicy == 2) { excludeList.add(0) }; exclude_uid = excludeList.takeIf { it.isNotEmpty() } } else { val includeList = (selectedUids + SagerNet.application.applicationInfo.uid + rootUidExtra).distinct(); include_uid = includeList.takeIf { it.isNotEmpty() } }; /* Global Root TUN also needs to honor an explicit UID 0 bypass. */ if (isRootTun && !DataStore.proxyApps && DataStore.rootUidPolicy == 2) { exclude_uid = listOf(0) }
                }
                // sing-box 1.13 移除了入站 sniff/domain_strategy 字段，
                // 改由路由规则动作实现（见下方 route.rules 构建处）；
                // inet4_address/inet6_address 与 endpoint_independent_nat 已于 1.12 移除（构造函数硬报错），
                // address 为合并后的新字段。
                address = when (ipv6Mode) {
                    IPv6Mode.DISABLE -> listOf(TunAddresses.PRIVATE_VLAN4_CLIENT + "/30")
                    IPv6Mode.ONLY -> listOf(TunAddresses.PRIVATE_VLAN6_CLIENT + "/126")
                    else -> listOf(
                        TunAddresses.PRIVATE_VLAN4_CLIENT + "/30",
                        TunAddresses.PRIVATE_VLAN6_CLIENT + "/126"
                    )
                }
            })
            if (!DataStore.mixedInboundDisabled) inbounds.add(Inbound_MixedOptions().apply {
                type = "mixed"
                tag = TAG_MIXED
                listen = bind
                listen_port = DataStore.mixedPort
                if (DataStore.mixedInboundNeedsAuth) {
                    users = listOf(User().also { u ->
                        u.username = DataStore.mixedUsername
                        u.password = DataStore.mixedPassword
                    })
                }
            })
        }

        endpoints = mutableListOf()
        outbounds = mutableListOf()

        // init routing object
        route = RouteOptions().apply {
            auto_detect_interface = true
            override_android_vpn = false
            find_process = true
            rules = mutableListOf()
            rule_set = mutableListOf()

            // 默认域名解析器强制走直连物理 DNS，彻底消除出站节点服务器域名在内核启动与规则集拉取时的循环死锁
            default_domain_resolver = "dns-direct"

            // 双网络加速与并发拨号策略
            // hybrid: 在所有可用接口并发传输；fallback: 并发快速容灾拨号（Happy Eyeballs）
            if (DataStore.dualNetworkAcceleration) {
                default_network_strategy = "hybrid"
            } else if (DataStore.concurrentDial) {
                default_network_strategy = "fallback"
            }
        }

        // returns outbound tag
        @Suppress("UNCHECKED_CAST")
        fun buildChain(
            chainId: Long,
            entity: ProxyEntity,
            useGroupFront: Boolean = true,
            useGroupLanding: Boolean = true,
            externalDetour: String? = null
        ): String {
            if (entity.type == ProxyEntity.TYPE_BALANCER) {
                val balancerBean = entity.balancerBean ?: (entity.requireBean() as? BalancerBean) ?: BalancerBean()
                val balancerFrontEntity = if (balancerBean.frontProxy > 0L && balancerBean.frontProxy != entity.id) {
                    SagerDatabase.proxyDao.getById(balancerBean.frontProxy)
                } else null
                val balancerLandingEntity = if (balancerBean.landingProxy > 0L && balancerBean.landingProxy != entity.id) {
                    SagerDatabase.proxyDao.getById(balancerBean.landingProxy)
                } else null

                val balancerFrontTag = balancerFrontEntity?.let { front ->
                    tagMap[front.id] ?: buildChain(front.id, front).also { tagMap[front.id] = it }
                }

                val memberEntities = (if (balancerBean.balancerType == BalancerBean.TYPE_GROUP) {
                    val targetGids = when {
                        balancerBean.targetGroupIds.isNotEmpty() -> balancerBean.targetGroupIds
                        balancerBean.targetGroupId > 0L -> listOf(balancerBean.targetGroupId)
                        else -> emptyList()
                    }
                    var list = targetGids.flatMap { gid ->
                        SagerDatabase.proxyDao.getByGroup(gid)
                    }.distinctBy { it.id }

                    if (!balancerBean.nameInclude.isNullOrBlank()) {
                        val regex = runCatching { Regex(balancerBean.nameInclude) }.getOrNull()
                        if (regex != null) {
                            list = list.filter { regex.containsMatchIn(it.displayName()) }
                        }
                    }
                    if (!balancerBean.nameExclude.isNullOrBlank()) {
                        val regex = runCatching { Regex(balancerBean.nameExclude) }.getOrNull()
                        if (regex != null) {
                            list = list.filterNot { regex.containsMatchIn(it.displayName()) }
                        }
                    }
                    list
                } else {
                    val rawEntities = SagerDatabase.proxyDao.getEntities(balancerBean.proxies).associateBy { it.id }
                    balancerBean.proxies.mapNotNull { rawEntities[it] }
                }).filter {
                    it.id != entity.id &&
                    it.id != (balancerFrontEntity?.id ?: 0L) &&
                    it.id != (balancerLandingEntity?.id ?: 0L) &&
                    it.type != ProxyEntity.TYPE_BALANCER &&
                    !DataStore.isGroupDisabled(it.groupId)
                }

                val useFront = if (balancerBean.balancerType == BalancerBean.TYPE_GROUP) balancerBean.useFrontProxy else true
                val useLanding = if (balancerBean.balancerType == BalancerBean.TYPE_GROUP) balancerBean.useLandingProxy else true

                val memberEffectiveDetour = balancerFrontTag ?: externalDetour

                val memberTags = memberEntities.mapNotNull { member ->
                    val customKey = if (memberEffectiveDetour != null) {
                        -member.id
                    } else if (balancerBean.balancerType == BalancerBean.TYPE_GROUP && (!useFront || !useLanding)) {
                        -member.id
                    } else {
                        member.id
                    }
                    tagMap[customKey] ?: buildChain(
                        member.id,
                        member,
                        useGroupFront = if (memberEffectiveDetour != null) false else useFront,
                        useGroupLanding = useLanding,
                        externalDetour = memberEffectiveDetour
                    ).also { tagMap[customKey] = it }
                }.ifEmpty { listOf(TAG_DIRECT) }

                val balancerTag = readableTag(entity.displayName())

                val balancerOutbound: SingBoxOption = if (balancerBean.strategy == "leastPing") {
                    val iv = balancerBean.interval.toLong().coerceAtLeast(10L)
                    val toleranceMs = balancerBean.calculateToleranceMs().coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
                    buildUrlTestOutbound(
                        memberTags = memberTags,
                        testUrl = balancerBean.testUrl,
                        intervalSec = iv,
                        toleranceMs = toleranceMs,
                        idleTimeoutStr = "${iv}s",
                        interruptExist = false,
                        customTag = balancerTag
                    )
                } else {
                    val iv = balancerBean.interval.toLong().coerceAtLeast(10L)
                    val toleranceMs = balancerBean.calculateToleranceMs().coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
                    val strat = when (balancerBean.strategy) {
                        "failover" -> "failover"
                        "stable" -> "stable"
                        "consistent_hash", "consistentHash" -> "consistent_hash"
                        "leastLoad" -> "leastLoad"
                        "round_robin", "roundRobin" -> "round_robin"
                        "random" -> "random"
                        else -> balancerBean.strategy?.takeIf { it.isNotBlank() } ?: "round_robin"
                    }
                    buildLoadBalanceOutbound(
                        memberTags = memberTags,
                        strategy = strat,
                        testUrl = balancerBean.testUrl,
                        intervalSec = iv,
                        toleranceMs = toleranceMs,
                        idleTimeoutStr = "${iv}s",
                        interruptExist = false,
                        customTag = balancerTag
                    )
                }

                outbounds.add(balancerOutbound)
                trafficMap[balancerTag] = listOf(entity)
                balancerMemberMap[entity.id] = memberEntities.map { it.id }

                val finalBalancerTag = if (balancerLandingEntity != null) {
                    val landingTag = buildChain(
                        chainId = -balancerLandingEntity.id,
                        entity = balancerLandingEntity,
                        useGroupFront = false,
                        useGroupLanding = false,
                        externalDetour = balancerTag
                    )
                    landingTag
                } else {
                    balancerTag
                }

                return finalBalancerTag
            }

            val profileList = entity.resolveChain(useGroupFront, useGroupLanding)
            // profileList 的顺序即应用流量经过各 outbound 的顺序：前一跳通过
            // detour 交给后一跳拨号，最后一项直接连接物理网络。
            Logs.d(
                "Outbound chain id=$chainId forTest=$forTest appToEgress=" +
                    profileList.joinToString(" -> ") { hop ->
                        val hopBean = hop.requireBean()
                        val host = serverHostOf(hopBean) ?: "<unknown>"
                        val endpoint = hopBean.displayAddress().takeIf { it.isNotBlank() }
                            ?: "$host:${hopBean.serverPort}"
                        "${hop.id}:${hop.displayType()}@$endpoint"
                    }
            )
            val chainTrafficSet = HashSet<ProxyEntity>().apply {
                plusAssign(profileList)
                add(entity)
            }

            var currentOutbound: SingBoxOption
            lateinit var pastOutbound: SingBoxOption
            lateinit var pastInboundTag: String
            var pastEntity: ProxyEntity? = null
            val externalChainMap = LinkedHashMap<Int, ProxyEntity>()
            externalIndexMap.add(IndexEntity(externalChainMap))
            val chainOutbounds = ArrayList<SingBoxOption>()

            // chainTagOut: v2ray outbound tag for this chain
            var chainTagOut = ""
            val chainTag = if (chainId < 0) "c-m${-chainId}" else "c-$chainId"
            var muxApplied = false

            val defaultServerDomainStrategy = if (ipv6Mode == IPv6Mode.DISABLE) "ipv4_only" else if (ipv6Mode == IPv6Mode.ONLY) "ipv6_only" else SingBoxOptionsUtil.domainStrategy("server")

            profileList.forEachIndexed { index, proxyEntity ->
                val bean = proxyEntity.requireBean()

                // tagOut: v2ray outbound tag for a profile
                // profile2 (in) (global)   tag g-(id)
                // profile1                 tag (chainTag)-(id)
                // profile0 (out)           tag (chainTag)-(id) / single: "proxy"
                var tagOut = "$chainTag-${proxyEntity.id}"

                // needGlobal: can only contain one?
                var needGlobal = false

                // first profile set as global
                if (index == profileList.lastIndex) {
                    needGlobal = true
                    tagOut = "g-" + proxyEntity.id
                    bypassDNSBeans += proxyEntity.requireBean()

                    if (!forTest) {
                        val ownerGid = entity.groupId
                        val ownerGroup = groupCache.getOrPut(ownerGid) {
                            SagerDatabase.groupDao.getById(ownerGid)
                        }
                        val resolver = ownerGroup
                            ?.takeIf { it.type == GroupType.SUBSCRIPTION }
                            ?.subscription?.serverDnsResolver
                            ?.let { sanitizeDnsEntry(it) }
                            ?.takeIf { it.isNotBlank() }

                        if (resolver != null) {
                            profileList.forEach { hop ->
                                val host = serverHostOf(hop.requireBean())
                                if (host != null && !host.isIpAddress()) {
                                    if (hop.groupId == ownerGid) {
                                        perGroupResolver[ownerGid] = resolver
                                        perGroupServerHosts.getOrPut(ownerGid) { mutableSetOf() }
                                            .add(host)
                                        hostResolvers.getOrPut(host) { mutableSetOf() }.add(resolver)
                                    } else {
                                        nonCustomFinalHosts.add(host)
                                    }
                                }
                            }
                        } else {
                            profileList.forEach { hop ->
                                val host = serverHostOf(hop.requireBean())
                                if (host != null && !host.isIpAddress()) {
                                    nonCustomFinalHosts.add(host)
                                }
                            }
                        }
                    }
                }

                if (index == 0) {
                    tagOut = readableTag(bean.displayName())
                }

                // Resolve a globally shared hop before writing the edge that references it.
                // A hop built by an earlier rule may use a readable or endpoint tag instead of
                // this chain's proposed g-<id> tag; the global map is the source of truth.
                val resolvedTag = resolveChainHopTag(
                    proxyEntity.id,
                    tagOut,
                    needGlobal,
                    globalOutbounds,
                )
                tagOut = resolvedTag.tag

                // chain rules
                if (index > 0) {
                    // chain route/proxy rules
                    if (pastEntity!!.needExternal()) {
                        route.rules.add(Rule_DefaultOptions().apply {
                            inbound = listOf(pastInboundTag)
                            outbound = tagOut
                        })
                    } else {
                        pastOutbound.detourTo(tagOut)
                    }
                } else {
                    // index == 0 means last profile in chain / not chain
                    chainTagOut = tagOut
                }

                // The edge now points at the previously generated final tag, so the duplicate
                // object can be skipped without leaving a dangling detour.
                if (resolvedTag.reused) return@forEachIndexed

                if (proxyEntity.needExternal()) { // externel outbound
                    val localPort = mkPort()
                    externalChainMap[localPort] = proxyEntity
                    currentOutbound = Outbound_SocksOptions().apply {
                        type = "socks"
                        server = LOCALHOST
                        server_port = localPort
                    }
                } else {
                    // internal outbound

                    currentOutbound = when (bean) {
                        is ConfigBean -> CustomSingBoxOption(bean.config) as SingBoxOption

                        is ShadowTLSBean -> // before StandardV2RayBean
                            buildSingBoxOutboundShadowTLSBean(bean)

                        is StandardV2RayBean -> // http/trojan/vmess/vless
                            buildSingBoxOutboundStandardV2RayBean(bean)

                        is HysteriaBean ->
                            buildSingBoxOutboundHysteriaBean(bean)

                        is TuicBean ->
                            buildSingBoxOutboundTuicBean(bean)

                        is JuicityBean ->
                            buildSingBoxOutboundJuicityBean(bean)

                        is SOCKSBean ->
                            buildSingBoxOutboundSocksBean(bean)

                        is ShadowsocksBean ->
                            buildSingBoxOutboundShadowsocksBean(bean)

                        is ShadowsocksRBean ->
                            buildSingBoxOutboundShadowsocksRBean(bean)

                        is WireGuardBean -> {
                            val endpointMtu = bean.mtu?.takeIf { it > 0 }
                            val peerAddressFamily = when {
                                bean.serverAddress?.contains(':') == true -> "ipv6"
                                bean.serverAddress?.isNotBlank() == true -> "ipv4-or-domain"
                                else -> "missing"
                            }
                            Logs.i(
                                "WireGuardEndpointTrace profileId=${proxyEntity.id} forTest=$forTest " +
                                    "endpointMtu=${endpointMtu ?: "default(1408)"} " +
                                    "tunMtu=${DataStore.mtu} peerAddressFamily=$peerAddressFamily " +
                                    "peerPort=${bean.serverPort}"
                            )
                            buildSingBoxEndpointWireGuardBean(bean)
                        }

                        is SSHBean ->
                            buildSingBoxOutboundSSHBean(bean)

                        is AnyTLSBean ->
                            buildSingBoxOutboundAnyTLSBean(bean)

                        is SnellBean ->
                            buildSingBoxOutboundSnellBean(bean)

                        else -> throw IllegalStateException("can't reach")
                    }

                    // internal mux
                    if (!muxApplied) {
                        val muxObj = proxyEntity.singMux()
                        if (muxObj != null && muxObj.enabled) {
                            muxApplied = true
                            currentOutbound._hack_config_map["multiplex"] = muxObj.asMap()
                        }
                    }

                    if (needGlobal && DataStore.enableTLSFragment) {
                        val outboundMap = currentOutbound.asMap()
                        val tlsOptions = outboundMap["tls"] as? Map<*, *>
                        if (tlsOptions?.get("enabled") == true) {
                            val delay = DataStore.fragmentInterval.let {
                                val first = it.split("-").firstOrNull()?.trim()
                                val num = first?.toLongOrNull() ?: 20L
                                "${num}ms"
                            }
                            currentOutbound._hack_config_map["tls"] = mapOf(
                                "fragment" to true,
                                "record_fragment" to true,
                                "fragment_fallback_delay" to delay
                            )
                        }
                    }
                }

                // internal & external
                currentOutbound.apply {
                    // udp over tcp
                    try {
                        val sUoT = bean.javaClass.getField("sUoT").get(bean)
                        if (sUoT is Boolean && sUoT) {
                            _hack_config_map["udp_over_tcp"] = true
                        }
                    } catch (_: Exception) {
                    }

                    // domain_strategy
                    pastEntity?.requireBean()?.apply {
                        // don't loopback
                        if (defaultServerDomainStrategy != "" && !serverAddress.isIpAddress()) {
                            domainListDNSDirectForce.add("full:$serverAddress")
                        }
                    }
                    // 测速配置必须与正式连接一致（对齐 husi）：沿用统一的服务器
                    // 域名解析策略。曾强制空——测速解析出的 IP/协议族与真实路径不同。
                    _hack_config_map["domain_strategy"] = defaultServerDomainStrategy

                    _hack_config_map["tag"] = tagOut

                    _hack_custom_config = bean.customOutboundJson
                }

                // External proxy need a dokodemo-door inbound to forward the traffic
                // For external proxy software, their traffic must goes to v2ray-core to use protected fd.
                bean.finalAddress = bean.serverAddress
                bean.finalPort = bean.serverPort
                if (bean.canMapping() && proxyEntity.needExternal()) {
                    // With ss protect, don't use mapping
                    var needExternal = true
                    if (index == profileList.lastIndex) {
                        val pluginId = when (bean) {
                            is HysteriaBean -> if (bean.protocolVersion == 1) "hysteria-plugin" else "hysteria2-plugin"
                            else -> ""
                        }
                        if (Plugins.isUsingMatsuriExe(pluginId)) {
                            needExternal = false
                        } else if (Plugins.getPluginExternal(pluginId) != null) {
                            throw Exception("You are using an unsupported $pluginId, please download the correct plugin.")
                        }
                    }
                    if (needExternal) {
                        val mappingPort = mkPort()
                        bean.finalAddress = LOCALHOST
                        bean.finalPort = mappingPort

                        inbounds.add(Inbound_DirectOptions().apply {
                            type = "direct"
                            listen = LOCALHOST
                            listen_port = mappingPort
                            tag = "$chainTag-mapping-${proxyEntity.id}"

                            override_address = bean.serverAddress
                            override_port = bean.serverPort

                            pastInboundTag = tag

                            // no chain rule and not outbound, so need to set to direct
                            if (index == profileList.lastIndex) {
                                route.rules.add(Rule_DefaultOptions().apply {
                                    inbound = listOf(tag)
                                    outbound = TAG_DIRECT
                                })
                            }
                        })
                    }
                }

                outbounds.add(currentOutbound)
                chainOutbounds.add(currentOutbound)
                pastOutbound = currentOutbound
                pastEntity = proxyEntity
            }

            if (!externalDetour.isNullOrBlank() && chainOutbounds.isNotEmpty()) {
                chainOutbounds.last().detourTo(externalDetour)
            }

            trafficMap[chainTagOut] = chainTrafficSet.toList()
            return chainTagOut
        }

        // build outbounds
        if (buildSelector || useAutoSelect || useLoadBalance) {
            val list = if (group != null && group.id != 0L) {
                SagerDatabase.proxyDao.getByGroup(group.id)
            } else {
                val all = SagerDatabase.proxyDao.getAll()
                if (all.size > 1) all else listOf(proxy)
            }.filter { entity ->
                !DataStore.isGroupDisabled(entity.groupId)
            }.ifEmpty { listOf(proxy) }

            list.forEach {
                tagMap[it.id] = buildChain(it.id, it)
            }
            if (useAutoSelect && tagMap.isNotEmpty()) {
                val testUrl = group?.let { DataStore.groupUrlTestUrl(it.id) }
                val intervalVal = group?.let { DataStore.groupUrlTestInterval(it.id) }
                val toleranceVal = group?.let { DataStore.groupUrlTestTolerance(it.id) }
                val idleTimeoutVal = group?.let { DataStore.groupUrlTestIdleTimeout(it.id) }
                val interruptVal = group?.let { DataStore.groupUrlTestInterrupt(it.id) }
                outbounds.add(
                    0, buildUrlTestOutbound(
                        tagMap.values.toList(),
                        testUrl = testUrl,
                        intervalSec = intervalVal,
                        toleranceMs = toleranceVal,
                        idleTimeoutStr = idleTimeoutVal,
                        interruptExist = interruptVal,
                        customTag = TAG_PROXY
                    )
                )
            } else if (useLoadBalance && tagMap.isNotEmpty()) {
                outbounds.add(0, buildLoadBalanceOutbound(tagMap.values.toList(), customTag = TAG_PROXY))
                balancerMemberMap[proxy.id] = list.mapNotNull { it.id.takeIf { id -> id != proxy.id } }
            } else {
                outbounds.add(0, buildSelectorOutbound(tagMap[proxy.id], tagMap.values.toList(), customTag = TAG_PROXY))
            }
            trafficMap[TAG_PROXY] = listOf(proxy)
        } else {
            val mainTag = buildChain(0, proxy)
            tagMap[proxy.id] = mainTag
        }
        // build outbounds from route item
        extraProxies.forEach { (key, p) ->
            tagMap[key] = buildChain(key, p)
        }

        val mainProxyTag = (if (buildSelector || useAutoSelect || useLoadBalance) TAG_PROXY else tagMap[proxy.id]) ?: TAG_PROXY

        if (!forTest) {
            // 关键安全隔离：本地回环、多播与链路本地地址强制直连，
            // 彻底防止 TUN 内部回环（TX/RX 对称空耗 50kB/s、CPU 狂飙 340% 的根本原因）
            route.rules.add(Rule_DefaultOptions().apply {
                ip_cidr = listOf(
                    "127.0.0.0/8",
                    "::1/128",
                    "224.0.0.0/4",
                    "169.254.0.0/16",
                    "fe80::/10"
                )
                outbound = TAG_DIRECT
            })

            // 绕过内部网络（无论全局还是规则模式，只要开启绕过局域网均生效）
            if (DataStore.bypassLan) {
                route.rules.add(Rule_DefaultOptions().apply {
                    ip_cidr = listOf(
                        "10.0.0.0/8",
                        "172.16.0.0/12",
                        "192.168.0.0/16",
                        "fc00::/7"
                    )
                    outbound = TAG_DIRECT
                })
            }
        }

        // @author 雾晚: 仅 export 保留历史全局截断分支；runtime (!forExport) 走动态 clash_mode 规则
        if (!forTest && forExport && DataStore.globalMode) {
            // 全局模式下的规则处理
            route.rules.add(Rule_DefaultOptions().apply {
                inbound = listOf("tun-in")
                outbound = mainProxyTag
            })

            // 禁用混合入站时不生成入站系的规则
            if (!DataStore.mixedInboundDisabled) route.rules.add(Rule_DefaultOptions().apply {
                inbound = listOf(TAG_MIXED)
                outbound = mainProxyTag
            })

            route.final_ = mainProxyTag
        } else {
            // @author 雾晚: explicit user matches precede rule-set-only fallbacks.
            // Keep userOrder within each tier; custom JSON and scoped rule sets stay explicit.
            // Core DNS/loop protections stay ahead, App-owned fallbacks are appended below.
            // @author 雾晚：仪表盘运行模式（规则/全局/直连），通过 clash_mode 条件规则实现。
            // 置于 Core DNS/安全规则之后、普通用户路由之前，确保模式切换不覆盖必要规则。
            // 运行时通过 Clash API PATCH /configs 切换模式，无需重启核心。
            if (!forTest && !forExport) {
                // 全局模式：clash_mode=global 时全部走代理
                route.rules.add(Rule_DefaultOptions().apply {
                    clash_mode = "global"
                    outbound = TAG_PROXY
                })
                // 直连模式：clash_mode=direct 时全部直连
                route.rules.add(Rule_DefaultOptions().apply {
                    clash_mode = "direct"
                    outbound = "direct"
                })
                // 规则模式（默认）：不添加额外规则，按现有规则路由
            }
            // 应用用户规则
            var resolvedForGeoIp = false
            for (rule in extraRules) {
                // Schema v42 transfers only explicit keyword: reject rules from original raw text.
                val rootDomainKeywordRejectInput = if (!forTest && !forExport && isRootTun) rootDomainKeywordRejectRouteInput(rule) else null
                if (rootDomainKeywordRejectInput != null) {
                    route.rules.add(Rule_DefaultOptions().apply {
                        _hack_config_map[ROOT_DOMAIN_KEYWORD_REJECT_ROUTE_MARKER] = mapOf(
                            "ruleId" to rootDomainKeywordRejectInput.ruleId,
                            "domainText" to rootDomainKeywordRejectInput.domainText
                        )
                    })
                    continue
                }
                // Schema v41 transfers only explicit domain:-suffix reject rules from original raw text.
                val rootDomainSuffixRejectInput = if (!forTest && !forExport && isRootTun) rootDomainSuffixRejectRouteInput(rule) else null
                if (rootDomainSuffixRejectInput != null) {
                    route.rules.add(Rule_DefaultOptions().apply {
                        _hack_config_map[ROOT_DOMAIN_SUFFIX_REJECT_ROUTE_MARKER] = mapOf(
                            "ruleId" to rootDomainSuffixRejectInput.ruleId,
                            "domainText" to rootDomainSuffixRejectInput.domainText
                        )
                    })
                    continue
                }
                // Schema v40 transfers only exact full: domain reject rules; other forms stay legacy.
                val rootDomainRejectInput = if (!forTest && !forExport && isRootTun) rootDomainRejectRouteInput(rule) else null
                if (rootDomainRejectInput != null) {
                    route.rules.add(Rule_DefaultOptions().apply {
                        _hack_config_map[ROOT_DOMAIN_REJECT_ROUTE_MARKER] = mapOf(
                            "ruleId" to rootDomainRejectInput.ruleId,
                            "domainText" to rootDomainRejectInput.domainText
                        )
                    })
                    continue
                }
                // Schema v32 transfers an exact raw network+destination-port reject conjunction.
                val rootNetworkPortRejectInput = if (!forTest && !forExport && isRootTun) rootNetworkPortRejectRouteInput(rule) else null
                if (rootNetworkPortRejectInput != null) {
                    route.rules.add(Rule_DefaultOptions().apply {
                        _hack_config_map[ROOT_NETWORK_PORT_REJECT_ROUTE_MARKER] = mapOf(
                            "ruleId" to rootNetworkPortRejectInput.ruleId,
                            "networkText" to rootNetworkPortRejectInput.networkText,
                            "portText" to rootNetworkPortRejectInput.portText
                        )
                    })
                    continue
                }
                // Schema v31 transfers only an exact, network-only reject policy as raw typed input.
                // The module parses the original network text and creates the reject route in place.
                val rootNetworkRejectInput = if (!forTest && !forExport && isRootTun) {
                    rootNetworkRejectRouteInput(rule)
                } else null
                if (rootNetworkRejectInput != null) {
                    route.rules.add(Rule_DefaultOptions().apply {
                        _hack_config_map[ROOT_NETWORK_REJECT_ROUTE_MARKER] = mapOf(
                            "ruleId" to rootNetworkRejectInput.ruleId,
                            "networkText" to rootNetworkRejectInput.networkText
                        )
                    })
                    continue
                }
                // Schema v30 transfers only an exact, port-only reject policy as raw typed input.
                // Unsupported rules continue through the unchanged App-generated legacy path.
                val rootPortRejectInput = if (!forTest && !forExport && isRootTun) {
                    rootPortRejectRouteInput(rule)
                } else null
                if (rootPortRejectInput != null) {
                    route.rules.add(Rule_DefaultOptions().apply {
                        _hack_config_map[ROOT_PORT_REJECT_ROUTE_MARKER] = mapOf(
                            "ruleId" to rootPortRejectInput.ruleId,
                            "portText" to rootPortRejectInput.portText
                        )
                    })
                    continue
                }
                // Schema v39 transfers exact protocol AND destination-port reject rules as raw input.
                val rootProtocolPortRejectInput = if (!forTest && !forExport && isRootTun) io.nekohasekai.sagernet.route.rootProtocolPortRejectRouteInput(rule) else null
                if (rootProtocolPortRejectInput != null) {
                    route.rules.add(Rule_DefaultOptions().apply {
                        _hack_config_map[io.nekohasekai.sagernet.route.ROOT_PROTOCOL_PORT_REJECT_ROUTE_MARKER] = mapOf(
                            "ruleId" to rootProtocolPortRejectInput.ruleId,
                            "protocolText" to rootProtocolPortRejectInput.protocolText,
                            "portText" to rootProtocolPortRejectInput.portText
                        )
                    })
                    continue
                }
                // Schema v38 transfers exact source-port AND destination-port-only reject rules as raw input.
                val rootSourceDestinationPortRejectInput = if (!forTest && !forExport && isRootTun) io.nekohasekai.sagernet.route.rootSourceDestinationPortRejectRouteInput(rule) else null
                if (rootSourceDestinationPortRejectInput != null) {
                    route.rules.add(Rule_DefaultOptions().apply {
                        _hack_config_map[io.nekohasekai.sagernet.route.ROOT_SOURCE_DESTINATION_PORT_REJECT_ROUTE_MARKER] = mapOf(
                            "ruleId" to rootSourceDestinationPortRejectInput.ruleId,
                            "sourcePortText" to rootSourceDestinationPortRejectInput.sourcePortText,
                            "portText" to rootSourceDestinationPortRejectInput.portText
                        )
                    })
                    continue
                }
                // Schema v33 transfers exact source-port-only reject rules as raw typed input.
                // Ranges and any mixed/custom predicates stay on the unchanged App path.
                val rootSourcePortRejectInput = if (!forTest && !forExport && isRootTun) rootSourcePortRejectRouteInput(rule) else null
                if (rootSourcePortRejectInput != null) {
                    route.rules.add(Rule_DefaultOptions().apply {
                        _hack_config_map[ROOT_SOURCE_PORT_REJECT_ROUTE_MARKER] = mapOf(
                            "ruleId" to rootSourcePortRejectInput.ruleId,
                            "sourcePortText" to rootSourcePortRejectInput.sourcePortText
                        )
                    })
                    continue
                }
                // Schema v34 transfers exact protocol-only reject policies as raw input.
                // Any mixed matcher or custom action stays on the unchanged App path.
                val rootProtocolRejectInput = if (!forTest && !forExport && isRootTun) rootProtocolRejectRouteInput(rule) else null
                if (rootProtocolRejectInput != null) {
                    route.rules.add(Rule_DefaultOptions().apply {
                        _hack_config_map[ROOT_PROTOCOL_REJECT_ROUTE_MARKER] = mapOf(
                            "ruleId" to rootProtocolRejectInput.ruleId,
                            "protocolText" to rootProtocolRejectInput.protocolText
                        )
                    })
                    continue
                }
                // Schema v37 transfers only the unambiguous source-CIDR AND destination-CIDR reject conjunction.
                val rootSourceDestinationIPRejectInput = if (!forTest && !forExport && isRootTun) rootSourceDestinationIPRejectRouteInput(rule) else null
                if (rootSourceDestinationIPRejectInput != null) {
                    route.rules.add(Rule_DefaultOptions().apply {
                        _hack_config_map[ROOT_SOURCE_DESTINATION_IP_REJECT_ROUTE_MARKER] = mapOf(
                            "ruleId" to rootSourceDestinationIPRejectInput.ruleId,
                            "sourceText" to rootSourceDestinationIPRejectInput.sourceText,
                            "ipText" to rootSourceDestinationIPRejectInput.ipText
                        )
                    })
                    continue
                }
                // Schema v36 transfers exact destination-IP-CIDR-only reject rules as raw input.
                // The Root module parses the original CIDR text and materializes the rule in place.
                val rootIPRejectInput = if (!forTest && !forExport && isRootTun) rootIPRejectRouteInput(rule) else null
                if (rootIPRejectInput != null) {
                    route.rules.add(Rule_DefaultOptions().apply {
                        _hack_config_map[ROOT_IP_REJECT_ROUTE_MARKER] = mapOf(
                            "ruleId" to rootIPRejectInput.ruleId,
                            "ipText" to rootIPRejectInput.ipText
                        )
                    })
                    continue
                }
                // Schema v35 transfers exact source-IP-CIDR-only reject rules as raw input.
                // Mixed/custom predicates stay in the unchanged App-generated config path.
                val rootSourceIPRejectInput = if (!forTest && !forExport && isRootTun) rootSourceIPRejectRouteInput(rule) else null
                if (rootSourceIPRejectInput != null) {
                    route.rules.add(Rule_DefaultOptions().apply {
                        _hack_config_map[ROOT_SOURCE_IP_REJECT_ROUTE_MARKER] = mapOf(
                            "ruleId" to rootSourceIPRejectInput.ruleId,
                            "sourceText" to rootSourceIPRejectInput.sourceText
                        )
                    })
                    continue
                }
                // @author 雾晚: resolve only after explicit/domain rules had a chance to
                // match. Official alpha.10 has no fork-specific match_only option.
                // Normal DNS routing chooses the resolver; never force public/direct DNS.
                if (!resolvedForGeoIp && RouteRulePriority.needsIpResolution(rule)) {
                    // @author 雾晚: narrow the resolve scope: only direct/bypass/block-bound
                    // IP rules need the locally resolved address for the routing decision.
                    // Proxy-bound IP rules keep the domain intact so the proxy-side DNS
                    // resolves it; resolving via direct DNS first risks acting on poisoned
                    // answers in filtered environments.
                    if (rule.outbound == -1L || rule.outbound == -2L) {
                        route.rules.add(Rule_DefaultOptions().apply {
                            action = "resolve"
                            _hack_config_map["timeout"] = "3s"
                        })
                        resolvedForGeoIp = true
                    }
                }
                if (rule.packages.isNotEmpty()) {
                    PackageCache.awaitLoadSync()
                }
                val uidList = rule.packages.flatMap { pkg ->
                    if (!useTun) {
                        Toast.makeText(
                            SagerNet.application,
                            SagerNet.application.getString(R.string.route_need_vpn, rule.displayName()),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    // @author 雾晚: match the package on all users (cloned/dual apps).
                    PackageCache.uidsForPackage(pkg).filter { uid -> uid >= 1000 }
                }.toHashSet()
                val ruleSets = mutableListOf<RuleSet>()

                val domainList = if (rule.domains.isNotBlank()) rule.domains.listByLineOrComma() else null
                val ipList = if (rule.ip.isNotBlank()) rule.ip.listByLineOrComma() else null

                // 存储ruleset标签和类型信息: Pair(tag, isIPRuleset)
                val rulesetTags = mutableListOf<Pair<String, Boolean>>()
                if (rule.ruleset.isNotBlank()) {
                    val rulesetUrls = rule.ruleset.listByLineOrComma()
                    rulesetUrls.forEach { origUrl ->
                        val (url, isIPRuleset) = processRulesetUrl(origUrl)
                        val tag = generateRemoteRuleSet(url, ruleSets, DataStore.rulesUpdateInterval)
                        rulesetTags.add(Pair(tag, isIPRuleset))
                    }
                }

                val hasDomainCriteria = !domainList.isNullOrEmpty()
                val hasIpCriteria = !ipList.isNullOrEmpty() || rulesetTags.any { it.second }
                val hasDomainRuleset = rulesetTags.any { !it.second }
                val isAppOnlyDns =
                    (uidList.isNotEmpty() || rule.packages.isNotEmpty()) &&
                        !hasDomainCriteria &&
                        !hasIpCriteria &&
                        !hasDomainRuleset &&
                        rule.port.isBlank() &&
                        rule.sourcePort.isBlank() &&
                        rule.network.isBlank() &&
                        rule.source.isBlank() &&
                        rule.protocol.isBlank()
                // @author 雾晚: auxiliary route actions do not select a DNS outbound.
                val routingAction = RouteRuleEditor.action(rule.config, rule.outbound)
                // DNS queries cannot represent destination-IP/port or inverted mixed predicates.
                // Such extended rules use the existing DNS policy instead of a broader derived rule.
                val extendedMatch = RouteRuleEditor.json(rule.config)
                val scopedMatch = setOf("domain", "domain_suffix", "domain_keyword", "domain_regex", "inbound",
                    "ip_version", "ip_is_private", "source_ip_is_private", "invert", "port", "port_range",
                    "source_ip_cidr", "source_port", "source_port_range")
                val usesDnsOutbound = routingAction in setOf("route", "reject") && scopedMatch.none { extendedMatch.has(it) }
                val shouldAddDnsRule = usesDnsOutbound && (hasDomainCriteria || isAppOnlyDns)

                fun makeDnsRuleObj(): DNSRule_DefaultOptions {
                    return DNSRule_DefaultOptions().apply {
                        if (uidList.isNotEmpty()) user_id = uidList.toList()
                        if (rule.packages.isNotEmpty()) package_name = rule.packages.toList()
                        domainList?.let { makeSingBoxRule(it) }
                    }
                }

                val rootExactDomainDNSInput = if (!forTest && !forExport && isRootTun && enableDnsRouting) {
                    io.nekohasekai.sagernet.route.rootExactDomainDNSRuleInput(rule, routingAction, useFakeDns)
                } else null
                // Schema v79 lets Root generate the package-only DNS routing/reject/FakeDNS
                // side effect from the same Android-resolved identity inputs. The route rule
                // itself remains App-generated here; this replaces only its DNS rule slot.
                val rootAppIdentityDNSInput = if (!forTest && !forExport && isRootTun && enableDnsRouting && isAppOnlyDns) {
                    rootAppIdentityDNSRuleInput(rule, routingAction, uidList.toList(), useFakeDns)
                } else null
                // Schema v80 reconstructs the independent DNS side effect for the
                // supported package-identity AND domain subset. DNS selection does not
                // inherit route inversion; unsupported predicates remain App-generated.
                val rootAppIdentityDomainDNSInput = if (!forTest && !forExport && isRootTun && enableDnsRouting) {
                    io.nekohasekai.sagernet.route.rootAppIdentityDomainDNSRuleInput(rule, routingAction, uidList.toList(), useFakeDns)
                } else null
                val rootDnsRuleStart = userDNSRuleList.size
                when (if (routingAction == "reject") -2L else rule.outbound) {
                    -1L -> {
                        if (shouldAddDnsRule) {
                            userDNSRuleList += makeDnsRuleObj().apply { server = "dns-direct" }
                        }
                        for ((tag, isIP) in rulesetTags.filter { usesDnsOutbound }) {
                            if (!isIP) {
                                userDNSRuleList += DNSRule_DefaultOptions().apply {
                                    rule_set = mutableListOf(tag)
                                    server = "dns-direct"
                                }
                            }
                        }
                    }

                    -2L -> {
                        if (shouldAddDnsRule) {
                            userDNSRuleList += makeDnsRuleObj().apply { action = "reject" }
                        }
                        for ((tag, isIP) in rulesetTags.filter { usesDnsOutbound }) {
                            if (!isIP) {
                                userDNSRuleList += DNSRule_DefaultOptions().apply {
                                    rule_set = mutableListOf(tag)
                                    action = "reject"
                                }
                            }
                        }
                    }

                    else -> {
                        if (shouldAddDnsRule) {
                            if (useFakeDns) {
                                userDNSRuleList += makeDnsRuleObj().apply {
                                    server = "dns-fake"
                                    inbound = listOf("tun-in")
                                    query_type = listOf("A", "AAAA")
                                }
                            } else {
                                userDNSRuleList += makeDnsRuleObj().apply { server = "dns-remote" }
                            }
                        }
                        for ((tag, isIP) in rulesetTags.filter { usesDnsOutbound }) {
                            if (!isIP) {
                                if (useFakeDns) {
                                    userDNSRuleList += DNSRule_DefaultOptions().apply {
                                        rule_set = mutableListOf(tag)
                                        server = "dns-fake"
                                        inbound = listOf("tun-in")
                                        query_type = listOf("A", "AAAA")
                                    }
                                } else {
                                    userDNSRuleList += DNSRule_DefaultOptions().apply {
                                        rule_set = mutableListOf(tag)
                                        server = "dns-remote"
                                    }
                                }
                            }
                        }
                    }
                }
                // Replace the domain DNS side effect with typed input. When rulesets are also
                // present, ConfigBuilder appends their independent DNS effects after this domain
                // rule; Root projects those later ruleset effects separately, preserving order.
                if (rootExactDomainDNSInput != null && userDNSRuleList.size > rootDnsRuleStart) {
                    userDNSRuleList[rootDnsRuleStart] = DNSRule_DefaultOptions().apply {
                        _hack_config_map[io.nekohasekai.sagernet.route.ROOT_EXACT_DOMAIN_DNS_RULE_MARKER] = mapOf(
                            "ruleId" to rootExactDomainDNSInput.ruleId,
                            "domainText" to rootExactDomainDNSInput.domainText,
                            "kind" to rootExactDomainDNSInput.kind,
                            "fakeDns" to rootExactDomainDNSInput.fakeDns
                        )
                    }
                }
                if (rootAppIdentityDNSInput != null && userDNSRuleList.size > rootDnsRuleStart) {
                    userDNSRuleList[rootDnsRuleStart] = DNSRule_DefaultOptions().apply {
                        _hack_config_map[ROOT_APP_IDENTITY_DNS_RULE_MARKER] = mapOf(
                            "ruleId" to rootAppIdentityDNSInput.ruleId,
                            "packages" to rootAppIdentityDNSInput.packages,
                            "uids" to rootAppIdentityDNSInput.uids,
                            "kind" to rootAppIdentityDNSInput.kind,
                            "fakeDns" to rootAppIdentityDNSInput.fakeDns
                        )
                    }
                }
                if (rootAppIdentityDomainDNSInput != null && userDNSRuleList.size > rootDnsRuleStart) {
                    userDNSRuleList[rootDnsRuleStart] = DNSRule_DefaultOptions().apply {
                        _hack_config_map[io.nekohasekai.sagernet.route.ROOT_APP_IDENTITY_DOMAIN_DNS_RULE_MARKER] = mapOf(
                            "ruleId" to rootAppIdentityDomainDNSInput.ruleId,
                            "packages" to rootAppIdentityDomainDNSInput.packages,
                            "uids" to rootAppIdentityDomainDNSInput.uids,
                            "domainText" to rootAppIdentityDomainDNSInput.domainText,
                            "kind" to rootAppIdentityDomainDNSInput.kind,
                            "fakeDns" to rootAppIdentityDomainDNSInput.fakeDns
                        )
                    }
                }

                val targetOutbound = when (val outId = rule.outbound) {
                    0L -> mainProxyTag
                    -1L -> TAG_BYPASS
                    -2L -> TAG_BLOCK
                    else -> if (outId == proxy.id) mainProxyTag else tagMap[outId] ?: ""
                }

                // Schema v85 handles closed range-only outbound rules; open or richer rules stay in App.
                val rootPortRangeOutbound = if (!forTest && !forExport && isRootTun &&
                    routingAction == "route" && targetOutbound.isNotBlank() && targetOutbound != TAG_BLOCK) {
                    io.nekohasekai.sagernet.route.rootPortRangeOutboundRouteInput(rule, targetOutbound)
                } else null
                if (rootPortRangeOutbound != null) {
                    route.rules.add(Rule_DefaultOptions().apply {
                        _hack_config_map[io.nekohasekai.sagernet.route.ROOT_PORT_RANGE_OUTBOUND_ROUTE_MARKER] = mapOf(
                            "index" to route.rules.size,
                            "ruleId" to rootPortRangeOutbound.ruleId,
                            "ranges" to rootPortRangeOutbound.ranges,
                            "outbound" to rootPortRangeOutbound.outbound
                        )
                    })
                    continue
                }
                val rootSourcePortRangeOutbound = if (!forTest && !forExport && isRootTun &&
                    routingAction == "route" && targetOutbound.isNotBlank() && targetOutbound != TAG_BLOCK) {
                    io.nekohasekai.sagernet.route.rootSourcePortRangeOutboundRouteInput(rule, targetOutbound)
                } else null
                if (rootSourcePortRangeOutbound != null) {
                    route.rules.add(Rule_DefaultOptions().apply {
                        _hack_config_map[io.nekohasekai.sagernet.route.ROOT_SOURCE_PORT_RANGE_OUTBOUND_ROUTE_MARKER] = mapOf(
                            "index" to route.rules.size,
                            "ruleId" to rootSourcePortRangeOutbound.ruleId,
                            "ranges" to rootSourcePortRangeOutbound.ranges,
                            "outbound" to rootSourcePortRangeOutbound.outbound
                        )
                    })
                    continue
                }

                // Schema v82 generates numeric-port-only outbound routes in Root; richer
                // predicates and unsupported port syntax retain the App-generated path.
                val rootPortOutbound = if (!forTest && !forExport && isRootTun &&
                    routingAction == "route" && targetOutbound.isNotBlank() && targetOutbound != TAG_BLOCK) {
                    io.nekohasekai.sagernet.route.rootPortOutboundRouteInput(rule, targetOutbound)
                } else null
                if (rootPortOutbound != null) {
                    route.rules.add(Rule_DefaultOptions().apply {
                        _hack_config_map[io.nekohasekai.sagernet.route.ROOT_PORT_OUTBOUND_ROUTE_MARKER] = mapOf(
                            "index" to route.rules.size,
                            "ruleId" to rootPortOutbound.ruleId,
                            "portText" to rootPortOutbound.portText,
                            "outbound" to rootPortOutbound.outbound
                        )
                    })
                    continue
                }

                // Schema v63 generates source-port-only outbound routes in Root; all
                // domain/ruleset and richer route semantics retain the App-generated path.
                val rootSourcePortOutbound = if (!forTest && !forExport && isRootTun &&
                    routingAction == "route" && targetOutbound.isNotBlank() && targetOutbound != TAG_BLOCK) {
                    io.nekohasekai.sagernet.route.rootSourcePortOutboundRouteInput(rule, targetOutbound)
                } else null
                if (rootSourcePortOutbound != null) {
                    route.rules.add(Rule_DefaultOptions().apply {
                        _hack_config_map[io.nekohasekai.sagernet.route.ROOT_SOURCE_PORT_OUTBOUND_ROUTE_MARKER] = mapOf(
                            "index" to route.rules.size,
                            "ruleId" to rootSourcePortOutbound.ruleId,
                            "sourcePortText" to rootSourcePortOutbound.sourcePortText,
                            "outbound" to rootSourcePortOutbound.outbound
                        )
                    })
                    continue
                }

                // Schema v62 generates a standalone user IPv4/IPv6-version route in Root.
                // This predicate has no DNS side effects; richer/custom rules remain legacy.
                val rootIPVersionOutbound = if (!forTest && !forExport && isRootTun &&
                    routingAction == "route" && targetOutbound.isNotBlank() && targetOutbound != TAG_BLOCK) {
                    io.nekohasekai.sagernet.route.rootIPVersionOutboundRouteInput(rule, targetOutbound)
                } else null
                if (rootIPVersionOutbound != null) {
                    route.rules.add(Rule_DefaultOptions().apply {
                        _hack_config_map[io.nekohasekai.sagernet.route.ROOT_IP_VERSION_OUTBOUND_ROUTE_MARKER] = mapOf(
                            "index" to route.rules.size,
                            "ruleId" to rootIPVersionOutbound.ruleId,
                            "ipVersion" to rootIPVersionOutbound.ipVersion,
                            "outbound" to rootIPVersionOutbound.outbound
                        )
                    })
                    continue
                }

                // Schema v58 lets Root generate this exact domain AND network conjunction.
                // DNS side effects were emitted above and remain in their original slots.
                val rootDomainNetworkOutbound = if (!forTest && !forExport && isRootTun &&
                    routingAction == "route" && targetOutbound.isNotBlank() && targetOutbound != TAG_BLOCK) {
                    io.nekohasekai.sagernet.route.rootDomainNetworkOutboundRouteInput(rule, targetOutbound)
                } else null
                if (rootDomainNetworkOutbound != null) {
                    route.rules.add(Rule_DefaultOptions().apply {
                        _hack_config_map[io.nekohasekai.sagernet.route.ROOT_DOMAIN_NETWORK_OUTBOUND_ROUTE_MARKER] = mapOf(
                            "index" to route.rules.size,
                            "ruleId" to rootDomainNetworkOutbound.ruleId,
                            "domainText" to rootDomainNetworkOutbound.domainText,
                            "networkText" to rootDomainNetworkOutbound.networkText,
                            "outbound" to rootDomainNetworkOutbound.outbound,
                            "invert" to rootDomainNetworkOutbound.invert
                        )
                    })
                    continue
                }

                // Schema v59 lets Root generate a supported domain AND numeric-port route.
                // Domain DNS side effects above remain in their original DNS slots.
                val rootDomainPortOutbound = if (!forTest && !forExport && isRootTun &&
                    routingAction == "route" && targetOutbound.isNotBlank() && targetOutbound != TAG_BLOCK) {
                    io.nekohasekai.sagernet.route.rootDomainPortOutboundRouteInput(rule, targetOutbound)
                } else null
                if (rootDomainPortOutbound != null) {
                    route.rules.add(Rule_DefaultOptions().apply {
                        _hack_config_map[io.nekohasekai.sagernet.route.ROOT_DOMAIN_PORT_OUTBOUND_ROUTE_MARKER] = mapOf(
                            "index" to route.rules.size,
                            "ruleId" to rootDomainPortOutbound.ruleId,
                            "domainText" to rootDomainPortOutbound.domainText,
                            "portText" to rootDomainPortOutbound.portText,
                            "outbound" to rootDomainPortOutbound.outbound,
                            "invert" to rootDomainPortOutbound.invert
                        )
                    })
                    continue
                }

                // Schema v57 lets Root generate this exact network AND destination-port
                // conjunction. Unsupported predicates, port ranges and custom actions stay legacy.
                val rootNetworkPortOutbound = if (!forTest && !forExport && isRootTun &&
                    routingAction == "route" && targetOutbound.isNotBlank() && targetOutbound != TAG_BLOCK) {
                    io.nekohasekai.sagernet.route.rootNetworkPortOutboundRouteInput(rule, targetOutbound)
                } else null
                if (rootNetworkPortOutbound != null) {
                    route.rules.add(Rule_DefaultOptions().apply {
                        _hack_config_map[io.nekohasekai.sagernet.route.ROOT_NETWORK_PORT_OUTBOUND_ROUTE_MARKER] = mapOf(
                            "index" to route.rules.size,
                            "ruleId" to rootNetworkPortOutbound.ruleId,
                            "networkText" to rootNetworkPortOutbound.networkText,
                            "portText" to rootNetworkPortOutbound.portText,
                            "outbound" to rootNetworkPortOutbound.outbound
                        )
                    })
                    continue
                }

                // Schema v52 moves only IP-CIDR-only routing to Root; no DNS matcher
                // is generated for IP-only rules by ConfigBuilder, so this has no DNS side effect.
                val rootIPOutbound = if (!forTest && !forExport && isRootTun &&
                    routingAction == "route" && targetOutbound.isNotBlank() && targetOutbound != TAG_BLOCK) {
                    io.nekohasekai.sagernet.route.rootIPOutboundRouteInput(rule, targetOutbound)
                } else null
                if (rootIPOutbound != null) {
                    route.rules.add(Rule_DefaultOptions().apply {
                        _hack_config_map[ROOT_IP_OUTBOUND_ROUTE_MARKER] = mapOf(
                            "index" to route.rules.size,
                            "ruleId" to rootIPOutbound.ruleId,
                            "ipText" to rootIPOutbound.ipText,
                            "outbound" to rootIPOutbound.outbound,
                            "invert" to rootIPOutbound.invert
                        )
                    })
                    continue
                }

                // Schema v60 moves only source-CIDR AND destination-CIDR outbound rules to Root.
                // These rules have no DNS side effect; unsupported or richer matchers stay legacy.
                val rootSourceDestinationIPOutbound = if (!forTest && !forExport && isRootTun &&
                    routingAction == "route" && targetOutbound.isNotBlank() && targetOutbound != TAG_BLOCK) {
                    io.nekohasekai.sagernet.route.rootSourceDestinationIPOutboundRouteInput(rule, targetOutbound)
                } else null
                if (rootSourceDestinationIPOutbound != null) {
                    route.rules.add(Rule_DefaultOptions().apply {
                        _hack_config_map[io.nekohasekai.sagernet.route.ROOT_SOURCE_DESTINATION_IP_OUTBOUND_ROUTE_MARKER] = mapOf(
                            "index" to route.rules.size,
                            "ruleId" to rootSourceDestinationIPOutbound.ruleId,
                            "sourceText" to rootSourceDestinationIPOutbound.sourceText,
                            "ipText" to rootSourceDestinationIPOutbound.ipText,
                            "outbound" to rootSourceDestinationIPOutbound.outbound
                        )
                    })
                    continue
                }

                // Schema v61 moves source-CIDR-only outbound rules to Root. This is a
                // route-only predicate with no DNS matcher/effects; richer rules stay legacy.
                val rootSourceIPOutbound = if (!forTest && !forExport && isRootTun &&
                    routingAction == "route" && targetOutbound.isNotBlank() && targetOutbound != TAG_BLOCK) {
                    io.nekohasekai.sagernet.route.rootSourceIPOutboundRouteInput(rule, targetOutbound)
                } else null
                if (rootSourceIPOutbound != null) {
                    route.rules.add(Rule_DefaultOptions().apply {
                        _hack_config_map[io.nekohasekai.sagernet.route.ROOT_SOURCE_IP_OUTBOUND_ROUTE_MARKER] = mapOf(
                            "index" to route.rules.size,
                            "ruleId" to rootSourceIPOutbound.ruleId,
                            "sourceText" to rootSourceIPOutbound.sourceText,
                            "outbound" to rootSourceIPOutbound.outbound
                        )
                    })
                    continue
                }

                // Schema v50 lets Root generate a narrow exact-domain route. ConfigBuilder
                // has already emitted any matching DNS route/reject/FakeDNS side effect above;
                // leave those DNS rules untouched and replace only this route slot in Root.
                val rootExactDomainOutbound = if (!forTest && !forExport && isRootTun &&
                    routingAction == "route" && targetOutbound.isNotBlank() && targetOutbound != TAG_BLOCK) {
                    io.nekohasekai.sagernet.route.rootExactDomainOutboundRouteInput(rule, targetOutbound)
                } else null
                if (rootExactDomainOutbound != null) {
                    route.rules.add(Rule_DefaultOptions().apply {
                        _hack_config_map[io.nekohasekai.sagernet.route.ROOT_EXACT_DOMAIN_OUTBOUND_ROUTE_MARKER] = mapOf(
                            "index" to route.rules.size,
                            "ruleId" to rootExactDomainOutbound.ruleId,
                            "domainText" to rootExactDomainOutbound.domainText,
                            "outbound" to rootExactDomainOutbound.outbound
                        )
                    })
                    continue
                }

                // Schema v45 transfers exact package-identity-only routing to Root. The
                // App still resolves Android package UIDs; richer predicates/actions and
                // every DNS-routing case remain on the legacy generator path.
                val rootAppUIDOnlyRoute = !forTest && !forExport && isRootTun && !enableDnsRouting &&
                    rule.packages.isNotEmpty() && domainList.isNullOrEmpty() && ipList.isNullOrEmpty() &&
                    rulesetTags.isEmpty() && rule.port.isBlank() && rule.sourcePort.isBlank() &&
                    rule.network.isBlank() && rule.source.isBlank() && rule.protocol.isBlank() &&
                    RouteRuleEditor.json(rule.config).length() == 0 && routingAction == "route" &&
                    targetOutbound.isNotBlank() && targetOutbound != TAG_BLOCK
                if (rootAppUIDOnlyRoute) {
                    route.rules.add(Rule_DefaultOptions().apply {
                        _hack_config_map[ROOT_APP_UID_ROUTE_MARKER] = mapOf(
                            "index" to route.rules.size,
                            "packages" to rule.packages.toList().sorted(),
                            "uids" to uidList.toList().sorted(),
                            "outbound" to targetOutbound
                        )
                    })
                    continue
                }

                // v81 transfers package identity AND supported-domain outbound routing.
                // UIDs remain App-resolved; Root rebuilds the conjunctive matcher. The
                // independent DNS effect, if any, has its own earlier v80 marker.
                val rootAppUIDDomainOutbound = if (!forTest && !forExport && isRootTun &&
                    routingAction == "route" && targetOutbound.isNotBlank() && targetOutbound != TAG_BLOCK) {
                    rootAppUIDDomainOutboundRouteInput(rule, targetOutbound, uidList.toList())
                } else null
                if (rootAppUIDDomainOutbound != null) {
                    route.rules.add(Rule_DefaultOptions().apply {
                        _hack_config_map[ROOT_APP_UID_DOMAIN_OUTBOUND_ROUTE_MARKER] = mapOf(
                            "index" to route.rules.size,
                            "ruleId" to rootAppUIDDomainOutbound.ruleId,
                            "packages" to rootAppUIDDomainOutbound.packages,
                            "uids" to rootAppUIDDomainOutbound.uids,
                            "domainText" to rootAppUIDDomainOutbound.domainText,
                            "outbound" to rootAppUIDDomainOutbound.outbound,
                            "invert" to rootAppUIDDomainOutbound.invert
                        )
                    })
                    continue
                }

                // v84 moves package/UID AND explicit supported-domain AND rssite rulesets to Root.
                // Any rsip/mixed-kind rule remains on ConfigBuilder's split-OR legacy path.
                val rootAppUIDDomainRuleSetOutbound = if (!forTest && !forExport && isRootTun &&
                    routingAction == "route" && targetOutbound.isNotBlank() && targetOutbound != TAG_BLOCK) {
                    rootAppUIDDomainRuleSetOutboundInput(rule, targetOutbound, uidList.toList(), rulesetTags)
                } else null
                if (rootAppUIDDomainRuleSetOutbound != null) {
                    route.rules.add(Rule_DefaultOptions().apply {
                        _hack_config_map[ROOT_APP_UID_DOMAIN_RULESET_OUTBOUND_MARKER] = mapOf(
                            "index" to route.rules.size,
                            "ruleId" to rootAppUIDDomainRuleSetOutbound.ruleId,
                            "packages" to rootAppUIDDomainRuleSetOutbound.packages,
                            "uids" to rootAppUIDDomainRuleSetOutbound.uids,
                            "domainText" to rootAppUIDDomainRuleSetOutbound.domainText,
                            "ruleSetTags" to rootAppUIDDomainRuleSetOutbound.ruleSetTags,
                            "ruleSetKinds" to rootAppUIDDomainRuleSetOutbound.ruleSetKinds,
                            "outbound" to rootAppUIDDomainRuleSetOutbound.outbound,
                            "invert" to rootAppUIDDomainRuleSetOutbound.invert
                        )
                    })
                    route.rule_set.addAll(ruleSets)
                    continue
                }

                // v83 moves App's package/UID AND homogeneous remote-rule-set outbound matcher to Root.
                // Mixed IP/domain sets expand to OR branches in ConfigBuilder and remain legacy.
                val rootAppUIDRuleSetOutbound = if (!forTest && !forExport && isRootTun &&
                    routingAction == "route" && targetOutbound.isNotBlank() && targetOutbound != TAG_BLOCK) {
                    rootAppUIDRuleSetOutboundInput(rule, targetOutbound, uidList.toList(), rulesetTags)
                } else null
                if (rootAppUIDRuleSetOutbound != null) {
                    route.rules.add(Rule_DefaultOptions().apply {
                        _hack_config_map[ROOT_APP_UID_RULESET_OUTBOUND_MARKER] = mapOf(
                            "index" to route.rules.size,
                            "ruleId" to rootAppUIDRuleSetOutbound.ruleId,
                            "packages" to rootAppUIDRuleSetOutbound.packages,
                            "uids" to rootAppUIDRuleSetOutbound.uids,
                            "ruleSetTags" to rootAppUIDRuleSetOutbound.ruleSetTags,
                            "outbound" to rootAppUIDRuleSetOutbound.outbound,
                            "invert" to rootAppUIDRuleSetOutbound.invert
                        )
                    })
                    // The legacy path normally attaches each rule's definitions below.
                    // This marker's early exit must retain them for Root's v46 projection.
                    route.rule_set.addAll(ruleSets)
                    continue
                }

                if (rule.packages.isNotEmpty()) {
                    // @author 雾晚: identifiers/counts only; never log package names or endpoints.
                    Logs.d("ApplicationRouteConfig mode=${if (isRootTun) "root" else "vpn"} " +
                        "ruleId=${rule.id} packages=${rule.packages.size} uids=${uidList.size} " +
                        "outboundId=${rule.outbound} identity=package-or-uid")
                }

                fun applyCommonFilters(ruleObj: Rule_DefaultOptions) {
                    if (uidList.isNotEmpty()) {
                        ruleObj.user_id = uidList.toList()
                    }
                    if (rule.packages.isNotEmpty()) {
                        ruleObj.package_name = rule.packages.toList()
                    }
                    if (rule.port.isNotBlank()) {
                        ruleObj.port = mutableListOf<Int>()
                        ruleObj.port_range = mutableListOf<String>()
                        rule.port.listByLineOrComma().forEach {
                            if (it.contains(":")) {
                                ruleObj.port_range.add(it)
                            } else {
                                it.toIntOrNull()?.let { p -> ruleObj.port.add(p) }
                            }
                        }
                    }
                    if (rule.sourcePort.isNotBlank()) {
                        ruleObj.source_port = mutableListOf<Int>()
                        ruleObj.source_port_range = mutableListOf<String>()
                        rule.sourcePort.listByLineOrComma().forEach {
                            if (it.contains(":")) {
                                ruleObj.source_port_range.add(it)
                            } else {
                                it.toIntOrNull()?.let { p -> ruleObj.source_port.add(p) }
                            }
                        }
                    }
                    if (rule.network.isNotBlank()) {
                        // @author 雾晚: the multiline editor stores one network per line.
                        ruleObj.network = rule.network.listByLineOrComma()
                    }
                    if (rule.source.isNotBlank()) {
                        ruleObj.source_ip_cidr = rule.source.listByLineOrComma()
                    }
                    if (rule.protocol.isNotBlank()) {
                        ruleObj.protocol = rule.protocol.listByLineOrComma()
                    }
                    if (targetOutbound == TAG_BLOCK) {
                        ruleObj.outbound = null
                        ruleObj.action = "reject"
                    } else {
                        ruleObj.outbound = targetOutbound
                    }
                    // @author 雾晚: non-routing actions must not inherit an outbound.
                    RouteRuleEditor.applyAction(ruleObj, rule.config)
                }

                val generatedSubRules = mutableListOf<Rule_DefaultOptions>()
                val hasDomain = hasDomainCriteria || hasDomainRuleset
                val hasIp = hasIpCriteria

                if (hasDomain && hasIp) {
                    // Split into two sub-rules (Domain and IP) to ensure OR semantics in sing-box
                    val domainSubRule = Rule_DefaultOptions().apply {
                        domainList?.let { makeSingBoxRule(it, false) }
                        val domainRulesetTags = rulesetTags.filter { !it.second }.map { it.first }
                        if (domainRulesetTags.isNotEmpty()) {
                            rule_set = (rule_set ?: mutableListOf()).apply { addAll(domainRulesetTags) }
                        }
                        if (rule_set != null) generateRuleSet(rule_set, ruleSets)
                        applyCommonFilters(this)
                    }
                    if (!domainSubRule.checkEmpty()) generatedSubRules.add(domainSubRule)

                    val ipSubRule = Rule_DefaultOptions().apply {
                        ipList?.let { makeSingBoxRule(it, true) }
                        val ipRulesetTags = rulesetTags.filter { it.second }.map { it.first }
                        if (ipRulesetTags.isNotEmpty()) {
                            rule_set = (rule_set ?: mutableListOf()).apply { addAll(ipRulesetTags) }
                        }
                        if (rule_set != null) generateRuleSet(rule_set, ruleSets)
                        applyCommonFilters(this)
                    }
                    if (!ipSubRule.checkEmpty()) generatedSubRules.add(ipSubRule)
                } else {
                    val singleRule = Rule_DefaultOptions().apply {
                        if (hasDomain) {
                            domainList?.let { makeSingBoxRule(it, false) }
                            val domainRulesetTags = rulesetTags.filter { !it.second }.map { it.first }
                            if (domainRulesetTags.isNotEmpty()) {
                                rule_set = (rule_set ?: mutableListOf()).apply { addAll(domainRulesetTags) }
                            }
                        }
                        if (hasIp) {
                            ipList?.let { makeSingBoxRule(it, true) }
                            val ipRulesetTags = rulesetTags.filter { it.second }.map { it.first }
                            if (ipRulesetTags.isNotEmpty()) {
                                rule_set = (rule_set ?: mutableListOf()).apply { addAll(ipRulesetTags) }
                            }
                        }
                        if (rule_set != null) generateRuleSet(rule_set, ruleSets)
                        applyCommonFilters(this)
                    }
                    if (!singleRule.checkEmpty()) generatedSubRules.add(singleRule)
                }

                // Schema v67 lets Root generate supported domain AND IP-version routes.
                // ip_version suppresses the related DNS-outbound side effect in ConfigBuilder.
                val rootDomainIPVersionOutbound = if (!forTest && !forExport && isRootTun &&
                    routingAction == "route" && targetOutbound.isNotBlank() && targetOutbound != TAG_BLOCK) {
                    io.nekohasekai.sagernet.route.rootDomainIPVersionOutboundRouteInput(rule, targetOutbound)
                } else null
                if (rootDomainIPVersionOutbound != null) {
                    route.rules.add(Rule_DefaultOptions().apply {
                        _hack_config_map[io.nekohasekai.sagernet.route.ROOT_DOMAIN_IP_VERSION_OUTBOUND_ROUTE_MARKER] = mapOf(
                            "index" to route.rules.size,
                            "ruleId" to rootDomainIPVersionOutbound.ruleId,
                            "domainText" to rootDomainIPVersionOutbound.domainText,
                            "ipVersion" to rootDomainIPVersionOutbound.ipVersion,
                            "outbound" to rootDomainIPVersionOutbound.outbound,
                            "invert" to rootDomainIPVersionOutbound.invert
                        )
                    })
                    continue
                }

                // Schema v55 moves plain domain full/suffix/keyword outbound matcher
                // generation to Root. Exact full:-domain rules already use v50; this
                // path includes the whole-rule invert flag and leaves DNS side effects
                // on their separately generated, ordered path.
                val rootDomainOutbound = if (!forTest && !forExport && isRootTun &&
                    routingAction == "route" && targetOutbound.isNotBlank() && targetOutbound != TAG_BLOCK &&
                    hasDomain && !hasIp) {
                    io.nekohasekai.sagernet.route.rootDomainOutboundRouteInput(rule, targetOutbound)
                } else null
                if (rootDomainOutbound != null) {
                    route.rules.add(Rule_DefaultOptions().apply {
                        _hack_config_map[io.nekohasekai.sagernet.route.ROOT_DOMAIN_OUTBOUND_ROUTE_MARKER] = mapOf(
                            "index" to route.rules.size,
                            "ruleId" to rootDomainOutbound.ruleId,
                            "domainText" to rootDomainOutbound.domainText,
                            "outbound" to rootDomainOutbound.outbound,
                            "invert" to rootDomainOutbound.invert
                        )
                    })
                    continue
                }

                // Schema v51: Root independently generates the narrowly supported
                // exact-domain OR IP-CIDR matcher, applying invert once to the whole group.
                // Any eligible DNS side effect above has its own typed Root marker; richer
                // or unsupported predicates remain on the legacy path.
                val rootCompositeDomainIP = if (!forTest && !forExport && isRootTun &&
                    routingAction == "route" && targetOutbound.isNotBlank() && targetOutbound != TAG_BLOCK) {
                    io.nekohasekai.sagernet.route.rootCompositeDomainIPOutboundRouteInput(rule, targetOutbound)
                } else null
                if (rootCompositeDomainIP != null) {
                    route.rules.add(Rule_DefaultOptions().apply {
                        _hack_config_map[io.nekohasekai.sagernet.route.ROOT_COMPOSITE_DOMAIN_IP_OUTBOUND_MARKER] = mapOf(
                            "index" to route.rules.size,
                            "ruleId" to rootCompositeDomainIP.ruleId,
                            "domainText" to rootCompositeDomainIP.domainText,
                            "ipText" to rootCompositeDomainIP.ipText,
                            "outbound" to rootCompositeDomainIP.outbound,
                            "invert" to rootCompositeDomainIP.invert
                        )
                    })
                    continue
                }

                // @author 雾晚: NOT(domain OR IP) must not become NOT(domain) OR NOT(IP).
                val invertedGroup = RouteRuleEditor.invertedGroup(generatedSubRules)
                if (invertedGroup != null) {
                    route.rules.add(invertedGroup)
                    route.rule_set.addAll(ruleSets)
                    continue
                }
                for (subRule in generatedSubRules) {
                    if (subRule.action !in setOf("reject", "sniff", "resolve", "hijack-dns", "route-options") && subRule.outbound.isNullOrBlank()) {
                        Toast.makeText(
                            SagerNet.application,
                            "Warning: " + rule.displayName() + ": A non-existent outbound was specified.",
                            Toast.LENGTH_LONG
                        ).show()
                    } else {
                        route.rules.add(subRule)
                        route.rule_set.addAll(ruleSets)
                        Logs.d("[ConfigBuilder] Route rule '${rule.displayName()}' condition added -> ${subRule.outbound ?: subRule.action}")
                    }
                }
            }
        }

        // 对 rule_set tag 去重
        if (route.rule_set != null) {
            route.rule_set = route.rule_set.distinctBy { it.tag }
        }

        for (freedom in arrayOf(TAG_DIRECT, TAG_BYPASS)) {
            outbounds.add(Outbound().apply {
                tag = freedom
                type = "direct"
                // Ensure both direct and bypass outbounds bind to Android default physical network interface
                _hack_config_map["network_strategy"] = "default"
                if (ipv6Mode == IPv6Mode.DISABLE) {
                    _hack_config_map["domain_strategy"] = "ipv4_only"
                } else if (ipv6Mode == IPv6Mode.ONLY) {
                    _hack_config_map["domain_strategy"] = "ipv6_only"
                } else if (ipv6Mode == IPv6Mode.PREFER) {
                    _hack_config_map["domain_strategy"] = "prefer_ipv6"
                }
            })
        }

        fun isExclusiveCustomHost(host: String): Boolean {
            return hostResolvers[host]?.size == 1 && !nonCustomFinalHosts.contains(host)
        }

        // Bypass Lookup for the first profile
        bypassDNSBeans.forEach {
            var serverAddr = it.serverAddress

            if (it is ConfigBean) {
                var config = mutableMapOf<String, Any>()
                config = gson.fromJson(it.config, config.javaClass)
                config["server"]?.apply {
                    serverAddr = toString()
                }
            }

            if (!serverAddr.isIpAddress()) {
                if (!isExclusiveCustomHost(serverAddr)) {
                    domainListDNSDirectForce.add("full:${serverAddr}")
                }
            }
        }

        remoteDns.forEach {
            var address = it
            if (address.contains("://")) {
                address = address.substringAfter("://")
            }
            "https://$address".toHttpUrlOrNull()?.apply {
                if (!host.isIpAddress()) {
                    domainListDNSDirectForce.add("full:$host")
                }
            }
        }

        dns.servers.add(DNSServerOptions().apply {
            type = "local"
            tag = "dns-local"
            detour = TAG_DIRECT
        })

        val directAddress = directDNS.firstOrNull()?.takeIf { it.isNotBlank() } ?: "https://223.5.5.5/dns-query"
        val normalizedDirect = normalizeDnsAddress(directAddress)
        dns.servers.add(
            buildDnsServer(
                address = normalizedDirect,
                tag = "dns-direct",
                detour = TAG_DIRECT,
                domainResolver = "dns-local",
                domainStrategy = autoDnsDomainStrategy(SingBoxOptionsUtil.domainStrategy("dns-direct"))
            )
        )

        val remoteAddress = remoteDns.firstOrNull()?.takeIf { it.isNotBlank() } ?: "https://dns.google/dns-query"
        val normalizedRemote = normalizeRemoteDnsAddress(remoteAddress)
        dns.servers.add(
            buildDnsServer(
                address = normalizedRemote,
                tag = "dns-remote",
                detour = mainProxyTag,
                domainResolver = "dns-direct",
                domainStrategy = autoDnsDomainStrategy(SingBoxOptionsUtil.domainStrategy("dns-remote"), isProxied = true)
            )
        )
        if (dnsHosts.isNotEmpty()) {
            dns.servers.add(DNSServerOptions().apply {
                type = "hosts"
                tag = TAG_DNS_HOSTS
                _hack_config_map["predefined"] = dnsHosts
            })
        }

        dns.final_ = "dns-remote"
        if (ipv6Mode == IPv6Mode.DISABLE) {
            dns.strategy = "ipv4_only"
        } else if (ipv6Mode == IPv6Mode.ONLY) {
            dns.strategy = "ipv6_only"
        } else if (ipv6Mode == IPv6Mode.PREFER) {
            dns.strategy = "prefer_ipv6"
        }

        // dns object user rules
        if (enableDnsRouting) {
            userDNSRuleList.forEach {
                if (it.server == "dns-block") {
                    it.server = null
                    it.action = "reject"
                }
                if (!it.checkEmpty()) dns.rules.add(AppRouteIdentity.dns(it))
            }
        }

        if (forTest) {
            val testRules = mutableListOf<DNSRule_DefaultOptions>()
            if (ipv6Mode == IPv6Mode.DISABLE) {
                testRules.add(DNSRule_DefaultOptions().apply {
                    query_type = listOf("AAAA")
                    action = "reject"
                })
            } else if (ipv6Mode == IPv6Mode.ONLY) {
                testRules.add(DNSRule_DefaultOptions().apply {
                    query_type = listOf("A")
                    action = "reject"
                })
            }
            // avoid loopback: outbound server domains must resolve directly
            testRules.add(DNSRule_DefaultOptions().apply {
                outbound = mutableListOf("any")
                server = "dns-direct"
            })
            dns.rules = testRules
        } else {
            // built-in DNS rules
            if (ipv6Mode == IPv6Mode.DISABLE) {
                dns.rules.add(0, DNSRule_DefaultOptions().apply {
                    query_type = listOf("AAAA")
                    action = "reject"
                })
            } else if (ipv6Mode == IPv6Mode.ONLY) {
                dns.rules.add(0, DNSRule_DefaultOptions().apply {
                    query_type = listOf("A")
                    action = "reject"
                })
            }

            // 提取 directDNS 与 remoteDns 的域名及 IP/CIDR 目标
            val (rawDirectDomains, directIps) = extractDnsTargets(directDNS, false)
            val (rawRemoteDomains, remoteIps) = extractDnsTargets(remoteDns, true)
            val directDomains = rawDirectDomains - rawRemoteDomains
            val remoteDomains = rawRemoteDomains

            // 构建最优先前置路由规则（须位于所有用户规则之前）
            val topRouteRules = mutableListOf<Rule>()

            // @author 雾晚: match Root's configured probe before sniff/resolve and user routes.
            // This keeps local CONNECT measurements on the same selected proxy as core tests.
            if (isRootTun) {
                val probeHost = runCatching { java.net.URI(DataStore.connectionTestURL).host }.getOrNull()
                if (!probeHost.isNullOrBlank()) topRouteRules.add(Rule_DefaultOptions().apply {
                    inbound = listOf(TAG_MIXED)
                    domain = listOf(probeHost)
                    outbound = mainProxyTag
                })
            }

            // 1. sing-box 1.13：sniff（须位于规则最前）
            if (needSniff) {
                topRouteRules.add(Rule_DefaultOptions().apply {
                    action = "sniff"
                })
            }

            // 2. resolve 动作：强制单栈解析杜绝远端 VPS 双栈泄露；用户显式开启 resolveDestination 时按策略传出
            // @author 雾晚: outbound strategy already handles address families.
            // Do not force remote DNS before every HTTP CONNECT / TLS handshake.
            if (DataStore.resolveDestination) {
                topRouteRules.add(Rule_DefaultOptions().apply {
                    action = "resolve"
                    strategy = genDomainStrategy(true)
                })
            }

            // 3. hijack-dns 拦截入站 DNS 流量进入内置 DNS 引擎
            topRouteRules.add(Rule_DefaultOptions().apply {
                port = listOf(53)
                action = "hijack-dns"
            })
            topRouteRules.add(Rule_DefaultOptions().apply {
                protocol = listOf("dns")
                action = "hijack-dns"
            })

            // 4. IP 版本禁用规则
            if (ipv6Mode == IPv6Mode.DISABLE) {
                topRouteRules.add(Rule_DefaultOptions().apply {
                    ip_version = 6
                    action = "reject"
                })
            } else if (ipv6Mode == IPv6Mode.ONLY) {
                topRouteRules.add(Rule_DefaultOptions().apply {
                    ip_version = 4
                    action = "reject"
                })
            }

            // 5. 直连 DNS 硬隔离规则（锁定 direct，绝不走代理）
            if (directDomains.isNotEmpty()) {
                topRouteRules.add(Rule_DefaultOptions().apply {
                    domain = directDomains.toList()
                    outbound = TAG_DIRECT
                })
            }
            if (directIps.isNotEmpty()) {
                topRouteRules.add(Rule_DefaultOptions().apply {
                    ip_cidr = directIps.toList()
                    outbound = TAG_DIRECT
                })
            }

            // 6. 远程 DNS 硬隔离规则（强制锁定 mainProxyTag，绝不回退或走国内直连）
            if (remoteDomains.isNotEmpty()) {
                topRouteRules.add(Rule_DefaultOptions().apply {
                    domain = remoteDomains.toList()
                    outbound = mainProxyTag
                })
            }
            if (remoteIps.isNotEmpty()) {
                topRouteRules.add(Rule_DefaultOptions().apply {
                    ip_cidr = remoteIps.toList()
                    outbound = mainProxyTag
                })
            }

            // 7. 未拦截远程 DNS 兜底保护（port 53 / protocol dns 流量强制走代理）
            topRouteRules.add(Rule_DefaultOptions().apply {
                port = listOf(53)
                outbound = mainProxyTag
            })
            topRouteRules.add(Rule_DefaultOptions().apply {
                protocol = listOf("dns")
                outbound = mainProxyTag
            })

            route.rules.addAll(0, topRouteRules)

            if (DataStore.bypassLanInCore) {
                route.rules.add(Rule_DefaultOptions().apply {
                    outbound = TAG_BYPASS
                    ip_is_private = true
                })
            }
            // block mcast
            route.rules.add(Rule_DefaultOptions().apply {
                ip_cidr = listOf("224.0.0.0/3", "ff00::/8")
                source_ip_cidr = listOf("224.0.0.0/3", "ff00::/8")
                action = "reject"
            })
            // FakeDNS obj (sing-box 1.14: fakeip configured as a server in dns.servers)
            if (useFakeDns) {
                // @author 雾晚: sing-box expresses Fake-IP exceptions as DNS rules.
                dns.rules.add(0, DNSRule_DefaultOptions().apply {
                    domain = listOf("localhost", "pool.ntp.org")
                    domain_suffix = listOf(".lan", ".local", ".localhost", ".pool.ntp.org")
                    domain_regex = listOf("(?i)^(ntp|time)([0-9]+)?[.]")
                    server = "dns-direct"
                })
                dns.servers.add(DNSServerOptions().apply {
                    type = "fakeip"
                    tag = "dns-fake"
                    inet4_range = "198.18.0.0/15"
                    if (ipv6Mode != IPv6Mode.DISABLE) {
                        inet6_range = "fc00::/18"
                    }
                })
                dns.rules.add(DNSRule_DefaultOptions().apply {
                    inbound = listOf("tun-in")
                    server = "dns-fake"
                    disable_cache = true
                    query_type = when (ipv6Mode) {
                        IPv6Mode.DISABLE -> listOf("A")
                        IPv6Mode.ONLY -> listOf("AAAA")
                        else -> listOf("A", "AAAA")
                    }
                })
            }
            if (dnsHosts.isNotEmpty()) {
                dns.rules.add(0, DNSRule_DefaultOptions().apply {
                    server = TAG_DNS_HOSTS
                    _hack_config_map["ip_accept_any"] = true
                })
            }
            // avoid loopback
            dns.rules.add(0, DNSRule_DefaultOptions().apply {
                outbound = mutableListOf("any")
                server = "dns-direct"
            })
            // force bypass (always top DNS rule)
            if (domainListDNSDirectForce.isNotEmpty()) {
                dns.rules.add(0, DNSRule_DefaultOptions().apply {
                    makeSingBoxRule(domainListDNSDirectForce.toHashSet().toList())
                    server = "dns-direct"
                })
            }
            perGroupResolver.forEach { (gid, resolver) ->
                val hosts = perGroupServerHosts[gid]
                    ?.filter { it.isNotBlank() && isExclusiveCustomHost(it) }
                    ?.map { "full:$it" }
                if (hosts.isNullOrEmpty()) return@forEach

                val serverTag = "dns-sub-$gid"
                val address = normalizeDnsAddress(resolver)
                dns.servers.add(
                    buildDnsServer(
                        address = address,
                        tag = serverTag,
                        detour = TAG_DIRECT,
                        domainResolver = "dns-direct",
                        domainStrategy = autoDnsDomainStrategy(SingBoxOptionsUtil.domainStrategy("server"))
                    )
                )
                dns.rules.add(0, DNSRule_DefaultOptions().apply {
                    makeSingBoxRule(hosts)
                    server = serverTag
                })
            }
        }

        if (!forTest && ipv6Mode == IPv6Mode.DISABLE) {
            dns.rules.add(0, DNSRule_DefaultOptions().apply {
                query_type = listOf("AAAA")
                action = "reject"
            })
        } else if (!forTest && ipv6Mode == IPv6Mode.ONLY) {
            dns.rules.add(0, DNSRule_DefaultOptions().apply {
                query_type = listOf("A")
                action = "reject"
            })
        }

        // Synchronize route.rules and route.final_ against available outbound tags to avoid "tag not found"
        val availableOutboundTags = outbounds.mapNotNull { it.asMap()["tag"]?.toString() }.toSet()
        route.rules.filterIsInstance<Rule_DefaultOptions>().forEach { r ->
            val out = r.outbound
            if (!out.isNullOrBlank() && out !in availableOutboundTags) {
                if (out == TAG_PROXY && availableOutboundTags.contains(groupTag)) {
                    r.outbound = groupTag
                } else if (out == groupTag && availableOutboundTags.contains(TAG_PROXY)) {
                    r.outbound = TAG_PROXY
                } else if (group?.name?.isNotBlank() == true && out == group.name && availableOutboundTags.contains(groupTag)) {
                    r.outbound = groupTag
                } else {
                    Logs.w("ConfigBuilder: rule outbound tag '$out' not found in outbounds, fallback to '$mainProxyTag'")
                    r.outbound = mainProxyTag
                }
            }
        }
        // @author 雾晚: transform only after existing outbound validation/fallback.
        route.rules = route.rules.map { AppRouteIdentity.route(it) }.toMutableList()
        if (!route.final_.isNullOrBlank() && route.final_ !in availableOutboundTags) {
            route.final_ = mainProxyTag
        }

        // Legacy outbounds implicitly used their first item as the default route. Endpoints are
        // partitioned out of that list, so an unset final would silently fall back to direct.
        route.ensureMainRouteFinal(mainProxyTag)
        val routeFinalState = when (route.final_) {
            null, "" -> "unset"
            mainProxyTag -> "main"
            else -> "other"
        }
        Logs.i(
            "RouteFinalTrace profileId=${proxy.id} forTest=$forTest " +
                "legacyExportGlobalMode=${!forTest && forExport && DataStore.globalMode} selector=$buildSelector " +
                "finalState=$routeFinalState ruleCount=${route.rules.size}"
        )

    }.let { options ->
        val configMap = finalizeRootConfig(
            options,
            globalCustomConfig = if (forTest) "" else DataStore.globalCustomConfig,
            profileCustomConfig = proxy.requireBean().customConfigJson,
        )
        val endpointTags = (configMap["endpoints"] as? List<*>)
            .orEmpty()
            .mapNotNull { (it as? Map<*, *>)?.get("tag")?.toString() }
        val outboundNodes = (configMap["outbounds"] as? List<*>)
            .orEmpty()
            .mapNotNull { it as? Map<*, *> }
        val outboundTags = outboundNodes.mapNotNull { it["tag"]?.toString() }
        val availableTags = (endpointTags + outboundTags).toSet()
        val references = buildList {
            val endpointNodes = (configMap["endpoints"] as? List<*>)
                .orEmpty()
                .mapNotNull { it as? Map<*, *> }
            (endpointNodes + outboundNodes).forEach { node ->
                val source = node["tag"]?.toString() ?: "<untagged>"
                node["detour"]?.toString()?.takeIf { it.isNotBlank() }?.let {
                    add("$source->$it")
                }
                if (node["type"] == "selector") {
                    (node["outbounds"] as? List<*>)?.forEach { target ->
                        target?.toString()?.takeIf { it.isNotBlank() }?.let {
                            add("$source=>$it")
                        }
                    }
                }
            }
            val route = configMap["route"] as? Map<*, *>
            route?.get("final")?.toString()?.takeIf { it.isNotBlank() }?.let {
                add("route.final->$it")
            }
        }
        val unresolvedTargets = references.mapNotNull { reference ->
            reference.substringAfterLast("->", reference.substringAfterLast("=>"))
                .takeUnless { it in availableTags }
        }.distinct()
        Logs.i(
            "ChainTopologyTrace profileId=${proxy.id} forTest=$forTest " +
                "endpoints=$endpointTags outbounds=$outboundTags " +
                "references=$references unresolved=$unresolvedTargets"
        )
        ConfigBuildResult(
            gson.toJson(configMap),
            externalIndexMap,
            proxy.id,
            trafficMap,
            tagMap,
            if (buildSelector || useAutoSelect || useLoadBalance) group?.id ?: 0L else -1L,
            balancerMemberMap
        )
    }

}

// B6（N4）：eBPF 共享路径（热点 / USB 共享流量接管）配置。
// 只接 eBPF 共享路径，默认关闭；字段名属 sing-box 原生入站 schema。
// JSON schema 与 rootmodule/shared.go 的 SharedConfig 一致：
// {"enabled":bool,"interface":[...],"data_plane":"socket_assign|packet_rewrite",
// "dns_mode":"hijack","default_interface":bool,"udp_timeout":"5m",
// "include_source_cidr":[...],"exclude_source_cidr":[...],
// "include_mac_address":[...],"exclude_mac_address":[...]}
package io.nekohasekai.sagernet.bg

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

data class SharedInterceptionConfig(
    val enabled: Boolean = false,
    val interfaces: List<String> = emptyList(),
    val dataPlane: String = DATA_PLANE_SOCKET_ASSIGN,
    val dnsMode: String = DNS_MODE_HIJACK,
    val defaultInterface: Boolean = false,
    /** UDP 超时（秒），下限 5 秒。 */
    val udpTimeoutSeconds: Long = 300,
    val includeSourceCidr: List<String> = emptyList(),
    val excludeSourceCidr: List<String> = emptyList(),
    val includeMacAddress: List<String> = emptyList(),
    val excludeMacAddress: List<String> = emptyList(),
) {
    companion object {
        const val DATA_PLANE_SOCKET_ASSIGN = "socket_assign"
        const val DATA_PLANE_PACKET_REWRITE = "packet_rewrite"
        const val DNS_MODE_HIJACK = "hijack"
        const val DNS_MODE_RESPECT_POLICY = "respect_policy"
        const val MIN_UDP_TIMEOUT_SECONDS = 5L
    }
}

object SharedInterception {
    private val interfaceName = Regex("^[a-zA-Z0-9_.-]{1,15}$")
    private val macAddress = Regex("^([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}$")

    fun defaults(): SharedInterceptionConfig = SharedInterceptionConfig()

    fun parse(json: String): Result<SharedInterceptionConfig> = runCatching {
        if (json.isBlank()) return@runCatching defaults()
        val root = JsonParser.parseString(json).asJsonObject
        val cfg = SharedInterceptionConfig(
            enabled = root.get("enabled")?.takeIf { !it.isJsonNull }?.asBoolean ?: false,
            interfaces = root.stringList("interface"),
            dataPlane = root.get("data_plane")?.takeIf { !it.isJsonNull }?.asString ?: SharedInterceptionConfig.DATA_PLANE_SOCKET_ASSIGN,
            dnsMode = root.get("dns_mode")?.takeIf { !it.isJsonNull }?.asString ?: SharedInterceptionConfig.DNS_MODE_HIJACK,
            defaultInterface = root.get("default_interface")?.takeIf { !it.isJsonNull }?.asBoolean ?: false,
            udpTimeoutSeconds = parseDurationSeconds(
                root.get("udp_timeout")?.takeIf { !it.isJsonNull }?.asString ?: "5m"
            ),
            includeSourceCidr = root.stringList("include_source_cidr"),
            excludeSourceCidr = root.stringList("exclude_source_cidr"),
            includeMacAddress = root.stringList("include_mac_address"),
            excludeMacAddress = root.stringList("exclude_mac_address"),
        )
        validate(cfg).getOrThrow()
        cfg
    }

    private fun JsonObject.stringList(key: String): List<String> {
        val arr = getAsJsonArray(key) ?: return emptyList()
        return arr.map { it.asString }
    }

    /** 接受 "5m"/"30s"/"90s" 等 Go duration 写法，返回秒数。 */
    fun parseDurationSeconds(text: String): Long {
        val t = text.trim()
        return when {
            t.endsWith("ms") -> t.dropLast(2).toLong() / 1000
            t.endsWith("m") -> t.dropLast(1).toLong() * 60
            t.endsWith("s") -> t.dropLast(1).toLong()
            else -> t.toLong()
        }
    }

    fun formatDuration(seconds: Long): String =
        if (seconds % 60 == 0L) "${seconds / 60}m" else "${seconds}s"

    /** 返回 null 表示合法，否则为稳定错误 token（与 Go 侧 CheckSharedConfig 对齐）。 */
    fun validate(cfg: SharedInterceptionConfig): Result<Unit> = runCatching {
        require(
            cfg.dataPlane == SharedInterceptionConfig.DATA_PLANE_SOCKET_ASSIGN ||
                cfg.dataPlane == SharedInterceptionConfig.DATA_PLANE_PACKET_REWRITE
        ) { "shared_data_plane_invalid" }
        require(
            cfg.dnsMode == SharedInterceptionConfig.DNS_MODE_HIJACK ||
                cfg.dnsMode == SharedInterceptionConfig.DNS_MODE_RESPECT_POLICY
        ) { "shared_dns_mode_invalid" }
        require(cfg.udpTimeoutSeconds >= SharedInterceptionConfig.MIN_UDP_TIMEOUT_SECONDS) {
            "shared_udp_timeout_invalid"
        }
        val seen = mutableSetOf<String>()
        for (iface in cfg.interfaces) {
            require(interfaceName.matches(iface) && iface != "lo") { "shared_interface_invalid" }
            require(seen.add(iface)) { "shared_interface_duplicate" }
        }
        require(!cfg.enabled || cfg.interfaces.isNotEmpty()) { "shared_interface_required" }
        for (cidr in cfg.includeSourceCidr + cfg.excludeSourceCidr) {
            require(isCidrOrIp(cidr)) { "shared_cidr_invalid" }
        }
        for (mac in cfg.includeMacAddress + cfg.excludeMacAddress) {
            require(macAddress.matches(mac)) { "shared_mac_invalid" }
        }
    }

    private fun isCidrOrIp(value: String): Boolean {
        if ("/" in value) {
            val parts = value.split("/")
            if (parts.size != 2) return false
            val bits = parts[1].toIntOrNull() ?: return false
            return isIp(parts[0]) && bits in 0..if (":" in parts[0]) 128 else 32
        }
        return isIp(value)
    }

    private fun isIp(value: String): Boolean {
        if (!value.matches(Regex("[0-9A-Fa-f:.]+"))) return false
        return runCatching { java.net.InetAddress.getByName(value) != null }.getOrDefault(false)
    }

    fun serialize(cfg: SharedInterceptionConfig): String {
        val root = JsonObject()
        root.addProperty("enabled", cfg.enabled)
        root.add("interface", JsonArray().apply { cfg.interfaces.forEach { add(it) } })
        root.addProperty("data_plane", cfg.dataPlane)
        root.addProperty("dns_mode", cfg.dnsMode)
        root.addProperty("default_interface", cfg.defaultInterface)
        root.addProperty("udp_timeout", formatDuration(cfg.udpTimeoutSeconds))
        fun addList(key: String, values: List<String>) {
            if (values.isNotEmpty()) root.add(key, JsonArray().apply { values.forEach { add(it) } })
        }
        addList("include_source_cidr", cfg.includeSourceCidr)
        addList("exclude_source_cidr", cfg.excludeSourceCidr)
        addList("include_mac_address", cfg.includeMacAddress)
        addList("exclude_mac_address", cfg.excludeMacAddress)
        return root.toString()
    }

    /**
     * 下游接口候选：优先用 Root 枚举（`wanboxctl __internal shared-interfaces`），
     * 失败时回退本地 NetworkInterface 枚举；loopback 永不入选。
     * 由 Root 侧校验、loopback 不得入选（见 rootmodule/shared.go）。
     */
    fun listCandidateInterfaces(): List<String> {
        runCatching {
            val process = ProcessBuilder(
                "su", "-c",
                "exec /data/adb/modules/wanbox/bin/wanboxctl __internal shared-interfaces"
            ).start()
            val out = process.inputStream.bufferedReader().readText()
            process.waitFor()
            if (process.exitValue() == 0) {
                val root = JsonParser.parseString(out).asJsonObject
                val names = root.getAsJsonArray("interfaces")?.map { it.asString } ?: emptyList()
                if (names.isNotEmpty()) return names.filter { it != "lo" }.sorted()
            }
        }
        return localCandidateInterfaces()
    }

    fun localCandidateInterfaces(): List<String> {
        return runCatching {
            java.net.NetworkInterface.getNetworkInterfaces().toList()
                .filter { iface ->
                    runCatching { iface.isUp && !iface.isLoopback }.getOrDefault(false) &&
                        iface.name != "lo" &&
                        iface.inetAddresses.toList().any { addr ->
                            !addr.isLoopbackAddress && !addr.isLinkLocalAddress
                        }
                }.map { it.name }.sorted()
        }.getOrDefault(emptyList())
    }

    /**
     * eBPF 能力预检预览（`wanboxctl __internal shared-preflight`），供设置页
     * 在开启前提示；真正的开启门禁在 Root apply 时（supervisor 预检失败拒绝启动）。
     * 预检失败返回 missing 列表，null 表示预检未跑通（由 Root 门禁兜底）。
     */
    fun preflight(dataPlane: String): List<String>? {
        return runCatching {
            val process = ProcessBuilder(
                "su", "-c",
                "exec /data/adb/modules/wanbox/bin/wanboxctl __internal shared-preflight $dataPlane"
            ).start()
            val out = process.inputStream.bufferedReader().readText()
            process.waitFor()
            if (process.exitValue() != 0) return null
            val root = JsonParser.parseString(out).asJsonObject
            if (root.get("ok")?.asBoolean == true) emptyList()
            else root.getAsJsonArray("missing")?.map { it.asString } ?: listOf("unknown")
        }.getOrNull()
    }
}

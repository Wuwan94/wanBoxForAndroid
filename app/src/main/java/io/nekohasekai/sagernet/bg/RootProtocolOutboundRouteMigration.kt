// @author 雾晚
package io.nekohasekai.sagernet.bg

import com.google.gson.JsonArray
import com.google.gson.JsonObject

internal const val ROOT_PROTOCOL_OUTBOUND_ROUTE_MARKER = "_wanbox_root_protocol_outbound_v78"

private val rootProtocolOutboundProtocols = setOf("tls", "http", "quic", "dns", "stun", "bittorrent", "dtls", "ssh", "rdp", "ntp")

/** Move only plain protocol-list -> outbound route rules; all richer matchers stay in App output. */
internal fun projectRootProtocolOutboundRoutes(config: JsonObject): Boolean {
    val rules = config.getAsJsonObject("route")?.getAsJsonArray("rules") ?: return false
    var moved = false
    for (index in 0 until rules.size()) {
        val raw = rules[index]
        if (!raw.isJsonObject) continue
        val rule = raw.asJsonObject
        if (rule.keySet().any { it !in setOf("protocol", "outbound", "invert") }) continue
        val protocolElement = rule.get("protocol") ?: continue
        val outboundElement = rule.get("outbound") ?: continue
        if (!protocolElement.isJsonArray || protocolElement.asJsonArray.size() !in 1..10 ||
            !outboundElement.isJsonPrimitive || !outboundElement.asJsonPrimitive.isString || outboundElement.asString.isBlank()) continue
        val protocols = protocolElement.asJsonArray.map { value ->
            if (!value.isJsonPrimitive || !value.asJsonPrimitive.isString) return@map null
            value.asString.takeIf { it in rootProtocolOutboundProtocols }
        }
        if (protocols.any { it == null } || protocols.filterNotNull().distinct().size != protocols.size) continue
        val invert = rule.get("invert")
        if (invert != null && (!invert.isJsonPrimitive || !invert.asJsonPrimitive.isBoolean)) continue
        rules.set(index, JsonObject().apply {
            add(ROOT_PROTOCOL_OUTBOUND_ROUTE_MARKER, JsonObject().apply {
                addProperty("index", index)
                add("protocols", JsonArray().apply { protocols.filterNotNull().forEach(::add) })
                addProperty("outbound", outboundElement.asString)
                addProperty("invert", invert?.asBoolean ?: false)
            })
        })
        moved = true
    }
    return moved
}

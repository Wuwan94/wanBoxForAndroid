// @author 雾晚
package io.nekohasekai.sagernet.route

import io.nekohasekai.sagernet.database.RuleEntity

internal const val ROOT_PORT_RANGE_OUTBOUND_ROUTE_MARKER = "_wanbox_root_route_port_range_outbound_v85"
internal const val ROOT_SOURCE_PORT_RANGE_OUTBOUND_ROUTE_MARKER = "_wanbox_root_route_source_port_range_outbound_v85"
internal const val ROOT_PORT_RANGE_OUTBOUND_MAX_RULE_ID = 9_007_199_254_740_991L

internal data class RootPortRangeOutboundRouteInput(val ruleId: Long, val ranges: List<String>, val outbound: String)
internal data class RootSourcePortRangeOutboundRouteInput(val ruleId: Long, val ranges: List<String>, val outbound: String)

/** Accept only a closed, numeric range-only outbound predicate. All richer shapes stay in ConfigBuilder. */
internal fun rootPortRangeOutboundRouteInput(rule: RuleEntity, outbound: String): RootPortRangeOutboundRouteInput? {
    if (!rule.enabled || rule.id !in 1..ROOT_PORT_RANGE_OUTBOUND_MAX_RULE_ID || rule.port.isBlank() ||
        rule.domains.isNotBlank() || rule.ip.isNotBlank() || rule.sourcePort.isNotBlank() || rule.network.isNotBlank() ||
        rule.source.isNotBlank() || rule.protocol.isNotBlank() || rule.ruleset.isNotBlank() ||
        rule.packages.isNotEmpty() || rule.config.isNotBlank() || !validRootRangeOutbound(outbound)) return null
    val ranges = parseRootClosedPortRanges(rule.port) ?: return null
    return RootPortRangeOutboundRouteInput(rule.id, ranges, outbound)
}

/** Accept only a closed, numeric source-range-only outbound predicate. */
internal fun rootSourcePortRangeOutboundRouteInput(rule: RuleEntity, outbound: String): RootSourcePortRangeOutboundRouteInput? {
    if (!rule.enabled || rule.id !in 1..ROOT_PORT_RANGE_OUTBOUND_MAX_RULE_ID || rule.sourcePort.isBlank() ||
        rule.domains.isNotBlank() || rule.ip.isNotBlank() || rule.port.isNotBlank() || rule.network.isNotBlank() ||
        rule.source.isNotBlank() || rule.protocol.isNotBlank() || rule.ruleset.isNotBlank() ||
        rule.packages.isNotEmpty() || rule.config.isNotBlank() || !validRootRangeOutbound(outbound)) return null
    val ranges = parseRootClosedPortRanges(rule.sourcePort) ?: return null
    return RootSourcePortRangeOutboundRouteInput(rule.id, ranges, outbound)
}

private fun validRootRangeOutbound(outbound: String): Boolean =
    outbound.isNotBlank() && outbound.trim() == outbound && outbound != "block" && outbound.length <= 256 &&
        outbound.none { it.isISOControl() || it == '/' || it == '\\' }

/** Mirrors ConfigBuilder's comma/newline splitting; reject open, malformed, zero and reversed bounds. */
internal fun parseRootClosedPortRanges(raw: String): List<String>? {
    if (raw.length > 4096) return null
    val ranges = raw.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }
    if (ranges.isEmpty() || ranges.size > 256) return null
    for (range in ranges) {
        val bounds = range.split(':')
        if (bounds.size != 2 || bounds.any { it.isEmpty() || it.any { c -> c !in '0'..'9' } }) return null
        val first = bounds[0].toIntOrNull() ?: return null
        val last = bounds[1].toIntOrNull() ?: return null
        if (first !in 1..65535 || last !in first..65535) return null
    }
    return ranges
}

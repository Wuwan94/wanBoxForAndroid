// @author 雾晚
package io.nekohasekai.sagernet.bg

import com.google.gson.JsonObject
import java.net.URI

internal const val ROOT_REMOTE_RULE_SET_MARKER = "_wanbox_root_remote_rule_set_v46"

/** Transfer only the exact remote binary RuleSet shape emitted by generateRemoteRuleSet. */
internal fun projectRootRemoteRuleSets(config: JsonObject): Boolean {
    val ruleSets = config.getAsJsonObject("route")?.getAsJsonArray("rule_set") ?: return false
    var moved = false
    for (index in 0 until ruleSets.size()) {
        val entry = ruleSets[index]
        if (!entry.isJsonObject) continue
        val item = entry.asJsonObject
        if (item.keySet() != setOf("type", "tag", "format", "url", "update_interval")) continue
        if (item.get("type")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString != "remote") continue
        if (item.get("format")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString != "binary") continue
        val tag = item.get("tag")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString ?: continue
        val url = item.get("url")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString ?: continue
        val interval = item.get("update_interval")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString ?: continue
        if (!validGeneratedRemoteRuleSetTag(tag) || !validGeneratedRemoteRuleSetURL(url) ||
            interval.toByteArray(Charsets.UTF_8).size > 64 || interval.any { it == '\\' || it.isISOControl() }) continue
        ruleSets.set(index, JsonObject().apply {
            add(ROOT_REMOTE_RULE_SET_MARKER, JsonObject().apply {
                addProperty("index", index)
                addProperty("tag", tag)
                addProperty("url", url)
                addProperty("updateInterval", interval)
            })
        })
        moved = true
    }
    return moved
}

private fun validGeneratedRemoteRuleSetTag(tag: String): Boolean =
    tag.isNotBlank() && tag.length <= 256 && tag.first().isLetterOrDigit() &&
        tag.all { it.isLetterOrDigit() || it == '.' || it == '_' || it == '-' }

private fun validGeneratedRemoteRuleSetURL(raw: String): Boolean {
    if (raw.isBlank() || raw.length > 4096 || raw != raw.trim() || raw.any { it.isWhitespace() || it.isISOControl() }) return false
    return try {
        val uri = URI(raw)
        (uri.scheme == "http" || uri.scheme == "https") && !uri.host.isNullOrBlank() && uri.rawFragment == null
    } catch (_: Exception) {
        false
    }
}

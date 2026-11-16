package io.nekohasekai.sagernet.group.editor

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.math.BigDecimal
import java.net.URI

/** Node IDs and container IDs are independent database namespaces. */
sealed class EntityId {
    data class Node(val id: Long) : EntityId()
    data class Group(val id: Long) : EntityId()
}
enum class GroupMode { SELECTOR, URL_TEST }
data class UrlTestParameters(val url: String, val intervalSeconds: Long, val toleranceMs: Int,
    val idleTimeout: String, val interruptExisting: Boolean)
data class GroupDraft(val id: Long, val name: String, val mode: GroupMode, val members: List<Long>,
    val parameters: UrlTestParameters) {
    fun normalized() = copy(name = name.trim(), members = members.toList(),
        parameters = parameters.copy(url = parameters.url.trim(), idleTimeout = parameters.idleTimeout.trim()))
}

object GroupEditor {
    data class Issue(val field: String, val code: String, val member: EntityId? = null)
    fun identityError(original: GroupDraft, edited: GroupDraft): String? =
        if (original.id != edited.id || original.name != edited.normalized().name || original.mode != edited.mode)
            "identity_locked" else null

    fun validate(draft: GroupDraft, existing: Set<EntityId>, graph: Map<EntityId, List<EntityId>>,
        occupiedTags: Set<String>): List<Issue> = buildList {
        val normalized = draft.normalized()
        if (normalized.name.isEmpty()) add(Issue("name", "name_required"))
        if (normalized.name in occupiedTags) add(Issue("name", "duplicate_tag"))
        val parameters = normalized.parameters
        if (parameters.url.isNotEmpty() && !validUrl(parameters.url)) add(Issue("url", "invalid_url"))
        if (parameters.intervalSeconds < 10) add(Issue("interval", "minimum_10_seconds"))
        if (parameters.toleranceMs < 0) add(Issue("tolerance", "nonnegative_required"))
        if (durationNanos(parameters.idleTimeout) == null) add(Issue("idleTimeout", "invalid_duration"))
        else if (durationNanos(parameters.idleTimeout)!! < 1_800_000_000_000L.toBigDecimal())
            add(Issue("idleTimeout", "minimum_30_minutes"))
        if (parameters.interruptExisting) add(Issue("interruptExisting", "interrupt_locked_off"))
        if (normalized.members.isEmpty()) add(Issue("members", "members_required"))
        val seen = hashSetOf<Long>()
        val root = EntityId.Group(draft.id)
        val candidateGraph = graph + (root to normalized.members.map { EntityId.Node(it) })
        normalized.members.forEachIndexed { index, id ->
            val entity = EntityId.Node(id)
            val field = "members[$index]"
            if (!seen.add(id)) add(Issue(field, "duplicate_member", entity))
            if (entity !in existing) add(Issue(field, "missing_entity", entity))
            val visiting = hashSetOf<EntityId>(root)
            val visited = hashSetOf<EntityId>()
            fun visit(current: EntityId): Boolean {
                if (current in visiting) { add(Issue(field, "cycle", current)); return false }
                if (!visited.add(current)) return true
                if (current !in existing) { add(Issue(field, "missing_entity", current)); return false }
                visiting += current
                val valid = candidateGraph[current].orEmpty().all(::visit)
                visiting -= current
                return valid
            }
            visit(entity)
        }
    }

    /** Whole-input reference check, preserving owner namespace and edge index. */
    fun referenceIssues(existing: Set<EntityId>, graph: Map<EntityId, List<EntityId>>): List<Issue> = buildList {
        graph.forEach { (owner, children) ->
            children.forEachIndexed { index, child ->
                val field = when (owner) {
                    is EntityId.Node -> "node[${owner.id}].references[$index]"
                    is EntityId.Group -> "group[${owner.id}].members[$index]"
                }
                if (child !in existing) add(Issue(field, "missing_entity", child))
            }
        }
    }

    private fun validUrl(value: String): Boolean = runCatching {
        val uri = URI(value)
        uri.scheme.lowercase() in setOf("http", "https") && !uri.host.isNullOrEmpty()
    }.getOrDefault(false)

    /** Go time.ParseDuration units, bounded to the core's signed nanosecond range. */
    internal fun durationNanos(value: String): BigDecimal? {
        if (value == "0") return BigDecimal.ZERO
        val token = Regex("([0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(ns|us|µs|μs|ms|s|m|h)")
        var end = 0
        var total = BigDecimal.ZERO
        val factors = mapOf("ns" to 1L, "us" to 1000L, "µs" to 1000L, "μs" to 1000L,
            "ms" to 1_000_000L, "s" to 1_000_000_000L, "m" to 60_000_000_000L, "h" to 3_600_000_000_000L)
        if (value.length > 128) return null
        for (match in token.findAll(value)) {
            if (match.range.first != end) return null
            total += match.groupValues[1].toBigDecimal() * factors.getValue(match.groupValues[2]).toBigDecimal()
            end = match.range.last + 1
        }
        return total.takeIf { end > 0 && end == value.length && total <= Long.MAX_VALUE.toBigDecimal() }
    }

    /** Patch a portable effective snapshot without reading live App settings. */
    fun patchSnapshot(baseline: JsonObject, draft: GroupDraft, runtimeTag: String, inheritedUrl: String, selectedProfileId: Long? = null): JsonObject {
        val encoded = baseline.getAsJsonObject("files")?.get("files/profile_tags.json")?.asString
            ?: throw java.io.IOException("baseline_member_tags_missing")
        val tags = com.google.gson.JsonParser.parseString(String(java.util.Base64.getDecoder().decode(encoded), Charsets.UTF_8))
            .asJsonObject.entrySet().associate { (id, entry) -> id.toLong() to entry.asJsonObject["tag"].asString }
        val config = baseline["config"].asJsonObject
        return baseline.deepCopy().apply {
            add("config", patchOutbounds(config, draft, runtimeTag, tags, inheritedUrl, selectedProfileId?.let { tags[it] }))
        }
    }

    /** Copy-on-write, preserving every partition except outbounds. */
    fun patchOutbounds(config: JsonObject, draft: GroupDraft, runtimeTag: String,
        memberTags: Map<Long, String>, inheritedUrl: String, defaultTag: String? = null): JsonObject {
        val result = config.deepCopy()
        val outbounds = result.getAsJsonArray("outbounds") ?: throw IllegalArgumentException("outbounds_missing")
        val tags = outbounds.map { it.asJsonObject["tag"]?.asString ?: error("tag_missing") }
        require(tags.size == tags.toSet().size) { "duplicate_tag" }
        val available = tags + result.getAsJsonArray("endpoints").orEmpty().map { it.asJsonObject["tag"].asString }
        val selectedTags = draft.members.map { requireNotNull(memberTags[it]) { "missing_member_tag" } }
        require(selectedTags.all { it in available }) { "missing_member_tag" }
        require(selectedTags.distinct().size == selectedTags.size && selectedTags.isNotEmpty()) { "invalid_members" }
        val index = tags.indexOf(runtimeTag)
        if (index >= 0) require(outbounds[index].asJsonObject["type"]?.asString in setOf("selector", "urltest")) { "duplicate_tag" }
        require(runtimeTag !in selectedTags) { "duplicate_tag" }
        val previousDefault = if (index >= 0) outbounds[index].asJsonObject["default"]?.asString else null
        val parameters = clampUrlTestParams(draft.parameters)
        val replacement = JsonObject().apply {
            addProperty("tag", runtimeTag)
            addProperty("type", if (draft.mode == GroupMode.URL_TEST) "urltest" else "selector")
            add("outbounds", JsonArray().apply { selectedTags.forEach { add(it) } })
            if (draft.mode == GroupMode.URL_TEST) {
                addProperty("url", parameters.url.ifBlank { inheritedUrl })
                addProperty("interval", "${parameters.intervalSeconds}s")
                addProperty("tolerance", parameters.toleranceMs)
                addProperty("idle_timeout", parameters.idleTimeout)
                addProperty("interrupt_exist_connections", parameters.interruptExisting)
            } else {
                (defaultTag ?: previousDefault)?.takeIf { it in selectedTags }?.let { addProperty("default", it) }
            }
        }
        if (index >= 0) outbounds.set(index, replacement) else outbounds.add(replacement)
        return result
    }
    private fun JsonArray?.orEmpty(): List<com.google.gson.JsonElement> = this?.toList().orEmpty()
}

/** One stability policy for the normal builder AND editor snapshot patches. */
internal fun clampUrlTestParams(parameters: UrlTestParameters): UrlTestParameters {
    val idle = parameters.idleTimeout.trim().let { if (it.isNotEmpty() && it.all(Char::isDigit)) "${it}s" else it }
    return parameters.copy(intervalSeconds = parameters.intervalSeconds.coerceAtLeast(10),
        idleTimeout = if ((GroupEditor.durationNanos(idle) ?: java.math.BigDecimal.ZERO) <
            1_800_000_000_000L.toBigDecimal()) "30m" else idle, interruptExisting = false)
}

/** A deliberately selected subset follows node deletion; an empty subset has no override. */
internal fun withoutDeletedMembers(draft: GroupDraft, deleted: Set<Long>): GroupDraft? =
    draft.copy(members = draft.members.filterNot { it in deleted }).takeIf { it.members.isNotEmpty() }

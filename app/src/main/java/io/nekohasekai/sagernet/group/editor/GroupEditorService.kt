package io.nekohasekai.sagernet.group.editor

import io.nekohasekai.sagernet.database.*
import io.nekohasekai.sagernet.database.preference.PublicDatabase
import io.nekohasekai.sagernet.fmt.KryoConverters
import io.nekohasekai.sagernet.fmt.internal.BalancerBean
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest

/** Reads local entities, never Clash API display labels. No write occurs while editing. */
object GroupEditorService {
    fun load(id: Long): GroupDraft {
        GroupEditorStore.read(id)?.let { return it }
        val group = SagerDatabase.groupDao.getById(id) ?: error("group_missing")
        return GroupDraft(id, group.displayName(), if (DataStore.isGroupUrlTest(id)) GroupMode.URL_TEST else GroupMode.SELECTOR,
            SagerDatabase.proxyDao.getByGroup(id).map { it.id },
            UrlTestParameters(DataStore.configurationStore.getString("group_${id}_urlTestUrl", "").orEmpty(),
                DataStore.groupUrlTestInterval(id), DataStore.groupUrlTestTolerance(id),
                DataStore.groupUrlTestIdleTimeout(id), DataStore.groupUrlTestInterrupt(id)))
    }

    fun validate(draft: GroupDraft): List<GroupEditor.Issue> {
        val profiles = SagerDatabase.proxyDao.getAll()
        if (DataStore.isGroupLoadBalance(draft.id)) return listOf(GroupEditor.Issue("name", "legacy_load_balance"))
        val groups = SagerDatabase.groupDao.allGroups()
        val existing = profiles.map { EntityId.Node(it.id) }.toSet() + groups.map { EntityId.Group(it.id) }
        val graph = mutableMapOf<EntityId, List<EntityId>>()
        profiles.forEach { profile ->
            val chain = profile.chainBean
            val balancer = profile.balancerBean
            val children = when {
                chain != null -> chain.proxies.map { EntityId.Node(it) }
                balancer != null && balancer.balancerType == BalancerBean.TYPE_LIST -> balancer.proxies.map { EntityId.Node(it) }
                balancer != null -> (balancer.targetGroupIds.takeIf { it.isNotEmpty() }
                    ?: listOf(balancer.targetGroupId)).map { EntityId.Group(it) }
                else -> emptyList()
            }
            val owner = SagerDatabase.groupDao.getById(profile.groupId)
            val attached = listOfNotNull(owner?.frontProxy?.takeIf { it > 0 }, owner?.landingProxy?.takeIf { it > 0 },
                balancer?.frontProxy?.takeIf { it > 0 }, balancer?.landingProxy?.takeIf { it > 0 })
                .map { EntityId.Node(it) }.filter { it.id != profile.id }
            graph[EntityId.Node(profile.id)] = children + attached
        }
        groups.forEach { group -> graph[EntityId.Group(group.id)] =
            (GroupEditorStore.read(group.id)?.members ?: profiles.filter { it.groupId == group.id }.map { it.id })
                .map { EntityId.Node(it) } }
        val occupied = buildSet {
            addAll(setOf("proxy", "direct", "block", "bypass", "fragment", "mixed"))
            addAll(groups.filter { it.id != draft.id }.mapNotNull { it.name?.trim() })
            addAll(profiles.map { "g-${it.id}" })
            addAll(profiles.mapNotNull { it.displayName().trim().takeIf(String::isNotEmpty) })
        }
        val errors = GroupEditor.validate(draft, existing, graph, occupied).toMutableList()
        // Inspect every edge, including nodes hidden by a saved member list.
        val candidateGraph = graph + (EntityId.Group(draft.id) to draft.members.map { EntityId.Node(it) })
        errors += GroupEditor.referenceIssues(existing + EntityId.Group(draft.id), candidateGraph)
        val original = groups.firstOrNull { it.id == draft.id }?.let { load(it.id) }
        if (original != null && GroupEditor.identityError(original, draft) != null)
            errors += GroupEditor.Issue("name", "identity_locked")
        return errors
    }

    /** Conservative content version: inputs are hashed with lengths, never logged or persisted. */
    fun inputVersion(): String {
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { data ->
            fun bytes(value: ByteArray) { data.writeInt(value.size); data.write(value) }
            SagerDatabase.proxyDao.getAll().sortedBy { it.id }.forEach { bytes(KryoConverters.serialize(it)) }
            // Gson retains group mode/selector/front/landing fields not all present in legacy Kryo exports.
            val gson = com.google.gson.Gson()
            SagerDatabase.groupDao.allGroups().sortedBy { it.id }.forEach { bytes(gson.toJson(it).toByteArray()) }
            SagerDatabase.rulesDao.allRules().sortedBy { it.id }.forEach { bytes(gson.toJson(it).toByteArray()) }
            PublicDatabase.kvPairDao.all().sortedBy { it.key }.forEach {
                bytes(it.key.toByteArray()); data.writeInt(it.valueType); bytes(it.value)
            }
        }
        return MessageDigest.getInstance("SHA-256").digest(output.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}

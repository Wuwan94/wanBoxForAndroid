package io.nekohasekai.sagernet.group.editor

import com.google.gson.Gson
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyGroup

/** Only explicitly accepted drafts reach configurationStore; UI edits stay in memory. */
object GroupEditorStore {
    private val gson = Gson()

    /** The draft exactly as saved (order + URLTest parameters). Never queries SagerDatabase, so
     * callers inside a snapshot transaction or on the main thread do no nested I/O. */
    fun read(id: Long): GroupDraft? = DataStore.configurationStore.getString("group_${id}_editor")
        ?.let { gson.fromJson(it, GroupDraft::class.java).normalized() }

    /** Saved drafts reconciled with database ownership: the name comes from the group row and the
     * members are exactly the nodes whose `groupId` is that group, in saved order then database order. */
    fun effectiveDrafts(groups: List<ProxyGroup>, profiles: List<ProxyEntity>): Map<Long, GroupDraft> {
        val owned = profiles.sortedBy { it.userOrder }.groupBy({ it.groupId }, { it.id })
        return groups.mapNotNull { group ->
            read(group.id)?.let { saved -> GroupMembership.reconcile(saved, group.displayName(), owned[group.id].orEmpty()) }
                ?.let { group.id to it }
        }.toMap()
    }

    /** Deleted nodes leave every saved order; a saved order with no node left is no override. */
    fun removeDeletedMembers(ids: Set<Long>) {
        if (ids.isEmpty()) return
        val store = DataStore.configurationStore
        val keys = io.nekohasekai.sagernet.database.preference.PublicDatabase.kvPairDao.all()
            .map { it.key }.filter { it.startsWith("group_") && it.endsWith("_editor") }
        keys.forEach { key ->
            val id = key.removePrefix("group_").removeSuffix("_editor").toLongOrNull() ?: return@forEach
            val old = read(id) ?: return@forEach
            val next = withoutDeletedMembers(old, ids)
            if (next == null) store.remove(key)
            else if (next != old) write(next)
        }
    }
    fun write(draft: GroupDraft) {
        val saved = draft.normalized()
        io.nekohasekai.sagernet.database.preference.PublicDatabase.instance.runInTransaction {
            DataStore.configurationStore.putString("group_${saved.id}_editor", gson.toJson(saved))
            DataStore.setGroupIsUrlTest(saved.id, saved.mode == GroupMode.URL_TEST)
            DataStore.setGroupIsLoadBalance(saved.id, false)
            DataStore.setGroupUrlTestUrl(saved.id, saved.parameters.url)
            DataStore.setGroupUrlTestInterval(saved.id, saved.parameters.intervalSeconds)
            DataStore.setGroupUrlTestTolerance(saved.id, saved.parameters.toleranceMs)
            DataStore.setGroupUrlTestIdleTimeout(saved.id, saved.parameters.idleTimeout)
            DataStore.setGroupUrlTestInterruptExist(saved.id, saved.parameters.interruptExisting)
        }
    }
}

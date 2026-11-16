package io.nekohasekai.sagernet.group.editor

import com.google.gson.Gson
import io.nekohasekai.sagernet.database.DataStore

/** Only explicitly accepted drafts reach configurationStore; UI edits stay in memory. */
object GroupEditorStore {
    private val gson = Gson()
    fun read(id: Long): GroupDraft? = DataStore.configurationStore.getString("group_${id}_editor")
        ?.let {
            val draft = gson.fromJson(it, GroupDraft::class.java).normalized()
            // Existing group identity has one source of truth: the database list.
            // Normalize legacy drafts that were saved before name locking existed.
            val group = io.nekohasekai.sagernet.database.SagerDatabase.groupDao.getById(id)
            if (group == null) draft else draft.copy(name = group.displayName())
        }
    /** Editor membership is an explicit subset, not ownership of subscription nodes.
     * Lifecycle deletion/update paths prune that subset before the next build/export. */
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

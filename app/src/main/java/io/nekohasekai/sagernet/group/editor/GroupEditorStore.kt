package io.nekohasekai.sagernet.group.editor

import com.google.gson.Gson
import io.nekohasekai.sagernet.database.DataStore

/** Only explicitly accepted drafts reach configurationStore; UI edits stay in memory. */
object GroupEditorStore {
    private val gson = Gson()
    fun read(id: Long): GroupDraft? = DataStore.configurationStore.getString("group_${id}_editor")
        ?.let { gson.fromJson(it, GroupDraft::class.java).normalized() }
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

package io.nekohasekai.sagernet.group.editor

import android.util.AtomicFile
import com.google.gson.Gson
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.database.preference.PublicDatabase
import java.io.File
import java.io.IOException

/** Cross-database undo record is durable BEFORE either store changes. */
class GroupLocalPreparation(private val draft: GroupDraft) {
    companion object {
        private val journal get() = File(SagerNet.application.filesDir, "group-editor-pending.json")
        fun pending() = journal.exists()
        data class UndoRecord(val id: Long, val existed: Boolean,
            val values: List<io.nekohasekai.sagernet.database.preference.KeyValuePair>?, val baseline: String?)
        fun undoRecord(): UndoRecord = Gson().fromJson(AtomicFile(journal).openRead().use {
            it.reader().readText()
        }, UndoRecord::class.java)
        fun restoreRecord(record: UndoRecord) {
            PublicDatabase.instance.runInTransaction {
                PublicDatabase.kvPairDao.all().filter { it.key.startsWith("group_${record.id}_") }
                    .forEach { PublicDatabase.kvPairDao.delete(it.key) }
                record.values.orEmpty().forEach { PublicDatabase.kvPairDao.put(it) }
            }
            if (!record.existed) SagerDatabase.groupDao.deleteById(record.id)
        }
        fun clearRecord() {
            AtomicFile(journal).delete()
            if (pending()) throw IOException("cleanup_required")
        }
        fun restorePendingLocal() {
            if (!pending()) return
            restoreRecord(undoRecord())
        }
    }
    private val oldValues = PublicDatabase.kvPairDao.all().filter { it.key.startsWith("group_${draft.id}_") }
    private val existed = SagerDatabase.groupDao.getById(draft.id) != null
    private var started = false
    private var originalRecord: UndoRecord? = null

    fun prepare(baselineJson: String? = null) {
        if (pending()) throw IOException("cleanup_required")
        val atomic = AtomicFile(journal)
        val output = atomic.startWrite()
        try {
            originalRecord = UndoRecord(draft.id, existed, oldValues, baselineJson)
            output.write(Gson().toJson(originalRecord).toByteArray())
            atomic.finishWrite(output)
        } catch (error: Exception) { atomic.failWrite(output); throw error }
        started = true
        if (!existed) SagerDatabase.groupDao.createGroup(ProxyGroup(
            id = draft.id, name = draft.name, isSelector = true,
            userOrder = SagerDatabase.groupDao.nextOrder() ?: 1L))
        // The coordinator owns compensation and clears the journal only after
        // both local restoration and any required module rollback are confirmed.
        GroupEditorStore.write(draft)
    }

    fun restore() {
        if (!started) return
        restoreRecord(originalRecord ?: UndoRecord(draft.id, existed, oldValues, null))
    }

    fun finish() {
        if (started) {
            clearRecord()
        }
    }
}

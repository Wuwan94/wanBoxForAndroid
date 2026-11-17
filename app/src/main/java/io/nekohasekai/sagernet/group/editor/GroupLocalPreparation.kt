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
        /** [owners] holds each moved node's previous groupId; null in journals written before preview.4. */
        data class UndoRecord(val id: Long, val existed: Boolean,
            val values: List<io.nekohasekai.sagernet.database.preference.KeyValuePair>?, val baseline: String?,
            val owners: Map<Long, Long>? = null)
        fun undoRecord(): UndoRecord = Gson().fromJson(AtomicFile(journal).openRead().use {
            it.reader().readText()
        }, UndoRecord::class.java)
        fun restoreRecord(record: UndoRecord) {
            PublicDatabase.instance.runInTransaction {
                PublicDatabase.kvPairDao.all().filter { it.key.startsWith("group_${record.id}_") }
                    .forEach { PublicDatabase.kvPairDao.delete(it.key) }
                record.values.orEmpty().forEach { PublicDatabase.kvPairDao.put(it) }
            }
            moveOwners(record.owners.orEmpty())
            if (!record.existed) SagerDatabase.groupDao.deleteById(record.id)
        }
        /** Writes node ownership in one database transaction; nodes deleted meanwhile are skipped. */
        private fun moveOwners(owners: Map<Long, Long>) {
            if (owners.isEmpty()) return
            SagerDatabase.instance.runInTransaction {
                val moved = SagerDatabase.proxyDao.getEntities(owners.keys.toList())
                    .filter { owners[it.id] != null && it.groupId != owners[it.id] }
                    .onEach { it.groupId = owners.getValue(it.id) }
                if (moved.isNotEmpty()) SagerDatabase.proxyDao.updateProxy(moved)
            }
        }
        fun clearRecord() {
            AtomicFile(journal).delete()
            if (pending()) throw IOException("group_editor_recovery_required")
        }
        fun restorePendingLocal() {
            if (!pending()) return
            restoreRecord(undoRecord())
        }
    }
    private val oldValues = PublicDatabase.kvPairDao.all().filter { it.key.startsWith("group_${draft.id}_") }
    private val existingGroup = SagerDatabase.groupDao.getById(draft.id)
    private val existed = existingGroup != null
    /** Ownership moves that make ProxyEntity.groupId match the saved members (validated earlier). */
    private val moves: Map<Long, Long> = run {
        val groups = SagerDatabase.groupDao.allGroups()
        val target = existingGroup?.let {
            GroupMembership.Target(it.id, it.type == io.nekohasekai.sagernet.GroupType.SUBSCRIPTION, it.ungrouped)
        }
        val sync = GroupMembership.plan(draft, target,
            SagerDatabase.proxyDao.getAll().associate { it.id to it.groupId }, groups.firstOrNull { it.ungrouped }?.id,
            groups.filter { it.type == io.nekohasekai.sagernet.GroupType.SUBSCRIPTION }.map { it.id }.toSet())
        if (sync.issues.isNotEmpty()) throw IOException("group_validation_failed")
        sync.moves
    }
    private val previousOwners: Map<Long, Long> = if (moves.isEmpty()) emptyMap()
        else SagerDatabase.proxyDao.getEntities(moves.keys.toList()).associate { it.id to it.groupId }
    private var started = false
    private var originalRecord: UndoRecord? = null

    fun prepare(baselineJson: String? = null) {
        if (pending()) throw IOException("group_editor_recovery_required")
        val atomic = AtomicFile(journal)
        val output = atomic.startWrite()
        try {
            originalRecord = UndoRecord(draft.id, existed, oldValues, baselineJson, previousOwners)
            output.write(Gson().toJson(originalRecord).toByteArray())
            atomic.finishWrite(output)
        } catch (error: Exception) { atomic.failWrite(output); throw error }
        started = true
        if (!existed) SagerDatabase.groupDao.createGroup(ProxyGroup(
            id = draft.id, name = draft.name, isSelector = true,
            userOrder = SagerDatabase.groupDao.nextOrder() ?: 1L))
        // The coordinator owns compensation and clears the journal only after
        // both local restoration and any required module rollback are confirmed.
        // Ownership moves first so the saved order and ProxyEntity.groupId agree (P1-3).
        moveOwners(moves)
        GroupEditorStore.write(draft)
    }

    fun restore() {
        if (!started) return
        restoreRecord(originalRecord ?: UndoRecord(draft.id, existed, oldValues, null, previousOwners))
    }

    fun finish() {
        if (started) {
            clearRecord()
        }
    }
}

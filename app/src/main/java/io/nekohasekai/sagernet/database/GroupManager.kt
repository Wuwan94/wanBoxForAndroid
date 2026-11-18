package io.nekohasekai.sagernet.database

import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.bg.SubscriptionUpdater
import io.nekohasekai.sagernet.ktx.applyDefaultValues

object GroupManager {

    interface Listener {
        suspend fun groupAdd(group: ProxyGroup)
        suspend fun groupUpdated(group: ProxyGroup)

        suspend fun groupRemoved(groupId: Long)
        suspend fun groupUpdated(groupId: Long)
    }

    interface Interface {
        suspend fun confirm(message: String): Boolean
        suspend fun alert(message: String)
        suspend fun onUpdateSuccess(
            group: ProxyGroup,
            changed: Int,
            added: List<String>,
            updated: Map<String, String>,
            deleted: List<String>,
            duplicate: List<String>,
            byUser: Boolean
        )

        suspend fun onUpdateFailure(group: ProxyGroup, message: String)
    }

    private val listeners = ArrayList<Listener>()
    var userInterface: Interface? = null

    suspend fun iterator(what: suspend Listener.() -> Unit) {
        synchronized(listeners) {
            listeners.toList()
        }.forEach { listener ->
            what(listener)
        }
    }

    fun addListener(listener: Listener) {
        synchronized(listeners) {
            listeners.add(listener)
        }
    }

    fun removeListener(listener: Listener) {
        synchronized(listeners) {
            listeners.remove(listener)
        }
    }

    suspend fun clearGroup(groupId: Long) {
        DataStore.selectedProxy = 0L
        val removed = SagerDatabase.proxyDao.getIdsByGroup(groupId).toSet()
        SagerDatabase.proxyDao.deleteAll(groupId)
        io.nekohasekai.sagernet.group.editor.GroupEditorStore.removeDeletedMembers(removed)
        iterator { groupUpdated(groupId) }
    }

    fun rearrange(groupId: Long) {
        val entities = SagerDatabase.proxyDao.getByGroup(groupId)
        for (index in entities.indices) {
            entities[index].userOrder = (index + 1).toLong()
        }
        SagerDatabase.proxyDao.updateProxy(entities)
    }

    suspend fun postUpdate(group: ProxyGroup) {
        iterator { groupUpdated(group) }
    }

    suspend fun postUpdate(groupId: Long) {
        postUpdate(SagerDatabase.groupDao.getById(groupId) ?: return)
    }

    suspend fun postReload(groupId: Long) {
        iterator { groupUpdated(groupId) }
    }

    suspend fun createGroup(group: ProxyGroup): ProxyGroup {
        group.userOrder = SagerDatabase.groupDao.nextOrder() ?: 1
        group.id = SagerDatabase.groupDao.createGroup(group.applyDefaultValues())
        iterator { groupAdd(group) }
        if (group.type == GroupType.SUBSCRIPTION) {
            SubscriptionUpdater.reconfigureUpdater()
        }
        return group
    }

    suspend fun updateGroup(group: ProxyGroup) {
        SagerDatabase.groupDao.updateGroup(group)
        iterator { groupUpdated(group) }
        if (group.type == GroupType.SUBSCRIPTION) {
            SubscriptionUpdater.reconfigureUpdater()
        }
    }

    /** N6：删除被路由规则引用的策略组时阻断，并定位到该组与首条引用规则。 */
    class GroupReferencedByRoutesException(
        val groupId: Long,
        val ruleId: Long,
        val ruleName: String,
    ) : IllegalStateException("route_group_referenced")

    suspend fun deleteGroup(groupId: Long) {
        val removed = SagerDatabase.proxyDao.getIdsByGroup(groupId).toSet()
        // 检查与删除在同一事务内：检查通过后组行与节点行一起删，检查失败则一行不动。
        SagerDatabase.instance.runInTransaction(java.util.concurrent.Callable {
            val blocking = io.nekohasekai.sagernet.route.RouteGroupTarget.firstBlockingReference(
                SagerDatabase.rulesDao.allRules(), setOf(groupId))
            if (blocking != null) throw GroupReferencedByRoutesException(
                blocking.groupId, blocking.rule.id, blocking.rule.displayName())
            SagerDatabase.groupDao.deleteById(groupId)
            SagerDatabase.proxyDao.deleteByGroup(groupId)
        })
        io.nekohasekai.sagernet.group.editor.GroupEditorStore.removeDeletedMembers(removed)
        iterator { groupRemoved(groupId) }
        SubscriptionUpdater.reconfigureUpdater()
    }

    suspend fun deleteGroup(group: List<ProxyGroup>) {
        val removed = group.flatMap { SagerDatabase.proxyDao.getIdsByGroup(it.id) }.toSet()
        SagerDatabase.instance.runInTransaction(java.util.concurrent.Callable {
            val blocking = io.nekohasekai.sagernet.route.RouteGroupTarget.firstBlockingReference(
                SagerDatabase.rulesDao.allRules(), group.map { it.id }.toSet())
            if (blocking != null) throw GroupReferencedByRoutesException(
                blocking.groupId, blocking.rule.id, blocking.rule.displayName())
            SagerDatabase.groupDao.deleteGroup(group)
            SagerDatabase.proxyDao.deleteByGroup(group.map { it.id }.toLongArray())
        })
        io.nekohasekai.sagernet.group.editor.GroupEditorStore.removeDeletedMembers(removed)
        for (proxyGroup in group) iterator { groupRemoved(proxyGroup.id) }
        SubscriptionUpdater.reconfigureUpdater()
    }

}
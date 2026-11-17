package io.nekohasekai.sagernet.group.editor

/**
 * One membership truth: `ProxyEntity.groupId` decides which nodes belong to a group.
 * A saved editor draft only orders those nodes and carries URLTest parameters, so
 * subscription updates, node moves, group counts, runtime and export builds all agree.
 */
internal object GroupMembership {
    /** The saved group's kind decides where ownership may move. `null` is a group being created. */
    data class Target(val id: Long, val subscription: Boolean, val ungrouped: Boolean)

    /** `moves` maps node id to its new `groupId`; any issue rejects the whole save. */
    data class Sync(val moves: Map<Long, Long>, val issues: List<GroupEditor.Issue>)

    /** Saved order for nodes the group still owns, then newly owned nodes in database order. */
    fun effectiveOrder(savedOrder: List<Long>, ownedInOrder: List<Long>): List<Long> {
        val owned = ownedInOrder.toSet()
        val kept = savedOrder.distinct().filter { it in owned }
        val keptSet = kept.toSet()
        return kept + ownedInOrder.filterNot { it in keptSet }
    }

    /** A draft without owned nodes is no override; the group name comes from the database. */
    fun reconcile(draft: GroupDraft, groupName: String, ownedInOrder: List<Long>): GroupDraft? {
        val members = effectiveOrder(draft.members, ownedInOrder)
        if (members.isEmpty()) return null
        return draft.copy(name = groupName, members = members).normalized()
    }

    /**
     * Ownership changes that make the database match the saved members.
     * Subscription groups are owned by their subscription: reorder only, because a node moved
     * in would be deleted as an orphan and a node moved out would be re-inserted by the next update.
     * Nodes dropped from an ordinary group go to the ungrouped group; the ungrouped group itself
     * has nowhere to drop nodes to.
     */
    fun plan(draft: GroupDraft, target: Target?, ownerOf: Map<Long, Long>, ungroupedId: Long?): Sync {
        val members = draft.members
        val memberSet = members.toSet()
        val moves = linkedMapOf<Long, Long>()
        val issues = mutableListOf<GroupEditor.Issue>()
        members.forEachIndexed { index, id ->
            if (ownerOf[id] == draft.id) return@forEachIndexed
            if (target?.subscription == true) issues += GroupEditor.Issue("members[$index]", "subscription_member_locked", EntityId.Node(id))
            else moves[id] = draft.id
        }
        val dropped = ownerOf.filter { (id, owner) -> owner == draft.id && id !in memberSet }.keys.sorted()
        dropped.forEach { id ->
            when {
                target?.subscription == true -> issues += GroupEditor.Issue("members", "subscription_member_locked", EntityId.Node(id))
                target?.ungrouped == true || ungroupedId == null || ungroupedId == draft.id ->
                    issues += GroupEditor.Issue("members", "ungrouped_member_locked", EntityId.Node(id))
                else -> moves[id] = ungroupedId
            }
        }
        return if (issues.isEmpty()) Sync(moves, emptyList()) else Sync(emptyMap(), issues)
    }
}

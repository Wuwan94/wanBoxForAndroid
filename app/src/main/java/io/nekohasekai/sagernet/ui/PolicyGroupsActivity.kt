package io.nekohasekai.sagernet.ui

import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.bg.RootModuleClient
import io.nekohasekai.sagernet.bg.proto.BalancerStats
import io.nekohasekai.sagernet.bg.proto.PolicyGroup
import io.nekohasekai.sagernet.bg.proto.PolicyGroupControllerClient
import io.nekohasekai.sagernet.bg.proto.PolicyGroupType
import io.nekohasekai.sagernet.bg.proto.PolicyMemberNameResolver
import io.nekohasekai.sagernet.bg.proto.ThinControllerDelayClient
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.fmt.BalancerLockState
import io.nekohasekai.sagernet.fmt.BalancerMemberRef
import io.nekohasekai.sagernet.fmt.resolveBalancerMembers
import io.nekohasekai.sagernet.fmt.resolveSnapshotMemberId
import io.nekohasekai.sagernet.fmt.snapshotTagForMember
import io.nekohasekai.sagernet.ktx.readableMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/** This page is a view of the running core, never a reconstruction of database groups. */
class PolicyGroupsActivity : ThemedActivity(R.layout.layout_policy_groups) {
    private val controller = PolicyGroupControllerClient()
    private val delayClient = ThinControllerDelayClient()
    private lateinit var content: LinearLayout
    private lateinit var toolbar: MaterialToolbar
    private var operation: Job? = null
    private var measurement: Job? = null
    private var groups = emptyList<PolicyGroup>()
    private var skipped = 0
    private var busy = false
    private val expanded = mutableSetOf<String>()
    private val results = mutableMapOf<Pair<String, String>, String>()
    private val resultViews = mutableMapOf<Pair<String, String>, MutableList<TextView>>()
    private lateinit var names: PolicyMemberNameResolver
    // B7b/O7：严格手动锁定与成员统计（统计页是锁定的唯一入口）。
    // tagsById/tagToProfileId 来自 `node tags`（运行配置的 profile→tag）；
    // balancerMemberNames 是各策略组已解析成员的 id→展示名（隔离链成员不在
    // profileTagMap 里时，靠展示名精确匹配回退解析 tag，见 BalancerManualLock）。
    private var locks = BalancerLockState.empty()
    private var tagsById = emptyMap<Long, String>()
    private var tagToProfileId = emptyMap<String, Long>()
    private var balancerMemberNames = emptyMap<Long, Map<Long, String>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        content = findViewById(R.id.policy_content)
        toolbar = findViewById(R.id.policy_toolbar)
        toolbar.setTitle(R.string.policy_groups)
        toolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
        toolbar.setNavigationOnClickListener { finish() }
        toolbar.menu.add(R.string.policy_refresh).setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS)
        val editItem = toolbar.menu.add(R.string.group_editor_local)
        val recoveryItem = toolbar.menu.add(R.string.group_editor_recover)
        // P1-7: secondary escape hatch so a stale baseline can never lock editing forever.
        val abandonItem = toolbar.menu.add(R.string.group_editor_abandon)
        fun syncRecoveryItems() {
            val pending = io.nekohasekai.sagernet.group.editor.GroupLocalPreparation.pending()
            recoveryItem.isVisible = pending
            abandonItem.isVisible = pending
        }
        syncRecoveryItems()
        toolbar.setOnMenuItemClickListener {
            if (it === recoveryItem) lifecycleScope.launch {
                try {
                    RootModuleClient.recoverGroupDraft()
                    syncRecoveryItems()
                    refresh()
                } catch (error: CancellationException) { throw error }
                catch (_: Exception) {
                    showMessage(R.string.group_editor_recovery_required, retry = false)
                    androidx.appcompat.app.AlertDialog.Builder(this@PolicyGroupsActivity)
                        .setMessage(R.string.group_editor_recovery_failed_choice)
                        .setPositiveButton(R.string.group_editor_abandon) { _, _ -> confirmAbandon { syncRecoveryItems() } }
                        .setNegativeButton(android.R.string.cancel, null).show()
                }
            } else if (it === abandonItem) confirmAbandon { syncRecoveryItems() }
            else if (it === editItem) openLocalEditor() else refresh()
            true
        }
    }

    private fun confirmAbandon(after: () -> Unit) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.group_editor_abandon)
            .setMessage(R.string.group_editor_abandon_confirm)
            .setPositiveButton(R.string.group_editor_abandon) { _, _ ->
                lifecycleScope.launch {
                    try {
                        RootModuleClient.abandonGroupDraft()
                        Toast.makeText(this@PolicyGroupsActivity, R.string.group_editor_abandon_done, Toast.LENGTH_SHORT).show()
                        refresh()
                    } catch (error: CancellationException) { throw error }
                    catch (error: Exception) {
                        content.removeAllViews()
                        content.addView(text(io.nekohasekai.sagernet.bg.RootModuleErrors.message(this@PolicyGroupsActivity, error)))
                    } finally { after() }
                }
            }.setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun openLocalEditor() {
        lifecycleScope.launch {
            val (local, loadBalanceIds) = withContext(Dispatchers.IO) {
                val local = SagerDatabase.groupDao.allGroups()
                local to local.filter { DataStore.isGroupLoadBalance(it.id) }.map { it.id }.toSet()
            }
            val entries = arrayOf(getString(R.string.group_editor_create)) + local.map { "${it.displayName()} (#${it.id})" }
            androidx.appcompat.app.AlertDialog.Builder(this@PolicyGroupsActivity)
                .setTitle(R.string.group_editor_local)
                .setItems(entries) { _, index ->
                    val id = if (index == 0) 0L else local[index - 1].id
                    val activity = if (id != 0L && id in loadBalanceIds)
                        GroupSettingsActivity::class.java else GroupEditorActivity::class.java
                    startActivity(android.content.Intent(this@PolicyGroupsActivity, activity).putExtra("id", id))
                }.setNegativeButton(android.R.string.cancel, null).show()
        }
    }

    override fun onStart() {
        super.onStart()
        refresh()
    }

    override fun onStop() {
        operation?.cancel()
        measurement?.cancel()
        super.onStop()
    }

    private fun refresh() {
        operation?.cancel()
        measurement?.cancel()
        results.clear()
        operation = lifecycleScope.launch { loadSnapshot() }
    }

    private suspend fun loadSnapshot() {
        busy = true
        showMessage(R.string.policy_loading, retry = false)
        try {
            val snapshot = withContext(Dispatchers.IO) { controller.snapshot() }
            // Names are optional decoration. Missing module metadata must not hide real members.
            val tags = try {
                val status = RootModuleClient.call("node tags")
                if (status.connected && status.runningRevision.isNotBlank()) status.profileTags else emptyMap()
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { emptyMap() }
            val profileNames = try {
                withContext(Dispatchers.IO) { SagerDatabase.proxyDao.getAll().associate { it.id to it.displayName() } }
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { emptyMap() }
            coroutineContext.ensureActive()
            groups = snapshot.groups.sortedWith(compareBy<PolicyGroup> { it.tag != "proxy" }.thenBy { it.tag })
            skipped = snapshot.skippedEntries
            names = PolicyMemberNameResolver(tags, profileNames, groups.map { it.tag }.toSet(),
                getString(R.string.policy_direct), getString(R.string.policy_block))
            // B7b/O7：锁定状态与 tag 对照随快照一起读（只读既有通道，不另开核心直连）。
            tagsById = tags
            tagToProfileId = tags.entries.associate { (id, tag) -> tag to id }
            locks = BalancerLockState.decode(DataStore.balancerManualLock)
            balancerMemberNames = try {
                withContext(Dispatchers.IO) {
                    val all = SagerDatabase.proxyDao.getAll()
                    all.filter { it.type == ProxyEntity.TYPE_BALANCER }.associate { balancer ->
                        val bean = balancer.balancerBean
                        if (bean == null) {
                            balancer.id to emptyMap<Long, String>()
                        } else {
                            val resolution = runCatching {
                                resolveBalancerMembers(bean) { gid ->
                                    runCatching { SagerDatabase.proxyDao.getByGroup(gid) }
                                        .getOrDefault(emptyList())
                                        .map { BalancerMemberRef(it.id, it.displayName()) }
                                }
                            }.getOrNull()
                            val ids = resolution?.memberIds.orEmpty()
                            val resolvedNames: Map<Long, String> = if (ids.isEmpty()) emptyMap() else runCatching {
                                SagerDatabase.proxyDao.getEntities(ids)
                                    .associate { it.id to it.displayName() }
                            }.getOrDefault(emptyMap())
                            balancer.id to resolvedNames
                        }
                    }
                }
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { emptyMap() }
            busy = false
            render()
        } catch (error: CancellationException) { throw error }
        catch (_: Exception) {
            groups = emptyList()
            busy = false
            showMessage(R.string.policy_unavailable)
        }
    }

    private fun text(value: String): TextView = TextView(this).apply {
        text = value
        setPadding(8, 12, 8, 12)
    }

    private fun button(label: String, enabled: Boolean = true, action: () -> Unit): MaterialButton =
        MaterialButton(this).apply {
            text = label
            isEnabled = enabled
            setOnClickListener { action() }
        }

    private fun showMessage(message: Int, retry: Boolean = true) {
        content.removeAllViews()
        resultViews.clear()
        content.addView(text(getString(message)))
        if (retry) content.addView(button(getString(R.string.policy_retry)) { refresh() })
    }

    // B7b/O7：快照组 tag → 策略组 profile id（只认 BalancerBean 策略组；
    // 顶层 proxy selector 与编辑器组没有策略组实体，不可锁定）。
    private fun balancerIdFor(group: PolicyGroup): Long? {
        val id = tagToProfileId[group.tag] ?: return null
        return id.takeIf { it in balancerMemberNames }
    }

    private fun render() {
        content.removeAllViews()
        resultViews.clear()
        if (groups.isEmpty()) {
            showMessage(R.string.policy_empty)
        }
        if (skipped > 0) content.addView(text(getString(R.string.policy_skipped, skipped)))
        for (group in groups) {
            val open = group.tag in expanded
            val type = when (group.type) {
                PolicyGroupType.SELECTOR -> "Selector"
                PolicyGroupType.URLTEST -> "URLTest"
                PolicyGroupType.LOADBALANCE -> "LoadBalance"
            }
            content.addView(button("${if (open) "▾" else "▸"} ${group.tag} [$type]") {
                if (!expanded.add(group.tag)) expanded.remove(group.tag)
                render()
            })
            content.addView(text(getString(R.string.policy_summary,
                group.now?.let { names.name(it) } ?: getString(R.string.policy_unknown), group.members.size)))
            if (group.type != PolicyGroupType.SELECTOR) content.addView(text(getString(R.string.policy_automatic)))
            // B7b/O7：策略组的严格手动锁定状态（只在策略组实体上出现）。
            val balancerId = balancerIdFor(group)
            val lock = balancerId?.let { locks.lockedMember(it) }
            if (balancerId != null) {
                if (lock != null) {
                    val memberNames = balancerMemberNames[balancerId].orEmpty()
                    val lockedTag = snapshotTagForMember(lock.memberId, tagsById, memberNames, group.members)
                    val lockedName = lockedTag?.let { names.name(it) }
                        ?: memberNames[lock.memberId] ?: "#${lock.memberId}"
                    content.addView(text(getString(R.string.balancer_locked_summary, lockedName)))
                    content.addView(text(getString(R.string.balancer_lock_note)))
                    if (lockedTag == null) {
                        content.addView(text(getString(R.string.balancer_locked_unavailable)).apply {
                            setTextColor(ContextCompat.getColor(this@PolicyGroupsActivity, R.color.connect_error_fill))
                        })
                    }
                    content.addView(button(getString(R.string.balancer_resume_auto),
                        !busy && measurement?.isActive != true) { resumeAuto(balancerId) })
                } else {
                    content.addView(text(getString(R.string.balancer_lock_hint)))
                }
            }
            content.addView(button(getString(R.string.policy_check_all), !busy && measurement?.isActive != true) { measure(group) })
            if (!open) continue
            if (group.members.isEmpty()) content.addView(text(getString(R.string.policy_no_members)))
            for (member in group.members) {
                val key = group.tag to member
                val label = text(memberLabel(group, member))
                resultViews.getOrPut(key) { mutableListOf() }.add(label)
                if (group.type == PolicyGroupType.SELECTOR) {
                    label.isEnabled = !busy && measurement?.isActive != true
                    label.isClickable = label.isEnabled
                    label.isFocusable = label.isEnabled
                    label.setOnClickListener { if (label.isEnabled) select(group, member) }
                } else if (balancerId != null) {
                    // B7b/O7：自动组点成员即锁定该节点（解析不到 id 的成员行不可点，
                    // 不得猜 id）；锁定后重建配置，核心不再自动切换。
                    val memberId = resolveSnapshotMemberId(
                        member, tagToProfileId, balancerMemberNames[balancerId].orEmpty())
                    if (memberId != null) {
                        label.isEnabled = !busy && measurement?.isActive != true
                        label.isClickable = label.isEnabled
                        label.isFocusable = label.isEnabled
                        label.setOnClickListener { if (label.isEnabled) lockMember(balancerId, member, memberId) }
                    }
                }
                content.addView(label)
            }
        }
    }

    // B7b/O7：锁定只在用户确认后生效；落盘后重建配置（urltest/loadbalance →
    // selector，default = 锁定成员），未连接时只落盘、下次连接时生效。
    private fun lockMember(balancerId: Long, member: String, memberId: Long) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.balancer_lock_member)
            .setMessage(getString(R.string.balancer_lock_confirm, names.name(member)))
            .setPositiveButton(R.string.balancer_lock_member) { _, _ ->
                locks = locks.lock(balancerId, memberId)
                DataStore.balancerManualLock = locks.encode()
                render()
                Toast.makeText(this@PolicyGroupsActivity, R.string.policy_lock_applied, Toast.LENGTH_SHORT).show()
                if (DataStore.serviceState.connected) SagerNet.reloadService()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun resumeAuto(balancerId: Long) {
        locks = locks.resumeAuto(balancerId)
        DataStore.balancerManualLock = locks.encode()
        render()
        Toast.makeText(this, R.string.policy_resume_applied, Toast.LENGTH_SHORT).show()
        if (DataStore.serviceState.connected) SagerNet.reloadService()
    }

    private fun memberLabel(group: PolicyGroup, member: String): String = buildString {
        if (group.now == member) append(getString(R.string.policy_selected)).append(" · ")
        // B7b/O7：锁定标记与成员统计（失败数/最近报错）；统计来自进程内
        // BalancerMemberStats（测速与探测失败都记，不分锁定与否）。
        val balancerId = balancerIdFor(group)
        val lock = balancerId?.let { locks.lockedMember(it) }
        if (lock != null) {
            val memberNames = balancerId?.let { balancerMemberNames[it] }.orEmpty()
            val memberId = resolveSnapshotMemberId(member, tagToProfileId, memberNames)
            if (memberId == lock.memberId) append(getString(R.string.balancer_locked_short)).append(" · ")
        }
        append(names.name(member))
        val stat = BalancerStats.store.stat(group.tag, member)
        if (stat != null && stat.failures > 0) {
            append(" · ").append(getString(R.string.policy_stats_failures, stat.failures))
            stat.lastError?.let { append(" · ").append(getString(R.string.policy_stats_last_error, it)) }
        }
        results[group.tag to member]?.let { append(" · ").append(it) }
    }

    private fun select(group: PolicyGroup, member: String) {
        if (busy || measurement?.isActive == true) return
        busy = true
        render()
        operation = lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { controller.select(group, member) }
                Toast.makeText(this@PolicyGroupsActivity, R.string.policy_switch_success, Toast.LENGTH_SHORT).show()
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) {
                Toast.makeText(this@PolicyGroupsActivity, R.string.policy_switch_failed, Toast.LENGTH_LONG).show()
            }
            // Both outcomes reload server truth; no optimistic local selection.
            results.clear()
            loadSnapshot()
        }
    }

    private fun measure(group: PolicyGroup) {
        if (busy || measurement?.isActive == true) return
        group.members.forEach { results.remove(group.tag to it) }
        measurement = lifecycleScope.launch {
            try {
                val target = withContext(Dispatchers.IO) {
                    runCatching { DataStore.connectionTestURL }.getOrNull()?.takeIf { it.isNotBlank() }
                }
                    ?: "https://www.gstatic.com/generate_204"
                for (member in group.members) {
                    // B7b/O7：一键全检的每次结果都记入 BalancerMemberStats（成功记延迟、
                    // 失败记失败数与报错），锁定期间的失败同样计入，不得因锁定跳过。
                    val delayMs = try {
                        withContext(Dispatchers.IO) { delayClient.measure(member, target, 5_000) }
                    } catch (error: CancellationException) { throw error }
                    catch (error: Exception) {
                        BalancerStats.store.recordFailure(group.tag, member, error.readableMessage)
                        null
                    }
                    coroutineContext.ensureActive()
                    val result = if (delayMs != null) {
                        BalancerStats.store.recordSuccess(group.tag, member, delayMs)
                        getString(R.string.policy_latency, delayMs)
                    } else {
                        getString(R.string.policy_failed)
                    }
                    results[group.tag to member] = result
                    resultViews[group.tag to member]?.forEach { it.text = memberLabel(group, member) }
                }
            } finally {
                // onStop cancels this job; never write UI from cancellation cleanup.
                if (coroutineContext[Job]?.isActive == true) {
                    measurement = null
                    render()
                }
            }
        }
        render()
    }
}

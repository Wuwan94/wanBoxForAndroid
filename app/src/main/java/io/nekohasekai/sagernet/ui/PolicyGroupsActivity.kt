package io.nekohasekai.sagernet.ui

import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.bg.RootModuleClient
import io.nekohasekai.sagernet.bg.proto.PolicyGroup
import io.nekohasekai.sagernet.bg.proto.PolicyGroupControllerClient
import io.nekohasekai.sagernet.bg.proto.PolicyGroupType
import io.nekohasekai.sagernet.bg.proto.PolicyMemberNameResolver
import io.nekohasekai.sagernet.bg.proto.ThinControllerDelayClient
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.SagerDatabase
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
        recoveryItem.isVisible = io.nekohasekai.sagernet.group.editor.GroupLocalPreparation.pending()
        toolbar.setOnMenuItemClickListener {
            if (it === recoveryItem) lifecycleScope.launch {
                try {
                    RootModuleClient.recoverGroupDraft()
                    recoveryItem.isVisible = false
                    refresh()
                } catch (error: CancellationException) { throw error }
                catch (_: Exception) { showMessage(R.string.group_editor_recovery_required, retry = false) }
            } else if (it === editItem) openLocalEditor() else refresh()
            true
        }
        val scroll = findViewById<View>(R.id.policy_scroll)
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.updatePadding(left = bars.left, right = bars.right, bottom = bars.bottom)
            insets
        }
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
            content.addView(button(getString(R.string.policy_measure), !busy && measurement?.isActive != true) { measure(group) })
            if (!open) continue
            if (group.members.isEmpty()) content.addView(text(getString(R.string.policy_no_members)))
            for (member in group.members) {
                val key = group.tag to member
                val label = text(memberLabel(group, member))
                resultViews.getOrPut(key) { mutableListOf() }.add(label)
                // Automatic groups deliberately expose no click handler.
                if (group.type == PolicyGroupType.SELECTOR) {
                    label.isEnabled = !busy && measurement?.isActive != true
                    label.isClickable = label.isEnabled
                    label.isFocusable = label.isEnabled
                    label.setOnClickListener { if (label.isEnabled) select(group, member) }
                }
                content.addView(label)
            }
        }
    }

    private fun memberLabel(group: PolicyGroup, member: String): String = buildString {
        if (group.now == member) append(getString(R.string.policy_selected)).append(" · ")
        append(names.name(member))
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
                    val result = try {
                        getString(R.string.policy_latency, withContext(Dispatchers.IO) { delayClient.measure(member, target, 5_000) })
                    } catch (error: CancellationException) { throw error }
                    catch (_: Exception) { getString(R.string.policy_failed) }
                    coroutineContext.ensureActive()
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

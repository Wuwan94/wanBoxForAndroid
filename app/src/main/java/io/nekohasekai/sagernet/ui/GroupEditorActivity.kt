package io.nekohasekai.sagernet.ui

import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.lifecycleScope
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.bg.RootModuleClient
import io.nekohasekai.sagernet.database.*
import io.nekohasekai.sagernet.group.editor.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A local draft editor. No Clash display name is a reference or persistence key. */
class GroupEditorActivity : ThemedActivity() {
    private lateinit var session: GroupEditSession
    private lateinit var draft: GroupDraft
    private lateinit var form: LinearLayout
    private lateinit var memberRows: LinearLayout
    private lateinit var nameField: EditText
    private lateinit var modeField: Spinner
    private lateinit var urlField: EditText
    private lateinit var intervalField: EditText
    private lateinit var toleranceField: EditText
    private lateinit var idleField: EditText
    private lateinit var modeLockedHint: TextView
    private lateinit var result: TextView
    private lateinit var save: Button
    private lateinit var recover: Button
    private var profiles = emptyList<ProxyEntity>()
    private var locked = false
    private var busy = false
    private var blocked = false
    private var creating = false
    private val members = mutableListOf<Long>()

    override fun onCreate(savedInstanceState: Bundle?) {
        blocked = savedInstanceState?.getBoolean("blocked") ?: false
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = cancelEditing()
        })
        form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 48, 24, 32) }
        setContentView(ScrollView(this).apply { addView(form) })
        lifecycleScope.launch {
            val restored = savedInstanceState?.getString("draft")
            val id = intent.getLongExtra("id", 0)
            val loaded = try { withContext(Dispatchers.IO) {
                profiles = SagerDatabase.proxyDao.getAll()
                val loadedDraft = if (restored != null) com.google.gson.Gson().fromJson(restored, GroupDraft::class.java)
                else if (id != 0L) GroupEditorService.load(id)
                else GroupDraft((SagerDatabase.groupDao.allGroups().maxOfOrNull { it.id } ?: 0) + 1,
                    "", GroupMode.SELECTOR, emptyList(), UrlTestParameters("", 600, 100, "30m", false))
                loadedDraft to (SagerDatabase.groupDao.getById(loadedDraft.id) != null)
            }
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) {
                form.addView(TextView(this@GroupEditorActivity).apply {
                    setText(if (blocked || GroupLocalPreparation.pending()) R.string.group_editor_cleanup else R.string.group_editor_not_applied)
                })
                form.addView(button(android.R.string.cancel) { finish() })
                return@launch
            }
            session = GroupEditSession(loaded.first)
            draft = loaded.first
            locked = loaded.second
            creating = id == 0L && !locked
            members.addAll(draft.members)
            render()
        }
    }

    private fun label(resource: Int) { form.addView(TextView(this).apply { setText(resource) }) }
    private fun field(resource: Int, value: String, numeric: Boolean = false): EditText {
        label(resource)
        return EditText(this).apply {
            setText(value)
            if (numeric) inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
            form.addView(this)
        }
    }
    private fun button(resource: Int, action: () -> Unit) = Button(this).apply {
        setText(resource); setOnClickListener { action() }
    }
    private fun render() {
        nameField = field(R.string.group_name, draft.name).apply { isEnabled = !locked }
        label(R.string.group_type)
        modeField = Spinner(this).apply {
            adapter = ArrayAdapter(this@GroupEditorActivity, android.R.layout.simple_spinner_dropdown_item,
                listOf("Selector", "URLTest"))
            setSelection(draft.mode.ordinal); isEnabled = !locked; form.addView(this)
        }
        // N7: say where an existing group's type can be changed instead of a silently disabled spinner.
        modeLockedHint = TextView(this).apply {
            setText(R.string.group_editor_mode_locked_hint)
            visibility = if (locked) View.VISIBLE else View.GONE
            form.addView(this)
        }
        memberRows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; form.addView(this) }
        renderMembers()
        form.addView(button(R.string.group_editor_add_member) { chooseMember() })
        urlField = field(R.string.urltest_url, draft.parameters.url)
        intervalField = field(R.string.balancer_interval, draft.parameters.intervalSeconds.toString(), true)
        toleranceField = field(R.string.urltest_tolerance, draft.parameters.toleranceMs.toString(), true)
        idleField = field(R.string.urltest_idle_timeout, draft.parameters.idleTimeout)
        // N6: a locked policy is plain text, not a disabled control that looks editable.
        TextView(this).apply {
            text = getString(R.string.group_editor_interrupt_locked) + "\n" + getString(R.string.group_editor_stability_limits)
            contentDescription = text
            form.addView(this)
        }
        result = TextView(this).apply { form.addView(this) }
        save = button(R.string.group_editor_apply) { applyDraft() }.also { form.addView(it) }
        recover = button(R.string.group_editor_recover) {
            if (!busy) lifecycleScope.launch {
                busy = true; enableForm(form, false)
                try {
                    withContext(Dispatchers.IO) {
                        val confirmed = RootModuleClient.recoverGroupDraft()
                        check(!GroupLocalPreparation.pending() && confirmed.phase in setOf("connected", "stopped", "suspended") &&
                            (confirmed.phase != "connected" || confirmed.runningRevision == confirmed.revision))
                    }
                    // Only successful recovery + fresh known module state + cleared journal unlocks editing.
                    blocked = false
                    result.setText(R.string.group_editor_not_applied)
                    recover.visibility = View.GONE
                } catch (error: CancellationException) { throw error }
                catch (_: Exception) { blocked = true; result.setText(R.string.group_editor_cleanup) }
                finally { busy = false; updateFormState() }
            }
        }.also { it.visibility = if (GroupLocalPreparation.pending()) View.VISIBLE else View.GONE; form.addView(it) }
        form.addView(button(android.R.string.cancel) { cancelEditing() })
        blocked = blocked || GroupLocalPreparation.pending()
        if (blocked) result.setText(R.string.group_editor_cleanup)
        updateFormState()
    }

    private fun renderMembers() {
        memberRows.removeAllViews()
        members.forEachIndexed { index, id ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(TextView(this).apply {
                text = profiles.firstOrNull { it.id == id }?.displayName() ?: getString(R.string.group_editor_missing_member, id)
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(button(R.string.group_editor_up) {
                if (index > 0 && !busy && !blocked) { java.util.Collections.swap(members, index, index - 1); renderMembers() }
            })
            row.addView(button(R.string.group_editor_down) {
                if (index < members.lastIndex && !busy && !blocked) { java.util.Collections.swap(members, index, index + 1); renderMembers() }
            })
            row.addView(button(R.string.group_editor_remove) { if (!busy && !blocked) { members.removeAt(index); renderMembers() } })
            memberRows.addView(row)
        }
    }
    private fun chooseMember() {
        if (busy || blocked) return
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.group_editor_add_member)
            .setItems(profiles.map { "${it.displayName()} (#${it.id})" }.toTypedArray()) { _, which ->
                if (!busy && !blocked) { members += profiles[which].id; renderMembers() }
            }.setNegativeButton(android.R.string.cancel, null).show()
    }
    private fun collect(): GroupDraft = draft.copy(name = nameField.text.toString(),
        mode = GroupMode.values()[modeField.selectedItemPosition], members = members.toList(),
        parameters = UrlTestParameters(urlField.text.toString(), intervalField.text.toString().toLongOrNull() ?: 0,
            toleranceField.text.toString().toIntOrNull() ?: -1, idleField.text.toString(), false)).normalized()

    private fun enableForm(view: View, enabled: Boolean) {
        view.isEnabled = enabled
        if (view is android.view.ViewGroup) for (index in 0 until view.childCount) enableForm(view.getChildAt(index), enabled)
    }

    private fun updateFormState() {
        enableForm(form, !busy && !blocked)
        nameField.isEnabled = !busy && !blocked && !locked
        modeField.isEnabled = !busy && !blocked && !locked
        modeLockedHint.visibility = if (locked) View.VISIBLE else View.GONE
        recover.visibility = if (blocked) View.VISIBLE else View.GONE
        // Recovery remains available while every draft control stays blocked.
        recover.isEnabled = !busy
    }

    private fun applyDraft() {
        if (busy) return
        if (blocked || GroupLocalPreparation.pending()) {
            blocked = true
            result.setText(R.string.group_editor_cleanup)
            updateFormState()
            return
        }
        draft = collect()
        busy = true
        enableForm(form, false)
        result.setText(R.string.group_editor_checking)
        lifecycleScope.launch {
            try {
                val issues = withContext(Dispatchers.IO) { GroupEditorService.validate(draft) }
                if (blocked || GroupLocalPreparation.pending()) {
                    blocked = true
                    result.setText(R.string.group_editor_cleanup)
                } else if (issues.isNotEmpty()) {
                    result.setText(R.string.group_editor_not_applied)
                    issues.forEach { issue ->
                        val target: View = when (issue.field) {
                            "name" -> nameField
                            "url" -> urlField
                            "interval" -> intervalField
                            "tolerance" -> toleranceField
                            "idleTimeout" -> idleField
                            else -> memberRows
                        }
                        val reason = getString(when (issue.code) {
                            "name_required" -> R.string.group_name_required
                            "duplicate_tag" -> R.string.group_editor_duplicate_tag
                            "identity_locked" -> R.string.group_editor_identity_locked
                            "missing_entity" -> R.string.group_editor_missing_entity
                            "cycle" -> R.string.group_editor_cycle
                            "duplicate_member" -> R.string.group_editor_duplicate_member
                            "subscription_member_locked" -> R.string.group_editor_subscription_member_locked
                            "ungrouped_member_locked" -> R.string.group_editor_ungrouped_member_locked
                            "minimum_10_seconds", "minimum_30_minutes", "interrupt_locked_off" -> R.string.group_editor_stability_limits
                            "positive_seconds_required" -> R.string.group_editor_positive_seconds
                            "nonnegative_required" -> R.string.group_editor_nonnegative
                            "invalid_duration" -> R.string.group_editor_duration
                            "invalid_url" -> R.string.group_editor_url
                            else -> R.string.group_editor_invalid_members
                        })
                        if (target is EditText) target.error = reason
                        else {
                            val index = if (issue.field.startsWith("members[")) issue.field.substringAfter("[").substringBefore("]").toIntOrNull() else null
                            val entity = when (val member = issue.member) {
                                is EntityId.Node -> "#${member.id}"
                                is EntityId.Group -> "#${member.id}"
                                null -> ""
                            }
                            result.append("\n" + if (index != null) getString(R.string.group_editor_member_detail, index + 1, entity, reason)
                                else "${issue.field}: $entity $reason")
                        }
                        target.requestFocus()
                    }
                } else {
                    withContext(Dispatchers.IO) { RootModuleClient.applyGroupDraft(draft, creating) }
                    creating = false; locked = true; nameField.isEnabled = false; modeField.isEnabled = false
                    modeLockedHint.visibility = View.VISIBLE
                    result.setText(R.string.group_editor_applied)
                }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                recover.visibility = if (GroupLocalPreparation.pending()) View.VISIBLE else View.GONE
                val code = RootModuleClient.safeError(error)
                blocked = blocked || GroupLocalPreparation.pending() || code in setOf("cleanup_required", "rollback_cleanup_failed")
                result.setText(if (blocked) R.string.group_editor_cleanup else when (code) {
                    "revision_conflict", "input_version_conflict", "candidate_version_conflict" -> R.string.group_editor_conflict
                    "missing_outbound_reference" -> R.string.group_editor_missing_reference
                    "cleanup_required", "rollback_cleanup_failed" -> R.string.group_editor_cleanup
                    "core_config_invalid" -> R.string.group_editor_core_invalid
                    "non_outbound_change" -> R.string.group_editor_partition_error
                    "baseline_unavailable", "baseline_revision_invalid", "baseline_member_tags_missing" -> R.string.group_editor_baseline_error
                    "profile_missing" -> R.string.group_editor_profile_missing
                    "module_not_ready", "data_update_pending" -> R.string.group_editor_module_not_ready
                    "duplicate_tag" -> R.string.group_editor_duplicate_tag
                    else -> R.string.group_editor_not_applied
                })
            } finally {
                busy = false
                updateFormState()
            }
        }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("blocked", blocked)
        if (::draft.isInitialized && ::nameField.isInitialized)
            outState.putString("draft", com.google.gson.Gson().toJson(collect()))
        super.onSaveInstanceState(outState)
    }
    private fun cancelEditing() {
        if (busy) return
        if (::session.isInitialized && ::nameField.isInitialized) {
            session.draft = collect()
            draft = session.cancel()
        }
        finish()
    }
}

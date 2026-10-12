package io.nekohasekai.sagernet.ui

import android.os.Bundle
import android.text.format.DateUtils
import android.view.*
import android.widget.*
import androidx.appcompat.widget.PopupMenu
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.*
import io.nekohasekai.sagernet.group.GroupUpdater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class SubscriptionsFragment : ReferenceDataFragment() {
    private val selectedIds = linkedSetOf<Long>()
    private lateinit var items: LinearLayout
    private lateinit var updateSelected: MaterialButton
    private lateinit var autoUpdateValue: TextView

    override fun onViewCreated(view: View, state: Bundle?) {
        super.onViewCreated(view, state)
        state?.getLongArray("selectedGroups")?.let { selectedIds.addAll(it.toList()) }
        toolbar.setTitle(R.string.ref_subscriptions)
        toolbar.menu.add(Menu.NONE, R.id.action_new_group, 0, "添加订阅").apply {
            setIcon(R.drawable.ic_action_note_add)
            setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            setOnMenuItemClickListener {
                ReferenceDialogs.editSubscription(this@SubscriptionsFragment)
                true
            }
        }
        val context = requireContext()
        val actions = ReferenceUi.row(context).apply {
            setPadding(ReferenceUi.dp(context, 20), ReferenceUi.dp(context, 8),
                ReferenceUi.dp(context, 20), ReferenceUi.dp(context, 8))
        }
        actions.addView(ReferenceUi.button(context, getString(R.string.ref_update_all), R.drawable.ic_baseline_refresh_24) {
            update(groups.filter { it.type == GroupType.SUBSCRIPTION })
        }, LinearLayout.LayoutParams(0, ReferenceUi.dp(context, 52), 1f).apply { marginEnd = ReferenceUi.dp(context, 8) })
        updateSelected = ReferenceUi.button(context, "", R.drawable.ic_baseline_check_circle_24) {
            update(groups.filter { it.id in selectedIds && it.type == GroupType.SUBSCRIPTION })
        }
        updateSelected.id = R.id.action_update_selected
        actions.addView(updateSelected, LinearLayout.LayoutParams(0, ReferenceUi.dp(context, 52), 1f))
        body.addView(actions)
        items = ReferenceUi.column(context).apply {
            setPadding(ReferenceUi.dp(context, 20), 0, ReferenceUi.dp(context, 20), ReferenceUi.dp(context, 12))
        }
        body.addView(ScrollView(context).apply { addView(items); isFillViewport = true },
            LinearLayout.LayoutParams(-1, 0, 1f))
        updateSelection()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putLongArray("selectedGroups", selectedIds.toLongArray())
        super.onSaveInstanceState(outState)
    }

    private fun update(targets: List<ProxyGroup>) {
        targets.filter { it.id !in GroupUpdater.updating }.forEach { GroupUpdater.startUpdate(it, true) }
        renderData()
    }

    private fun updateSelection() {
        updateSelected.text = getString(R.string.ref_update_selected, selectedIds.size)
        updateSelected.isEnabled = selectedIds.isNotEmpty()
    }

    override fun renderData() {
        if (view == null) return
        toolbar.subtitle = getString(R.string.ref_subtitle, groups.count { it.type == GroupType.SUBSCRIPTION }, profiles.size)
        selectedIds.retainAll(groups.filter { it.type == GroupType.SUBSCRIPTION }.map { it.id }.toSet())
        updateSelection()
        items.removeAllViews()
        val visibleGroups = groups.filter { it.type == GroupType.SUBSCRIPTION }
        if (visibleGroups.isEmpty()) {
            items.addView(ReferenceUi.text(requireContext(), getString(R.string.ref_empty_subscriptions), 16f, true).apply {
                gravity = Gravity.CENTER
                setPadding(0, ReferenceUi.dp(context, 72), 0, ReferenceUi.dp(context, 72))
            }, LinearLayout.LayoutParams(-1, -2))
        }
        visibleGroups.forEach { group ->
            val context = requireContext()
            val row = ReferenceUi.row(context).apply {
                background = ReferenceUi.shape(context,
                    if (group.id in selectedIds) R.color.ref_surface else R.color.ref_background)
                setPadding(ReferenceUi.dp(context, 6), ReferenceUi.dp(context, 8), 0, ReferenceUi.dp(context, 8))
            }
            val check = CheckBox(context).apply {
                isChecked = group.id in selectedIds
                isEnabled = group.type == GroupType.SUBSCRIPTION
                contentDescription = group.displayName()
                buttonTintList = android.content.res.ColorStateList.valueOf(ReferenceUi.color(context, R.color.ref_control))
                setOnCheckedChangeListener { _, checked ->
                    if (checked) selectedIds.add(group.id) else selectedIds.remove(group.id)
                    row.background = ReferenceUi.shape(context, if (checked) R.color.ref_surface else R.color.ref_background)
                    updateSelection()
                }
            }
            row.addView(check, LinearLayout.LayoutParams(ReferenceUi.dp(context, 44), ReferenceUi.dp(context, 48)))
            val labels = ReferenceUi.column(context)
            labels.addView(ReferenceUi.text(context, group.displayName(), 16f, bold = true),
                LinearLayout.LayoutParams(-1, -2))
            val updating = group.id in GroupUpdater.updating
            val updatedAt = group.subscription?.lastUpdated?.toLong()?.times(1000) ?: 0L
            val elapsed = (System.currentTimeMillis() - updatedAt).coerceAtLeast(0L)
            val updated = when {
                updating -> getString(R.string.ref_update_running)
                updatedAt == 0L -> getString(R.string.ref_never_updated)
                elapsed < DateUtils.MINUTE_IN_MILLIS -> getString(R.string.ref_updated_now)
                elapsed < DateUtils.HOUR_IN_MILLIS -> getString(R.string.ref_updated_minutes, elapsed / DateUtils.MINUTE_IN_MILLIS)
                elapsed < DateUtils.DAY_IN_MILLIS -> getString(R.string.ref_updated_hours, elapsed / DateUtils.HOUR_IN_MILLIS)
                else -> getString(R.string.ref_updated_days, elapsed / DateUtils.DAY_IN_MILLIS)
            }
            labels.addView(ReferenceUi.text(context, getString(R.string.ref_node_meta,
                profiles.count { it.groupId == group.id }, updated), 12f, true),
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = ReferenceUi.dp(context, 6) })
            row.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
            val update = ReferenceUi.button(context, getString(R.string.ref_update), R.drawable.ic_baseline_refresh_24) {
                update(listOf(group))
            }.apply {
                strokeWidth = 0
                backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.TRANSPARENT)
                isEnabled = !updating
                isVisible = group.type == GroupType.SUBSCRIPTION
                setPadding(ReferenceUi.dp(context, 4), 0, ReferenceUi.dp(context, 4), 0)
                iconPadding = ReferenceUi.dp(context, 4)
                maxLines = 1
                contentDescription = "${group.displayName()} ${getString(R.string.ref_update)}"
            }
            row.addView(update, LinearLayout.LayoutParams(ReferenceUi.dp(context, 76), ReferenceUi.dp(context, 48)))
            row.addView(ReferenceUi.icon(context, R.drawable.ic_baseline_more_vert_24, getString(R.string.more)) {
                PopupMenu(context, row).apply {
                    menu.add(R.string.edit).setOnMenuItemClickListener {
                        ReferenceDialogs.editSubscription(this@SubscriptionsFragment, group)
                        true
                    }
                    menu.add(R.string.delete).setOnMenuItemClickListener {
                        if (group.id in GroupUpdater.updating) return@setOnMenuItemClickListener true
                        if (DataStore.serviceState.started && profiles.any {
                            it.groupId == group.id && (it.id == DataStore.selectedProxy || it.id == DataStore.currentProfile)
                        }) {
                            Toast.makeText(context, R.string.cannot_delete_active_profile, Toast.LENGTH_LONG).show()
                            return@setOnMenuItemClickListener true
                        }
                        MaterialAlertDialogBuilder(context).setTitle(R.string.delete)
                            .setMessage(group.displayName()).setNegativeButton(android.R.string.cancel, null)
                            .setPositiveButton(android.R.string.ok) { _, _ ->
                                ReferenceDialogs.run(this@SubscriptionsFragment, {
                                    if (profiles.any { it.groupId == group.id && it.id == DataStore.selectedProxy }) {
                                        DataStore.selectedProxy = 0L
                                    }
                                    GroupManager.deleteGroup(group.id)
                                })
                            }.show()
                        true
                    }
                    show()
                }
            }, LinearLayout.LayoutParams(ReferenceUi.dp(context, 40), ReferenceUi.dp(context, 48)))
            items.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ReferenceUi.dp(context, 8) })
        }
        val auto = ReferenceUi.row(requireContext()).apply {
            minimumHeight = ReferenceUi.dp(context, 56)
            setOnClickListener { showAutoUpdate() }
        }
        auto.addView(ReferenceUi.text(requireContext(), getString(R.string.ref_auto_update), 16f, bold = true),
            LinearLayout.LayoutParams(0, -2, 1f))
        val subscriptions = groups.mapNotNull { it.subscription }
        val label = when {
            subscriptions.isEmpty() || subscriptions.all { it.autoUpdate != true } -> R.string.ref_auto_off
            subscriptions.all { it.autoUpdate == true && it.autoUpdateDelay == 1440 } -> R.string.ref_auto_daily
            subscriptions.all { it.autoUpdate == true && it.autoUpdateDelay == 360 } -> R.string.ref_auto_six_hours
            else -> R.string.ref_auto_custom
        }
        autoUpdateValue = ReferenceUi.text(requireContext(), getString(label), 14f, true)
        auto.addView(autoUpdateValue)
        auto.addView(ReferenceUi.icon(requireContext(), R.drawable.ic_baseline_chevron_right_24, ""))
        items.addView(auto)
    }

    private fun showAutoUpdate() {
        val options = arrayOf(getString(R.string.ref_auto_off), getString(R.string.ref_auto_daily),
            getString(R.string.ref_auto_six_hours))
        MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.ref_auto_update)
            .setItems(options) { _, index ->
                val targets = groups.filter { it.type == GroupType.SUBSCRIPTION && it.subscription != null }
                ReferenceDialogs.run(this, {
                    targets.forEach { group ->
                        group.subscription!!.autoUpdate = index != 0
                        group.subscription!!.autoUpdateDelay = if (index == 2) 360 else 1440
                        GroupManager.updateGroup(group)
                    }
                })
            }.show()
    }
}

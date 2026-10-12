package io.nekohasekai.sagernet.ui

import android.os.Bundle
import android.view.*
import android.widget.*
import androidx.appcompat.widget.PopupMenu
import androidx.appcompat.widget.SearchView
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.*
import io.nekohasekai.sagernet.group.GroupUpdater
import kotlinx.coroutines.*

/** Grouped node cards; imports and protocol editors use the existing domain workflows. */
class NodesFragment : ReferenceDataFragment() {
    private var filterGroup = 0L
    private var query = ""
    private var selected = 0L
    private lateinit var tabs: LinearLayout
    private lateinit var list: RecyclerView
    private lateinit var empty: TextView
    private var displayPanel: View? = null
    private val cards = NodeAdapter()
    private sealed class Row {
        data class Header(val group: ProxyGroup, val count: Int) : Row()
        data class Node(val profile: ProxyEntity) : Row()
    }
    private var rows: List<Row> = emptyList()

    override fun onViewCreated(view: View, state: Bundle?) {
        super.onViewCreated(view, state)
        filterGroup = state?.getLong("filterGroup") ?: 0L
        query = state?.getString("query").orEmpty()
        toolbar.setTitle(R.string.tab_nodes)
        val search = SearchView(requireContext()).apply {
            queryHint = getString(R.string.ref_search)
            setOnQueryTextListener(object : SearchView.OnQueryTextListener {
                override fun onQueryTextChange(value: String): Boolean {
                    this@NodesFragment.query = value
                    renderCards()
                    return true
                }
                override fun onQueryTextSubmit(value: String) = true
            })
        }
        toolbar.menu.add(Menu.NONE, R.id.action_search, 0, getString(R.string.ref_search)).apply {
            setIcon(R.drawable.ic_baseline_search_24)
            actionView = search
            setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS or MenuItem.SHOW_AS_ACTION_COLLAPSE_ACTION_VIEW)
        }
        toolbar.menu.add(Menu.NONE, R.id.action_update_subscription, 1, getString(R.string.ref_update_all)).apply {
            setIcon(R.drawable.ic_baseline_refresh_24)
            setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            setOnMenuItemClickListener {
                groups.filter { it.type == GroupType.SUBSCRIPTION && (filterGroup == 0L || it.id == filterGroup) }
                    .forEach { GroupUpdater.startUpdate(it, true) }
                true
            }
        }
        toolbar.menu.add(Menu.NONE, R.id.action_display_settings, 2, getString(R.string.node_display_settings)).apply {
            setIcon(R.drawable.ic_baseline_tune_24)
            setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            setOnMenuItemClickListener {
                if (displayPanel != null) {
                    body.removeView(displayPanel)
                    displayPanel = null
                } else {
                    displayPanel = NodeDisplaySettingsPanel(requireContext(), ::onDisplaySettingsChanged) {
                        body.removeView(displayPanel)
                        displayPanel = null
                    }
                    body.addView(displayPanel, LinearLayout.LayoutParams(-1, -2))
                }
                true
            }
        }
        toolbar.menu.add("导入节点").setOnMenuItemClickListener {
            ReferenceDialogs.importNodes(this)
            true
        }
        val context = requireContext()
        tabs = ReferenceUi.row(context)
        val scroller = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            addView(tabs)
            setPadding(ReferenceUi.dp(context, 20), 0, ReferenceUi.dp(context, 20), ReferenceUi.dp(context, 10))
        }
        body.addView(scroller, LinearLayout.LayoutParams(-1, -2))
        val frame = FrameLayout(context)
        list = RecyclerView(context).apply {
            clipToPadding = false
            setPadding(ReferenceUi.dp(context, 16), 0, ReferenceUi.dp(context, 16), ReferenceUi.dp(context, 12))
            adapter = cards
            itemAnimator = null
        }
        frame.addView(list, FrameLayout.LayoutParams(-1, -1))
        empty = ReferenceUi.text(context, getString(R.string.ref_empty_nodes), 16f, true).apply {
            gravity = Gravity.CENTER
        }
        frame.addView(empty, FrameLayout.LayoutParams(-1, -1))
        body.addView(frame, LinearLayout.LayoutParams(-1, 0, 1f))
        selected = DataStore.selectedProxy
        viewLifecycleOwner.lifecycleScope.launch {
            while (isActive) {
                if (selected != DataStore.selectedProxy) {
                    selected = DataStore.selectedProxy
                    renderCards()
                }
                delay(500)
            }
        }
        if (query.isNotEmpty()) {
            search.isIconified = false
            search.setQuery(query, false)
            search.clearFocus()
        }
        onDisplaySettingsChanged()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putLong("filterGroup", filterGroup)
        outState.putString("query", query)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroyView() {
        displayPanel = null
        super.onDestroyView()
    }

    override fun renderData() {
        if (!isAdded || view == null) return
        toolbar.subtitle = getString(R.string.ref_subtitle, groups.count { it.type == GroupType.SUBSCRIPTION }, profiles.size)
        if (filterGroup != 0L && groups.none { it.id == filterGroup }) filterGroup = 0L
        tabs.removeAllViews()
        fun tab(id: Long, name: String) {
            val button = TextView(requireContext()).apply {
                text = name
                textSize = 14f
                gravity = Gravity.CENTER
                minWidth = ReferenceUi.dp(context, 78)
                minimumHeight = ReferenceUi.dp(context, 40)
                setPadding(ReferenceUi.dp(context, 12), 0, ReferenceUi.dp(context, 12), 0)
                setTextColor(ReferenceUi.color(context, if (id == filterGroup) R.color.ref_text else R.color.ref_secondary))
                background = ReferenceUi.shape(context, if (id == filterGroup) R.color.ref_selected else R.color.ref_surface)
                isSelected = id == filterGroup
                setOnClickListener { filterGroup = id; renderData() }
            }
            tabs.addView(button, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = ReferenceUi.dp(requireContext(), 4) })
        }
        tab(0L, getString(R.string.ref_all))
        groups.forEach { tab(it.id, it.displayName()) }
        renderCards()
    }

    private fun renderCards() {
        if (!::list.isInitialized) return
        rows = buildList {
            groups.filter { filterGroup == 0L || it.id == filterGroup }.forEach { group ->
                val nodes = profiles.filter { it.groupId == group.id &&
                    (query.isBlank() || it.displayName().contains(query, true) || it.displayType().contains(query, true)) }
                if (nodes.isNotEmpty()) {
                    add(Row.Header(group, nodes.size))
                    nodes.forEach { add(Row.Node(it)) }
                }
            }
        }
        empty.setText(if (query.isBlank()) R.string.ref_empty_nodes else R.string.ref_no_search_results)
        empty.isVisible = rows.isEmpty()
        cards.notifyDataSetChanged()
    }

    fun onDisplaySettingsChanged() {
        if (!::list.isInitialized) return
        val columns = DataStore.nodeColumnCount.coerceIn(1, 3)
        list.layoutManager = GridLayoutManager(requireContext(), columns).apply {
            spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int) = if (rows.getOrNull(position) is Row.Header) columns else 1
            }
        }
        renderCards()
    }

    private inner class NodeAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = rows.size
        override fun getItemViewType(position: Int) = if (rows[position] is Row.Header) 0 else 1
        override fun onCreateViewHolder(parent: ViewGroup, type: Int): RecyclerView.ViewHolder =
            if (type == 0) object : RecyclerView.ViewHolder(ReferenceUi.text(parent.context, size = 14f, secondary = true).apply {
                setPadding(ReferenceUi.dp(context, 4), ReferenceUi.dp(context, 14),
                    ReferenceUi.dp(context, 4), ReferenceUi.dp(context, 8))
                layoutParams = RecyclerView.LayoutParams(-1, -2)
            }) {} else NodeHolder(parent)
        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val row = rows[position]) {
                is Row.Header -> (holder.itemView as TextView).text =
                    getString(R.string.ref_group_count, row.group.displayName(), row.count)
                is Row.Node -> (holder as NodeHolder).bind(row.profile)
            }
        }
    }

    private inner class NodeHolder(parent: ViewGroup) : RecyclerView.ViewHolder(ReferenceUi.row(parent.context)) {
        val root = itemView as LinearLayout
        val radio = RadioButton(parent.context).apply {
            isClickable = false
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            buttonTintList = android.content.res.ColorStateList.valueOf(ReferenceUi.color(context, R.color.ref_control))
            setPadding(0, 0, 0, 0)
        }
        val name = ReferenceUi.text(parent.context, bold = true).apply {
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        val protocol = ReferenceUi.text(parent.context, size = 12f, secondary = true)
        val latency = ReferenceUi.text(parent.context, size = 12f, secondary = true)
        val menu = ReferenceUi.icon(parent.context, R.drawable.ic_baseline_more_vert_24, getString(R.string.more))
        init {
            root.gravity = Gravity.TOP
            root.isFocusable = true
            root.background = androidx.core.content.ContextCompat.getDrawable(parent.context, R.drawable.ref_card)
            root.addView(radio, LinearLayout.LayoutParams(ReferenceUi.dp(parent.context, 30), ReferenceUi.dp(parent.context, 32)))
            val labels = ReferenceUi.column(parent.context)
            labels.addView(name, LinearLayout.LayoutParams(-1, -2))
            labels.addView(protocol, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ReferenceUi.dp(parent.context, 4) })
            labels.addView(latency, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ReferenceUi.dp(parent.context, 4) })
            root.addView(labels, LinearLayout.LayoutParams(0, -2, 1f).apply { topMargin = ReferenceUi.dp(parent.context, 4) })
            menu.setPadding(0, ReferenceUi.dp(parent.context, 6), 0, ReferenceUi.dp(parent.context, 6))
            menu.minimumWidth = ReferenceUi.dp(parent.context, 32)
            root.addView(menu, LinearLayout.LayoutParams(ReferenceUi.dp(parent.context, 32), ReferenceUi.dp(parent.context, 48)))
        }
        fun bind(profile: ProxyEntity) {
            val context = root.context
            val gap = ReferenceUi.dp(context, DataStore.nodeCardSpacing.coerceIn(0, 24)) / 2
            root.layoutParams = RecyclerView.LayoutParams(-1, -2).apply { setMargins(gap, gap, gap, gap) }
            val padding = ReferenceUi.dp(context, when (DataStore.nodeCardDensity) { 0 -> 6; 2 -> 16; else -> 10 })
            root.setPadding(padding, padding, 0, padding)
            name.text = profile.displayName()
            name.textSize = DataStore.nodeNameTextSize.coerceIn(10, 24).toFloat()
            protocol.text = profile.displayType()
            latency.text = if (profile.ping > 0) "${profile.ping} ms" else "—"
            root.isSelected = profile.id == selected
            radio.isChecked = root.isSelected
            root.isEnabled = !DataStore.isGroupDisabled(profile.groupId)
            root.alpha = if (root.isEnabled) 1f else 0.5f
            root.contentDescription = getString(
                if (root.isSelected) R.string.ref_selected_node else R.string.ref_select_node, profile.displayName())
            root.setOnClickListener { SagerNet.selectProfile(profile.id) }
            menu.isClickable = true
            menu.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            menu.setOnClickListener { anchor ->
                PopupMenu(requireContext(), anchor).apply {
                    menu.add(R.string.edit).setOnMenuItemClickListener {
                        ReferenceDialogs.editNode(this@NodesFragment, profile)
                        true
                    }
                    menu.add(R.string.delete).setOnMenuItemClickListener {
                        if (DataStore.serviceState.started && (profile.id == DataStore.selectedProxy || profile.id == DataStore.currentProfile)) {
                            Toast.makeText(requireContext(), R.string.cannot_delete_active_profile, Toast.LENGTH_SHORT).show()
                        } else MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.delete)
                            .setMessage(profile.displayName()).setNegativeButton(android.R.string.cancel, null)
                            .setPositiveButton(android.R.string.ok) { _, _ ->
                                ReferenceDialogs.run(this@NodesFragment, {
                                    ProfileManager.deleteProfile(profile.groupId, profile.id)
                                })
                            }.show()
                        true
                    }
                    show()
                }
            }
        }
    }
}

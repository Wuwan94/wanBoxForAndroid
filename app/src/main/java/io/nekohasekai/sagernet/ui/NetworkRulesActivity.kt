// B6（N3）：统一「按网络」规则表 UI。单规则表替代 F1 独立规则编辑入口；
// 与 F1 开关共用同一启用状态（DataStore.smartAutoStopEnabled），
// 不得出现两个可互相覆盖的网络开关。规则上限 5 条；新规则默认动作 keep。
package io.nekohasekai.sagernet.ui

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.bg.NetworkPolicy
import io.nekohasekai.sagernet.bg.NetworkRule
import io.nekohasekai.sagernet.bg.NetworkRuleAction
import io.nekohasekai.sagernet.bg.NetworkRules
import io.nekohasekai.sagernet.bg.SmartAutoStopAndroidUser
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.alert
import io.nekohasekai.sagernet.ktx.dp2px
import io.nekohasekai.sagernet.ktx.tryToShow

class NetworkRulesActivity : ThemedActivity() {

    private var rules: MutableList<NetworkRule> = mutableListOf()
    private lateinit var adapter: RulesAdapter
    private lateinit var emptyView: TextView
    private lateinit var list: RecyclerView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (NetworkRules.migrateIfNeeded()) {
            Toast.makeText(this, R.string.network_rules_migrated, Toast.LENGTH_LONG).show()
        }
        rules = NetworkRules.parse(DataStore.networkRulesJson).getOrDefault(NetworkRules.emptyPolicy())
            .rules.toMutableList()

        supportActionBar?.apply {
            setTitle(R.string.network_rules_title)
            setDisplayHomeAsUpEnabled(true)
            setHomeAsUpIndicator(R.drawable.ic_navigation_close)
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        emptyView = TextView(this).apply {
            setText(R.string.network_rules_empty)
            setPadding(dp2px(16), dp2px(16), dp2px(16), dp2px(16))
            visibility = if (rules.isEmpty()) View.VISIBLE else View.GONE
        }
        list = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@NetworkRulesActivity)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        adapter = RulesAdapter()
        list.adapter = adapter
        root.addView(emptyView)
        root.addView(list)
        setContentView(root)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(Menu.NONE, MENU_ADD, Menu.NONE, R.string.network_rules_add)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == MENU_ADD) {
            if (rules.size >= NetworkRules.MAX_RULES) {
                Toast.makeText(this, R.string.network_rules_too_many, Toast.LENGTH_LONG).show()
                return true
            }
            showEditDialog(null)
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun refresh() {
        adapter.notifyDataSetChanged()
        emptyView.visibility = if (rules.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun save() {
        val policy = NetworkPolicy(
            enabled = DataStore.smartAutoStopEnabled,
            rules = rules.toList(),
            appPackage = io.nekohasekai.sagernet.BuildConfig.APPLICATION_ID,
            userId = SmartAutoStopAndroidUser.fromUid(android.os.Process.myUid()),
        )
        DataStore.networkRulesJson = NetworkRules.serialize(policy)
        SagerNet.reloadService { error ->
            if (!isFinishing && !isDestroyed) {
                alert(io.nekohasekai.sagernet.bg.SmartAutoStopApplyErrors.message(this, error)).tryToShow()
            }
        }
    }

    private fun ruleSummary(rule: NetworkRule): String {
        val parts = mutableListOf<String>()
        rule.ssid?.takeIf { it.isNotEmpty() }?.let { parts.add("SSID: $it") }
        rule.gateway?.takeIf { it.isNotEmpty() }?.let { parts.add("gateway: $it") }
        rule.cidr?.takeIf { it.isNotEmpty() }?.let { parts.add(it) }
        rule.egress?.let {
            parts.add(
                when (it) {
                    "wifi" -> getString(R.string.network_rules_egress_wifi)
                    "not_wifi" -> getString(R.string.network_rules_egress_not_wifi)
                    else -> it
                }
            )
        }
        return parts.joinToString(" · ")
    }

    private fun actionLabel(action: NetworkRuleAction): String = when (action) {
        NetworkRuleAction.SUSPEND -> getString(R.string.network_rules_action_suspend)
        NetworkRuleAction.DIRECT -> getString(R.string.network_rules_action_direct)
        NetworkRuleAction.KEEP -> getString(R.string.network_rules_action_keep)
    }

    private inner class RulesAdapter : RecyclerView.Adapter<RuleViewHolder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RuleViewHolder {
            val view = layoutInflater.inflate(android.R.layout.simple_list_item_2, parent, false)
            return RuleViewHolder(view)
        }

        override fun getItemCount(): Int = rules.size

        override fun onBindViewHolder(holder: RuleViewHolder, position: Int) {
            val rule = rules[position]
            holder.title.text = actionLabel(rule.action)
            holder.subtitle.text = ruleSummary(rule).ifEmpty { getString(R.string.network_rules_no_matcher) }
            holder.itemView.setOnClickListener { showEditDialog(position) }
        }
    }

    private class RuleViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(android.R.id.text1)
        val subtitle: TextView = view.findViewById(android.R.id.text2)
    }

    private fun showEditDialog(position: Int?) {
        val existing = position?.let { rules[it] } ?: NetworkRules.defaultNewRule()
        val actions = NetworkRuleAction.entries.toList()
        val actionLabels = actions.map { actionLabel(it) }
        val egressOptions = listOf(null, "wifi", "not_wifi")
        val egressLabels = listOf(
            getString(R.string.network_rules_egress_any),
            getString(R.string.network_rules_egress_wifi),
            getString(R.string.network_rules_egress_not_wifi),
        )

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp2px(20), dp2px(8), dp2px(20), dp2px(8))
        }
        fun labeledEdit(hint: String, initial: String?): EditText {
            return EditText(this).apply {
                this.hint = hint
                setText(initial ?: "")
                layout.addView(this)
            }
        }
        val actionSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@NetworkRulesActivity, android.R.layout.simple_spinner_dropdown_item, actionLabels)
            setSelection(actions.indexOf(existing.action).coerceAtLeast(0))
            layout.addView(this)
        }
        val ssidEdit = labeledEdit(getString(R.string.network_rules_ssid), existing.ssid)
        ssidEdit.hint = "${getString(R.string.network_rules_ssid)}（${getString(R.string.network_rules_ssid_hint)}）"
        val gatewayEdit = labeledEdit(getString(R.string.network_rules_gateway), existing.gateway)
        val cidrEdit = labeledEdit(getString(R.string.network_rules_cidr), existing.cidr)
        val egressSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@NetworkRulesActivity, android.R.layout.simple_spinner_dropdown_item, egressLabels)
            setSelection(egressOptions.indexOf(existing.egress).coerceAtLeast(0))
            layout.addView(this)
        }

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(if (position == null) R.string.network_rules_add else R.string.network_rules_edit)
            .setView(layout)
            .setPositiveButton(R.string.confirm) { _, _ ->
                val rule = NetworkRule(
                    action = actions[actionSpinner.selectedItemPosition],
                    ssid = ssidEdit.text.toString().ifEmpty { null },
                    gateway = gatewayEdit.text.toString().ifEmpty { null },
                    cidr = cidrEdit.text.toString().ifEmpty { null },
                    egress = egressOptions[egressSpinner.selectedItemPosition],
                )
                runCatching { NetworkRules.validateRule(rule) }.onFailure {
                    Toast.makeText(this, R.string.network_rules_invalid, Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                if (position == null) rules.add(rule) else rules[position] = rule
                refresh()
                save()
            }
            .setNegativeButton(android.R.string.cancel, null)
        if (position != null) {
            dialog.setNeutralButton(R.string.network_rules_delete) { _, _ ->
                rules.removeAt(position)
                refresh()
                save()
            }
        }
        dialog.show()
    }

    companion object {
        private const val MENU_ADD = 1
    }
}

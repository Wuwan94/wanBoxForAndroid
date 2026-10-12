package io.nekohasekai.sagernet.ui

import android.os.Bundle
import android.graphics.Typeface
import android.text.InputType
import android.view.*
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.switchmaterial.SwitchMaterial
import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.bg.RootModuleClient
import io.nekohasekai.sagernet.bg.RootModuleErrors
import io.nekohasekai.sagernet.database.*
import io.nekohasekai.sagernet.utils.*
import kotlinx.coroutines.*
import moe.matsuri.nb4a.utils.SendLog

/** All settings detail pages use the same new list/form language as the landing page. */
class ReferenceSettingsFragment : ReferenceFragment() {
    private lateinit var content: LinearLayout
    private val section get() = arguments?.getString("section").orEmpty()
    private var appQuery = ""
    private var apps: List<Pair<String, String>> = emptyList()
    private val exportBackup = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) ReferenceDialogs.run(this, {
            val bytes = BackupHelper.doBackup()
            requireContext().contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) }
                ?: throw java.io.IOException("无法写入备份")
        }) { Toast.makeText(requireContext(), "备份已保存", Toast.LENGTH_SHORT).show() }
    }
    private val importBackup = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) ReferenceDialogs.run(this, {
            val bytes = requireContext().contentResolver.openInputStream(uri)?.use { BoundedInput.read(it) }
                ?: throw java.io.IOException("无法读取备份")
            val plan = BackupRestore.parse(PortableBackup.document(bytes))
            withContext(Dispatchers.Main) {
                MaterialAlertDialogBuilder(requireContext()).setTitle("恢复备份")
                    .setMessage("将替换当前节点、订阅、路由规则和设置")
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        if (DataStore.serviceState.started) {
                            Toast.makeText(requireContext(), "请先断开连接", Toast.LENGTH_LONG).show()
                        } else ReferenceDialogs.run(this@ReferenceSettingsFragment, { BackupRestore.apply(plan, true, true, true) }) {
                            requireActivity().recreate()
                        }
                    }.show()
            }
        })
    }

    override fun onViewCreated(view: View, state: Bundle?) {
        super.onViewCreated(view, state)
        toolbar.setTitle(requireArguments().getInt("title", R.string.settings))
        content = ReferenceUi.column(requireContext()).apply {
            val pad = ReferenceUi.dp(context, 20)
            setPadding(pad, 0, pad, pad)
        }
        body.addView(ScrollView(requireContext()).apply { addView(content) }, LinearLayout.LayoutParams(-1, -1))
        render()
    }

    private fun divider() = content.addView(ReferenceUi.divider(requireContext()),
        LinearLayout.LayoutParams(-1, ReferenceUi.dp(requireContext(), 1)))
    private fun label(value: String) {
        content.addView(ReferenceUi.text(requireContext(), value, 13f, true).apply {
            setPadding(0, ReferenceUi.dp(context, 18), 0, ReferenceUi.dp(context, 12))
        }, LinearLayout.LayoutParams(-1, -2))
    }
    private fun entry(name: String, value: String = "", action: () -> Unit = {}) {
        val row = ReferenceUi.row(requireContext()).apply {
            minimumHeight = ReferenceUi.dp(context, 60)
            setOnClickListener { action() }
            isFocusable = true
        }
        val labels = ReferenceUi.column(requireContext())
        labels.addView(ReferenceUi.text(requireContext(), name, 16f), LinearLayout.LayoutParams(-1, -2))
        if (value.isNotBlank()) labels.addView(ReferenceUi.text(requireContext(), value, 13f, true),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = ReferenceUi.dp(requireContext(), 6) })
        row.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(ReferenceUi.icon(requireContext(), R.drawable.ic_baseline_chevron_right_24, ""),
            LinearLayout.LayoutParams(ReferenceUi.dp(requireContext(), 40), ReferenceUi.dp(requireContext(), 48)))
        content.addView(row, LinearLayout.LayoutParams(-1, -2))
        divider()
    }
    private fun toggle(name: String, checked: Boolean, save: (Boolean) -> Unit) {
        val control = SwitchMaterial(requireContext()).apply {
            text = name
            textSize = 16f
            setTextColor(ReferenceUi.color(context, R.color.ref_text))
            minHeight = ReferenceUi.dp(context, 60)
            buttonTintList = android.content.res.ColorStateList.valueOf(ReferenceUi.color(context, R.color.ref_control))
            isChecked = checked
            setOnCheckedChangeListener { _, value -> save(value) }
        }
        content.addView(control, LinearLayout.LayoutParams(-1, -2))
        divider()
    }
    private fun edit(label: String, old: String, save: (String) -> Unit) {
        val form = ReferenceDialogs.form(this)
        val input = ReferenceDialogs.field(this, form, label, old)
        val dialog = MaterialAlertDialogBuilder(requireContext()).setTitle(label).setView(form)
            .setNegativeButton(android.R.string.cancel, null).setPositiveButton(android.R.string.ok, null).create()
        dialog.setOnShowListener {
            dialog.getButton(-1).setOnClickListener {
                try {
                    save(input.text.toString().trim())
                    dialog.dismiss()
                    render()
                } catch (error: IllegalArgumentException) { input.error = error.message }
            }
        }
        dialog.show()
    }
    private fun reload() { if (DataStore.serviceState.started) SagerNet.reloadService() }

    private fun render() {
        if (view == null) return
        content.removeAllViews()
        when (section) {
            "dns" -> {
                entry("远程 DNS", DataStore.remoteDns) { edit("远程 DNS", DataStore.remoteDns) {
                    require(it.isNotBlank()) { "DNS 不能为空" }; DataStore.remoteDns = it; reload()
                } }
                entry("直连 DNS", DataStore.directDns) { edit("直连 DNS", DataStore.directDns) {
                    require(it.isNotBlank()) { "DNS 不能为空" }; DataStore.directDns = it; reload()
                } }
                toggle("DNS 路由", DataStore.enableDnsRouting) { DataStore.enableDnsRouting = it; reload() }
                toggle("Fake DNS", DataStore.enableFakeDns) { DataStore.enableFakeDns = it; reload() }
            }
            "network" -> {
                toggle("双网络加速", DataStore.dualNetworkAcceleration) { DataStore.dualNetworkAcceleration = it; reload() }
                toggle("并发拨号", DataStore.concurrentDial) { DataStore.concurrentDial = it; reload() }
                toggle("自动选择最低延迟", DataStore.autoSelectLowestLatency) { DataStore.autoSelectLowestLatency = it; reload() }
                toggle("绕过局域网", DataStore.bypassLan) { DataStore.bypassLan = it; reload() }
            }
            "appearance" -> {
                label("浅灰简洁版")
                entry("节点显示设置") { NodeDisplaySettingsDialog().show(childFragmentManager, "display_settings") }
                toggle("触感反馈", DataStore.hapticFeedback) { DataStore.hapticFeedback = it }
            }
            "root" -> {
                val status = ReferenceUi.text(requireContext(), "正在读取模块状态", 14f, true)
                content.addView(status, LinearLayout.LayoutParams(-1, -2).apply {
                    topMargin = ReferenceUi.dp(requireContext(), 20); bottomMargin = ReferenceUi.dp(requireContext(), 20)
                })
                viewLifecycleOwner.lifecycleScope.launch {
                    try {
                        val state = RootModuleClient.call("status")
                        status.text = "状态：${state.phase}\n版本：${state.runningRevision.ifBlank { state.revision }}"
                    } catch (cancel: CancellationException) { throw cancel }
                    catch (error: Exception) { status.text = RootModuleErrors.message(requireContext(), error) }
                }
                entry("启动模块") { SagerNet.startService() }
                entry("停止模块") { SagerNet.stopService() }
                entry("重新加载配置") { SagerNet.reloadService() }
                toggle("开机自动启动", DataStore.persistAcrossReboot) { enabled ->
                    ReferenceDialogs.run(this, { RootModuleClient.setAutoStart(enabled) }) {
                        DataStore.configurationStore.putBoolean(io.nekohasekai.sagernet.Key.PERSIST_ACROSS_REBOOT, enabled)
                        render()
                    }
                }
            }
            "logs" -> {
                toolbar.menu.clear()
                toolbar.menu.add("刷新").apply {
                    setIcon(R.drawable.ic_baseline_refresh_24)
                    setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
                    setOnMenuItemClickListener { render(); true }
                }
                val log = ReferenceUi.text(requireContext(), "", 12f, true).apply {
                    typeface = Typeface.MONOSPACE
                    setTextIsSelectable(true)
                }
                content.addView(log, LinearLayout.LayoutParams(-1, -2))
                ReferenceDialogs.run(this, {
                    val text = String(SendLog.getNekoLog(50 * 1024))
                    withContext(Dispatchers.Main) { log.text = text.ifBlank { "暂无日志" } }
                })
            }
            "backup" -> {
                entry("导出备份", "节点、订阅、路由规则与设置") { exportBackup.launch(BackupFiles.fileName()) }
                entry("恢复备份") { importBackup.launch(arrayOf("application/json", "text/plain")) }
            }
            "routes" -> renderRoutes()
            "apps" -> loadApps()
            "about" -> {
                label("wanBox")
                label(BuildConfig.VERSION_NAME)
                label("Root Android 代理")
                label("GPL-3.0-or-later")
            }
        }
    }

    private fun renderRoutes() {
        toolbar.menu.clear()
        toolbar.menu.add("添加规则").apply {
            setIcon(R.drawable.ic_action_note_add)
            setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            setOnMenuItemClickListener { editRule(RuleEntity(enabled = true)); true }
        }
        ReferenceDialogs.run(this, {
            val rules = SagerDatabase.rulesDao.allRules()
            withContext(Dispatchers.Main) {
                if (rules.isEmpty()) label("暂无路由规则")
                rules.forEach { rule ->
                    entry(rule.displayName(), rule.mkSummary() + "\n" + rule.displayOutbound()) { editRule(rule) }
                }
            }
        })
    }

    private fun editRule(rule: RuleEntity) {
        val form = ReferenceDialogs.form(this)
        val name = ReferenceDialogs.field(this, form, "规则名称", rule.name)
        val domains = ReferenceDialogs.field(this, form, "域名", rule.domains,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE)
        val ips = ReferenceDialogs.field(this, form, "IP / CIDR", rule.ip,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE)
        val nodes = SagerDatabase.proxyDao.getAll()
        val targets = listOf(0L to "代理", -1L to "直连", -2L to "拦截") + nodes.map { it.id to it.displayName() }
        val spinner = Spinner(requireContext()).apply {
            adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, targets.map { it.second })
            setSelection(targets.indexOfFirst { it.first == rule.outbound }.coerceAtLeast(0))
        }
        form.addView(spinner, LinearLayout.LayoutParams(-1, ReferenceUi.dp(requireContext(), 48)))
        val enabled = SwitchMaterial(requireContext()).apply { text = "启用"; isChecked = rule.enabled }
        form.addView(enabled)
        val dialog = MaterialAlertDialogBuilder(requireContext()).setTitle("路由规则").setView(ScrollView(requireContext()).apply { addView(form) })
            .setNegativeButton(android.R.string.cancel, null).setPositiveButton(android.R.string.ok, null)
            .apply {
                if (rule.id != 0L) setNeutralButton(R.string.delete) { _, _ ->
                    MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.delete).setMessage(rule.displayName())
                        .setNegativeButton(android.R.string.cancel, null).setPositiveButton(android.R.string.ok) { _, _ ->
                            ReferenceDialogs.run(this@ReferenceSettingsFragment, { ProfileManager.deleteRule(rule.id) }) { reload(); render() }
                        }.show()
                }
            }.create()
        dialog.setOnShowListener {
            dialog.getButton(-1).setOnClickListener {
                if (domains.text.isBlank() && ips.text.isBlank() && rule.packages.isEmpty() && rule.config.isBlank()
                    && rule.port.isBlank() && rule.source.isBlank() && rule.protocol.isBlank() && rule.ruleset.isBlank()) {
                    domains.error = "至少填写一个匹配条件"
                    return@setOnClickListener
                }
                rule.name = name.text.toString().trim()
                rule.domains = domains.text.toString().trim()
                rule.ip = ips.text.toString().trim()
                rule.outbound = targets[spinner.selectedItemPosition].first
                rule.enabled = enabled.isChecked
                ReferenceDialogs.run(this, {
                    if (rule.id == 0L) ProfileManager.createRule(rule) else ProfileManager.updateRule(rule)
                }) { dialog.dismiss(); reload(); render() }
            }
        }
        dialog.show()
    }

    private fun loadApps() {
        if (apps.isNotEmpty()) { renderApps(); return }
        label("正在读取应用")
        ReferenceDialogs.run(this, {
            val pm = requireContext().packageManager
            apps = pm.getInstalledApplications(0).filter { it.packageName != BuildConfig.APPLICATION_ID }
                .map { it.packageName to it.loadLabel(pm).toString() }.sortedBy { it.second.lowercase() }
        }) { content.removeAllViews(); renderApps() }
    }

    private fun renderApps() {
        toolbar.menu.clear()
        val search = androidx.appcompat.widget.SearchView(requireContext()).apply {
            queryHint = "搜索应用"
            setOnQueryTextListener(object : androidx.appcompat.widget.SearchView.OnQueryTextListener {
                override fun onQueryTextChange(value: String): Boolean {
                    appQuery = value
                    renderAppRows()
                    return true
                }
                override fun onQueryTextSubmit(value: String) = true
            })
        }
        toolbar.menu.add("搜索应用").apply {
            actionView = search
            setIcon(R.drawable.ic_baseline_search_24)
            setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS or MenuItem.SHOW_AS_ACTION_COLLAPSE_ACTION_VIEW)
        }
        renderAppRows()
    }
    private fun renderAppRows() {
        content.removeAllViews()
        val rules = SagerDatabase.rulesDao.allRules()
        apps.filter { it.first.contains(appQuery, true) || it.second.contains(appQuery, true) }.forEach { (pkg, name) ->
            val rule = rules.firstOrNull { pkg in it.packages }
            entry(name, rule?.displayOutbound() ?: "跟随规则") {
                val nodes = SagerDatabase.proxyDao.getAll()
                val options = listOf(null to "跟随规则", 0L to "代理", -1L to "直连", -2L to "拦截") +
                    nodes.map { it.id to it.displayName() }
                MaterialAlertDialogBuilder(requireContext()).setTitle(name)
                    .setItems(options.map { it.second }.toTypedArray()) { _, index ->
                        ReferenceDialogs.run(this, {
                            // Keep other packages when editing a historical multi-app rule.
                            if (rule != null) {
                                val remaining = rule.packages - pkg
                                if (remaining.isEmpty()) ProfileManager.deleteRule(rule.id)
                                else { rule.packages = remaining; ProfileManager.updateRule(rule) }
                            }
                            options[index].first?.let { target ->
                                ProfileManager.createRule(RuleEntity(name = name, packages = setOf(pkg), enabled = true, outbound = target))
                            }
                        }) { reload(); renderAppRows() }
                    }.show()
            }
        }
    }
}

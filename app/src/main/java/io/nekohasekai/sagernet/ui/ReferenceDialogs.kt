package io.nekohasekai.sagernet.ui

import android.text.InputType
import android.widget.*
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.*
import io.nekohasekai.sagernet.group.RawUpdater
import io.nekohasekai.sagernet.group.GroupUpdater
import kotlinx.coroutines.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal object ReferenceDialogs {
    fun run(fragment: Fragment, action: suspend () -> Unit, done: () -> Unit = {}) {
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { action() }
                if (fragment.isAdded) done()
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) {
                if (fragment.isAdded) Toast.makeText(fragment.requireContext(),
                    error.message ?: fragment.getString(R.string.error_title), Toast.LENGTH_LONG).show()
            }
        }
    }
    fun field(fragment: Fragment, container: LinearLayout, label: String, value: String = "",
              input: Int = InputType.TYPE_CLASS_TEXT): EditText {
        val context = fragment.requireContext()
        container.addView(ReferenceUi.text(context, label, 13f, true),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = ReferenceUi.dp(context, 12) })
        return EditText(context).apply {
            setText(value)
            inputType = input
            setTextColor(ReferenceUi.color(context, R.color.ref_text))
            textSize = 15f
            minHeight = ReferenceUi.dp(context, 48)
            container.addView(this, LinearLayout.LayoutParams(-1, -2))
        }
    }
    fun form(fragment: Fragment) = ReferenceUi.column(fragment.requireContext()).apply {
        val padding = ReferenceUi.dp(context, 20)
        setPadding(padding, 0, padding, padding)
    }
    fun editNode(fragment: Fragment, profile: ProxyEntity) {
        val form = form(fragment)
        val bean = profile.requireBean()
        val name = field(fragment, form, "节点名称", bean.name.orEmpty())
        val host = field(fragment, form, "服务器地址", bean.serverAddress.orEmpty())
        val port = field(fragment, form, "端口", bean.serverPort.toString(), InputType.TYPE_CLASS_NUMBER)
        val dialog = MaterialAlertDialogBuilder(fragment.requireContext()).setTitle(R.string.edit)
            .setView(form).setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok, null).create()
        dialog.setOnShowListener {
            dialog.getButton(-1).setOnClickListener {
                val number = port.text.toString().toIntOrNull()
                if (host.text.isBlank() || number == null || number !in 1..65535) {
                    port.error = "请输入有效地址和端口"
                    return@setOnClickListener
                }
                bean.name = name.text.toString().trim()
                bean.serverAddress = host.text.toString().trim()
                bean.serverPort = number
                profile.putBean(bean)
                run(fragment, {
                    ProfileManager.updateProfile(profile)
                    if (DataStore.selectedProxy == profile.id && DataStore.serviceState.canStop) {
                        SagerNet.reloadService()
                    }
                }) { dialog.dismiss() }
            }
        }
        dialog.show()
    }
    fun editSubscription(fragment: Fragment, existing: ProxyGroup? = null) {
        val form = form(fragment)
        val name = field(fragment, form, "订阅名称", existing?.name.orEmpty())
        val url = field(fragment, form, "订阅地址", existing?.subscription?.link.orEmpty(),
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        val dialog = MaterialAlertDialogBuilder(fragment.requireContext())
            .setTitle(if (existing == null) "添加订阅" else "编辑订阅").setView(form)
            .setNegativeButton(android.R.string.cancel, null).setPositiveButton(android.R.string.ok, null).create()
        dialog.setOnShowListener {
            dialog.getButton(-1).setOnClickListener {
                val link = url.text.toString().trim().toHttpUrlOrNull()
                if (name.text.isBlank() || link == null) {
                    url.error = "请输入名称及有效的 HTTP / HTTPS 地址"
                    return@setOnClickListener
                }
                val group = existing ?: ProxyGroup(type = GroupType.SUBSCRIPTION)
                group.name = name.text.toString().trim()
                val subscription = group.subscription ?: SubscriptionBean().apply { initializeDefaultValues() }
                subscription.link = link.toString()
                group.subscription = subscription
                run(fragment, {
                    if (existing == null) GroupManager.createGroup(group) else GroupManager.updateGroup(group)
                }) {
                    dialog.dismiss()
                    if (existing == null) GroupUpdater.startUpdate(group, true)
                }
            }
        }
        dialog.show()
    }
    fun importNodes(fragment: Fragment) {
        val form = form(fragment)
        val value = field(fragment, form, "节点链接或配置", input =
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE)
        value.minLines = 4
        val dialog = MaterialAlertDialogBuilder(fragment.requireContext()).setTitle("导入节点")
            .setView(form).setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok, null).create()
        dialog.setOnShowListener {
            dialog.getButton(-1).setOnClickListener {
                val text = value.text.toString()
                run(fragment, {
                    val beans = RawUpdater.parseRaw(text)?.takeIf { it.isNotEmpty() }
                        ?: throw IllegalArgumentException("未找到有效节点")
                    val group = SagerDatabase.groupDao.allGroups().firstOrNull { it.type == GroupType.BASIC }
                        ?: GroupManager.createGroup(ProxyGroup(name = "本地节点"))
                    beans.forEach { ProfileManager.createProfile(group.id, it) }
                }) { dialog.dismiss() }
            }
        }
        dialog.show()
    }
}

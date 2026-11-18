package io.nekohasekai.sagernet.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.text.TextUtils
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Filter
import android.widget.Filterable
import android.widget.Toast
import androidx.annotation.UiThread
import androidx.core.view.ViewCompat
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.simplecityapps.recyclerview_fastscroll.views.FastScrollRecyclerView
import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.databinding.LayoutAppsBinding
import io.nekohasekai.sagernet.databinding.LayoutAppsItemBinding
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.crossFadeFrom
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.utils.PackageCache
import io.nekohasekai.sagernet.widget.ListListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import moe.matsuri.nb4a.utils.NGUtil
import kotlin.coroutines.coroutineContext

class AppManagerActivity : ThemedActivity() {
    companion object {
        @SuppressLint("StaticFieldLeak")
        private var instance: AppManagerActivity? = null
        private const val SWITCH = "switch"

        private val cachedApps
            get() = PackageCache.installedPackages.toMutableMap().apply {
                remove(BuildConfig.APPLICATION_ID)
            }
    }

    private class ProxiedApp(
        val packageName: String,
        // B6（N5）：同一包名在不同用户下是不同条目；UID 按用户分别解析。
        val userId: Int,
        val uid: Int,
        val name: CharSequence,
        val icon: android.graphics.drawable.Drawable?,
        val sys: Boolean,
    ) {
        val ref: String get() = "$userId:$packageName"
    }

    private inner class AppViewHolder(val binding: LayoutAppsItemBinding) : RecyclerView.ViewHolder(
        binding.root
    ),
        View.OnClickListener {
        private lateinit var item: ProxiedApp

        init {
            binding.root.setOnClickListener(this)
        }

        fun bind(app: ProxiedApp) {
            item = app
            binding.itemicon.setImageDrawable(app.icon)
            binding.title.text = app.name
            // B6（N5）：同一包名在不同用户/分身下分别列出，分属不同用户分组。
            binding.desc.text = "${app.packageName} (${itemView.context.getString(R.string.per_user_apps_user, app.userId)} · ${app.uid})"
            binding.itemcheck.isChecked = isProxiedApp(app)
        }

        fun handlePayload(payloads: List<String>) {
            if (payloads.contains(SWITCH)) binding.itemcheck.isChecked = isProxiedApp(item)
        }

        override fun onClick(v: View?) {
            if (isProxiedApp(item)) proxiedRefs.remove(item.ref) else proxiedRefs.add(item.ref)
            persistProxiedRefs()
            appsAdapter.notifyItemRangeChanged(0, appsAdapter.itemCount, SWITCH)
        }
    }

    private inner class AppsAdapter : RecyclerView.Adapter<AppViewHolder>(),
        Filterable,
        FastScrollRecyclerView.SectionedAdapter {
        var filteredApps = apps

        suspend fun reload() {
            PackageCache.reload()
            // B6（N5）：刷新跨用户 UID 映射，让工作资料/分身应用分别列出。
            PackageCache.refreshMultiUserUids()
            apps = buildList {
                cachedApps.mapNotNull { (packageName, packageInfo) ->
                    coroutineContext[Job]!!.ensureActive()
                    packageInfo.applicationInfo?.let { appInfo ->
                        ProxiedApp(
                            packageName = packageName,
                            userId = 0,
                            uid = appInfo.uid,
                            name = appInfo.loadLabel(packageManager),
                            icon = appInfo.loadIcon(packageManager),
                            sys = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                        )
                    }
                }.let { addAll(it) }
                // 其他用户：无 PackageManager 条目，只显示包名与按用户查到的 UID。
                PackageCache.multiUserEntries()
                    .filter { (userId, _, _) -> userId != 0 }
                    .forEach { (userId, packageName, uid) ->
                        coroutineContext[Job]!!.ensureActive()
                        add(
                            ProxiedApp(
                                packageName = packageName,
                                userId = userId,
                                uid = uid,
                                name = packageName,
                                icon = null,
                                sys = false,
                            )
                        )
                    }
            }.sortedWith(compareBy({ !isProxiedApp(it) }, { it.name.toString() }, { it.userId }))
        }

        override fun onBindViewHolder(holder: AppViewHolder, position: Int) =
            holder.bind(filteredApps[position])

        override fun onBindViewHolder(holder: AppViewHolder, position: Int, payloads: List<Any>) {
            if (payloads.isNotEmpty()) {
                @Suppress("UNCHECKED_CAST") holder.handlePayload(payloads as List<String>)
                return
            }

            onBindViewHolder(holder, position)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppViewHolder =
            AppViewHolder(LayoutAppsItemBinding.inflate(layoutInflater, parent, false))

        override fun getItemCount(): Int = filteredApps.size

        private val filterImpl = object : Filter() {
            override fun performFiltering(constraint: CharSequence) = FilterResults().apply {
                var filteredApps = if (constraint.isEmpty()) apps else apps.filter {
                    it.name.contains(constraint, true) || it.packageName.contains(
                        constraint, true
                    ) || it.uid.toString().contains(constraint)
                }
                if (!sysApps) filteredApps = filteredApps.filter { !it.sys }
                count = filteredApps.size
                values = filteredApps
            }

            override fun publishResults(constraint: CharSequence, results: FilterResults) {
                @Suppress("UNCHECKED_CAST")
                filteredApps = results.values as List<ProxiedApp>
                notifyDataSetChanged()
            }
        }

        override fun getFilter(): Filter = filterImpl

        override fun getSectionName(position: Int): String {
            return filteredApps[position].name.firstOrNull()?.toString() ?: ""
        }

    }

    private val loading by lazy { findViewById<View>(R.id.loading) }

    private lateinit var binding: LayoutAppsBinding
    // B6（N5）：勾选态按 <userId>:<package> 引用存取，不再按 UID 存取
    //（同一包名在不同用户下 UID 不同、须分别勾选）。
    private val proxiedRefs = mutableSetOf<String>()
    private var loader: Job? = null
    private var apps = emptyList<ProxiedApp>()
    private val appsAdapter = AppsAdapter()

    private fun initProxiedUids() {
        proxiedRefs.clear()
        val migrated = io.nekohasekai.sagernet.bg.PerUserApps.migrateIfNeeded()
        proxiedRefs.addAll(io.nekohasekai.sagernet.bg.PerUserApps.currentRefs().map { it.key })
        if (migrated) {
            val retained = DataStore.individualUnresolved.lineSequence()
                .filter { it.isNotBlank() }.count()
            if (retained > 0) {
                Toast.makeText(this, getString(R.string.per_user_apps_legacy_kept, retained), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun persistProxiedRefs() {
        DataStore.individualPerUser = apps.filter { isProxiedApp(it) }
            .joinToString("\n") { it.ref }
    }

    private fun isProxiedApp(app: ProxiedApp) = proxiedRefs.contains(app.ref)

    @UiThread
    private fun loadApps() {
        loader?.cancel()
        loader = lifecycleScope.launchWhenCreated {
            loading.crossFadeFrom(binding.list)
            val adapter = binding.list.adapter as AppsAdapter
            withContext(Dispatchers.IO) { adapter.reload() }
            adapter.filter.filter(binding.search.text?.toString() ?: "")
            if (apps.isEmpty()) {
                binding.list.visibility = View.GONE
                binding.appPlaceholder.root.crossFadeFrom(loading)
            } else {
                binding.list.crossFadeFrom(loading)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = LayoutAppsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.appPlaceholder.openSettings.setOnClickListener {
            val intent =
                Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = android.net.Uri.fromParts("package", packageName, null)
                }
            startActivity(intent)
        }

        setSupportActionBar(binding.toolbar)
        supportActionBar?.apply {
            setTitle(R.string.proxied_apps)
            setDisplayHomeAsUpEnabled(true)
            setHomeAsUpIndicator(R.drawable.ic_navigation_close)
        }

        // fix: 仅打开本页不再自动开启分应用代理；状态如实显示，用户选择「代理/绕过」才开启。
        binding.bypassGroup.check(
            when {
                !DataStore.proxyApps -> R.id.appProxyModeDisable
                DataStore.bypass -> R.id.appProxyModeBypass
                else -> R.id.appProxyModeOn
            }
        )
        binding.bypassGroup.setOnCheckedChangeListener { _, checkedId ->
            when (checkedId) {
                R.id.appProxyModeDisable -> DataStore.proxyApps = false

                R.id.appProxyModeOn -> {
                    DataStore.bypass = false
                    DataStore.proxyApps = true
                }

                R.id.appProxyModeBypass -> {
                    DataStore.bypass = true
                    DataStore.proxyApps = true
                }
            }
        }
        binding.autoSelectProxyApps.setOnClickListener { selectProxyApp() }

        initProxiedUids()
        binding.list.layoutManager = LinearLayoutManager(this, RecyclerView.VERTICAL, false)
        binding.list.itemAnimator = DefaultItemAnimator()
        binding.list.adapter = appsAdapter

        ViewCompat.setOnApplyWindowInsetsListener(binding.root, ListListener)

        binding.search.addTextChangedListener {
            appsAdapter.filter.filter(it?.toString() ?: "")
        }

        binding.showSystemApps.isChecked = sysApps
        binding.showSystemApps.setOnCheckedChangeListener { _, isChecked ->
            sysApps = isChecked
            appsAdapter.filter.filter(binding.search.text?.toString() ?: "")
        }

        instance = this
        loadApps()
    }

    private var sysApps = true

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.per_app_proxy_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_invert_selections -> {
                runOnDefaultDispatcher {
                    for (app in apps) {
                        if (proxiedRefs.contains(app.ref)) proxiedRefs.remove(app.ref) else proxiedRefs.add(app.ref)
                    }
                    persistProxiedRefs()
                    apps = apps.sortedWith(compareBy({ !isProxiedApp(it) }, { it.name.toString() }, { it.userId }))
                    onMainDispatcher {
                        appsAdapter.filter.filter(binding.search.text?.toString() ?: "")
                    }
                }

                return true
            }

            R.id.action_clear_selections -> {
                runOnDefaultDispatcher {
                    proxiedRefs.clear()
                    DataStore.individualPerUser = ""
                    apps = apps.sortedWith(compareBy({ !isProxiedApp(it) }, { it.name.toString() }, { it.userId }))
                    onMainDispatcher {
                        appsAdapter.filter.filter(binding.search.text?.toString() ?: "")
                    }
                }
            }

            R.id.action_export_clipboard -> {
                val success =
                    SagerNet.trySetPrimaryClip("${DataStore.bypass}\n${DataStore.individualPerUser}")
                Snackbar.make(
                    binding.list,
                    if (success) R.string.action_export_msg else R.string.action_export_err,
                    Snackbar.LENGTH_LONG
                ).show()
                return true
            }

            R.id.action_import_clipboard -> {
                val proxiedAppString =
                    SagerNet.clipboard.primaryClip?.getItemAt(0)?.text?.toString()
                if (!proxiedAppString.isNullOrEmpty()) {
                    val i = proxiedAppString.indexOf('\n')
                    try {
                        val (enabled, apps) = if (i < 0) {
                            proxiedAppString to ""
                        } else proxiedAppString.substring(
                            0, i
                        ) to proxiedAppString.substring(i + 1)
                        binding.bypassGroup.check(if (enabled.toBoolean()) R.id.appProxyModeBypass else R.id.appProxyModeOn)
                        // 新格式为 <userId>:<package> 行；旧格式（裸包名/UID）走迁移，
                        // 无法反查的行保留在旧字段并提示用户手动确认。
                        val (migrated, retained) = io.nekohasekai.sagernet.bg.PerUserApps.migrateLegacy(
                            apps.lineSequence().toList()
                        )
                        DataStore.individualPerUser =
                            io.nekohasekai.sagernet.bg.PerUserApps.serializeRefs(migrated)
                        if (retained.isNotEmpty()) {
                            DataStore.individualUnresolved = retained.joinToString("\n")
                            Toast.makeText(
                                this,
                                getString(R.string.per_user_apps_legacy_kept, retained.size),
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                        Snackbar.make(
                            binding.list, R.string.action_import_msg, Snackbar.LENGTH_LONG
                        ).show()
                        initProxiedUids()
                        appsAdapter.notifyItemRangeChanged(0, appsAdapter.itemCount, SWITCH)
                        return true
                    } catch (_: IllegalArgumentException) {
                    }
                }
                Snackbar.make(binding.list, R.string.action_import_err, Snackbar.LENGTH_LONG).show()
            }
        }
        return super.onOptionsItemSelected(item)
    }

    private fun selectProxyApp() {
        MaterialAlertDialogBuilder(this).setTitle(R.string.confirm)
            .setMessage(R.string.auto_select_proxy_apps_message)
            .setPositiveButton(R.string.yes) { _, _ ->
                try {
                    val needProxyAppsList = getAutoProxyApps("")
                    val bypass = DataStore.bypass
                    proxiedRefs.clear()
                    for (app in cachedApps) {
                        val needProxy =
                            needProxyAppsList.contains(app.key) || (app.value.applicationInfo?.uid
                                ?: 0) == 1000
                        if (needProxy) {
                            if (!bypass) {
                                app.value.applicationInfo?.apply {
                                    proxiedRefs.add("0:${app.key}")
                                }
                            }
                        } else {
                            if (bypass) {
                                app.value.applicationInfo?.apply {
                                    proxiedRefs.add("0:${app.key}")
                                }
                            }
                        }
                    }
                    persistProxiedRefs()
                    apps = apps.sortedWith(compareBy({ !isProxiedApp(it) }, { it.name.toString() }, { it.userId }))
                    appsAdapter.filter.filter(binding.search.text?.toString() ?: "")
                } catch (e: Exception) {
                    Logs.e(e)
                }
            }
            .setNegativeButton(R.string.no, null)
            .show()
    }

    private fun getAutoProxyApps(content: String): List<String> {
        var list = listOf<String>()
        try {
            val proxyApps = if (TextUtils.isEmpty(content)) {
                NGUtil.readTextFromAssets(app, "proxy_packagename.txt")
            } else {
                content
            }
            if (!TextUtils.isEmpty(proxyApps)) {
                list = proxyApps.split("\n")
            }
        } catch (_: Exception) {
        }
        return list
    }

    override fun supportNavigateUpTo(upIntent: Intent) =
        super.supportNavigateUpTo(upIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))

    override fun onKeyUp(keyCode: Int, event: KeyEvent?) = if (keyCode == KeyEvent.KEYCODE_MENU) {
        if (binding.toolbar.isOverflowMenuShowing) binding.toolbar.hideOverflowMenu() else binding.toolbar.showOverflowMenu()
    } else super.onKeyUp(keyCode, event)

    override fun onDestroy() {
        instance = null
        loader?.cancel()
        super.onDestroy()
    }
}

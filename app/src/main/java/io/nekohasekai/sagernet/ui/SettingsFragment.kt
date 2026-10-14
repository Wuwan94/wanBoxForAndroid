// @author 雾晚
package io.nekohasekai.sagernet.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.R

/** Settings landing page; existing preference handlers live in focused subpages. */
class SettingsFragment : ToolbarFragment() {
    private val section get() = arguments?.getString("section")
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(
        if (section == null) R.layout.layout_settings_home else R.layout.layout_config_settings,
        container,
        false
    )

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val host = requireActivity() as MainActivity
        val page = section
        if (page == null) {
            toolbar.setTitle(R.string.settings)
            toolbar.menu.add(Menu.NONE, R.id.nav_tools, Menu.NONE, R.string.menu_tools)
            toolbar.menu.add(Menu.NONE, R.id.nav_docs, Menu.NONE, R.string.menu_documentation)
            toolbar.setOnMenuItemClickListener {
                host.displayFragmentWithId(it.itemId)
                true
            }
            view.findViewById<ComposeView>(R.id.settings_compose)?.apply {
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
                setContent {
                    SettingsHomeComposeScreen(
                        versionName = BuildConfig.VERSION_NAME,
                        onOpenPerAppProxy = {
                            startActivity(Intent(requireContext(), AppManagerActivity::class.java))
                        },
                        onOpenSection = { destination ->
                            host.displayFragment(forSection(destination))
                        }
                    )
                }
            }
            view.findViewById<TextView>(R.id.settings_version)?.text = BuildConfig.VERSION_NAME
            view.findViewById<View>(R.id.settings_apps)?.setOnClickListener {
                startActivity(Intent(requireContext(), AppManagerActivity::class.java))
            }
            mapOf(
                R.id.settings_runtime to "runtime",
                R.id.settings_http_tls to "http_tls",
                R.id.settings_dns to "dns",
                R.id.settings_route to "route",
                R.id.settings_network to "speedtest",
                R.id.settings_appearance to "appearance",
                R.id.settings_logs to "logs",
                R.id.settings_backup to "other",
                R.id.settings_about to "other"
            ).forEach { (id, destination) ->
                view.findViewById<View>(id)?.setOnClickListener {
                    host.displayFragment(forSection(destination))
                }
            }
        } else {
            toolbar.setTitle(
                when (page) {
                    "runtime" -> R.string.settings_group_runtime
                    "http_tls" -> R.string.settings_group_http_tls
                    "dns" -> R.string.settings_group_dns
                    "route" -> R.string.settings_group_routing
                    "speedtest", "network" -> R.string.settings_group_speedtest
                    "appearance" -> R.string.minimal_appearance
                    "logs" -> R.string.minimal_logs
                    "other" -> R.string.settings_group_other
                    "backup" -> R.string.minimal_backup
                    else -> R.string.settings
                }
            )
            toolbar.setNavigationIcon(R.drawable.baseline_arrow_back_24)
            toolbar.setNavigationOnClickListener { host.displayFragmentWithId(R.id.nav_settings) }
            if (childFragmentManager.findFragmentById(R.id.settings) == null) {
                val content: Fragment = if (page == "backup") {
                    BackupFragment()
                } else {
                    SettingsPreferenceFragment().apply {
                        arguments = Bundle().apply { putString("section", page) }
                    }
                }
                childFragmentManager.beginTransaction().replace(R.id.settings, content).commit()
            }
        }
    }

    override fun onBackPressed(): Boolean {
        if (section == null) return false
        (activity as? MainActivity)?.displayFragmentWithId(R.id.nav_settings)
        return true
    }

    companion object {
        fun forSection(section: String) = SettingsFragment().apply {
            arguments = Bundle().apply { putString("section", section) }
        }
    }
}

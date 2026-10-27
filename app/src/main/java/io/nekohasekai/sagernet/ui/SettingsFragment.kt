package io.nekohasekai.sagernet.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.R

/** Settings landing page; existing preference handlers live in focused subpages. */
class SettingsFragment : ToolbarFragment() {
    private val section get() = arguments?.getString("section")
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(if (section == null) R.layout.layout_settings_home else R.layout.layout_config_settings, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val host = requireActivity() as MainActivity
        val page = section
        if (page == null) {
            toolbar.setTitle(R.string.settings)
            toolbar.menu.add(Menu.NONE, R.id.settings_advanced, Menu.NONE, R.string.minimal_advanced)
            toolbar.menu.add(Menu.NONE, R.id.nav_tools, Menu.NONE, R.string.menu_tools)
            toolbar.menu.add(Menu.NONE, R.id.nav_docs, Menu.NONE, R.string.menu_documentation)
            toolbar.setOnMenuItemClickListener {
                when (it.itemId) {
                    R.id.settings_advanced -> host.displayFragment(forSection("advanced"))
                    else -> host.displayFragmentWithId(it.itemId)
                }
                true
            }
            view.findViewById<TextView>(R.id.settings_version).text = BuildConfig.VERSION_NAME
            view.findViewById<View>(R.id.settings_apps).setOnClickListener { startActivity(Intent(requireContext(), AppManagerActivity::class.java)) }
            mapOf(R.id.settings_root to "root", R.id.settings_dns to "dns", R.id.settings_network to "network", R.id.settings_appearance to "appearance", R.id.settings_backup to "backup").forEach { (id, destination) ->
                view.findViewById<View>(id).setOnClickListener { host.displayFragment(forSection(destination)) }
            }
            view.findViewById<View>(R.id.settings_route).setOnClickListener { host.displayFragment(RouteFragment()) }
            view.findViewById<View>(R.id.settings_logs).setOnClickListener { host.displayFragment(LogcatFragment()) }
            view.findViewById<View>(R.id.settings_about).setOnClickListener { host.displayFragment(AboutFragment()) }
        } else {
            toolbar.setTitle(when (page) {
                "root" -> R.string.minimal_root
                "dns" -> R.string.minimal_dns
                "network" -> R.string.minimal_network
                "appearance" -> R.string.minimal_appearance
                "backup" -> R.string.minimal_backup
                else -> R.string.minimal_advanced
            })
            toolbar.setNavigationIcon(R.drawable.baseline_arrow_back_24)
            toolbar.setNavigationOnClickListener { host.displayFragmentWithId(R.id.nav_settings) }
            if (childFragmentManager.findFragmentById(R.id.settings) == null) {
                val content: Fragment = if (page == "backup") BackupFragment() else SettingsPreferenceFragment().apply {
                    arguments = Bundle().apply { putString("section", page) }
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
        fun forSection(section: String) = SettingsFragment().apply { arguments = Bundle().apply { putString("section", section) } }
    }
}

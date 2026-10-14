// @author 雾晚
package io.nekohasekai.sagernet.ui

import android.content.Intent
import android.graphics.RectF
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.R

/** Settings landing page; existing preference handlers live in focused subpages. */
class SettingsFragment : ToolbarFragment() {
    private val section get() = arguments?.getString("section")
    private val renderedComposeItemBounds = LinkedHashMap<String, RectF>()
    private val renderedComposeItemTitles = LinkedHashMap<String, String>()

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
            renderedComposeItemBounds.clear()
            renderedComposeItemTitles.clear()
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
                        },
                        onItemPositioned = { key, title, bounds ->
                            if (bounds.width > 0f && bounds.height > 0f) {
                                renderedComposeItemBounds[key] = RectF(bounds.left, bounds.top, bounds.right, bounds.bottom)
                                renderedComposeItemTitles[key] = title
                            } else {
                                renderedComposeItemBounds.remove(key)
                                renderedComposeItemTitles.remove(key)
                            }
                        },
                        onDisposeHome = {
                            renderedComposeItemBounds.clear()
                            renderedComposeItemTitles.clear()
                        }
                    )
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

    override fun onDestroyView() {
        renderedComposeItemBounds.clear()
        renderedComposeItemTitles.clear()
        super.onDestroyView()
    }

    fun isComposeHomeReady(): Boolean {
        val composeView = view?.findViewById<ComposeView>(R.id.settings_compose) ?: return false
        if (!composeView.isShown || !composeView.isLaidOut || composeView.width <= 0 || composeView.height <= 0) {
            return false
        }
        if ("per_app_proxy" !in renderedComposeItemBounds) return false
        val expectedKeys = SettingsSectionGroup.entries.map { it.sectionKey }
        if (!renderedComposeItemBounds.keys.containsAll(expectedKeys)) return false
        return expectedKeys.all { key ->
            val b = renderedComposeItemBounds[key]
            b != null && b.width() > 0f && b.height() > 0f
        }
    }

    fun getRenderedSectionKeys(): List<String> =
        SettingsSectionGroup.entries.map { it.sectionKey }.filter { key ->
            val b = renderedComposeItemBounds[key]
            b != null && b.width() > 0f && b.height() > 0f
        }

    fun hasRenderedItem(key: String): Boolean {
        val b = renderedComposeItemBounds[key] ?: return false
        return b.width() > 0f && b.height() > 0f
    }

    fun getRenderedItemTitle(key: String): String? = renderedComposeItemTitles[key]

    fun getComposeItemBounds(key: String): RectF? = renderedComposeItemBounds[key]?.let { RectF(it) }

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

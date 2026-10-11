package io.nekohasekai.sagernet.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore

/**
 * @author 雾晚：设置页列表导航（浅灰简洁版）
 * 9 个入口，点击进入对应的偏好设置子页面
 */
class SettingsMenuFragment : ToolbarFragment(R.layout.layout_settings_menu) {

    private data class MenuItem(
        val titleRes: Int,
        val summaryRes: Int,
        val iconRes: Int,
        val prefXmlRes: Int,
    )

    private val menuItems = listOf(
        MenuItem(
            R.string.settings_menu_per_app_proxy,
            R.string.settings_menu_per_app_proxy_summary,
            R.drawable.ic_navigation_apps,
            R.xml.preferences_per_app_proxy,
        ),
        MenuItem(
            R.string.settings_menu_root_module,
            R.string.settings_menu_root_module_summary,
            R.drawable.ic_device_developer_mode,
            R.xml.preferences_root_module,
        ),
        MenuItem(
            R.string.settings_menu_dns,
            R.string.settings_menu_dns_summary,
            R.drawable.ic_action_dns,
            R.xml.preferences_dns,
        ),
        MenuItem(
            R.string.settings_menu_routing,
            R.string.settings_menu_routing_summary,
            R.drawable.ic_baseline_add_road_24,
            R.xml.preferences_routing,
        ),
        MenuItem(
            R.string.settings_menu_network_policy,
            R.string.settings_menu_network_policy_summary,
            R.drawable.ic_baseline_folder_open_24,
            R.xml.preferences_network_policy,
        ),
        MenuItem(
            R.string.settings_menu_appearance,
            R.string.settings_menu_appearance_summary,
            R.drawable.ic_baseline_color_lens_24,
            R.xml.preferences_appearance,
        ),
        MenuItem(
            R.string.settings_menu_log,
            R.string.settings_menu_log_summary,
            R.drawable.ic_action_description,
            R.xml.preferences_log,
        ),
        MenuItem(
            R.string.settings_menu_backup,
            R.string.settings_menu_backup_summary,
            R.drawable.ic_baseline_folder_open_24,
            R.xml.preferences_backup,
        ),
        MenuItem(
            R.string.settings_menu_about,
            R.string.settings_menu_about_summary,
            R.drawable.ic_baseline_info_24,
            R.xml.preferences_about,
        ),
    )

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View? {
        val view = super.onCreateView(inflater, container, savedInstanceState) ?: return null

        val recyclerView = view.findViewById<RecyclerView>(R.id.settingsMenuList)
        recyclerView.layoutManager = LinearLayoutManager(context)
        recyclerView.adapter = MenuAdapter(menuItems) { item ->
            // 打开对应的偏好设置子页面
            parentFragmentManager.beginTransaction()
                .replace(
                    R.id.settings,
                    SettingsPreferenceFragment.newInstance(item.prefXmlRes),
                )
                .addToBackStack(null)
                .commit()
        }

        val versionText = view.findViewById<TextView>(R.id.settingsVersion)
        versionText.text = DataStore.versionName

        return view
    }

    private class MenuAdapter(
        private val items: List<MenuItem>,
        private val onClick: (MenuItem) -> Unit,
    ) : RecyclerView.Adapter<MenuAdapter.ViewHolder>() {

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val card: MaterialCardView = view as MaterialCardView
            val icon: ImageView = view.findViewById(R.id.menuIcon)
            val title: TextView = view.findViewById(R.id.menuTitle)
            val summary: TextView = view.findViewById(R.id.menuSummary)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_settings_menu, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            holder.icon.setImageResource(item.iconRes)
            holder.title.setText(item.titleRes)
            holder.summary.setText(item.summaryRes)
            holder.card.setOnClickListener { onClick(item) }
        }

        override fun getItemCount() = items.size
    }
}

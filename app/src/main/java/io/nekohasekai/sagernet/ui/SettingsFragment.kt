package io.nekohasekai.sagernet.ui

import android.os.Bundle
import android.view.View
import android.view.Gravity
import android.widget.*
import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.R

class SettingsFragment : ReferenceFragment() {
    override fun onViewCreated(view: View, state: Bundle?) {
        super.onViewCreated(view, state)
        toolbar.setTitle(R.string.ref_settings)
        val context = requireContext()
        val content = ReferenceUi.column(context).apply {
            setPadding(ReferenceUi.dp(context, 20), 0, ReferenceUi.dp(context, 20), 0)
        }
        body.addView(ScrollView(context).apply { addView(content) }, LinearLayout.LayoutParams(-1, -1))
        fun divider(gap: Int = 0) {
            content.addView(ReferenceUi.divider(context), LinearLayout.LayoutParams(-1, ReferenceUi.dp(context, 1)).apply {
                topMargin = ReferenceUi.dp(context, gap)
                bottomMargin = ReferenceUi.dp(context, gap)
            })
        }
        fun entry(title: Int, icon: Int, subtitle: Int? = null, action: () -> Unit) {
            val row = ReferenceUi.row(context).apply {
                minimumHeight = ReferenceUi.dp(context, if (subtitle == null) 56 else 84)
                isFocusable = true
                setOnClickListener { action() }
            }
            row.addView(ReferenceUi.icon(context, icon, ""), LinearLayout.LayoutParams(
                ReferenceUi.dp(context, 52), ReferenceUi.dp(context, 48)))
            val labels = ReferenceUi.column(context)
            labels.addView(ReferenceUi.text(context, getString(title), 16f, bold = subtitle != null),
                LinearLayout.LayoutParams(-1, -2))
            if (subtitle != null) labels.addView(ReferenceUi.text(context, getString(subtitle), 13f, true),
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = ReferenceUi.dp(context, 5) })
            row.addView(labels, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = ReferenceUi.dp(context, 8) })
            row.addView(ReferenceUi.icon(context, R.drawable.ic_baseline_chevron_right_24, ""),
                LinearLayout.LayoutParams(ReferenceUi.dp(context, 32), ReferenceUi.dp(context, 48)))
            content.addView(row, LinearLayout.LayoutParams(-1, -2))
        }
        fun open(fragment: ToolbarFragment) { (activity as? MainActivity)?.openDetail(fragment) }
        fun preferences(title: Int, section: String) {
            open(ReferenceSettingsFragment().apply {
                arguments = Bundle().apply { putInt("title", title); putString("section", section) }
            })
        }
        divider()
        entry(R.string.per_app_proxy, R.drawable.ic_navigation_apps, R.string.per_app_proxy_summary) {
            preferences(R.string.per_app_proxy, "apps")
        }
        divider(10)
        entry(R.string.ref_root_module, R.drawable.ic_device_developer_mode) {
            preferences(R.string.ref_root_module, "root")
        }
        divider()
        entry(R.string.ref_dns, R.drawable.ic_action_dns) { preferences(R.string.ref_dns, "dns") }
        divider()
        entry(R.string.ref_route, R.drawable.ic_baseline_hub_24) { preferences(R.string.ref_route, "routes") }
        divider()
        entry(R.string.ref_network, R.drawable.ic_baseline_cast_connected_24) {
            preferences(R.string.ref_network, "network")
        }
        divider(10)
        entry(R.string.ref_appearance, R.drawable.ic_baseline_color_lens_24) {
            preferences(R.string.ref_appearance, "appearance")
        }
        divider()
        entry(R.string.ref_diagnostics, R.drawable.ic_action_description_outlined) { preferences(R.string.ref_diagnostics, "logs") }
        divider()
        entry(R.string.ref_backup, R.drawable.ic_baseline_folder_open_24) { preferences(R.string.ref_backup, "backup") }
        divider()
        entry(R.string.ref_about, R.drawable.ic_baseline_info_24) { preferences(R.string.ref_about, "about") }
        divider()
        content.addView(ReferenceUi.text(context, BuildConfig.VERSION_NAME, 12f, true).apply {
            gravity = Gravity.CENTER
            setPadding(0, ReferenceUi.dp(context, 18), 0, ReferenceUi.dp(context, 18))
        }, LinearLayout.LayoutParams(-1, -2))
    }
}

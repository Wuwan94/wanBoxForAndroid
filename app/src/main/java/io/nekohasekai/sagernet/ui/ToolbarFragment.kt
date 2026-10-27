// @author 雾晚
package io.nekohasekai.sagernet.ui

import android.graphics.Color
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import androidx.appcompat.widget.Toolbar
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import com.google.android.material.appbar.AppBarLayout
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.ktx.getColorAttr
import io.nekohasekai.sagernet.utils.Theme

open class ToolbarFragment : Fragment {

    constructor() : super()
    constructor(contentLayoutId: Int) : super(contentLayoutId)

    private var toolbarView: Toolbar? = null
    var toolbar: Toolbar
        get() = requireNotNull(toolbarView)
        set(value) { toolbarView = value }
    val isToolbarInitialized: Boolean get() = toolbarView != null

    override fun onDestroyView() {
        toolbarView?.setOnClickListener(null)
        toolbarView?.setNavigationOnClickListener(null)
        toolbarView = null
        super.onDestroyView()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val tb = view.findViewById<Toolbar?>(R.id.toolbar)
        if (tb != null) {
            toolbar = tb
            val mainPage = this is DashboardFragment || this is ConfigurationFragment || this is GroupFragment || this is SettingsFragment
            if (mainPage) {
                toolbar.navigationIcon = null
            } else {
                toolbar.setNavigationIcon(R.drawable.baseline_arrow_back_24)
                toolbar.setNavigationOnClickListener {
                    (activity as? MainActivity)?.displayFragmentWithId(R.id.nav_settings)
                }
            }
            val primaryTextColor = requireContext().getColor(R.color.minimal_text)
            toolbar.setTitleTextColor(primaryTextColor)
            toolbar.navigationIcon?.let {
                val tinted = it.mutate()
                DrawableCompat.setTint(tinted, primaryTextColor)
                toolbar.navigationIcon = tinted
            }
            toolbar.overflowIcon?.let {
                val tinted = it.mutate()
                DrawableCompat.setTint(tinted, primaryTextColor)
                toolbar.overflowIcon = tinted
            }
        }
        val appbar = view.findViewById<AppBarLayout?>(R.id.appbar)
        if (appbar != null) {
            UiChrome.apply(appbar)
            ViewCompat.setOnApplyWindowInsetsListener(appbar) { v, insets ->
                val bars = insets.getInsets(
                    WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
                )
                v.updatePadding(top = bars.top)
                insets
            }
            ViewCompat.requestApplyInsets(appbar)
        }
    }

    open fun onKeyDown(ketCode: Int, event: KeyEvent) = false
    open fun onBackPressed(): Boolean = false
}

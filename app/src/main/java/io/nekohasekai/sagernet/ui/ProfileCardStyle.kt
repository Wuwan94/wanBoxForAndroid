package io.nekohasekai.sagernet.ui

import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore

internal class ProfileCardStyle(private val card: View) {
    private fun dp(value: Int) = (value * card.resources.displayMetrics.density + 0.5f).toInt()
    fun selection(selected: Boolean, connected: Boolean) {
        card.findViewById<View>(R.id.profile_selection_label).isVisible = false
        card.findViewById<ImageView>(R.id.profile_check).apply {
            isVisible = true
            setImageResource(if (selected) R.drawable.ic_minimal_selected else R.drawable.ic_minimal_circle)
        }
        val state = card.context.getString(if (connected) R.string.ui_profile_connected else R.string.ui_profile_selected)
        ViewCompat.setStateDescription(card, if (selected) state else null)
    }
    private var actionRow: LinearLayout? = null
    private fun arrangeActions(threeColumns: Boolean) {
        val container = card.findViewById<LinearLayout>(R.id.container)
        val title = card.findViewById<LinearLayout>(R.id.profile_title_area)
        val check = card.findViewById<ImageView>(R.id.profile_check)
        val menu = card.findViewById<View>(R.id.double_column_menu)
        fun detach(view: View) { (view.parent as? ViewGroup)?.removeView(view) }
        if (threeColumns) {
            if (actionRow == null) {
                val row = LinearLayout(card.context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                detach(check); detach(menu)
                row.addView(check)
                row.addView(View(card.context), LinearLayout.LayoutParams(0, 1, 1f))
                row.addView(menu)
                container.addView(row, 0, LinearLayout.LayoutParams(-1, -2))
                actionRow = row
            }
            container.orientation = LinearLayout.VERTICAL
            title.layoutParams = LinearLayout.LayoutParams(-1, -2)
        } else {
            actionRow?.let { row ->
                detach(check); detach(menu); container.removeView(row)
                container.addView(check, 0)
                container.addView(menu)
                actionRow = null
            }
            container.orientation = LinearLayout.HORIZONTAL
            title.layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
    }
    fun apply(compact: Boolean) {
        arrangeActions(compact && DataStore.nodeColumnCount == 3)
        val name = card.findViewById<TextView>(R.id.profile_name)
        name.textSize = DataStore.nodeNameTextSize.coerceIn(10, 24).toFloat()
        name.maxLines = if (compact) 1 else Int.MAX_VALUE
        name.ellipsize = if (compact) TextUtils.TruncateAt.MIDDLE else null
        card.findViewById<TextView>(R.id.profile_address).apply { maxLines = if (compact) 1 else Int.MAX_VALUE; ellipsize = if (compact) TextUtils.TruncateAt.END else null }
        card.findViewById<TextView>(R.id.profile_status).apply { maxLines = if (compact) 2 else Int.MAX_VALUE; textSize = 12f; ellipsize = if (compact) TextUtils.TruncateAt.END else null }
        card.findViewById<TextView>(R.id.profile_type).apply { textSize = 12f; setPadding(0, 0, 0, 0) }
        card.findViewById<LinearLayout>(R.id.profile_title_area).apply {
            orientation = LinearLayout.VERTICAL
            updatePadding(top = dp(4), bottom = dp(2))
        }
        card.findViewById<LinearLayout>(R.id.profile_status_area).apply {
            orientation = LinearLayout.VERTICAL
            updatePadding(bottom = dp(when (DataStore.nodeCardDensity) { 0 -> 6; 2 -> 18; else -> 10 }))
        }
        val gap = DataStore.nodeCardSpacing.coerceIn(0, 20)
        val params = card.layoutParams as? ViewGroup.MarginLayoutParams
        params?.let { it.setMargins(dp(gap / 2), dp(gap / 2), dp(gap / 2), dp(gap / 2)); card.layoutParams = it }
    }
}

package io.nekohasekai.sagernet.ui

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import com.google.android.material.appbar.AppBarLayout
import io.nekohasekai.sagernet.R

/** Native components shared by the four reference screens. */
internal object ReferenceUi {
    fun dp(context: Context, value: Int) = (context.resources.displayMetrics.density * value).toInt()
    fun color(context: Context, id: Int) = ContextCompat.getColor(context, id)
    fun text(context: Context, value: CharSequence = "", size: Float = 14f, secondary: Boolean = false,
             bold: Boolean = false) = TextView(context).apply {
        text = value
        textSize = size
        setTextColor(color(context, if (secondary) R.color.ref_secondary else R.color.ref_text))
        if (bold) setTypeface(typeface, Typeface.BOLD)
        includeFontPadding = false
    }
    fun column(context: Context) = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    fun row(context: Context) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    fun shape(context: Context, fill: Int = R.color.ref_surface, radius: Int = 10, border: Boolean = true) =
        GradientDrawable().apply {
            setColor(color(context, fill))
            cornerRadius = dp(context, radius).toFloat()
            if (border) setStroke(dp(context, 1), color(context, R.color.ref_border))
        }
    fun icon(context: Context, drawable: Int, description: CharSequence, action: (() -> Unit)? = null) =
        androidx.appcompat.widget.AppCompatImageButton(context).apply {
            setImageResource(drawable)
            imageTintList = android.content.res.ColorStateList.valueOf(color(context, R.color.ref_text))
            contentDescription = description
            setBackgroundResource(android.R.color.transparent)
            setPadding(dp(context, 12), dp(context, 12), dp(context, 12), dp(context, 12))
            minimumWidth = dp(context, 48)
            minimumHeight = dp(context, 48)
            if (action != null) setOnClickListener { action() } else {
                isClickable = false
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
        }
    fun button(context: Context, label: CharSequence, drawable: Int? = null, action: () -> Unit) =
        com.google.android.material.button.MaterialButton(context, null,
            com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = label
            isAllCaps = false
            textSize = 14f
            maxLines = 2
            setPadding(dp(context, 8), 0, dp(context, 8), 0)
            setTextColor(color(context, R.color.ref_text))
            backgroundTintList = android.content.res.ColorStateList.valueOf(color(context, R.color.ref_background))
            strokeColor = android.content.res.ColorStateList.valueOf(color(context, R.color.ref_border))
            cornerRadius = dp(context, 10)
            insetTop = 0
            insetBottom = 0
            if (drawable != null) {
                setIconResource(drawable)
                iconTint = android.content.res.ColorStateList.valueOf(color(context, R.color.ref_text))
                iconSize = dp(context, 22)
            }
            setOnClickListener { action() }
        }
    fun divider(context: Context) = View(context).apply { setBackgroundColor(color(context, R.color.ref_border)) }
    fun quantity(value: String): CharSequence = android.text.SpannableString(value).apply {
        val units = indexOfFirst { !it.isDigit() && it != '.' && it != ',' }
        if (units > 0) setSpan(android.text.style.RelativeSizeSpan(0.58f), units, length,
            android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
}

/** Shares the native toolbar and status-bar inset handling with existing detail screens. */
abstract class ReferenceFragment : ToolbarFragment() {
    protected lateinit var body: LinearLayout
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        val context = requireContext()
        val root = ReferenceUi.column(context).apply {
            setBackgroundColor(ReferenceUi.color(context, R.color.ref_background))
        }
        val appbar = AppBarLayout(context).apply {
            id = R.id.appbar
            elevation = 0f
            addView(Toolbar(context).apply {
                id = R.id.toolbar
                setTitleTextAppearance(context, R.style.TextAppearance_WanBox_ReferenceTitle)
                contentInsetStartWithNavigation = 0
                setContentInsetsRelative(ReferenceUi.dp(context, 20), ReferenceUi.dp(context, 8))
                minimumHeight = ReferenceUi.dp(context, 64)
            }, ViewGroup.LayoutParams(-1, -2))
        }
        root.addView(appbar, LinearLayout.LayoutParams(-1, -2))
        body = ReferenceUi.column(context)
        root.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        return root
    }
    override fun onViewCreated(view: View, state: Bundle?) {
        super.onViewCreated(view, state)
        if (arguments?.getBoolean("referenceDetail") != true) toolbar.navigationIcon = null
        view.findViewById<AppBarLayout>(R.id.appbar).setBackgroundColor(
            ReferenceUi.color(requireContext(), R.color.ref_background))
        toolbar.setTitleTextColor(ReferenceUi.color(requireContext(), R.color.ref_text))
        toolbar.setSubtitleTextColor(ReferenceUi.color(requireContext(), R.color.ref_secondary))
    }
}

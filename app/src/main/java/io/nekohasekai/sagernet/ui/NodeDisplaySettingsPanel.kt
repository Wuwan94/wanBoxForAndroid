package io.nekohasekai.sagernet.ui

import android.content.Context
import android.view.Gravity
import android.widget.*
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore

internal class NodeDisplaySettingsPanel(context: Context, changed: () -> Unit, close: () -> Unit) : LinearLayout(context) {
    init {
        orientation = VERTICAL
        setBackgroundColor(ReferenceUi.color(context, R.color.ref_background))
        setPadding(ReferenceUi.dp(context, 20), ReferenceUi.dp(context, 10),
            ReferenceUi.dp(context, 20), ReferenceUi.dp(context, 12))
        addView(ReferenceUi.divider(context), LayoutParams(-1, ReferenceUi.dp(context, 1)))
        val heading = ReferenceUi.row(context)
        heading.addView(ReferenceUi.text(context, context.getString(R.string.node_display_settings), 16f, bold = true),
            LayoutParams(0, -2, 1f))
        heading.addView(ReferenceUi.icon(context, R.drawable.ic_navigation_close, context.getString(android.R.string.cancel), close),
            LayoutParams(ReferenceUi.dp(context, 40), ReferenceUi.dp(context, 40)))
        addView(heading)
        fun row(label: String): LinearLayout = ReferenceUi.row(context).also {
            it.addView(ReferenceUi.text(context, label, 14f, true),
                LayoutParams(ReferenceUi.dp(context, 88), -2))
            addView(it, LayoutParams(-1, -2).apply { topMargin = ReferenceUi.dp(context, 4) })
        }
        fun segments(label: String, names: List<String>, selected: Int, save: (Int) -> Unit) {
            val target = row(label)
            val group = RadioGroup(context).apply {
                orientation = HORIZONTAL
                background = ReferenceUi.shape(context, border = false)
            }
            names.forEachIndexed { index, name ->
                group.addView(RadioButton(context).apply {
                    id = android.view.View.generateViewId()
                    text = name
                    textSize = 14f
                    gravity = Gravity.CENTER
                    buttonDrawable = null
                    setTextColor(ReferenceUi.color(context, R.color.ref_text))
                    setBackgroundResource(R.drawable.ref_segment)
                    setPadding(0, 0, 0, 0)
                    isChecked = index == selected
                    setOnCheckedChangeListener { _, checked -> if (checked) { save(index); changed() } }
                }, RadioGroup.LayoutParams(0, ReferenceUi.dp(context, 36), 1f))
            }
            target.addView(group, LayoutParams(0, -2, 1f))
        }
        fun slider(label: String, value: Int, minimum: Int, maximum: Int, save: (Int) -> Unit) {
            val target = row(label)
            val output = ReferenceUi.text(context, value.toString(), 14f, true).apply { gravity = Gravity.END }
            val seek = SeekBar(context).apply {
                min = minimum
                max = maximum
                progress = value.coerceIn(minimum, maximum)
                thumbTintList = android.content.res.ColorStateList.valueOf(ReferenceUi.color(context, R.color.ref_control))
                progressTintList = thumbTintList
                contentDescription = label
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                        if (fromUser) { save(progress); output.text = progress.toString(); changed() }
                    }
                    override fun onStartTrackingTouch(bar: SeekBar?) {}
                    override fun onStopTrackingTouch(bar: SeekBar?) {}
                })
            }
            target.addView(seek, LayoutParams(0, ReferenceUi.dp(context, 40), 1f))
            target.addView(output, LayoutParams(ReferenceUi.dp(context, 30), -2))
        }
        segments("列数", listOf("单列", "双列", "多列"), DataStore.nodeColumnCount.coerceIn(1, 3) - 1) {
            DataStore.nodeColumnCount = it + 1
        }
        slider("名称字号", DataStore.nodeNameTextSize, 10, 24) { DataStore.nodeNameTextSize = it }
        slider("卡片间距", DataStore.nodeCardSpacing, 0, 24) { DataStore.nodeCardSpacing = it }
        segments("卡片大小", listOf("小", "中", "大"), DataStore.nodeCardDensity.coerceIn(0, 2)) {
            DataStore.nodeCardDensity = it
        }
        addView(ReferenceUi.text(context, context.getString(R.string.ref_edit_hint), 12f, true).apply {
            setPadding(0, ReferenceUi.dp(context, 6), 0, 0)
        }, LayoutParams(-1, -2))
    }
}

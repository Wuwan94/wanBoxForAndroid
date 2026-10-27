package io.nekohasekai.sagernet.ui

import android.app.Dialog
import android.os.Bundle
import android.view.View
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore

/** One binding shared by the inline panel and legacy dialog callers. */
internal object NodeDisplaySettings {
    fun bind(view: View, changed: () -> Unit) {
        val columns = view.findViewById<RadioGroup>(R.id.columnCountGroup)
        val density = view.findViewById<RadioGroup>(R.id.densityGroup)
        val size = view.findViewById<SeekBar>(R.id.textSizeSeekBar)
        val spacing = view.findViewById<SeekBar>(R.id.spacingSeekBar)
        columns.check(when (if (DataStore.groupLayoutMode == 0) 1 else DataStore.nodeColumnCount.coerceIn(2, 3)) {
            1 -> R.id.radioSingle; 3 -> R.id.radioMulti; else -> R.id.radioDouble
        })
        density.check(when (DataStore.nodeCardDensity) { 0 -> R.id.radioSmall; 2 -> R.id.radioLarge; else -> R.id.radioMedium })
        size.progress = DataStore.nodeNameTextSize.coerceIn(10, 24)
        spacing.progress = DataStore.nodeCardSpacing.coerceIn(0, 20)
        fun labels() {
            view.findViewById<TextView>(R.id.display_text_size_value).text = size.progress.toString()
            view.findViewById<TextView>(R.id.display_spacing_value).text = spacing.progress.toString()
        }
        labels()
        columns.setOnCheckedChangeListener { _, id ->
            DataStore.nodeColumnCount = when (id) { R.id.radioSingle -> 1; R.id.radioMulti -> 3; else -> 2 }
            DataStore.groupLayoutMode = if (DataStore.nodeColumnCount == 1) 0 else 1
            changed()
        }
        density.setOnCheckedChangeListener { _, id ->
            DataStore.nodeCardDensity = when (id) { R.id.radioSmall -> 0; R.id.radioLarge -> 2; else -> 1 }
            changed()
        }
        fun listener(save: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) {
                labels()
                if (fromUser) { save(value); changed() }
            }
            override fun onStartTrackingTouch(bar: SeekBar?) = Unit
            override fun onStopTrackingTouch(bar: SeekBar?) = Unit
        }
        size.setOnSeekBarChangeListener(listener { DataStore.nodeNameTextSize = it })
        spacing.setOnSeekBarChangeListener(listener { DataStore.nodeCardSpacing = it })
    }
}

class NodeDisplaySettingsDialog : DialogFragment() {
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val view = layoutInflater.inflate(R.layout.dialog_node_display_settings, null)
        NodeDisplaySettings.bind(view) {
            val host = parentFragment as? ConfigurationFragment ?: (activity as? MainActivity)?.supportFragmentManager?.findFragmentById(R.id.fragment_holder) as? ConfigurationFragment
            host?.onDisplaySettingsChanged()
        }
        view.findViewById<View>(R.id.display_settings_close).setOnClickListener { dismiss() }
        return MaterialAlertDialogBuilder(requireContext()).setView(view).setPositiveButton(android.R.string.ok, null).create()
    }
}

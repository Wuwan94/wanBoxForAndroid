package io.nekohasekai.sagernet.ui

import android.app.Dialog
import android.os.Bundle
import android.widget.RadioGroup
import android.widget.SeekBar
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore

/**
 * preview.18 UI 改造：节点页显示设置对话框。
 * 支持列数、节点名称字号、卡片间距、卡片大小的持久化设置。
 */
class NodeDisplaySettingsDialog : DialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val view = layoutInflater.inflate(R.layout.dialog_node_display_settings, null)

        val columnCountGroup = view.findViewById<RadioGroup>(R.id.columnCountGroup)
        val textSizeSeekBar = view.findViewById<SeekBar>(R.id.textSizeSeekBar)
        val spacingSeekBar = view.findViewById<SeekBar>(R.id.spacingSeekBar)
        val densityGroup = view.findViewById<RadioGroup>(R.id.densityGroup)

        // 初始化当前值
        when (DataStore.nodeColumnCount) {
            1 -> columnCountGroup.check(R.id.radioSingle)
            3 -> columnCountGroup.check(R.id.radioMulti)
            else -> columnCountGroup.check(R.id.radioDouble)
        }
        textSizeSeekBar.progress = DataStore.nodeNameTextSize
        spacingSeekBar.progress = DataStore.nodeCardSpacing
        when (DataStore.nodeCardDensity) {
            0 -> densityGroup.check(R.id.radioSmall)
            2 -> densityGroup.check(R.id.radioLarge)
            else -> densityGroup.check(R.id.radioMedium)
        }

        // 监听变化并保存
        columnCountGroup.setOnCheckedChangeListener { _, checkedId ->
            DataStore.nodeColumnCount = when (checkedId) {
                R.id.radioSingle -> 1
                R.id.radioMulti -> 3
                else -> 2
            }
            // 同步更新 groupLayoutMode（0=单列，1=多列）
            DataStore.groupLayoutMode = if (DataStore.nodeColumnCount == 1) 0 else 1
            notifySettingsChanged()
        }

        textSizeSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    DataStore.nodeNameTextSize = progress.coerceAtLeast(10)
                    notifySettingsChanged()
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        spacingSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    DataStore.nodeCardSpacing = progress
                    notifySettingsChanged()
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        densityGroup.setOnCheckedChangeListener { _, checkedId ->
            DataStore.nodeCardDensity = when (checkedId) {
                R.id.radioSmall -> 0
                R.id.radioLarge -> 2
                else -> 1
            }
            notifySettingsChanged()
        }

        return AlertDialog.Builder(requireContext())
            .setTitle(R.string.node_display_settings)
            .setView(view)
            .setPositiveButton(android.R.string.ok, null)
            .create()
    }

    private fun notifySettingsChanged() {
        // 通知父 Fragment 刷新布局
        (parentFragment as? ConfigurationFragment)?.onDisplaySettingsChanged()
            ?: (activity as? MainActivity)?.let {
                val fragment = it.supportFragmentManager.findFragmentById(R.id.fragment_holder)
                (fragment as? ConfigurationFragment)?.onDisplaySettingsChanged()
            }
    }
}

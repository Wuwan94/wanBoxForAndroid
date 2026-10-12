package io.nekohasekai.sagernet.ui

import android.app.Dialog
import android.os.Bundle
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class NodeDisplaySettingsDialog : BottomSheetDialogFragment() {
    override fun onCreateDialog(state: Bundle?): Dialog = BottomSheetDialog(requireContext()).apply {
        setContentView(NodeDisplaySettingsPanel(requireContext(), {
            val main = activity as? MainActivity
            (main?.supportFragmentManager?.findFragmentById(io.nekohasekai.sagernet.R.id.fragment_holder)
                as? NodesFragment)?.onDisplaySettingsChanged()
        }, { dismiss() }))
    }
}

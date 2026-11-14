package io.nekohasekai.sagernet.ui

import android.app.Dialog
import android.content.DialogInterface
import android.os.Bundle
import androidx.fragment.app.DialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet

/** Arguments contain only the sanitized warning text and its one-use token. */
class OutboundReferenceWarningDialog : DialogFragment() {
    val requestToken: String get() = requireArguments().getString("token").orEmpty()

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog =
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.outbound_reference_title)
            .setMessage(requireArguments().getString("message"))
            .setNegativeButton(android.R.string.cancel) { _, _ -> cancelWarning() }
            .setNeutralButton(R.string.outbound_reference_review) { _, _ ->
                if (SagerNet.commandController.cancelOutboundConfirmation(requestToken)) {
                    (activity as? MainActivity)?.displayFragmentWithId(R.id.nav_route)
                }
            }
            .setPositiveButton(R.string.outbound_reference_continue) { _, _ ->
                SagerNet.commandController.continueOutboundConfirmation(requestToken)
            }
            .create()

    override fun onCancel(dialog: DialogInterface) {
        cancelWarning()
        super.onCancel(dialog)
    }

    private fun cancelWarning() {
        SagerNet.commandController.cancelOutboundConfirmation(requestToken)
    }
}

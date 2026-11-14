package io.nekohasekai.sagernet.ui

import android.app.Dialog
import android.content.DialogInterface
import android.os.Bundle
import androidx.fragment.app.DialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.bg.OutboundReferenceWarning

/** Arguments contain only the sanitized warning text and its one-use token. */
class OutboundReferenceWarningDialog : DialogFragment() {
    val requestToken: String get() = requireArguments().getString("token").orEmpty()
    val isResult: Boolean get() = requireArguments().getBoolean("result")

    override fun onStart() {
        super.onStart()
        if (dialog?.isShowing == true) arguments?.getString("pending_review_id")?.let {
            (activity as? MainActivity)?.acknowledgeOutboundReview(it)
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val builder = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.outbound_reference_title)
            .setMessage(requireArguments().getString("message"))
        if (isResult) return builder
            .setPositiveButton(android.R.string.ok) { _, _ -> cancelWarning() }
            .setNeutralButton(R.string.outbound_reference_review) { _, _ ->
                cancelWarning()
                (activity as? MainActivity)?.displayFragmentWithId(R.id.nav_route)
            }.create()
        return builder
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
    }

    override fun onCancel(dialog: DialogInterface) {
        cancelWarning()
        super.onCancel(dialog)
    }

    private fun cancelWarning() {
        if (isResult) {
            val state = SagerNet.commandController.state.value as?
                io.nekohasekai.sagernet.bg.ServiceCommandController.CommandState.Confirmed
            if (state?.warningResult?.requestToken == requestToken) SagerNet.commandController.acknowledge()
        } else SagerNet.commandController.cancelOutboundConfirmation(requestToken)
    }

    companion object {
        fun newResult(warning: OutboundReferenceWarning) = newWarning(warning, result = true)

        fun newWarning(warning: OutboundReferenceWarning, result: Boolean = false, pendingReviewId: String? = null) =
            OutboundReferenceWarningDialog().apply {
                val context = SagerNet.application
                arguments = Bundle().apply {
                    putString("token", warning.requestToken)
                    putBoolean("result", result)
                    putString("pending_review_id", pendingReviewId)
                    putString("message", context.getString(if (result) R.string.outbound_reference_result
                        else R.string.outbound_reference_message) + "\n\n" + warning.issues.joinToString("\n") {
                        context.getString(R.string.outbound_reference_item, it.ruleName, it.ruleId,
                            context.getString(R.string.outbound_reference_type), it.missingId)
                    })
                }
            }
    }
}

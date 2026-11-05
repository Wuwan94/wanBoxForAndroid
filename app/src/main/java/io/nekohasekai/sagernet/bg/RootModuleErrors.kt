// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package io.nekohasekai.sagernet.bg

import android.content.Context
import io.nekohasekai.sagernet.R

/** Fixed error codes only: never render exception messages containing user data. @author 雾晚 */
object RootModuleErrors {
    internal fun resource(code: String): Int = when (code) {
        "install_data_update_pending", "data_update_pending" -> R.string.root_module_data_pending
        "stop_node_tests_before_update" -> R.string.root_module_data_test_busy
        "stop_subscription_updates_before_update", "subscription_cancel_timeout" -> R.string.root_module_data_subscription_busy
        "install_data_selection_changed", "install_data_selection_invalid" -> R.string.root_module_data_selection_error
        "installer_journal_corrupt" -> R.string.root_module_data_journal_corrupt
        "node_tag_not_found", "node_select_failed", "node_select_not_confirmed",
        "node_select_unsupported", "clash_api_disabled", "module_not_running" -> R.string.root_module_node_switch_failed
        "revision_conflict" -> R.string.root_module_revision_conflict
        // phase2: core watchdog / health codes get plain-language messages
        "core_exited", "plugin_exited", "core_killed_after_timeout", "core_retry" -> R.string.module_reason_core_exit
        "retry_limit_reached" -> R.string.module_reason_retry_limit
        "startup_timeout" -> R.string.module_reason_startup_timeout
        "cleanup_required" -> R.string.module_reason_cleanup_required
        "root_required" -> R.string.module_reason_root_required
        "module_protocol_mismatch" -> R.string.module_reason_protocol_mismatch
        "module_disabled_or_missing" -> R.string.module_reason_disabled
        "node_failover_no_candidate" -> R.string.module_failover_no_candidate
        else -> R.string.root_module_action_failed
    }

    /** phase2: disconnect reason categories (module events disconnect_* / reconnect_*). */
    fun reasonCategory(code: String): Int? = when (code.substringBefore(':').removePrefix("reconnect_").let { if (it.startsWith("disconnect_")) it else "disconnect_" + it }) {
        "disconnect_core_exit" -> R.string.module_cat_core_exit
        "disconnect_network_change" -> R.string.module_cat_network_change
        "disconnect_node_fail" -> R.string.module_cat_node_fail
        "disconnect_tun_fail" -> R.string.module_cat_tun_fail
        "disconnect_unknown" -> R.string.module_cat_unknown
        else -> null
    }

    /** Human text for a module event code (events.json); unknown codes are shown verbatim. */
    fun eventLabel(context: Context, code: String): String {
        val base = code.substringBefore(':')
        reasonCategory(base)?.takeIf { base.startsWith("disconnect_") }?.let {
            return context.getString(R.string.module_event_disconnect, context.getString(it))
        }
        return when {
            base == "reconnect_ok" -> context.getString(R.string.module_event_reconnect)
            base.startsWith("core_exit_") -> context.getString(R.string.module_event_core_exit, base.removePrefix("core_exit_"))
            base == "node_unhealthy" -> context.getString(R.string.module_event_node_unhealthy)
            base == "node_failover" || base == "node_failover_manual" -> context.getString(R.string.module_event_failover)
            base == "tun_rebuilt" -> context.getString(R.string.module_event_tun_rebuilt)
            base == "network_changed" -> context.getString(R.string.module_cat_network_change)
            else -> code
        }
    }

    fun message(context: Context, code: String): String {
        val safe = code.takeIf { it.matches(Regex("[a-z_]{1,80}")) } ?: "module_operation_failed"
        return context.getString(resource(safe)) + " ($safe)"
    }

    fun message(context: Context, error: Exception): String {
        val code = RootModuleClient.safeError(error)
        if (error !is InstallerDataCommit.Failure || resource(code) != R.string.root_module_action_failed)
            return message(context, code)
        val stage = when (error.stage) {
            InstallerDataCommit.Stage.QUIESCE -> R.string.root_module_stage_quiesce
            InstallerDataCommit.Stage.BACKUP -> R.string.root_module_stage_backup
            InstallerDataCommit.Stage.JOURNAL -> R.string.root_module_stage_journal
            InstallerDataCommit.Stage.MODULE_PREPARE -> R.string.root_module_stage_prepare
            InstallerDataCommit.Stage.APP_RESTORE -> R.string.root_module_stage_restore
            InstallerDataCommit.Stage.MODULE_FINISH -> R.string.root_module_stage_finish
            InstallerDataCommit.Stage.ACKNOWLEDGE -> R.string.root_module_stage_acknowledge
            InstallerDataCommit.Stage.REFRESH -> R.string.root_module_stage_refresh
        }
        return context.getString(R.string.root_module_data_stage_failed, context.getString(stage), code)
    }
}

package io.nekohasekai.sagernet

import android.content.DialogInterface
import android.content.Intent
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import io.nekohasekai.sagernet.bg.OutboundReferenceWarning
import io.nekohasekai.sagernet.bg.RootModuleClient
import io.nekohasekai.sagernet.bg.ServiceCommandController
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.RuleEntity
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.route.OutboundReferenceValidator
import io.nekohasekai.sagernet.ui.MainActivity
import io.nekohasekai.sagernet.ui.RouteFragment
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Reserved fixtures only; no root command or service start is requested. */
class OutboundReferenceAlarmTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private fun fixtures() = listOf(
        RuleEntity(id = 910001L, name = "outbound-alarm.invalid missing route", userOrder = 1L,
            enabled = true, outbound = 9223372036854775806L,
            domains = "domain:outbound-alarm.invalid",
            config = """{"action":"route","password":"fixture-password.invalid","address":"fixture-address.invalid","stderr":"fixture-stderr.invalid"}"""),
        RuleEntity(id = 910002L, name = "outbound-alarm.invalid built-in route", userOrder = 2L,
            enabled = true, outbound = 0L, domains = "domain:valid-route.invalid"),
        RuleEntity(id = 910003L, name = "outbound-alarm.invalid disabled route", userOrder = 3L,
            enabled = false, outbound = 9223372036854775805L, domains = "domain:disabled-route.invalid"),
    )

    private fun replaceRules(rules: List<RuleEntity>) {
        SagerDatabase.rulesDao.reset()
        SagerDatabase.rulesDao.insert(rules)
    }

    private fun clearController() {
        val controller = SagerNet.commandController
        val waiting = controller.state.value as? ServiceCommandController.CommandState.ConfirmationRequired
        if (waiting != null) controller.cancelOutboundConfirmation(waiting.warning.requestToken)
        controller.acknowledge()
    }

    private fun awaitMain(message: String, predicate: () -> Boolean) {
        repeat(100) {
            var ready = false
            instrumentation.runOnMainSync { ready = predicate() }
            if (ready) return
            Thread.sleep(100)
        }
        fail(message)
    }

    private fun launch(): MainActivity = instrumentation.startActivitySync(
        Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    ) as MainActivity

    @Test
    fun replacedRootWarningRequiresFreshConfirmationWhenContinuingOldToken() = runBlocking {
        val original = SagerDatabase.rulesDao.allRules()
        val tokens = mutableListOf<String>()
        try {
            clearController()
            assertNull(SagerDatabase.proxyDao.getById(9223372036854775806L))
            assertNull(SagerDatabase.proxyDao.getById(9223372036854775804L))
            replaceRules(fixtures())
            val oldWarning = checkNotNull(RootModuleClient.prepareOutboundWarning(true))
                .also { tokens += it.requestToken }
            replaceRules(fixtures().map {
                if (it.id == 910001L) it.copy(outbound = 9223372036854775804L) else it
            })
            val replacement = checkNotNull(RootModuleClient.prepareOutboundWarning(true))
                .also { tokens += it.requestToken }
            assertNotEquals(oldWarning.requestToken, replacement.requestToken)

            // Both tokens describe missing references, so Continue must stop before core config/application.
            try {
                RootModuleClient.continueOutboundWarning(oldWarning.requestToken, true)
                fail("An old token must require fresh confirmation")
            } catch (required: RootModuleClient.OutboundReferenceWarningRequired) {
                tokens += required.warning.requestToken
                assertNotEquals(oldWarning.requestToken, required.warning.requestToken)
                assertEquals(listOf(9223372036854775804L), required.warning.issues.map { it.missingId })
                assertTrue(required.warning.startIfStopped)
            }
        } finally {
            try {
                tokens.forEach { RootModuleClient.cancelOutboundWarning(it) }
                clearController()
            } finally {
                replaceRules(original)
            }
        }
    }

    @Test
    fun startupPreflightFindsInvalidRulesWhileControllerIsIdle() {
        val original = SagerDatabase.rulesDao.allRules()
        val oldPreview = DataStore.previewHintDismissedVersion
        val controller = SagerNet.commandController
        var activity: MainActivity? = null
        try {
            clearController()
            assertNull(SagerDatabase.proxyDao.getById(9223372036854775806L))
            replaceRules(fixtures())
            DataStore.previewHintDismissedVersion = BuildConfig.PRE_VERSION_NAME
            assertEquals(ServiceCommandController.CommandState.Idle, controller.state.value)
            activity = launch()
            val host = checkNotNull(activity)
            awaitMain("Startup preflight must discover missing outbounds and show confirmation") {
                val waiting = controller.state.value as? ServiceCommandController.CommandState.ConfirmationRequired
                waiting?.warning?.issues?.map { it.missingId } == listOf(9223372036854775806L) &&
                    (host.supportFragmentManager.findFragmentByTag("outbound_reference_warning") as? DialogFragment)
                        ?.dialog?.isShowing == true
            }
        } finally {
            try {
                activity?.let { host -> instrumentation.runOnMainSync { host.finish() } }
                instrumentation.waitForIdleSync()
            } finally {
                try { clearController() } finally {
                    try { replaceRules(original) } finally {
                        DataStore.previewHintDismissedVersion = oldPreview
                    }
                }
            }
        }
    }

    @Test
    fun foregroundWarningBroadcastDiscoversNewlyInvalidRules() {
        val original = SagerDatabase.rulesDao.allRules()
        val oldPreview = DataStore.previewHintDismissedVersion
        val controller = SagerNet.commandController
        var activity: MainActivity? = null
        try {
            clearController()
            assertNull(SagerDatabase.proxyDao.getById(9223372036854775806L))
            replaceRules(fixtures().map { it.copy(outbound = 0L) })
            DataStore.previewHintDismissedVersion = BuildConfig.PRE_VERSION_NAME
            activity = launch()
            val host = checkNotNull(activity)
            instrumentation.waitForIdleSync()
            runBlocking {
                assertNull(RootModuleClient.prepareOutboundWarning(startIfStopped = true))
            }
            instrumentation.runOnMainSync {
                assertTrue(host.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED))
                assertEquals(ServiceCommandController.CommandState.Idle, controller.state.value)
                assertNull(host.supportFragmentManager.findFragmentByTag("outbound_reference_warning"))
            }
            replaceRules(fixtures())
            instrumentation.targetContext.sendBroadcast(
                Intent("io.nekohasekai.sagernet.action.OUTBOUND_REFERENCE_WARNING_REQUIRED")
                    .setPackage(instrumentation.targetContext.packageName)
            )
            awaitMain("A foreground warning broadcast must discover missing outbounds and show confirmation") {
                val waiting = controller.state.value as? ServiceCommandController.CommandState.ConfirmationRequired
                waiting?.warning?.issues?.map { it.missingId } == listOf(9223372036854775806L) &&
                    (host.supportFragmentManager.findFragmentByTag("outbound_reference_warning") as? DialogFragment)
                        ?.dialog?.isShowing == true
            }
        } finally {
            try {
                activity?.let { host -> instrumentation.runOnMainSync { host.finish() } }
                instrumentation.waitForIdleSync()
            } finally {
                try { clearController() } finally {
                    try { replaceRules(original) } finally {
                        DataStore.previewHintDismissedVersion = oldPreview
                    }
                }
            }
        }
    }

    @Test
    fun preparationReportsOnlyEnabledMissingOutboundWithoutSensitivePayload() = runBlocking {
        val original = SagerDatabase.rulesDao.allRules()
        var warning: OutboundReferenceWarning? = null
        try {
            clearController()
            assertEquals(null, SagerDatabase.proxyDao.getById(9223372036854775806L))
            assertEquals(null, SagerDatabase.proxyDao.getById(9223372036854775805L))
            replaceRules(fixtures())
            warning = RootModuleClient.prepareOutboundWarning(startIfStopped = true)
            assertNotNull("Enabled missing outbound must require confirmation", warning)
            val actual = checkNotNull(warning)
            assertTrue(actual.startIfStopped)
            assertEquals(listOf(OutboundReferenceValidator.Issue(
                ruleId = 910001L,
                ruleName = "outbound-alarm.invalid missing route",
                kind = OutboundReferenceValidator.ReferenceKind.NODE_OR_STRATEGY_GROUP,
                missingId = 9223372036854775806L,
            )), actual.issues)
            val serialized = Gson().toJson(actual)
            for (forbidden in listOf("config", "password", "address", "stderr",
                "fixture-password.invalid", "fixture-address.invalid", "fixture-stderr.invalid")) {
                assertFalse("Warning must omit $forbidden", serialized.contains(forbidden, ignoreCase = true))
            }
        } finally {
            try {
                warning?.let { RootModuleClient.cancelOutboundWarning(it.requestToken) }
                clearController()
            } finally {
                replaceRules(original)
            }
        }
    }

    @Test
    fun recycledRouteRowClearsMissingOutboundIndicatorForBuiltInRoute() {
        val original = SagerDatabase.rulesDao.allRules()
        val oldPreview = DataStore.previewHintDismissedVersion
        var activity: MainActivity? = null
        try {
            clearController()
            replaceRules(fixtures())
            DataStore.previewHintDismissedVersion = BuildConfig.PRE_VERSION_NAME
            activity = launch()
            val host = checkNotNull(activity)
            instrumentation.runOnMainSync { assertTrue(host.displayFragmentWithId(R.id.nav_route)) }
            awaitMain("Route adapter did not load the three fixture rules") {
                val fragment = host.supportFragmentManager.findFragmentById(R.id.fragment_holder) as? RouteFragment
                fragment?.view != null && fragment.ruleListView.adapter != null &&
                    fragment.ruleAdapter.ruleList.map { it.id } == listOf(910001L, 910002L, 910003L)
            }
            instrumentation.runOnMainSync {
                val fragment = host.supportFragmentManager.findFragmentById(R.id.fragment_holder) as RouteFragment
                val adapter = fragment.ruleAdapter
                val holder = adapter.onCreateViewHolder(fragment.ruleListView, adapter.getItemViewType(1))
                adapter.onBindViewHolder(holder, 1)
                val indicator = holder.itemView.findViewById<TextView>(R.id.outbound_error)
                assertEquals("Missing outbound must be visible", View.VISIBLE, indicator.visibility)
                val name = holder.itemView.findViewById<TextView>(R.id.profile_name)
                val outbound = holder.itemView.findViewById<TextView>(R.id.route_outbound)
                assertEquals(host.getColor(R.color.color_route_block), name.currentTextColor)
                assertEquals(host.getColor(R.color.color_route_block), outbound.currentTextColor)
                adapter.onBindViewHolder(holder, 2)
                assertEquals("Recycled built-in row must hide the warning", View.GONE, indicator.visibility)
                assertEquals("Recycled row must clear diagnostic text", "", indicator.text.toString())
                assertEquals(host.getColor(R.color.color_route_proxy), outbound.currentTextColor)
                assertNotEquals(host.getColor(R.color.color_route_block), name.currentTextColor)
            }
        } finally {
            try {
                activity?.let { host -> instrumentation.runOnMainSync { host.finish() } }
                instrumentation.waitForIdleSync()
            } finally {
                try { clearController() } finally {
                    try { replaceRules(original) } finally {
                        DataStore.previewHintDismissedVersion = oldPreview
                    }
                }
            }
        }
    }

    @Test
    fun startupShowsPendingWarningAndCancelReleasesController() {
        val original = SagerDatabase.rulesDao.allRules()
        val oldPreview = DataStore.previewHintDismissedVersion
        val controller = SagerNet.commandController
        var warning: OutboundReferenceWarning? = null
        var activity: MainActivity? = null
        try {
            clearController()
            assertNull(SagerDatabase.proxyDao.getById(9223372036854775806L))
            replaceRules(fixtures())
            DataStore.previewHintDismissedVersion = BuildConfig.PRE_VERSION_NAME
            warning = runBlocking {
                checkNotNull(RootModuleClient.prepareOutboundWarning(startIfStopped = true))
            }
            assertTrue(controller.requestOutboundConfirmation(checkNotNull(warning)))
            activity = launch()
            val host = checkNotNull(activity)
            awaitMain("MainActivity must show the pending outbound warning on startup") {
                (host.supportFragmentManager.findFragmentByTag("outbound_reference_warning") as? DialogFragment)
                    ?.dialog?.isShowing == true
            }
            instrumentation.runOnMainSync {
                val fragment = host.supportFragmentManager.findFragmentByTag("outbound_reference_warning") as DialogFragment
                val dialog = fragment.requireDialog() as AlertDialog
                assertTrue(dialog.isShowing)
                val message = dialog.findViewById<TextView>(android.R.id.message)!!.text.toString()
                assertTrue(message.contains("outbound-alarm.invalid missing route"))
                assertTrue(message.contains("9223372036854775806"))
                assertTrue(message.contains(host.getString(R.string.outbound_reference_type)))
                for (forbidden in listOf("fixture-password.invalid", "fixture-address.invalid", "fixture-stderr.invalid")) {
                    assertFalse(message.contains(forbidden))
                }
                val cancel = dialog.getButton(DialogInterface.BUTTON_NEGATIVE)
                assertEquals(View.VISIBLE, cancel.visibility)
                assertTrue(cancel.text.isNotBlank())
                assertTrue(cancel.performClick())
            }
            awaitMain("Cancel must release the command controller") {
                controller.state.value == ServiceCommandController.CommandState.Idle
            }
            assertFalse(controller.isBusy())
        } finally {
            try {
                activity?.let { host -> instrumentation.runOnMainSync { host.finish() } }
                instrumentation.waitForIdleSync()
            } finally {
                try {
                    warning?.let { RootModuleClient.cancelOutboundWarning(it.requestToken) }
                    clearController()
                } finally {
                    try { replaceRules(original) } finally {
                        DataStore.previewHintDismissedVersion = oldPreview
                    }
                }
            }
        }
    }
}

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
import io.nekohasekai.sagernet.ui.OutboundReferenceWarningDialog
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
        OutboundReferenceWarning.peekPendingReview(instrumentation.targetContext)?.let {
            OutboundReferenceWarning.clearPendingReview(instrumentation.targetContext, it.requestId)
        }
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
    fun pendingReviewIsLatestWinsAndStaleAcknowledgmentPreservesReplacement() {
        val context = instrumentation.targetContext
        try {
            clearController()
            val first = OutboundReferenceWarning.queuePendingReview(context, true)
            assertEquals(first, OutboundReferenceWarning.peekPendingReview(context))
            assertEquals(first, OutboundReferenceWarning.peekPendingReview(context))
            val latest = OutboundReferenceWarning.queuePendingReview(context, false)
            assertNotEquals(first.requestId, latest.requestId)
            assertFalse(OutboundReferenceWarning.clearPendingReview(context, first.requestId))
            assertEquals(latest, OutboundReferenceWarning.peekPendingReview(context))
            assertFalse(latest.startIfStopped)
            assertTrue(OutboundReferenceWarning.clearPendingReview(context, latest.requestId))
            assertNull(OutboundReferenceWarning.peekPendingReview(context))
        } finally { clearController() }
    }

    @Test
    fun continuedWarningResultShowsUnresolvedRuleAndReviewOrAcknowledgment() {
        val original = SagerDatabase.rulesDao.allRules()
        val oldPreview = DataStore.previewHintDismissedVersion
        val warning = OutboundReferenceWarning(
            requestToken = "continued-warning-result.invalid",
            startIfStopped = true,
            issues = listOf(OutboundReferenceValidator.Issue(
                ruleId = 910001L,
                ruleName = "continued-warning.invalid unresolved rule",
                kind = OutboundReferenceValidator.ReferenceKind.NODE_OR_STRATEGY_GROUP,
                missingId = 9223372036854775806L,
            )),
        )
        var activity: MainActivity? = null
        var fragment: OutboundReferenceWarningDialog? = null
        try {
            clearController()
            replaceRules(fixtures().filter { it.outbound == 0L })
            DataStore.previewHintDismissedVersion = BuildConfig.PRE_VERSION_NAME
            activity = launch()
            val host = checkNotNull(activity)
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                val result = OutboundReferenceWarningDialog.newResult(warning)
                fragment = result
                result.showNow(host.supportFragmentManager, "outbound_reference_warning_result")
                val dialog = result.requireDialog() as AlertDialog
                assertTrue("The continued-warning result must be visible", dialog.isShowing)
                val message = checkNotNull(dialog.findViewById<TextView>(android.R.id.message))
                assertTrue(message.isShown)
                assertTrue(message.text.contains("continued-warning.invalid unresolved rule"))
                assertTrue(message.text.contains("9223372036854775806"))
                val visibleActions = listOf(
                    DialogInterface.BUTTON_POSITIVE,
                    DialogInterface.BUTTON_NEGATIVE,
                    DialogInterface.BUTTON_NEUTRAL,
                ).map { dialog.getButton(it) }.filter { it.isShown }
                assertTrue("The result must offer acknowledgment or Review", visibleActions.any {
                    it.isEnabled && (it.text.toString() == host.getString(android.R.string.ok) ||
                        it.text.toString() == host.getString(R.string.outbound_reference_review))
                })
                assertFalse("The result must not offer Continue Anyway", visibleActions.any {
                    it.text.toString() == host.getString(R.string.outbound_reference_continue)
                })
            }
        } finally {
            try {
                try {
                    instrumentation.runOnMainSync { fragment?.dismissNow() }
                } finally {
                    activity?.let { host -> instrumentation.runOnMainSync { host.finish() } }
                    instrumentation.waitForIdleSync()
                }
            } finally {
                try { clearController() } finally {
                    try {
                        DataStore.previewHintDismissedVersion = oldPreview
                    } finally {
                        replaceRules(original)
                    }
                }
            }
        }
    }

    @Test
    fun changedRootIssuesRequireFreshConfirmationAfterInstallerSelection() = runBlocking {
        val original = SagerDatabase.rulesDao.allRules()
        val tokens = mutableListOf<String>()
        val oldLauncher = RootModuleClient.commandProcess
        val commands = mutableListOf<String>()
        RootModuleClient.invalidateStatusCache()
        RootModuleClient.commandProcess = { command ->
            commands += command
            check(command == "status") { "Unexpected Root command: $command" }
            FakeRootStatusProcess()
        }
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

            // The active token permits installer selection before final restored-rule validation.
            try {
                RootModuleClient.continueOutboundWarning(oldWarning.requestToken, true)
                fail("An old token must require fresh confirmation")
            } catch (required: RootModuleClient.OutboundReferenceWarningRequired) {
                tokens += required.warning.requestToken
                assertNotEquals(oldWarning.requestToken, required.warning.requestToken)
                assertEquals(listOf(9223372036854775804L), required.warning.issues.map { it.missingId })
                assertTrue(required.warning.startIfStopped)
                assertTrue("Installer selection must read module status first", commands.contains("status"))
                assertFalse(commands.contains("config apply"))
                assertFalse(commands.contains("start"))
            }
        } finally {
            try {
                RootModuleClient.commandProcess = oldLauncher
                RootModuleClient.invalidateStatusCache()
                tokens.forEach { RootModuleClient.cancelOutboundWarning(it) }
                clearController()
            } finally {
                replaceRules(original)
            }
        }
    }

    /** No subprocess is spawned, and unexpected mutation commands fail at the CLI seam. */
    private class FakeRootStatusProcess : Process() {
        private val response = """{"schemaVersion":1,"ok":true,"state":{"phase":"disabled"}}"""
            .byteInputStream()
        override fun getInputStream(): java.io.InputStream = response
        override fun getErrorStream(): java.io.InputStream = java.io.ByteArrayInputStream(byteArrayOf())
        override fun getOutputStream(): java.io.OutputStream = java.io.ByteArrayOutputStream()
        override fun waitFor(): Int = 0
        override fun exitValue(): Int = 0
        override fun destroy() = Unit
    }

    @Test
    fun fixedRootIssuesProceedPastValidationAndConsumeConfirmation() = runBlocking {
        val original = SagerDatabase.rulesDao.allRules()
        val oldLauncher = RootModuleClient.commandProcess
        val commands = mutableListOf<String>()
        var token: String? = null
        RootModuleClient.invalidateStatusCache()
        RootModuleClient.commandProcess = { command ->
            commands += command
            check(command == "status") { "Unexpected Root command: $command" }
            FakeRootStatusProcess()
        }
        try {
            clearController()
            replaceRules(fixtures())
            val warning = checkNotNull(RootModuleClient.prepareOutboundWarning(true))
            token = warning.requestToken
            replaceRules(fixtures().map { it.copy(outbound = 0L) })

            try {
                RootModuleClient.continueOutboundWarning(warning.requestToken, true)
                fail("The fake disabled module must prevent config creation")
            } catch (error: java.io.IOException) {
                // A disabled-module failure is after final route validation; expiration is wrong.
                assertEquals("module_disabled_or_missing", error.message)
            }
            assertTrue(commands.contains("status"))
            assertFalse(commands.contains("config apply"))
            assertFalse(commands.contains("start"))

            commands.clear()
            RootModuleClient.invalidateStatusCache()
            try {
                RootModuleClient.continueOutboundWarning(warning.requestToken, true)
                fail("The consumed token must not be reusable")
            } catch (error: java.io.IOException) {
                assertEquals("outbound_confirmation_expired", error.message)
            }
            assertTrue("An inactive token must fail before Root operations", commands.isEmpty())
        } finally {
            RootModuleClient.commandProcess = oldLauncher
            RootModuleClient.invalidateStatusCache()
            token?.let { RootModuleClient.cancelOutboundWarning(it) }
            try { clearController() } finally { replaceRules(original) }
        }
    }

    @Test
    fun startupAcknowledgesBackgroundWarningOnlyAfterDialogPresentation() {
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
            OutboundReferenceWarning.queuePendingReview(instrumentation.targetContext, startIfStopped = false)
            activity = launch()
            val host = checkNotNull(activity)
            awaitMain("Startup preflight must discover missing outbounds and show confirmation") {
                val waiting = controller.state.value as? ServiceCommandController.CommandState.ConfirmationRequired
                waiting?.warning?.issues?.map { it.missingId } == listOf(9223372036854775806L) &&
                    (host.supportFragmentManager.findFragmentByTag("outbound_reference_warning") as? DialogFragment)
                        ?.dialog?.isShowing == true
            }
            assertFalse((controller.state.value as ServiceCommandController.CommandState.ConfirmationRequired)
                .warning.startIfStopped)
            awaitMain("The presented warning must acknowledge the pending request") {
                OutboundReferenceWarning.peekPendingReview(instrumentation.targetContext) == null
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
    fun foregroundWarningWaitsForInstallerSelectionBeforeConfirmationAndAcknowledgment() {
        val original = SagerDatabase.rulesDao.allRules()
        val oldPreview = DataStore.previewHintDismissedVersion
        val oldLauncher = RootModuleClient.commandProcess
        val context = instrumentation.targetContext
        val statusReadEntered = java.util.concurrent.CountDownLatch(1)
        val releaseStatusRead = java.util.concurrent.CountDownLatch(1)
        val statusCallFinished = java.util.concurrent.CountDownLatch(1)
        val statusProcessCreated = java.util.concurrent.atomic.AtomicBoolean(false)
        val broadcastDelivered = java.util.concurrent.CountDownLatch(1)
        var pending: OutboundReferenceWarning.PendingReview? = null
        var activity: MainActivity? = null
        try {
            clearController()
            assertNull(SagerDatabase.proxyDao.getById(9223372036854775806L))
            replaceRules(fixtures())
            DataStore.previewHintDismissedVersion = BuildConfig.PRE_VERSION_NAME
            RootModuleClient.invalidateStatusCache()
            RootModuleClient.commandProcess = { command ->
                check(command == "status") { "Unexpected Root command: $command" }
                statusProcessCreated.set(true)
                object : Process() {
                    private val status = FakeRootStatusProcess()
                    private val statusReadFinished = java.util.concurrent.CountDownLatch(1)
                    private val response = object : java.io.FilterInputStream(status.getInputStream()) {
                        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                            statusReadEntered.countDown()
                            if (!releaseStatusRead.await(10, java.util.concurrent.TimeUnit.SECONDS)) {
                                throw java.io.IOException("Timed out waiting to release fake installer status")
                            }
                            return synchronized(this) {
                                super.read(buffer, offset, length).also {
                                    if (it < 0) statusReadFinished.countDown()
                                }
                            }
                        }
                        override fun close() {
                            releaseStatusRead.countDown()
                            synchronized(this) {
                                try { super.close() } finally { statusReadFinished.countDown() }
                            }
                        }
                    }
                    override fun getInputStream(): java.io.InputStream = response
                    override fun getErrorStream(): java.io.InputStream = status.getErrorStream()
                    override fun getOutputStream(): java.io.OutputStream = status.getOutputStream()
                    override fun waitFor(): Int {
                        check(statusReadFinished.await(10, java.util.concurrent.TimeUnit.SECONDS)) {
                            "Fake installer status read did not finish"
                        }
                        statusCallFinished.countDown()
                        return 0
                    }
                    override fun exitValue(): Int = 0
                    override fun destroy() { response.close() }
                }
            }
            activity = launch()
            val host = checkNotNull(activity)
            assertTrue("Installer selection must enter the blocked status read",
                statusReadEntered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            instrumentation.runOnMainSync {
                assertTrue(host.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED))
            }
            val queued = OutboundReferenceWarning.queuePendingReview(context, startIfStopped = true)
            pending = queued
            // The final receiver establishes delivery without relying on a sleep for the broadcast.
            context.sendOrderedBroadcast(
                Intent(OutboundReferenceWarning.ACTION_REQUIRED).setPackage(context.packageName)
                    .putExtra(OutboundReferenceWarning.EXTRA_REQUEST_ID, queued.requestId)
                    .putExtra(OutboundReferenceWarning.EXTRA_START_IF_STOPPED, queued.startIfStopped),
                null,
                object : android.content.BroadcastReceiver() {
                    override fun onReceive(context: android.content.Context, intent: Intent) {
                        broadcastDelivered.countDown()
                    }
                },
                android.os.Handler(android.os.Looper.getMainLooper()), 0, null, null,
            )
            assertTrue("The visible Activity must receive the warning broadcast",
                broadcastDelivered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            // Allow IO discovery and posted dialog work five seconds to expose an early warning.
            repeat(100) {
                instrumentation.waitForIdleSync()
                instrumentation.runOnMainSync {
                    val dialog = (host.supportFragmentManager.findFragmentByTag("outbound_reference_warning")
                        as? DialogFragment)?.dialog
                    assertFalse("No warning may be visible before installer status completes",
                        dialog?.isShowing == true)
                    assertEquals("An unpresented warning must remain pending", queued,
                        OutboundReferenceWarning.peekPendingReview(context))
                }
                Thread.sleep(50)
            }
            releaseStatusRead.countDown()
            awaitMain("Installer completion must show the warning message and confirmation actions") {
                val dialog = (host.supportFragmentManager.findFragmentByTag("outbound_reference_warning")
                    as? DialogFragment)?.dialog as? AlertDialog
                val message = dialog?.findViewById<TextView>(android.R.id.message)?.text?.toString().orEmpty()
                dialog?.isShowing == true &&
                    message.contains(host.getString(R.string.outbound_reference_message)) &&
                    message.contains("outbound-alarm.invalid missing route") &&
                    message.contains("9223372036854775806") &&
                    dialog.getButton(DialogInterface.BUTTON_POSITIVE)?.let {
                        it.isShown && it.text.toString() == host.getString(R.string.outbound_reference_continue)
                    } == true &&
                    dialog.getButton(DialogInterface.BUTTON_NEUTRAL)?.let {
                        it.isShown && it.text.toString() == host.getString(R.string.outbound_reference_review)
                    } == true &&
                    dialog.getButton(DialogInterface.BUTTON_NEGATIVE)?.let {
                        it.isShown && it.text.toString() == host.getString(android.R.string.cancel)
                    } == true
            }
            awaitMain("Presentation must acknowledge the matching pending request") {
                OutboundReferenceWarning.peekPendingReview(context)?.requestId != queued.requestId
            }
            assertTrue("Fake status I/O must finish before restoring the launcher or database",
                statusCallFinished.await(5, java.util.concurrent.TimeUnit.SECONDS))
        } finally {
            var cleanupFailure: Throwable? = null
            fun cleanup(action: () -> Unit) {
                runCatching(action).onFailure {
                    if (cleanupFailure == null) cleanupFailure = it
                }
            }
            try {
                cleanup { releaseStatusRead.countDown() }
                runCatching {
                    if (statusProcessCreated.get()) {
                        statusCallFinished.await(5, java.util.concurrent.TimeUnit.SECONDS)
                    }
                }
                runCatching {
                    pending?.let { OutboundReferenceWarning.clearPendingReview(context, it.requestId) }
                }
                cleanup { activity?.let { host -> instrumentation.runOnMainSync { host.finish() } } }
                cleanup { instrumentation.waitForIdleSync() }
            } finally {
                try {
                    cleanup { RootModuleClient.commandProcess = oldLauncher }
                    cleanup { RootModuleClient.invalidateStatusCache() }
                } finally {
                    cleanup {
                        val controller = SagerNet.commandController
                        cleanup {
                            val waiting = controller.state.value as? ServiceCommandController.CommandState.ConfirmationRequired
                            waiting?.let { controller.cancelOutboundConfirmation(it.warning.requestToken) }
                        }
                        cleanup { controller.acknowledge() }
                    }
                    cleanup { replaceRules(original) }
                    cleanup { DataStore.previewHintDismissedVersion = oldPreview }
                }
            }
            cleanupFailure?.let { throw it }
        }
    }

    @Test
    fun queuedReviewSurvivesBusyControllerAndRetriesWhenIdle() {
        val original = SagerDatabase.rulesDao.allRules()
        val oldPreview = DataStore.previewHintDismissedVersion
        val controller = SagerNet.commandController
        var activity: MainActivity? = null
        try {
            clearController()
            replaceRules(fixtures())
            DataStore.previewHintDismissedVersion = BuildConfig.PRE_VERSION_NAME
            val busy = runBlocking { checkNotNull(RootModuleClient.prepareOutboundWarning(false)) }
            assertTrue(controller.requestOutboundConfirmation(busy))
            val pending = OutboundReferenceWarning.queuePendingReview(instrumentation.targetContext, true)
            activity = launch()
            val host = checkNotNull(activity)
            awaitMain("The existing confirmation must be presented") {
                (host.supportFragmentManager.findFragmentByTag("outbound_reference_warning") as? DialogFragment)
                    ?.dialog?.isShowing == true
            }
            assertEquals("An unrelated confirmation must not acknowledge the queued review", pending,
                OutboundReferenceWarning.peekPendingReview(instrumentation.targetContext))
            instrumentation.runOnMainSync { assertTrue(controller.cancelOutboundConfirmation(busy.requestToken)) }
            awaitMain("Idle must retry and present the queued review without another broadcast") {
                val waiting = controller.state.value as? ServiceCommandController.CommandState.ConfirmationRequired
                waiting?.warning?.startIfStopped == true &&
                    (host.supportFragmentManager.findFragmentByTag("outbound_reference_warning") as? DialogFragment)
                        ?.dialog?.isShowing == true
            }
            awaitMain("Presentation must acknowledge the queued review") {
                OutboundReferenceWarning.peekPendingReview(instrumentation.targetContext) == null
            }
        } finally {
            try {
                activity?.let { host -> instrumentation.runOnMainSync { host.finish() } }
                instrumentation.waitForIdleSync()
            } finally {
                try { clearController() } finally {
                    try { replaceRules(original) } finally { DataStore.previewHintDismissedVersion = oldPreview }
                }
            }
        }
    }

    @Test
    fun ordinaryLaunchWithInvalidRulesDoesNotRequestConfirmationOrStart() {
        val original = SagerDatabase.rulesDao.allRules()
        val oldPreview = DataStore.previewHintDismissedVersion
        val controller = SagerNet.commandController
        var activity: MainActivity? = null
        try {
            clearController()
            replaceRules(fixtures())
            DataStore.previewHintDismissedVersion = BuildConfig.PRE_VERSION_NAME
            activity = launch()
            val host = checkNotNull(activity)
            // Allow asynchronous preflight to settle; an ordinary launch must stay idle throughout.
            repeat(20) {
                instrumentation.waitForIdleSync()
                instrumentation.runOnMainSync {
                    assertEquals(ServiceCommandController.CommandState.Idle, controller.state.value)
                    assertFalse(controller.isBusy())
                    assertNull(host.supportFragmentManager.findFragmentByTag("outbound_reference_warning"))
                }
                Thread.sleep(100)
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
    fun foregroundWarningBroadcastDiscoversNewlyInvalidRules() = foregroundWarningBroadcast(persist = true)

    @Test
    fun foregroundWarningBroadcastUsesMinimalExtraWhenMarkerUnavailable() = foregroundWarningBroadcast(persist = false)

    private fun foregroundWarningBroadcast(persist: Boolean) {
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
            val pending = if (persist) OutboundReferenceWarning.queuePendingReview(instrumentation.targetContext, true)
                else OutboundReferenceWarning.PendingReview(java.util.UUID.randomUUID().toString(), true)
            instrumentation.targetContext.sendBroadcast(
                Intent("io.nekohasekai.sagernet.action.OUTBOUND_REFERENCE_WARNING_REQUIRED")
                    .setPackage(instrumentation.targetContext.packageName)
                    .putExtra(OutboundReferenceWarning.EXTRA_REQUEST_ID, pending.requestId)
                    .putExtra(OutboundReferenceWarning.EXTRA_START_IF_STOPPED, pending.startIfStopped)
            )
            awaitMain("A foreground warning broadcast must discover missing outbounds and show confirmation") {
                val waiting = controller.state.value as? ServiceCommandController.CommandState.ConfirmationRequired
                waiting?.warning?.issues?.map { it.missingId } == listOf(9223372036854775806L) &&
                    (host.supportFragmentManager.findFragmentByTag("outbound_reference_warning") as? DialogFragment)
                        ?.dialog?.isShowing == true
            }
            assertTrue((controller.state.value as ServiceCommandController.CommandState.ConfirmationRequired)
                .warning.startIfStopped)
            awaitMain("The presented live warning must acknowledge its pending request") {
                OutboundReferenceWarning.peekPendingReview(instrumentation.targetContext) == null
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
        val oldRulesFirstCreate = DataStore.rulesFirstCreate
        var activity: MainActivity? = null
        try {
            clearController()
            replaceRules(fixtures())
            DataStore.rulesFirstCreate = true
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
            // Queue a reload on the UI thread, mutate before its posted publication can run,
            // and observe every publication (not just the eventual final list).
            val publications = mutableListOf<List<Long>>()
            instrumentation.runOnMainSync {
                val fragment = host.supportFragmentManager.findFragmentById(R.id.fragment_holder) as RouteFragment
                val adapter = fragment.ruleAdapter
                adapter.registerAdapterDataObserver(object : androidx.recyclerview.widget.RecyclerView.AdapterDataObserver() {
                    override fun onChanged() { publications += adapter.ruleList.map { it.id } }
                })
                runBlocking {
                    adapter.reload()
                    replaceRules(fixtures().filterNot { it.id == 910001L })
                    adapter.onRemoved(910001L)
                }
            }
            instrumentation.waitForIdleSync()
            assertTrue("Removal must publish current rules/errors", publications.isNotEmpty())
            assertTrue("A queued reload must not resurrect a removed row", publications.all { 910001L !in it })
            publications.clear()
            instrumentation.runOnMainSync {
                val fragment = host.supportFragmentManager.findFragmentById(R.id.fragment_holder) as RouteFragment
                val adapter = fragment.ruleAdapter
                runBlocking {
                    adapter.reload()
                    SagerDatabase.rulesDao.reset()
                    adapter.onCleared()
                }
            }
            instrumentation.waitForIdleSync()
            assertTrue("Clear must publish a fresh empty rules/errors pair", publications.isNotEmpty())
            assertTrue("A queued reload must not resurrect cleared rows", publications.all { it.isEmpty() })
        } finally {
            try {
                activity?.let { host -> instrumentation.runOnMainSync { host.finish() } }
                instrumentation.waitForIdleSync()
            } finally {
                try { clearController() } finally {
                    try { replaceRules(original) } finally {
                        DataStore.rulesFirstCreate = oldRulesFirstCreate
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

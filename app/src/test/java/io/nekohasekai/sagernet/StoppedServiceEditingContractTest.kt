// @author 雾晚
package io.nekohasekai.sagernet

import io.nekohasekai.sagernet.bg.RootSpeedTestResult
import io.nekohasekai.sagernet.bg.proto.ProfileNotInRunningConfigException
import io.nekohasekai.sagernet.bg.proto.RunningProfileTagResolver
import io.nekohasekai.sagernet.bg.proto.SpeedTestSnapshot
import io.nekohasekai.sagernet.bg.proto.ThinControllerDelayClient
import io.nekohasekai.sagernet.bg.proto.UrlTest
import io.nekohasekai.sagernet.bg.proto.toSnapshot
import io.nekohasekai.sagernet.core.CoreRuntime
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.group.editor.CandidateHost
import io.nekohasekai.sagernet.group.editor.CandidateSnapshot
import io.nekohasekai.sagernet.group.editor.CandidateStatus
import io.nekohasekai.sagernet.group.editor.GroupCandidateCommit
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.io.IOException

/** B5（N1）：停止态可编辑回归契约。行为测试走 JVM 可跑的纯路径； */
class StoppedServiceEditingContractTest {

    // 停核下路由/策略组编辑：validate → apply 走同一份候选字节，
    // 确认后 phase 仍为 stopped、runningRevision 仍为空，即不启动核心。
    private class StoppedHost : CandidateHost {
        val commands = mutableListOf<String>()
        var revision = "r1"
        override suspend fun inputVersion() = "db1"
        override suspend fun freshStatus() =
            CandidateStatus(revision, "stopped", "")
        override suspend fun capture(expectedRevision: String, inputVersion: String) =
            CandidateSnapshot("{\"expectedRevision\":\"$expectedRevision\"}", inputVersion, expectedRevision)
        override suspend fun command(command: String, candidate: CandidateSnapshot): CandidateStatus {
            commands += command
            if (command == "config apply") revision = "r2"
            return CandidateStatus(if (command == "config apply") "r2" else revision, "stopped", "")
        }
    }

    @Test
    fun stoppedGroupApplyKeepsPhaseStopped() = runTest {
        val host = StoppedHost()
        val result = GroupCandidateCommit.apply(host)
        assertEquals("r2", result.revision)
        assertEquals("stopped", result.phase)
        assertEquals("", result.runningRevision)
        assertEquals(listOf("config validate", "config apply"), host.commands)
    }

    @Test
    fun stoppedSubscriptionUpdateIsConfirmOnly() {
        val updater = source("main/java/io/nekohasekai/sagernet/group/GroupUpdater.kt")
        val confirm = updater.substringAfter("suspend fun executeUpdate")
        assertTrue(confirm.contains("subscription.link?.startsWith(\"http://\")"))
        assertTrue(confirm.contains("subscription.updateWhenConnectedOnly"))
        assertTrue(confirm.contains("!connected"))
        assertTrue(confirm.contains("R.string.update_subscription_warning"))
        assertTrue(confirm.contains("RawUpdater.doUpdate(proxyGroup, subscription, userInterface, byUser)"))
        // 下载/解析失败发生在事务之前，旧 Provider 与当前选择不受影响；
        // 写库在同一个事务里完成。
        val rawUpdater = source("main/java/io/nekohasekai/sagernet/group/RawUpdater.kt")
        assertTrue(rawUpdater.contains("runInTransaction"))
    }

    @Test
    fun stoppedUrlTestFallsBackToRootSession() = runBlocking {
        // 单测跑在瘦构建上（WITH_CORE=false），doTest 走 thin 分支。
        assertFalse(CoreRuntime.available)
        val profile = ProxyEntity(id = 7, groupId = 1)
        val seen = mutableListOf<Pair<Long, String>>()
        val rootSession: suspend (ProxyEntity, String) -> Int = { entity, link ->
            seen += entity.id to link
            42
        }
        for ((name, error) in listOf(
            "core_not_connected" to IOException("core_not_connected"),
            "profile_not_in_running_config" to ProfileNotInRunningConfigException(),
        )) {
            seen.clear()
            val urlTest = UrlTest(
                overrideLink = "http://127.0.0.1/generate_204",
                runningProfileTags = RunningProfileTagResolver(tagsReader = { throw error }),
                controllerDelayClient = ThinControllerDelayClient(),
                rootTestSession = rootSession,
            )
            assertEquals(name, 42, urlTest.doTest(profile, useCache = false))
            assertEquals(name, listOf(7L to "http://127.0.0.1/generate_204"), seen)
        }
    }

    @Test
    fun urlTestFallbackDoesNotSwallowOtherErrors() = runBlocking {
        assertFalse(CoreRuntime.available)
        val profile = ProxyEntity(id = 8, groupId = 1)
        val urlTest = UrlTest(
            overrideLink = "http://127.0.0.1/generate_204",
            runningProfileTags = RunningProfileTagResolver(tagsReader = { throw IOException("profile_tags_unavailable") }),
            controllerDelayClient = ThinControllerDelayClient(),
            rootTestSession = { _, _ -> fail("unrelated tag failure must not fall back to a Root session") },
        )
        try {
            urlTest.doTest(profile, useCache = false)
            fail("expected profile_tags_unavailable to propagate")
        } catch (e: IOException) {
            assertEquals("profile_tags_unavailable", e.message)
        }
    }

    @Test
    fun rootSpeedTestResultMapsToSnapshot() {
        val profile = ProxyEntity(
            id = 9, groupId = 1,
            socksBean = SOCKSBean().apply {
                name = "fixture-node"
                serverAddress = "127.0.0.1"
                serverPort = 1080
            },
        )
        val snapshot: SpeedTestSnapshot = RootSpeedTestResult(
            profileKey = "9",
            mode = "simple_download",
            stage = "complete",
            downloadBitsPerSecond = 123456,
            uploadBitsPerSecond = 0,
            downloadBytes = 1048576,
            uploadBytes = 0,
            latencyMs = 12,
            serverName = "fixture",
            serverCountry = "CN",
            error = "",
            cancelled = false,
            done = true,
        ).toSnapshot(profile)
        assertEquals(9L, snapshot.profileId)
        assertEquals("fixture-node", snapshot.profileName)
        assertEquals("simple_download", snapshot.mode)
        assertEquals("complete", snapshot.stage)
        assertEquals(123456L, snapshot.downloadBitsPerSecond)
        assertEquals(1048576L, snapshot.downloadBytes)
        assertEquals(12L, snapshot.latencyMs)
        assertEquals("fixture", snapshot.serverName)
        assertEquals("CN", snapshot.serverCountry)
        assertEquals("", snapshot.error)
        assertFalse(snapshot.cancelled)
        assertTrue(snapshot.done)
    }

    @Test
    fun thinMenuEntriesAreNotRemoved() {
        val fragment = source("main/java/io/nekohasekai/sagernet/ui/ConfigurationFragment.kt")
        assertFalse(
            fragment.contains("action_connection_url_test)?.let { toolbar.menu.removeItem"),
        )
        assertFalse(
            fragment.contains("action_speed_test_group)?.let { toolbar.menu.removeItem"),
        )
        assertFalse(fragment.contains("popup.menu.removeItem(R.id.action_test_profile_speed)"))
        assertFalse(fragment.contains("popup.menu.removeItem(R.id.action_urltest)"))
        assertTrue(fragment.contains("safeSnackbar(R.string.stopped_nodes_hint)"))
    }

    @Test
    fun speedTestThinBranchUsesRootSession() {
        val runner = source("main/java/io/nekohasekai/sagernet/bg/proto/SpeedTestRunner.kt")
        assertTrue(runner.contains("runRootTestSession"))
        assertTrue(runner.contains("RootModuleClient.speedTest("))
        assertTrue(runner.contains("speed_test_plugin_unsupported"))
        assertTrue(runner.contains("STAGE_PENDING"))
        val urlTest = source("main/java/io/nekohasekai/sagernet/bg/proto/UrlTest.kt")
        assertTrue(urlTest.contains("core_not_connected"))
        assertTrue(urlTest.contains("rootTestSession"))
        assertTrue(urlTest.contains("url_test_plugin_unsupported"))
    }

    private fun source(relativePath: String): String = File("src/$relativePath").readText()
}

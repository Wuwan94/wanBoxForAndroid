// @author 雾晚
package io.nekohasekai.sagernet

import androidx.test.platform.app.InstrumentationRegistry
import io.nekohasekai.sagernet.database.*
import io.nekohasekai.sagernet.database.preference.*
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.utils.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.Assume.assumeTrue
import java.io.File

/** Same test is compiled against both source revisions; only fictional data leaves the device. */
class BackupCrossVersionTest {
    private fun snapshot() = BackupRestore.Plan(SagerDatabase.proxyDao.getAll(), SagerDatabase.groupDao.allGroups(),
        SagerDatabase.rulesDao.allRules(), PublicDatabase.kvPairDao.all())
    private fun sample() = BackupRestore.Plan(
        listOf(ProxyEntity(id=812, groupId=811, tx=22).putBean(SOCKSBean().apply {
            initializeDefaultValues(); serverAddress="192.0.2.2"; name="cross-version fixture"
            username="fictional"; password="test-only"
        })), listOf(ProxyGroup(id=811, name="cross-version group", isSelector=true, frontProxy=812)),
        listOf(RuleEntity(id=813, enabled=true, outbound=812, domains="example.invalid", config="{\"invert\":true}")),
        listOf(KeyValuePair("fixture.cross-version").put("中文虚构值")))
    @Test fun interchange() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val mode = InstrumentationRegistry.getArguments().getString("wanboxBackupMode")
        assumeTrue("Explicit export/import mode required", mode == "export" || mode == "import")
        val file = File(requireNotNull(instrumentation.targetContext.getExternalFilesDir(null)), "cross-version.json")
        val original = snapshot()
        try {
            if (mode == "export") {
                BackupRestore.apply(sample(), true, true, true)
                file.writeBytes(BackupHelper.doBackup())
                assertTrue(file.length() > 0)
            } else {
                BackupRestore.apply(BackupRestore.Plan(emptyList(), emptyList(), emptyList(), emptyList()), true, true, true)
                val root = BackupFiles.read(file.inputStream(), "cross-version.json")
                BackupRestore.apply(BackupRestore.parse(root), true, true, true)
                val restored = snapshot()
                assertEquals(1, restored.profiles!!.size)
                val proxy = restored.profiles.single()
                assertEquals(812L, proxy.id); assertEquals(811L, proxy.groupId); assertEquals(22L, proxy.tx)
                assertEquals("192.0.2.2", proxy.socksBean!!.serverAddress)
                assertEquals("test-only", proxy.socksBean!!.password)
                assertEquals("fictional", proxy.socksBean!!.username)
                assertEquals(812L, restored.groups!!.single().frontProxy)
                assertTrue(restored.groups.single().isSelector)
                assertEquals(sample().rules, restored.rules)
                assertEquals("中文虚构值", PublicDatabase.kvPairDao["fixture.cross-version"]!!.string)
            }
        } finally { BackupRestore.apply(original, true, true, true) }
    }
}

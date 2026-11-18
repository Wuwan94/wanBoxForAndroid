// @author 雾晚
package io.nekohasekai.sagernet

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** Direct shortcuts stay private; authorization and node selection retain their paths. @author 雾晚 */
class ShortcutControlContractTest {
    @Test fun directControlsArePrivateAndNoLongerWaitForAppConsent() {
        val manifest = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(File("src/main/AndroidManifest.xml"))
        val activities = manifest.getElementsByTagName("activity")
        for (name in listOf("QuickToggleShortcut", "QuickEnableShortcut", "QuickDisableShortcut")) {
            val activity = (0 until activities.length).map { activities.item(it) as org.w3c.dom.Element }
                .single { it.getAttributeNS("http://schemas.android.com/apk/res/android", "name").endsWith(".$name") }
            assertEquals("false", activity.getAttributeNS("http://schemas.android.com/apk/res/android", "exported"))
            val text = File("src/main/java").walkTopDown().single { it.name == "$name.kt" }.readText()
            assertFalse(text.contains("AlertDialog")); assertFalse(text.contains("confirmControl"))
            assertTrue(text.substringAfter("override fun onCreate").substringBefore("override fun onServiceConnected")
                .contains("connection.connect(this, this)"))
            assertTrue(text.contains("connection.disconnect(this)"))
        }
    }

    @Test fun toggleKeepsNodeValidationAndServiceSemanticsAndSystemAuthorization() {
        val toggle = File("src/main/java/io/nekohasekai/sagernet/QuickToggleShortcut.kt").readText()
        assertTrue(toggle.contains("ProfileManager.getProfile(profileId) == null"))
        assertTrue(toggle.contains("SagerNet.toggleService(profileId)"))
        val commands = File("src/main/java/io/nekohasekai/sagernet/SagerNet.kt").readText()
        assertTrue(commands.contains("client.toggleService(profileId)"))
        val rootClient = File("src/main/java/io/nekohasekai/sagernet/bg/RootModuleClient.kt").readText()
        assertTrue(rootClient.contains("val status = callFreshStatus()"))
        assertTrue(rootClient.contains("serviceToggleAction(status.state, profileId, status.profileId)"))
        assertTrue(rootClient.contains("tryWithChangesLock {"))
        assertTrue(rootClient.contains("DataStore.selectedProxy = profileId"))
        assertFalse(File("src/main/java/io/nekohasekai/sagernet/ui/VpnRequestActivity.kt").exists())
        val core = File("src/main/java/io/nekohasekai/sagernet/SagerNet.kt").readText()
        assertTrue(core.contains("RootModuleClient.startOrReload()"))
        // 修红 CI #179/#180：O2d 之后 SwitchActivity 不再直调 startService()——那样运行中
        // 也会全量重建、中断现有连接，改为走热切换通道；“选中节点后停止时也能连上”
        // 的旧契约意图改由通道的停止分支守住：它走规范的 startService() 入口。
        // 旧的字面断言（SwitchActivity 源码含 "SagerNet.startService()"）已被 O2d 适配
        // 取代，见 design.md 的 O2d 修红记录；此处不断言旧字面形式。
        val switchActivity = File("src/main/java/io/nekohasekai/sagernet/ui/SwitchActivity.kt").readText()
        assertTrue(switchActivity.contains("SagerNet.switchProfileService(profileId)"))
        val channel = core.substringAfter("fun switchProfileService(profileId: Long)").substringBefore("\n        fun ", "")
        // 按调用行（含换行 + 16 空格缩进）断言，避免注释里的同名文字把断言骗过
        assertTrue(channel.contains("\n                startService()"))
        assertTrue(core.contains("RootModuleClient.stop()"))
        val shortcuts = File("src/main/res/xml/shortcuts.xml").readText()
        assertTrue(shortcuts.contains("QuickToggleShortcut")); assertTrue(shortcuts.contains("QuickEnableShortcut"))
        assertTrue(shortcuts.contains("QuickDisableShortcut"))
    }
}

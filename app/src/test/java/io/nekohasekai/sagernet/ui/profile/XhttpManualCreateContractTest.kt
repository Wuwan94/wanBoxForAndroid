package io.nekohasekai.sagernet.ui.profile

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class XhttpManualCreateContractTest {
    @Test fun manualMenuRoutesXhttpChoiceToExistingVlessForm() {
        val menu = File("src/main/res/menu/add_profile_menu.xml").readText()
        assertTrue(menu.contains("android:id=\"@+id/action_new_xhttp\""))
        assertTrue(menu.contains("android:title=\"@string/action_vless_xhttp\""))
        val defaultStrings = File("src/main/res/values/strings.xml").readText()
        assertTrue(defaultStrings.contains("name=\"action_vless_xhttp\">VLESS + XHTTP</string>"))
        val chineseStrings = File("src/main/res/values-zh-rCN/strings.xml").readText()
        assertTrue(chineseStrings.contains("name=\"action_vless_xhttp\">VLESS + XHTTP</string>"))

        val fragment = File("src/main/java/io/nekohasekai/sagernet/ui/ConfigurationFragment.kt").readText()
        val handler = fragment.substringAfter("R.id.action_new_xhttp ->", "")
            .substringBefore("R.id.action_new_trojan ->")
        assertTrue(handler.contains("VMessSettingsActivity::class.java"))
        assertTrue(handler.contains("putExtra(\"vless\", true)"))
        assertTrue(handler.contains("putExtra(\"xhttp\", true)"))

        val activity = File("src/main/java/io/nekohasekai/sagernet/ui/profile/VMessSettingsActivity.kt")
            .readText()
        assertTrue(activity.contains("getBooleanExtra(\"xhttp\", false)"))
        assertTrue(activity.contains("createVlessBean"))
        assertTrue(activity.contains("VMessBean().apply { initializeDefaultValues() }"))
    }
}

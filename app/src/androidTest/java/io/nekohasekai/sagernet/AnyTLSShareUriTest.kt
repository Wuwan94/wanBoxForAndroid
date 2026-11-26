// @author 雾晚
package io.nekohasekai.sagernet

import android.view.ContextThemeWrapper
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceManager
import androidx.test.platform.app.InstrumentationRegistry
import io.nekohasekai.sagernet.fmt.parseUniversal
import io.nekohasekai.sagernet.fmt.toUniversalLink
import io.nekohasekai.sagernet.utils.Theme
import moe.matsuri.nb4a.proxy.anytls.AnyTLSBean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Exercises the real Android settings and Base64 paths for AnyTLS mTLS. */
class AnyTLSShareUriTest {
    @Test fun universalShareExcludesClientIdentityAndPreservesNodeFields() {
        val bean = AnyTLSBean().apply {
            name = "instrumentation-share-fixture"
            serverAddress = "anytls.example"
            serverPort = 443
            password = "share-password"
            clientCertificate = "-----BEGIN CERTIFICATE-----\nTEST-ONLY-CERTIFICATE\n-----END CERTIFICATE-----"
            clientKey = "-----BEGIN PRIVATE KEY-----\nTEST-ONLY-PRIVATE-KEY\n-----END PRIVATE KEY-----"
            initializeDefaultValues()
        }

        val sharedUri = bean.toUniversalLink()
        val restored = parseUniversal(sharedUri) as AnyTLSBean

        assertEquals("anytls.example", restored.serverAddress)
        assertEquals("share-password", restored.password)
        assertEquals("instrumentation-share-fixture", restored.name)
        assertTrue(restored.clientCertificate.isNullOrBlank())
        assertTrue(restored.clientKey.isNullOrBlank())
        assertTrue(bean.clientCertificate.contains("TEST-ONLY-CERTIFICATE"))
        assertTrue(bean.clientKey.contains("TEST-ONLY-PRIVATE-KEY"))
    }

    @Test fun mTlsFieldsAreEditableWithoutEchoingPrivateKeyInSummary() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val context = ContextThemeWrapper(instrumentation.targetContext, Theme.getTheme())
            val manager = PreferenceManager(context)
            val screen = manager.inflateFromResource(context, R.xml.anytls_preferences, null)
            val certificate = screen.findPreference<EditTextPreference>("clientCertificate")
            val privateKey = screen.findPreference<EditTextPreference>("clientKey")

            assertNotNull(certificate)
            assertNotNull(privateKey)
            val safeSummary = privateKey!!.summary?.toString()
            assertNotNull(safeSummary)
            privateKey.text = "TEST-ONLY-PRIVATE-KEY"
            assertEquals(safeSummary, privateKey.summary?.toString())
            assertFalse(privateKey.summary?.toString()?.contains("TEST-ONLY-PRIVATE-KEY") == true)
        }
    }
}

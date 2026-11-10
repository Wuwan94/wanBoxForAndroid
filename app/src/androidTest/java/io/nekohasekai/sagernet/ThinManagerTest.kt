// @author 雾晚
package io.nekohasekai.sagernet

import androidx.test.platform.app.InstrumentationRegistry
import io.nekohasekai.sagernet.core.CoreAssets
import io.nekohasekai.sagernet.core.CoreRuntime
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile

/** Runs against the installed manager, without a JNI bridge or a rooted emulator. */
class ThinManagerTest {
    @Test fun installedManagerHasNoNativeCoreAndKotlinHttpCanInitialize() {
        assertFalse("CI must test the shipping thin configuration", BuildConfig.WITH_CORE)
        assertFalse(CoreRuntime.available)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        ZipFile(context.applicationInfo.sourceDir).use { apk ->
            assertFalse(apk.entries().asSequence().any { it.name.startsWith("lib/") && it.name.endsWith(".so") })
        }
        CoreRuntime.httpClient().modernTLS().newRequest().setURL("https://example.invalid")
        // This only verifies the non-JNI construction path; network routing has separate tests.
    }

    @Test fun offlineGeoDatabasesAreDeployedWithoutJni() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as SagerNet
        CoreAssets.ensure(app)
        for (name in listOf("geoip", "geosite")) {
            assertTrue("missing $name database", File(app.externalAssets, "$name.db").length() > 0)
            assertTrue("missing $name version", File(app.externalAssets, "$name.version.txt").readText().trim().isNotEmpty())
        }
    }
}

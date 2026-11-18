package io.nekohasekai.sagernet.route

import io.nekohasekai.sagernet.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B3/O6：集中式系统组件排除名单（借鉴 OwnBox 8.8 SYSTEM_EXCLUDED_PACKAGES，
 * 上游 commit d401e93）。名单内组件不得被应用层规则错分流。
 */
class SystemExcludedPackagesTest {

    @Test
    fun systemComponentsAreExcluded() {
        for (pkg in listOf(
            "android",
            "com.android.systemui",
            "com.android.settings",
            "com.android.providers.telephony",
            "com.android.providers.downloads",
            "com.android.providers.downloads.ui",
            "com.android.providers.media",
            "com.android.providers.media.module",
            "com.android.captiveportallogin",
            "com.android.networkstack",
            "com.google.android.networkstack",
            "com.android.externalstorage",
            "com.android.shell",
            "com.android.phone",
            "com.android.nfc",
            "com.android.bluetooth",
            "com.google.android.inputmethod.latin",
            "com.google.android.inputmethod.pinyin",
        )) {
            assertTrue("$pkg should be excluded", SystemExcludedPackages.isExcluded(pkg))
        }
        // 管理器自身也不参与自身规则的强绑（与上游一致）。
        assertTrue("own package should be excluded", SystemExcludedPackages.isExcluded(BuildConfig.APPLICATION_ID))
    }

    @Test
    fun ordinaryAppsAreNotExcluded() {
        for (pkg in listOf(
            "com.android.vending",
            "com.google.android.gms",
            "org.telegram.messenger",
            "com.tencent.mm",
        )) {
            assertFalse("$pkg should not be excluded", SystemExcludedPackages.isExcluded(pkg))
        }
    }

    @Test
    fun excludeRemovesOnlySystemComponents() {
        val input = setOf(
            "com.android.vending",
            "com.android.providers.downloads",
            "org.telegram.messenger",
            "com.android.networkstack",
            "com.android.providers.media",
        )
        assertEquals(
            setOf("com.android.vending", "org.telegram.messenger"),
            SystemExcludedPackages.exclude(input)
        )
        assertEquals(emptySet<String>(), SystemExcludedPackages.exclude(emptySet()))
        // 原集合不变。
        assertEquals(5, input.size)
    }
}

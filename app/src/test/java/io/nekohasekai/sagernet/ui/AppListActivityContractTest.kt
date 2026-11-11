package io.nekohasekai.sagernet.ui

import android.content.pm.ApplicationInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AppListActivityContractTest {
    @Test
    fun updatedSystemAppsRemainInTheSystemCategory() {
        assertTrue(isSystemApplication(ApplicationInfo.FLAG_SYSTEM))
        assertTrue(isSystemApplication(ApplicationInfo.FLAG_UPDATED_SYSTEM_APP))
        assertFalse(isSystemApplication(0))
    }

    @Test
    fun userFilterExcludesSystemAppsButKeepsMatchingUserApps() {
        assertFalse(matchesAppListFilter("System Settings", "android.settings", 1000, true, "", false))
        assertTrue(matchesAppListFilter("Chat", "com.example.chat", 10123, false, "chat", false))
        assertFalse(matchesAppListFilter("Maps", "com.example.maps", 10124, false, "chat", false))
    }

    @Test
    fun systemFilterIncludesBothSystemAndUserAppsAndSearchStillApplies() {
        assertTrue(matchesAppListFilter("System Settings", "android.settings", 1000, true, "settings", true))
        assertTrue(matchesAppListFilter("Chat", "com.example.chat", 10123, false, "10123", true))
        assertFalse(matchesAppListFilter("Maps", "com.example.maps", 10124, false, "missing", true))
    }

    @Test
    fun iconLoaderReturnsFallbackWhenPackageManagerThrows() {
        val fallback = "android-placeholder"
        assertEquals(fallback, loadWithFallback({ error("broken icon") }, fallback))
    }

    @Test
    fun iconLoaderPreservesSuccessfulIcon() {
        val icon = Any()
        assertSame(icon, loadWithFallback({ icon }, Any()))
    }
}

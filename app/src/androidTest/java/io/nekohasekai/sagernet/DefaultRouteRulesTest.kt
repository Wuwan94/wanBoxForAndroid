// @author 雾晚
package io.nekohasekai.sagernet

import io.nekohasekai.sagernet.database.*
import io.nekohasekai.sagernet.database.preference.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class DefaultRouteRulesTest {
    private fun snapshot() = BackupRestore.Plan(
        SagerDatabase.proxyDao.getAll(),
        SagerDatabase.groupDao.allGroups(),
        SagerDatabase.rulesDao.allRules(),
        PublicDatabase.kvPairDao.all()
    )

    @Test
    fun seededDefaultsUseCanonicalAdsAndDoNotForcePlayDownloads() = runBlocking {
        val original = snapshot()
        val oldRulesFirstCreate = DataStore.rulesFirstCreate
        try {
            BackupRestore.apply(
                BackupRestore.Plan(emptyList(), emptyList(), emptyList(), emptyList()),
                true, true, true
            )
            DataStore.rulesFirstCreate = false

            val rules = ProfileManager.getRules()
            val ads = rules.single { it.outbound == -2L && it.domains.contains("category-ads-all") }
            assertEquals("geosite-category-ads-all", ads.domains)

            val play = rules.single { it.packages.contains("com.android.vending") }
            assertFalse("The seeded Play rule must not be forced on", play.enabled)
            assertFalse(
                "Root rule-mode must not force the Android download manager through the Play rule",
                play.packages.contains("com.android.providers.downloads")
            )
        } finally {
            BackupRestore.apply(original, true, true, true)
            DataStore.rulesFirstCreate = oldRulesFirstCreate
        }
    }

    @Test
    fun historicalPlayRulesDropOnlyInjectedDownloadsPackages() = runBlocking {
        val original = snapshot()
        val playDomains = "domain:googleapis.cn\ndomain:gvt1.com"
        val downloadsPackages = setOf(
            "com.android.providers.downloads",
            "com.android.providers.downloads.ui"
        )
        val playPackages = setOf(
            "com.android.vending",
            "com.google.android.gms"
        ) + downloadsPackages + "org.example.keep"
        val enabledPlay = RuleEntity(
            id = 101L,
            userOrder = 1L,
            domains = playDomains,
            packages = playPackages,
            enabled = true
        )
        val disabledPlay = RuleEntity(
            id = 102L,
            userOrder = 2L,
            domains = playDomains,
            packages = playPackages,
            enabled = false
        )
        val unrelated = RuleEntity(
            id = 103L,
            userOrder = 3L,
            domains = "domain:example.org",
            packages = setOf("com.android.providers.downloads", "org.example.keep"),
            enabled = true
        )
        try {
            BackupRestore.apply(
                BackupRestore.Plan(null, null, listOf(enabledPlay, disabledPlay, unrelated), null),
                false, true, false
            )

            val rules = ProfileManager.getRules().associateBy { it.id }
            val expectedPlayPackages = setOf(
                "com.android.vending",
                "com.google.android.gms",
                "org.example.keep"
            )
            assertEquals(expectedPlayPackages, rules.getValue(enabledPlay.id).packages)
            assertEquals(expectedPlayPackages, rules.getValue(disabledPlay.id).packages)
            assertEquals(true, rules.getValue(enabledPlay.id).enabled)
            assertEquals(false, rules.getValue(disabledPlay.id).enabled)
            assertEquals(unrelated.packages, rules.getValue(unrelated.id).packages)
            assertEquals(unrelated.enabled, rules.getValue(unrelated.id).enabled)
        } finally {
            BackupRestore.apply(original, true, true, true)
        }
    }
}

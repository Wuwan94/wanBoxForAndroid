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
}

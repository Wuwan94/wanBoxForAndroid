package io.nekohasekai.sagernet

import io.nekohasekai.sagernet.database.*
import io.nekohasekai.sagernet.group.editor.*
import io.nekohasekai.sagernet.ui.GroupSettingsActivity
import org.junit.Assert.*
import org.junit.Test

/** Real preference persistence, not a source-text or simulated page assertion. */
class GroupEditorPersistenceTest {
    @Test fun actualRootProducerPreservesOtherPartitionsAndUsesOrderedStableMembers() = kotlinx.coroutines.runBlocking {
        val original = BackupRestore.parse(org.json.JSONObject(
            io.nekohasekai.sagernet.utils.BackupHelper.doBackup().toString(Charsets.UTF_8)))
        fun node(id: Long) = ProxyEntity(id = id, groupId = 99124).putBean(
            io.nekohasekai.sagernet.fmt.socks.SOCKSBean().apply {
                initializeDefaultValues(); serverAddress = "192.0.2.1"; serverPort = 1080
            })
        val first = node(99125)
        val second = node(99126)
        try {
            BackupRestore.apply(BackupRestore.Plan(listOf(first, second),
                listOf(ProxyGroup(id = 99124, name = "fixture", isSelector = true)), emptyList(), null), true, true, false)
            DataStore.serviceMode = Key.MODE_ROOT
            DataStore.globalCustomConfig = ""; DataStore.globalMode = false; DataStore.enableClashAPI = false
            val routes = io.nekohasekai.sagernet.route.RootRouteBuildSnapshot.capture()
            suspend fun config(drafts: Map<Long, GroupDraft>): com.google.gson.JsonObject {
                val instance = io.nekohasekai.sagernet.bg.proto.ProxyInstance(first,
                    rootRouteSnapshot = routes.withEditorDrafts(drafts), persistBuildMetadata = false)
                try {
                    instance.init()
                    return io.nekohasekai.sagernet.bg.RootModuleSnapshot(instance, "fixture-revision")
                        .build()
                } finally { instance.close() }
            }
            DataStore.selectedProxy = first.id
            val selectorDraft = GroupDraft(99124, "fixture", GroupMode.SELECTOR, listOf(99126, 99125),
                UrlTestParameters("", 600, 100, "30m", false))
            val builtSelector = config(mapOf(99124L to selectorDraft))
            val selector = builtSelector["config"].asJsonObject["outbounds"].asJsonArray
                .first { it.asJsonObject["tag"].asString == "proxy" }.asJsonObject
            val builtTags = com.google.gson.JsonParser.parseString(String(java.util.Base64.getDecoder()
                .decode(builtSelector["files"].asJsonObject["files/profile_tags.json"].asString), Charsets.UTF_8)).asJsonObject
            assertEquals(builtTags[first.id.toString()].asJsonObject["tag"].asString, selector["default"].asString)
            assertNotEquals(selector["outbounds"].asJsonArray[0].asString, selector["default"].asString)
            val baseline = config(emptyMap())
            val before = baseline["config"].asJsonObject
            assertTrue(before["inbounds"].asJsonArray.any { it.asJsonObject["type"]?.asString == "tun" })
            // These settings are deliberately not applied to the current generation.
            DataStore.remoteDns = "https://unapplied.example/dns-query"
            DataStore.directDns = "https://unapplied-direct.example/dns-query"
            val draft = GroupDraft(99124, "fixture", GroupMode.URL_TEST, listOf(99126, 99125),
                UrlTestParameters("https://example.test/probe", 31, 12, "2m", true))
            val after = GroupEditor.patchSnapshot(baseline, draft, "proxy", DataStore.connectionTestURL)["config"].asJsonObject
            val group = after["outbounds"].asJsonArray.first { it.asJsonObject["tag"].asString == "proxy" }.asJsonObject
            assertEquals("urltest", group["type"].asString)
            val baselineTags = com.google.gson.JsonParser.parseString(String(
                java.util.Base64.getDecoder().decode(baseline["files"].asJsonObject["files/profile_tags.json"].asString),
                Charsets.UTF_8)).asJsonObject
            val expectedMembers = listOf("99126", "99125").map { baselineTags[it].asJsonObject["tag"].asString }
            assertEquals(expectedMembers, group["outbounds"].asJsonArray.map { it.asString })
            assertEquals("https://example.test/probe", group["url"].asString)
            assertEquals("31s", group["interval"].asString)
            assertEquals(12, group["tolerance"].asInt)
            assertEquals("30m", group["idle_timeout"].asString)
            assertFalse(group["interrupt_exist_connections"].asBoolean)
            before.remove("outbounds"); after.remove("outbounds")
            assertEquals(before, after)
        } finally { BackupRestore.apply(original, true, true, true) }
    }

    @Test fun existingIdentityAndDeletedSubscriptionMembersHaveOneTruth() = kotlinx.coroutines.runBlocking {
        val original = BackupRestore.parse(org.json.JSONObject(
            io.nekohasekai.sagernet.utils.BackupHelper.doBackup().toString(Charsets.UTF_8)))
        fun node(id: Long) = ProxyEntity(id = id, groupId = 99124).putBean(
            io.nekohasekai.sagernet.fmt.socks.SOCKSBean().apply {
                initializeDefaultValues(); name = "node-$id"; serverAddress = "192.0.2.1"; serverPort = 1080
            })
        try {
            BackupRestore.apply(BackupRestore.Plan(listOf(node(99125), node(99126)),
                listOf(ProxyGroup(id = 99124, name = "database-name", isSelector = true)), emptyList(), null), true, true, false)
            val draft = GroupDraft(99124, "stale-draft-name", GroupMode.SELECTOR, listOf(99125, 99126),
                UrlTestParameters("", 600, 100, "30m", false))
            GroupEditorStore.write(draft)
            val canonical = GroupEditorService.load(draft.id)
            assertEquals("database-name", canonical.name)
            assertTrue(GroupEditorService.validate(canonical.copy(name = "renamed")).any { it.code == "identity_locked" })
            assertTrue(GroupEditorService.validate(canonical.copy(name = "node-99125")).any { it.code == "duplicate_tag" })
            ProfileManager.deleteProfile(99124, 99125)
            assertEquals(listOf(99126L), GroupEditorStore.read(draft.id)?.members)
            assertFalse(GroupEditorService.validate(GroupEditorService.load(draft.id)).any { it.code == "missing_entity" })
            ProfileManager.deleteProfile(99124, 99126)
            assertNull(GroupEditorStore.read(draft.id))
        } finally { BackupRestore.apply(original, true, true, true) }
    }

    /** P1-3 + N2: save -> subscription insert -> cross-group move keeps count, runtime and export equal,
     * and a node named like a non-main group no longer breaks the ordinary build. */
    @Test fun countRuntimeAndExportShareOneMembershipTruth() = kotlinx.coroutines.runBlocking {
        val original = BackupRestore.parse(org.json.JSONObject(
            io.nekohasekai.sagernet.utils.BackupHelper.doBackup().toString(Charsets.UTF_8)))
        fun node(id: Long, group: Long, label: String) = ProxyEntity(id = id, groupId = group, userOrder = id).putBean(
            io.nekohasekai.sagernet.fmt.socks.SOCKSBean().apply {
                initializeDefaultValues(); name = label; serverAddress = "192.0.2.1"; serverPort = 1080
            })
        val main = node(99125, 99124, "main")
        try {
            BackupRestore.apply(BackupRestore.Plan(
                listOf(main, node(99142, 99124, "moved"), node(99141, 99140, "hk-1")),
                listOf(ProxyGroup(id = 99124, name = "订阅A", isSelector = true, userOrder = 1),
                    ProxyGroup(id = 99140, name = "香港", isSelector = true, userOrder = 2)), emptyList(), null), true, true, false)
            DataStore.serviceMode = Key.MODE_ROOT
            DataStore.globalCustomConfig = ""; DataStore.globalMode = false; DataStore.enableClashAPI = false
            DataStore.selectedProxy = main.id
            // Save "香港" with node 99142 taken over from "订阅A", ordered first.
            val draft = GroupDraft(99140, "香港", GroupMode.SELECTOR, listOf(99142, 99141),
                UrlTestParameters("", 600, 100, "30m", false))
            assertEquals(emptyList<GroupEditor.Issue>(), GroupEditorService.validate(draft))
            val preparation = GroupLocalPreparation(draft)
            try { preparation.prepare(); preparation.finish() }
            catch (error: Exception) { preparation.restore(); preparation.finish(); throw error }
            assertEquals(99140L, SagerDatabase.proxyDao.getById(99142)!!.groupId)
            // Subscription-style updates only write groupId: a new "香港" member arrives, and the main
            // group's subscription brings a node whose name equals the group name (N2). 99141 moves away.
            SagerDatabase.proxyDao.insert(listOf(node(99143, 99140, "hk-new"), node(99126, 99124, "香港")))
            SagerDatabase.proxyDao.getById(99141)!!.also { it.groupId = 99124; SagerDatabase.proxyDao.updateProxy(it) }
            val expected = listOf(99142L, 99143L)
            assertEquals(expected.size.toLong(), SagerDatabase.proxyDao.countByGroup(99140))
            fun members(result: io.nekohasekai.sagernet.fmt.ConfigBuildResult): List<Long> {
                val byTag = result.profileTagMap.entries.associate { (id, tag) -> tag to id }
                return com.google.gson.JsonParser.parseString(result.config).asJsonObject["outbounds"].asJsonArray
                    .map { it.asJsonObject }.single { it["tag"].asString == "香港" }["outbounds"].asJsonArray
                    .map { byTag.getValue(it.asString) }
            }
            val runtime = io.nekohasekai.sagernet.fmt.buildConfig(main,
                rootRouteSnapshot = io.nekohasekai.sagernet.route.RootRouteBuildSnapshot.capture())
            val export = io.nekohasekai.sagernet.fmt.buildConfig(main, forExport = true)
            assertEquals(expected, members(runtime))
            assertEquals(expected, members(export))
            // N2: the node named "香港" kept its own suffixed tag next to the group.
            assertEquals("香港-1", runtime.profileTagMap[99126])
        } finally { BackupRestore.apply(original, true, true, true) }
    }

    @Test fun ordinaryOutputMigratesLegacyDraftUrlAndClearedOverrideMatchesCandidate() = kotlinx.coroutines.runBlocking {
        val original = BackupRestore.parse(org.json.JSONObject(
            io.nekohasekai.sagernet.utils.BackupHelper.doBackup().toString(Charsets.UTF_8)))
        val node = ProxyEntity(id = 99125, groupId = 99124).putBean(
            io.nekohasekai.sagernet.fmt.socks.SOCKSBean().apply {
                initializeDefaultValues(); serverAddress = "192.0.2.1"; serverPort = 1080
            })
        try {
            BackupRestore.apply(BackupRestore.Plan(listOf(node),
                listOf(ProxyGroup(id = 99124, name = "fixture", isSelector = true)), emptyList(), null), true, true, false)
            DataStore.serviceMode = Key.MODE_ROOT
            DataStore.globalCustomConfig = ""; DataStore.globalMode = false; DataStore.enableClashAPI = false
            DataStore.connectionTestURL = "https://global.example/probe"
            suspend fun build(): com.google.gson.JsonObject {
                val instance = io.nekohasekai.sagernet.bg.proto.ProxyInstance(node,
                    rootRouteSnapshot = io.nekohasekai.sagernet.route.RootRouteBuildSnapshot.capture(), persistBuildMetadata = false)
                try {
                    instance.init()
                    return io.nekohasekai.sagernet.bg.RootModuleSnapshot(instance, "fixture-revision").build()
                } finally { instance.close() }
            }
            fun url(snapshot: com.google.gson.JsonObject) = snapshot["config"].asJsonObject["outbounds"].asJsonArray
                .first { it.asJsonObject["tag"].asString == "proxy" }.asJsonObject["url"].asString
            val draft = GroupDraft(99124, "fixture", GroupMode.URL_TEST, listOf(99125),
                UrlTestParameters(SagerNet.application.getString(R.string.default_group_test_url), 31, 12, "2m", false))
            GroupEditorStore.write(draft)
            assertEquals("https://global.example/probe", url(build()))
            val override = draft.copy(parameters = draft.parameters.copy(url = "https://override.example/probe"))
            GroupEditorStore.write(override)
            val baseline = build()
            assertEquals("https://override.example/probe", url(baseline))
            val cleared = override.copy(parameters = override.parameters.copy(url = ""))
            val candidate = GroupEditor.patchSnapshot(baseline, cleared, "proxy", DataStore.connectionTestURL)
            assertEquals("https://global.example/probe", url(candidate))
            GroupEditorStore.write(cleared)
            assertEquals(url(candidate), url(build()))
        } finally { BackupRestore.apply(original, true, true, true) }
    }

    @Test fun capturedEditorInputsIgnoreLaterPreferenceChanges() = kotlinx.coroutines.runBlocking {
        val settings = io.nekohasekai.sagernet.database.preference.PublicDatabase.kvPairDao.all()
        val id = 99123L
        val previous = SagerDatabase.groupDao.getById(id)
        // Drafts only order nodes the group owns (P1-3), so the fixture group owns both members.
        val owned = listOf(99131L, 99130L).map { node -> ProxyEntity(id = node, groupId = id).putBean(
            io.nekohasekai.sagernet.fmt.socks.SOCKSBean().apply {
                initializeDefaultValues(); serverAddress = "192.0.2.1"; serverPort = 1080
            }) }
        try {
            if (previous == null) SagerDatabase.groupDao.createGroup(ProxyGroup(id = id, name = "fixture"))
            SagerDatabase.proxyDao.insert(owned)
            val name = SagerDatabase.groupDao.getById(id)!!.displayName()
            val draft = GroupDraft(id, name, GroupMode.URL_TEST, listOf(99131, 99130),
                UrlTestParameters("", 31, 12, "2m", true))
            GroupEditorStore.write(draft)
            val snapshot = io.nekohasekai.sagernet.route.RootRouteBuildSnapshot.capture()
            GroupEditorStore.write(draft.copy(members = listOf(99130, 99131)))
            assertEquals(draft, snapshot.editorDrafts()[id])
            assertEquals(listOf(99130L, 99131L), GroupEditorStore.read(id)?.members)
        } finally {
            SagerDatabase.proxyDao.deleteProxy(owned)
            if (previous == null) SagerDatabase.groupDao.deleteById(id)
            io.nekohasekai.sagernet.database.preference.PublicDatabase.kvPairDao.reset()
            io.nekohasekai.sagernet.database.preference.PublicDatabase.kvPairDao.insert(settings)
        }
    }

    @Test fun ordinaryEditingPreservesUrlTestAndLegacyLoadBalanceModes() {
        val settings = io.nekohasekai.sagernet.database.preference.PublicDatabase.kvPairDao.all()
        val cache = moe.matsuri.nb4a.TempDatabase.profileCacheDao.all()
        try {
            for (mode in listOf(true to false, false to true)) {
                val group = ProxyGroup(id = 99121, name = "fixture")
                DataStore.setGroupIsUrlTest(group.id, mode.first)
                DataStore.setGroupIsLoadBalance(group.id, mode.second)
                lateinit var activity: GroupSettingsActivity
                androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().runOnMainSync {
                    activity = GroupSettingsActivity()
                }
                with(activity) { group.init(); group.serialize() }
                assertEquals(mode.first, DataStore.groupIsUrlTest(group.id))
                assertEquals(mode.second, DataStore.groupIsLoadBalance(group.id))
            }
        } finally {
            io.nekohasekai.sagernet.database.preference.PublicDatabase.kvPairDao.reset()
            io.nekohasekai.sagernet.database.preference.PublicDatabase.kvPairDao.insert(settings)
            moe.matsuri.nb4a.TempDatabase.profileCacheDao.reset()
            moe.matsuri.nb4a.TempDatabase.profileCacheDao.insert(cache)
        }
    }

    @Test fun createdGroupIsInSnapshotAndBalancerUsesItsStableNodeMembers() = kotlinx.coroutines.runBlocking {
        val original = BackupRestore.parse(org.json.JSONObject(
            io.nekohasekai.sagernet.utils.BackupHelper.doBackup().toString(Charsets.UTF_8)))
        val node = ProxyEntity(id = 99125, groupId = 99124).putBean(
            io.nekohasekai.sagernet.fmt.socks.SOCKSBean().apply {
                initializeDefaultValues(); serverAddress = "192.0.2.1"; serverPort = 1080
            })
        // A group and node intentionally share 99125 in independent namespaces.
        val draft = GroupDraft(99125, "created", GroupMode.SELECTOR, listOf(99125),
            UrlTestParameters("", 31, 12, "2m", false))
        val balancer = ProxyEntity(id = 99126, groupId = 99124).putBean(
            io.nekohasekai.sagernet.fmt.internal.BalancerBean().apply {
                initializeDefaultValues()
                balancerType = io.nekohasekai.sagernet.fmt.internal.BalancerBean.TYPE_GROUP
                // Match the UI's round-robin choice; the default leastPing emits urltest.
                strategy = io.nekohasekai.sagernet.fmt.internal.BalancerBean.STRATEGY_ROUND_ROBIN
                targetGroupIds = arrayListOf(draft.id); targetGroupId = draft.id
            })
        try {
            BackupRestore.apply(BackupRestore.Plan(listOf(node),
                listOf(ProxyGroup(id = 99124, name = "fixture", isSelector = true)), emptyList(), null), true, true, false)
            val preparation = GroupLocalPreparation(draft)
            try { preparation.prepare(); preparation.finish() }
            catch (error: Exception) { preparation.restore(); preparation.finish(); throw error }
            // Only reference the new group after the editor has actually created it.
            SagerDatabase.proxyDao.insert(listOf(balancer))
            assertTrue(SagerDatabase.groupDao.allGroups().any { it.id == draft.id })
            val snapshot = io.nekohasekai.sagernet.route.RootRouteBuildSnapshot.capture()
            assertTrue(snapshot.allGroups().any { it.id == draft.id })
            assertEquals(draft, snapshot.editorDrafts()[draft.id])
            assertTrue(GroupEditorService.validate(draft).none { it.code == "missing_entity" || it.code == "cycle" })
            DataStore.serviceMode = Key.MODE_ROOT
            DataStore.globalCustomConfig = ""; DataStore.globalMode = false; DataStore.enableClashAPI = false
            val instance = io.nekohasekai.sagernet.bg.proto.ProxyInstance(balancer,
                rootRouteSnapshot = snapshot, persistBuildMetadata = false)
            try {
                instance.init()
                val baseline = io.nekohasekai.sagernet.bg.RootModuleSnapshot(instance, "fixture-revision").build()
                val baselineTags = com.google.gson.JsonParser.parseString(String(
                    java.util.Base64.getDecoder().decode(baseline["files"].asJsonObject["files/profile_tags.json"].asString),
                    Charsets.UTF_8)).asJsonObject
                val expectedMembers = listOf(baselineTags["99125"].asJsonObject["tag"].asString)
                val config = baseline["config"].asJsonObject
                val outbounds = config["outbounds"].asJsonArray.map { it.asJsonObject }
                assertTrue(outbounds.any { it["tag"]?.asString == "created" })
                val loadbalance = outbounds.first { it["type"]?.asString == "loadbalance" }
                assertEquals(expectedMembers, loadbalance["outbounds"].asJsonArray.map { it.asString })
            } finally { instance.close() }
        } finally { BackupRestore.apply(original, true, true, true) }
    }

    @Test fun ordinaryUrlResolutionMigratesOnlyExactDefault() {
        val settings = io.nekohasekai.sagernet.database.preference.PublicDatabase.kvPairDao.all()
        val id = 99122L
        val key = "group_${id}_urlTestUrl"
        val default = SagerNet.application.getString(R.string.default_group_test_url)
        try {
            DataStore.configurationStore.putString(key, default)
            assertEquals(DataStore.connectionTestURL, DataStore.resolvedGroupUrlTestUrl(id))
            assertEquals("", DataStore.configurationStore.getString(key))
            DataStore.configurationStore.putString(key, " $default ")
            assertEquals(default, DataStore.resolvedGroupUrlTestUrl(id))
            assertEquals(" $default ", DataStore.configurationStore.getString(key))
        } finally {
            io.nekohasekai.sagernet.database.preference.PublicDatabase.kvPairDao.reset()
            io.nekohasekai.sagernet.database.preference.PublicDatabase.kvPairDao.insert(settings)
        }
    }

    @Test fun localPreparationRestoresBothEntityAndEveryPreference() {
        val settings = io.nekohasekai.sagernet.database.preference.PublicDatabase.kvPairDao.all()
        val draft = GroupDraft(99122, "fixture", GroupMode.URL_TEST, listOf(7, 5),
            UrlTestParameters("https://example.test/probe", 31, 12, "2m", true))
        assertNull(SagerDatabase.groupDao.getById(draft.id))
        val preparation = GroupLocalPreparation(draft)
        try {
            preparation.prepare("""{"config":{"inbounds":[{"type":"tun"}]}}""")
            val undo = GroupLocalPreparation.undoRecord()
            assertEquals(draft.id, undo.id)
            assertFalse(undo.existed)
            assertEquals("tun", com.google.gson.JsonParser.parseString(undo.baseline).asJsonObject["config"].asJsonObject["inbounds"].asJsonArray[0].asJsonObject["type"].asString)
            assertNotNull(SagerDatabase.groupDao.getById(draft.id))
            assertEquals(draft, GroupEditorStore.read(draft.id))
            GroupLocalPreparation.restoreRecord(undo)
            assertNull(SagerDatabase.groupDao.getById(draft.id))
            val after = io.nekohasekai.sagernet.database.preference.PublicDatabase.kvPairDao.all().associateBy { it.key }
            assertEquals(settings.map { it.key }.toSet(), after.keys)
            settings.forEach { old ->
                assertEquals(old.valueType, after.getValue(old.key).valueType)
                assertArrayEquals(old.value, after.getValue(old.key).value)
            }
        } finally { preparation.restore(); preparation.finish() }
    }

    @Test fun preferenceWriteFailureRestoresCreatedEntityAndStoredValues() {
        val settings = io.nekohasekai.sagernet.database.preference.PublicDatabase.kvPairDao.all()
        val draft = GroupDraft(99122, "fixture", GroupMode.URL_TEST, listOf(7),
            UrlTestParameters("", 31, 12, "2m", true))
        assertNull(SagerDatabase.groupDao.getById(draft.id))
        val preparation = GroupLocalPreparation(draft)
        val listener = object : io.nekohasekai.sagernet.database.preference.OnPreferenceDataStoreChangeListener {
            override fun onPreferenceDataStoreChanged(store: androidx.preference.PreferenceDataStore, key: String) {
                if (key == "group_99122_editor") throw java.io.IOException("disk_full")
            }
        }
        DataStore.configurationStore.registerChangeListener(listener)
        try {
            try { preparation.prepare(); fail("write fault ignored") }
            catch (error: java.io.IOException) { assertEquals("disk_full", error.message) }
            assertTrue(GroupLocalPreparation.pending())
            preparation.restore()
            assertNull(SagerDatabase.groupDao.getById(draft.id))
            val after = io.nekohasekai.sagernet.database.preference.PublicDatabase.kvPairDao.all().associateBy { it.key }
            assertEquals(settings.map { it.key }.toSet(), after.keys)
            settings.forEach { old ->
                assertEquals(old.valueType, after.getValue(old.key).valueType)
                assertArrayEquals(old.value, after.getValue(old.key).value)
            }
        } finally {
            DataStore.configurationStore.unregisterChangeListener(listener)
            preparation.restore(); preparation.finish()
        }
        assertFalse(GroupLocalPreparation.pending())
    }

    @Test fun cancelEditedSessionKeepsEveryStoredValueAndEntity() {
        val previousSettings = io.nekohasekai.sagernet.database.preference.PublicDatabase.kvPairDao.all()
        val original = GroupDraft(99122, "fixture", GroupMode.URL_TEST, listOf(99128, 99127),
            UrlTestParameters("", 31, 12, "2m", true))
        val previousGroup = SagerDatabase.groupDao.getById(original.id)
        val owned = original.members.map { node -> ProxyEntity(id = node, groupId = original.id).putBean(
            io.nekohasekai.sagernet.fmt.socks.SOCKSBean().apply {
                initializeDefaultValues(); serverAddress = "192.0.2.1"; serverPort = 1080
            }) }
        try {
            if (previousGroup == null) SagerDatabase.groupDao.createGroup(ProxyGroup(id = original.id, name = original.name))
            SagerDatabase.proxyDao.insert(owned)
            GroupEditorStore.write(original)
            val settings = io.nekohasekai.sagernet.database.preference.PublicDatabase.kvPairDao.all()
            val entities = SagerDatabase.groupDao.allGroups()
            val session = GroupEditSession(GroupEditorService.load(original.id))
            session.draft = original.copy(members = listOf(99127, 99128), parameters = original.parameters.copy(intervalSeconds = 99))
            assertEquals(original, session.cancel())
            assertEquals(original, GroupEditorService.load(original.id))
            assertEquals(entities, SagerDatabase.groupDao.allGroups())
            val after = io.nekohasekai.sagernet.database.preference.PublicDatabase.kvPairDao.all().associateBy { it.key }
            assertEquals(settings.map { it.key }.toSet(), after.keys)
            settings.forEach { old ->
                assertEquals(old.valueType, after.getValue(old.key).valueType)
                assertArrayEquals(old.value, after.getValue(old.key).value)
            }
        } finally {
            SagerDatabase.proxyDao.deleteProxy(owned)
            if (previousGroup == null) SagerDatabase.groupDao.deleteById(original.id)
            io.nekohasekai.sagernet.database.preference.PublicDatabase.kvPairDao.reset()
            io.nekohasekai.sagernet.database.preference.PublicDatabase.kvPairDao.insert(previousSettings)
        }
    }

    @Test fun explicitSaveAndReopenKeepsFiveParametersAndOrder() {
        val key = "group_99122_editor"
        val settings = io.nekohasekai.sagernet.database.preference.PublicDatabase.kvPairDao.all()
        try {
            DataStore.configurationStore.putString(key, null)
            val draft = GroupDraft(99122, "fixture", GroupMode.URL_TEST, listOf(7, 5),
                UrlTestParameters("https://example.test/probe", 31, 12, "2m", true))
            assertNull(GroupEditorStore.read(99122))
            GroupEditorStore.write(draft)
            assertEquals(draft, GroupEditorStore.read(99122))
            assertEquals(draft, GroupEditorStore.read(99122))
        } finally {
            io.nekohasekai.sagernet.database.preference.PublicDatabase.kvPairDao.reset()
            io.nekohasekai.sagernet.database.preference.PublicDatabase.kvPairDao.insert(settings)
        }
    }
}

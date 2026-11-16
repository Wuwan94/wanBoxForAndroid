// @author 雾晚
package io.nekohasekai.sagernet

import io.nekohasekai.sagernet.database.*
import io.nekohasekai.sagernet.database.preference.KeyValuePair
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Batch 4c direction-matrix contracts with fictional data only. @author 雾晚 */
class BackupCompatibilityTest {
    private fun sample(): BackupRestore.Plan {
        val p = ProxyEntity(id = 92, groupId = 91, speedTestMode = "download").putBean(SOCKSBean().apply {
            initializeDefaultValues(); serverAddress = "192.0.2.1"; serverPort = 1080; name = "虚构节点"; password = "fictional"
        })
        return BackupRestore.Plan(
            listOf(p),
            listOf(ProxyGroup(id = 91, name = "虚构分组")),
            listOf(RuleEntity(id = 93, enabled = true, outbound = 92)),
            listOf(KeyValuePair(Key.MTU).put("1450")),
        )
    }

    @Test fun sourceFormatDetectsPortableV2() {
        val root = PortableBackup.document(PortableBackup.encode(sample()))
        assertEquals(BackupCompatibility.SourceFormat.PORTABLE_V2, BackupCompatibility.sourceFormat(root))
        assertFalse(BackupCompatibility.needsConversion(BackupCompatibility.sourceFormat(root)))
    }

    @Test fun sourceFormatDetectsLegacyManualAndAutomatic() {
        val manual = JSONObject().put("version", 1).put("profiles", JSONArray())
        val automatic = JSONObject().put("proxies", JSONArray())
        assertEquals(BackupCompatibility.SourceFormat.LEGACY_MANUAL_V1, BackupCompatibility.sourceFormat(manual))
        assertEquals(BackupCompatibility.SourceFormat.LEGACY_AUTO, BackupCompatibility.sourceFormat(automatic))
        assertTrue(BackupCompatibility.needsConversion(BackupCompatibility.SourceFormat.LEGACY_MANUAL_V1))
        assertTrue(BackupCompatibility.needsConversion(BackupCompatibility.SourceFormat.LEGACY_AUTO))
    }

    @Test fun sourceFormatIsUnknownForForeignDocuments() {
        assertEquals(BackupCompatibility.SourceFormat.UNKNOWN, BackupCompatibility.sourceFormat(JSONObject()))
        assertEquals(
            BackupCompatibility.SourceFormat.UNKNOWN,
            BackupCompatibility.sourceFormat(JSONObject().put("format", PortableBackup.FORMAT).put("schemaVersion", 999)),
        )
        assertFalse(BackupCompatibility.needsConversion(BackupCompatibility.SourceFormat.UNKNOWN))
    }

    @Test fun legacyImportReportsDefaultedGroupFieldsAndSettingsCategory() {
        val plan = sample()
        val root = JSONObject().put("version", 1).put("profiles", JSONArray())
        val fields = BackupCompatibility.defaultedFields(BackupCompatibility.SourceFormat.LEGACY_MANUAL_V1, plan, root)
        assertTrue(fields.contains("groups.isSelector"))
        assertTrue(fields.contains("groups.frontProxy"))
        assertTrue(fields.contains("groups.landingProxy"))
        // Legacy settings records are Parcel blobs: keys are not visible pre-decode,
        // so the defaults are reported as one explicit category, never as backup content.
        assertTrue(fields.contains("settings.coreDefaults"))
    }

    @Test fun portableImportReportsDefaultedSettingKeysByName() {
        val root = PortableBackup.document(PortableBackup.encode(
            BackupRestore.Plan(null, null, null, listOf(KeyValuePair(Key.MTU).put("1450")))
        ))
        // Simulate a pre-default v2 export, as PortableBackupTest does: only the
        // explicit MTU entry survives in the document. A current export cannot be
        // used here: the main spec requires encode to materialize core defaults,
        // so the round-trip document genuinely carries those keys and reports
        // none of them as defaulted (see the next test).
        val allSettings = root.getJSONArray("settings")
        val mtu = (0 until allSettings.length()).map { allSettings.getJSONObject(it) }
            .single { it.getString("key") == Key.MTU }
        root.put("settings", JSONArray().put(mtu))
        val plan = BackupRestore.parse(root)
        val fields = BackupCompatibility.defaultedFields(BackupCompatibility.SourceFormat.PORTABLE_V2, plan, root)
        assertTrue(fields.contains("settings." + Key.REMOTE_DNS))
        assertTrue(fields.contains("settings." + Key.DIRECT_DNS))
        assertFalse(fields.contains("settings." + Key.MTU))
        assertFalse(fields.any { it.startsWith("groups.") })
    }

    @Test fun portableImportFromCurrentExportReportsNoDefaultedSettings() {
        val root = PortableBackup.document(PortableBackup.encode(
            BackupRestore.Plan(null, null, null, listOf(KeyValuePair(Key.MTU).put("1450")))
        ))
        val plan = BackupRestore.parse(root)
        val fields = BackupCompatibility.defaultedFields(BackupCompatibility.SourceFormat.PORTABLE_V2, plan, root)
        // Every core default was materialized into the document by encode (main
        // spec), so none of them is "missing from the source" to report.
        assertFalse(fields.any { it.startsWith("settings.") })
    }

    @Test fun legacyExportRejectsUnrepresentableGroupFieldsBeforeWriting() {
        val plan = sample().copy(groups = listOf(ProxyGroup(id = 91, name = "虚构分组", isSelector = true, frontProxy = 92)))
        val verdict = BackupCompatibility.checkLegacyExport(plan)
        assertFalse(verdict.allowed)
        assertTrue(verdict.unrepresentableFields.contains("groups.isSelector"))
        assertTrue(verdict.unrepresentableFields.contains("groups.frontProxy"))
        assertFalse(verdict.unrepresentableFields.contains("groups.landingProxy"))
    }

    @Test fun legacyExportRejectsRepresentablePlanWhileReaderCapabilityUnverified() {
        val verdict = BackupCompatibility.checkLegacyExport(sample())
        assertFalse(verdict.allowed)
        assertTrue(verdict.unrepresentableFields.isEmpty())
        assertFalse(verdict.readerCapabilityVerified)
    }

    @Test fun requiredReadingMarksConversionNeedFromSourceFormat() {
        val plan = sample()
        val legacy = BackupMigrationNotes.requiredReading(JSONObject().put("version", 1), plan, "9.9.9")
        assertTrue(legacy.needsConversion)
        assertEquals("9.9.9", legacy.targetAppVersion)
        val portableRoot = PortableBackup.document(PortableBackup.encode(plan))
        val portable = BackupMigrationNotes.requiredReading(portableRoot, BackupRestore.parse(portableRoot), "9.9.9")
        assertFalse(portable.needsConversion)
        assertEquals(1, portable.profileCount)
        assertEquals(1, portable.groupCount)
        assertEquals(1, portable.ruleCount)
        assertTrue(portable.settingCount > 0)
    }

    @Test fun conversionGateRequiresConfirmationReadableBackupAndValidation() {
        assertTrue(BackupMigrationNotes.canStartConversion(confirmed = true, backupReadable = true, validationPassed = true))
        assertFalse(BackupMigrationNotes.canStartConversion(confirmed = false, backupReadable = true, validationPassed = true))
        assertFalse(BackupMigrationNotes.canStartConversion(confirmed = true, backupReadable = false, validationPassed = true))
        assertFalse(BackupMigrationNotes.canStartConversion(confirmed = true, backupReadable = true, validationPassed = false))
    }

    @Test fun restoreReportSeparatesDatabaseAndCoreResultsAndOmitsCredentials() {
        val root = PortableBackup.document(PortableBackup.encode(sample()))
        val plan = BackupRestore.parse(root)
        val report = BackupMigrationNotes.restoreReport(
            root, plan, BackupMigrationNotes.RestoreStatus.COMPLETED, null,
        )
        assertEquals(BackupMigrationNotes.RestoreStatus.COMPLETED, report.status)
        assertTrue(report.databaseRestored)
        assertFalse(report.coreApplyPerformed)
        assertTrue(report.referencesChecked)
        assertEquals(1, report.profileCount)
        assertEquals(1, report.groupCount)
        assertEquals(1, report.ruleCount)
        assertFalse(report.toString().contains("fictional"))
    }

    @Test fun restoreReportBlockedStatusMeansNothingWasWritten() {
        val report = BackupMigrationNotes.restoreReport(
            JSONObject().put("version", 1), sample(), BackupMigrationNotes.RestoreStatus.BLOCKED, "fixture block",
        )
        assertEquals(BackupMigrationNotes.RestoreStatus.BLOCKED, report.status)
        assertFalse(report.databaseRestored)
        assertFalse(report.coreApplyPerformed)
    }

    @Test fun restoreReportCarriesDefaultedFieldsAndFailureStatus() {
        val root = JSONObject().put("version", 1)
        val report = BackupMigrationNotes.restoreReport(
            root, sample(), BackupMigrationNotes.RestoreStatus.ROLLED_BACK, "fixture failure",
        )
        assertEquals(BackupMigrationNotes.RestoreStatus.ROLLED_BACK, report.status)
        assertFalse(report.databaseRestored)
        assertEquals("fixture failure", report.reason)
        assertTrue(report.defaultedFields.contains("groups.isSelector"))
    }
}

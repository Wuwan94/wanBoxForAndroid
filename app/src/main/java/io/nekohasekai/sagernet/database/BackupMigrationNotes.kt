// @author 雾晚
package io.nekohasekai.sagernet.database

import org.json.JSONObject

/**
 * Pure assembly of the pre-restore required reading and the post-restore
 * result report (batch 4c). The fragment only localizes labels and shows the
 * confirmation gate; every fact in both texts is computed here so JVM tests
 * cover the content. Reports never include credentials or payload values.
 * @author 雾晚
 */
object BackupMigrationNotes {
    fun sourceSchemaLabel(format: BackupCompatibility.SourceFormat): String = when (format) {
        BackupCompatibility.SourceFormat.PORTABLE_V2 -> "${PortableBackup.FORMAT}/${PortableBackup.VERSION}"
        BackupCompatibility.SourceFormat.LEGACY_MANUAL_V1 -> "legacy manual v1 (profiles)"
        BackupCompatibility.SourceFormat.LEGACY_AUTO -> "legacy automatic (proxies, no version)"
        BackupCompatibility.SourceFormat.UNKNOWN -> "unknown"
    }

    const val TARGET_SCHEMA = "${PortableBackup.FORMAT}/${PortableBackup.VERSION}"

    data class RequiredReading(
        val sourceFormat: BackupCompatibility.SourceFormat,
        val sourceSchema: String,
        val targetSchema: String,
        val targetAppVersion: String,
        val needsConversion: Boolean,
        val backupVerified: Boolean,
        val profileCount: Int,
        val groupCount: Int,
        val ruleCount: Int,
        val settingCount: Int,
        val defaultedFields: List<String>,
    )

    /**
     * Call only with a [plan] that already passed [BackupRestore.parse]
     * (full-section + reference validation before any write); that is what
     * [RequiredReading.backupVerified] attests.
     */
    fun requiredReading(root: JSONObject, plan: BackupRestore.Plan, targetAppVersion: String): RequiredReading {
        val format = BackupCompatibility.sourceFormat(root)
        return RequiredReading(
            sourceFormat = format,
            sourceSchema = sourceSchemaLabel(format),
            targetSchema = TARGET_SCHEMA,
            targetAppVersion = targetAppVersion,
            needsConversion = BackupCompatibility.needsConversion(format),
            backupVerified = true,
            profileCount = plan.profiles?.size ?: 0,
            groupCount = plan.groups?.size ?: 0,
            ruleCount = plan.rules?.size ?: 0,
            settingCount = plan.settings?.size ?: 0,
            defaultedFields = BackupCompatibility.defaultedFields(format, plan, root),
        )
    }

    /** The conversion gate: unconfirmed, unreadable or unvalidated means no conversion. */
    fun canStartConversion(confirmed: Boolean, backupReadable: Boolean, validationPassed: Boolean): Boolean =
        confirmed && backupReadable && validationPassed

    enum class RestoreStatus { COMPLETED, ROLLED_BACK, BLOCKED }

    data class RestoreReport(
        val sourceSchema: String,
        val targetSchema: String,
        val profileCount: Int,
        val groupCount: Int,
        val ruleCount: Int,
        val settingCount: Int,
        val referencesChecked: Boolean,
        val defaultedFields: List<String>,
        val status: RestoreStatus,
        val reason: String?,
        val databaseRestored: Boolean,
        // Restore writes databases only; applying the configuration to the
        // core is a separate step (service start after restart) and is always
        // reported separately, never merged into the database result.
        val coreApplyPerformed: Boolean = false,
    )

    fun restoreReport(
        root: JSONObject,
        plan: BackupRestore.Plan,
        status: RestoreStatus,
        reason: String?,
    ): RestoreReport {
        val format = BackupCompatibility.sourceFormat(root)
        return RestoreReport(
            sourceSchema = sourceSchemaLabel(format),
            targetSchema = TARGET_SCHEMA,
            profileCount = plan.profiles?.size ?: 0,
            groupCount = plan.groups?.size ?: 0,
            ruleCount = plan.rules?.size ?: 0,
            settingCount = plan.settings?.size ?: 0,
            referencesChecked = true,
            defaultedFields = BackupCompatibility.defaultedFields(format, plan, root),
            status = status,
            reason = reason,
            databaseRestored = status == RestoreStatus.COMPLETED,
        )
    }
}

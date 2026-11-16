// @author 雾晚
package io.nekohasekai.sagernet.database

import org.json.JSONObject

/**
 * Pure direction-matrix logic for versioned backups (batch 4c). No Android
 * runtime dependency: source-format detection, defaulted-field reporting and
 * the pre-write legacy-export guard are all decided here, before any write.
 * @author 雾晚
 */
object BackupCompatibility {
    enum class SourceFormat { PORTABLE_V2, LEGACY_MANUAL_V1, LEGACY_AUTO, UNKNOWN }

    /** Mirrors the format branching of [BackupRestore.parse]; never throws. */
    fun sourceFormat(root: JSONObject): SourceFormat = when {
        root.has("format") || root.has("schemaVersion") ->
            if (root.optString("format") == PortableBackup.FORMAT &&
                root.optInt("schemaVersion", -1) == PortableBackup.VERSION
            ) SourceFormat.PORTABLE_V2 else SourceFormat.UNKNOWN
        root.optInt("version", -1) == 1 -> SourceFormat.LEGACY_MANUAL_V1
        !root.has("version") && root.has("proxies") -> SourceFormat.LEGACY_AUTO
        else -> SourceFormat.UNKNOWN
    }

    fun needsConversion(format: SourceFormat): Boolean =
        format == SourceFormat.LEGACY_MANUAL_V1 || format == SourceFormat.LEGACY_AUTO

    // The legacy (Parcel/Kryo) group record serializes only id, userOrder,
    // ungrouped, name, type, subscription and order; these three fields were
    // never carried and always import with the explicit defaults false/-1/-1.
    private val legacyGroupDefaultedFields = listOf("groups.isSelector", "groups.frontProxy", "groups.landingProxy")

    /**
     * Fields that did NOT come from the source backup but were filled with
     * explicit default semantics, so reports never claim they were backed up.
     * Portable v2 settings keys are readable from the document and are listed
     * per key; legacy settings records are opaque Parcel blobs, so their core
     * defaults are reported as the single category `settings.coreDefaults`.
     */
    fun defaultedFields(format: SourceFormat, plan: BackupRestore.Plan, root: JSONObject): List<String> = buildList {
        if (needsConversion(format)) {
            if (plan.groups != null) addAll(legacyGroupDefaultedFields)
            if (plan.settings != null) add("settings.coreDefaults")
        } else if (format == SourceFormat.PORTABLE_V2 && plan.settings != null && root.has("settings")) {
            val explicitKeys = hashSetOf<String>()
            val array = root.getJSONArray("settings")
            for (index in 0 until array.length()) {
                array.optJSONObject(index)?.optString("key")?.takeIf { it.isNotEmpty() }?.let(explicitKeys::add)
            }
            PortableBackup.defaultedCoreSettingKeys(explicitKeys).forEach { add("settings.$it") }
        }
    }

    data class LegacyExportVerdict(
        val allowed: Boolean,
        val unrepresentableFields: List<String>,
        val readerCapabilityVerified: Boolean,
    )

    /**
     * Pre-write guard for a legacy-targeted ("compatible") export. Legacy group
     * records cannot represent isSelector/frontProxy/landingProxy when set.
     * Historical clients' ability to read newly written legacy files has not
     * been verified on a real old client, so the export is refused Fail-Closed
     * even when every field is representable; nothing is silently degraded.
     */
    fun checkLegacyExport(plan: BackupRestore.Plan): LegacyExportVerdict {
        val unrepresentable = buildList {
            plan.groups?.forEach { group ->
                if (group.isSelector) add("groups.isSelector")
                if (group.frontProxy != -1L) add("groups.frontProxy")
                if (group.landingProxy != -1L) add("groups.landingProxy")
            }
        }.distinct()
        val readerCapabilityVerified = false
        return LegacyExportVerdict(
            allowed = unrepresentable.isEmpty() && readerCapabilityVerified,
            unrepresentableFields = unrepresentable,
            readerCapabilityVerified = readerCapabilityVerified,
        )
    }
}

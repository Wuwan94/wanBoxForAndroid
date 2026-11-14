package io.nekohasekai.sagernet.bg

import android.content.Context

/** Error text policy for Smart Auto-stop Apply; global module errors remain redacted. */
object SmartAutoStopApplyErrors {
    fun message(context: Context, error: Exception): String =
        message(error) { RootModuleErrors.message(context, error) }

    /** The fallback supplies localized module errors without requiring Android in policy tests. */
    internal fun message(error: Exception, fallback: () -> String): String {
        val code = RootModuleClient.safeError(error)
        if (code != "module_operation_failed" || error.message.isNullOrBlank()) return fallback()
        val raw = error.message!!
        if (raw.matches(Regex("[a-z_]{1,80}")) || raw.length > 240 ||
            raw.any { it.isISOControl() && it != '\n' && it != '\r' } ||
            Regex("(?i)(password|passwd|token|secret|authorization|bearer|api[_ -]?key)").containsMatchIn(raw)) return fallback()
        return raw
    }
}

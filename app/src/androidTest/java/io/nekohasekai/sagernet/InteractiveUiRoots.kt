package io.nekohasekai.sagernet

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Instrumentation
import android.view.accessibility.AccessibilityNodeInfo

/** Read visible test UI roots, including transient dialogs and overflow popups. */
internal fun Instrumentation.interactiveUiRoots(): List<AccessibilityNodeInfo> {
    val automation = uiAutomation
    val info = checkNotNull(automation.serviceInfo)
    val flag = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
    // Android returns no interactive windows unless the automation client opts in.
    if ((info.flags and flag) == 0) {
        info.flags = info.flags or flag
        automation.serviceInfo = info
    }
    return automation.windows.mapNotNull { it.root }
        .ifEmpty { listOfNotNull(automation.rootInActiveWindow) }
}

/** Failure-only window metadata; never dump node text or other app content. */
internal fun Instrumentation.interactiveUiDiagnostics(): String = runCatching {
    val automation = uiAutomation
    val keyguard = targetContext.getSystemService(android.app.KeyguardManager::class.java)
    val power = targetContext.getSystemService(android.os.PowerManager::class.java)
    val windows = automation.windows.joinToString { window ->
        val root = window.root
        try {
            "type=${window.type}/active=${window.isActive}/focused=${window.isFocused}" +
                "/target=${root?.packageName?.toString() == targetContext.packageName}" +
                "/hasRoot=${root != null}/children=${root?.childCount ?: 0}"
        } finally { root?.recycle(); window.recycle() }
    }
    val active = automation.rootInActiveWindow
    val activeIsTarget = try {
        active?.packageName?.toString() == targetContext.packageName
    } finally { active?.recycle() }
    "interactive=${power?.isInteractive}, keyguard=${keyguard?.isKeyguardLocked}, " +
        "flags=${automation.serviceInfo?.flags}, activeRootIsTarget=$activeIsTarget, windows=[$windows]"
}.getOrElse { "window metadata unavailable (${it.javaClass.simpleName})" }

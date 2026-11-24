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

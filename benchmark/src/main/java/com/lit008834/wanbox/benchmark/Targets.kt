package com.lit008834.wanbox.benchmark

import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until

/** The stable release applicationId (the benchmark build type has no suffix). */
const val TARGET_PACKAGE = "com.lit008834.pixel.wanboxforandroid"

/** Open the node list from the bottom navigation and fling it; no-op if the view is missing. */
fun UiDevice.openNodesAndScroll() {
    val nav = findObject(By.text("节点")) ?: findObject(By.text("Nodes"))
    nav?.click()
    wait(Until.hasObject(By.scrollable(true)), 3_000)
    val list = findObject(By.scrollable(true)) ?: return
    list.setGestureMargin(displayWidth / 5)
    repeat(3) { list.fling(Direction.DOWN) }
    list.fling(Direction.UP)
    waitForIdle()
}

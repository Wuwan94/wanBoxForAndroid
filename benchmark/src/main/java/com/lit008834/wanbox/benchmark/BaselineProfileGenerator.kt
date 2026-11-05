package com.lit008834.wanbox.benchmark

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Generates a baseline profile covering cold start → home → node list scroll.
 * Needs a rooted device or an API 33+ emulator/userdebug image; output is written to the
 * device's additional test output dir and pulled into benchmark/build/outputs/.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule val rule = BaselineProfileRule()

    @Test fun generate() = rule.collect(packageName = TARGET_PACKAGE) {
        pressHome()
        startActivityAndWait()
        device.openNodesAndScroll()
    }
}

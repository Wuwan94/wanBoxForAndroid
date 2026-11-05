package com.lit008834.wanbox.benchmark

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Cold-start and node-list jank numbers for the phase-2 before/after report.
 * Compare CompilationMode.None (no profile) with Partial(BaselineProfileMode.Require).
 */
@RunWith(AndroidJUnit4::class)
class StartupBenchmark {
    @get:Rule val rule = MacrobenchmarkRule()

    @Test fun coldStartNoProfile() = startup(CompilationMode.None())
    @Test fun coldStartBaselineProfile() = startup(CompilationMode.Partial(BaselineProfileMode.Require))

    private fun startup(mode: CompilationMode) = rule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(StartupTimingMetric()),
        compilationMode = mode,
        startupMode = StartupMode.COLD,
        iterations = 10,
    ) {
        pressHome()
        startActivityAndWait()
    }

    @Test fun nodeListScroll() = rule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = CompilationMode.Partial(BaselineProfileMode.Require),
        startupMode = StartupMode.WARM,
        iterations = 5,
        setupBlock = { pressHome(); startActivityAndWait() },
    ) {
        device.openNodesAndScroll()
    }
}

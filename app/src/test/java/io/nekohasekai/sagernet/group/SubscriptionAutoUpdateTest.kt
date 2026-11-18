// @author 雾晚
package io.nekohasekai.sagernet.group

import io.nekohasekai.sagernet.bg.SubscriptionSchedule
import io.nekohasekai.sagernet.ui.SubscriptionAutoUpdateDialog
import io.nekohasekai.sagernet.ui.SubscriptionAutoUpdateDialog.IntervalDescription
import io.nekohasekai.sagernet.ui.SubscriptionAutoUpdateDialog.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** B2/O5: subscription auto-update presets, custom conversion, due math. @author 雾晚 */
class SubscriptionAutoUpdateTest {

    @Test
    fun presetMinutesAreTheSevenUpstreamPresets() {
        val presets = SubscriptionAutoUpdateDialog.PRESET_MINUTES
        assertEquals(listOf(15, 30, 60, 180, 360, 720, 1440), presets)
    }

    @Test
    fun customConversionAcrossUnits() {
        assertEquals(45, SubscriptionAutoUpdateDialog.toMinutes(45, TimeUnit.MINUTES))
        assertEquals(120, SubscriptionAutoUpdateDialog.toMinutes(2, TimeUnit.HOURS))
        assertEquals(1440, SubscriptionAutoUpdateDialog.toMinutes(1, TimeUnit.DAYS))
        assertEquals(2880, SubscriptionAutoUpdateDialog.toMinutes(2, TimeUnit.DAYS))
    }

    @Test
    fun customConversionRejectsNonPositiveNumbers() {
        assertNull(SubscriptionAutoUpdateDialog.toMinutes(0, TimeUnit.MINUTES))
        assertNull(SubscriptionAutoUpdateDialog.toMinutes(-3, TimeUnit.HOURS))
    }

    @Test
    fun customConversionBelowMinimumIsFlaggedSeparately() {
        // 10 minutes converts fine; the minimum is enforced by the caller so the
        // dialog can show a distinct "minimum is 15 minutes" error.
        assertEquals(10, SubscriptionAutoUpdateDialog.toMinutes(10, TimeUnit.MINUTES))
        assertTrue(SubscriptionAutoUpdateDialog.isBelowMinimum(10))
        assertFalse(SubscriptionAutoUpdateDialog.isBelowMinimum(15))
        assertFalse(SubscriptionAutoUpdateDialog.isBelowMinimum(1440))
    }

    @Test
    fun customDialogInitialValueDecomposesStoredMinutes() {
        assertEquals("1" to TimeUnit.DAYS, SubscriptionAutoUpdateDialog.initialValueAndUnit(1440))
        assertEquals("3" to TimeUnit.HOURS, SubscriptionAutoUpdateDialog.initialValueAndUnit(180))
        assertEquals("6" to TimeUnit.HOURS, SubscriptionAutoUpdateDialog.initialValueAndUnit(360))
        assertEquals("45" to TimeUnit.MINUTES, SubscriptionAutoUpdateDialog.initialValueAndUnit(45))
        assertEquals("15" to TimeUnit.MINUTES, SubscriptionAutoUpdateDialog.initialValueAndUnit(0))
    }

    @Test
    fun intervalDescriptionMapsToHumanizedUnits() {
        assertEquals(IntervalDescription.Disabled, SubscriptionAutoUpdateDialog.describeInterval(0))
        assertEquals(IntervalDescription.Disabled, SubscriptionAutoUpdateDialog.describeInterval(-5))
        assertEquals(IntervalDescription.Minutes(15), SubscriptionAutoUpdateDialog.describeInterval(15))
        assertEquals(IntervalDescription.Minutes(30), SubscriptionAutoUpdateDialog.describeInterval(30))
        assertEquals(IntervalDescription.Hours(1), SubscriptionAutoUpdateDialog.describeInterval(60))
        assertEquals(IntervalDescription.Hours(3), SubscriptionAutoUpdateDialog.describeInterval(180))
        assertEquals(IntervalDescription.Hours(12), SubscriptionAutoUpdateDialog.describeInterval(720))
        assertEquals(IntervalDescription.Days(1), SubscriptionAutoUpdateDialog.describeInterval(1440))
        assertEquals(IntervalDescription.Days(2), SubscriptionAutoUpdateDialog.describeInterval(2880))
    }

    @Test
    fun initialDelayIsExactDueTimeWithNoSixtySecondCap() {
        // Subscription updated 1 hour ago, 24-hour interval: due in 82800s.
        // Upstream's old bug truncated this to 60s; wanBox must keep the exact value.
        val now = 1_000_000L
        val plan = SubscriptionSchedule.plan(
            listOf(SubscriptionSchedule.Entry(1440, (now - 3600L).toInt())), now
        )!!
        assertEquals(1440L, plan.intervalMinutes)
        assertEquals(82800L, plan.initialDelaySeconds)
        assertTrue(plan.initialDelaySeconds > 60L)
    }

    @Test
    fun overdueOrNeverUpdatedSubscriptionsAreDueImmediately() {
        val now = 1_000_000L
        val overdue = SubscriptionSchedule.plan(
            listOf(SubscriptionSchedule.Entry(1440, (now - 87_000L).toInt())), now
        )!!
        assertEquals(0L, overdue.initialDelaySeconds)
        val never = SubscriptionSchedule.plan(
            listOf(SubscriptionSchedule.Entry(1440, 0)), now
        )!!
        assertEquals(0L, never.initialDelaySeconds)
    }

    @Test
    fun isDueRespectsIntervalAndMinimumClamp() {
        val now = 1_000_000L
        // Due: 25 hours elapsed on a 24-hour interval.
        assertTrue(
            SubscriptionSchedule.isDue(SubscriptionSchedule.Entry(1440, (now - 90_000L).toInt()), now)
        )
        // Not due: 1 hour elapsed on a 24-hour interval.
        assertFalse(
            SubscriptionSchedule.isDue(SubscriptionSchedule.Entry(1440, (now - 3600L).toInt()), now)
        )
        // Never updated counts as due.
        assertTrue(SubscriptionSchedule.isDue(SubscriptionSchedule.Entry(1440, 0), now))
        // Interval below the 15-minute WorkManager minimum is clamped to 15:
        // 10 minutes elapsed on a 5-minute setting is still not due.
        assertFalse(
            SubscriptionSchedule.isDue(SubscriptionSchedule.Entry(5, (now - 600L).toInt()), now)
        )
        // ...but 16 minutes elapsed is due under the same clamped 15-minute interval.
        assertTrue(
            SubscriptionSchedule.isDue(SubscriptionSchedule.Entry(5, (now - 960L).toInt()), now)
        )
    }
}

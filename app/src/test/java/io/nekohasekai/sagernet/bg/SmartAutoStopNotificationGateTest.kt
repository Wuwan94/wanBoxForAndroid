package io.nekohasekai.sagernet.bg

import org.junit.Assert.assertEquals
import org.junit.Test

class SmartAutoStopNotificationGateTest {
    @Test fun android12RequiresBothAppAndChannelNotifications() {
        for (sdk in 31..32) {
            assertEquals(NotificationGate.OPEN_SETTINGS, SmartAutoStopNotificationGate.evaluate(sdk, true, false, true))
            assertEquals(NotificationGate.OPEN_SETTINGS, SmartAutoStopNotificationGate.evaluate(sdk, true, true, false))
            assertEquals(NotificationGate.AVAILABLE, SmartAutoStopNotificationGate.evaluate(sdk, false, true, true))
        }
    }

    @Test fun android13RequestsPermissionBeforeSettingsAndRechecksChannelAfterGrant() {
        assertEquals(NotificationGate.REQUEST_PERMISSION, SmartAutoStopNotificationGate.evaluate(33, false, false, false))
        assertEquals(NotificationGate.OPEN_SETTINGS, SmartAutoStopNotificationGate.evaluate(33, true, false, true))
        assertEquals(NotificationGate.OPEN_SETTINGS, SmartAutoStopNotificationGate.evaluate(33, true, true, false))
        assertEquals(NotificationGate.AVAILABLE, SmartAutoStopNotificationGate.evaluate(33, true, true, true))
    }
}

// @author 雾晚
package io.nekohasekai.sagernet.ui

import io.nekohasekai.sagernet.ui.ProfileListPrefsChange.Companion.between
import org.junit.Assert.assertEquals
import org.junit.Test

class ProfileListPrefsChangeTest {
    @Test fun togglingAlwaysShowAddressRebindsExistingCards() {
        assertEquals(ProfileListPrefsChange.REBIND, between(false, 2, false, false, 2, true))
        assertEquals(ProfileListPrefsChange.REBIND, between(true, 3, true, true, 3, false))
    }

    @Test fun layoutChangesRelayoutEvenWhenAddressAlsoChanged() {
        assertEquals(ProfileListPrefsChange.RELAYOUT, between(false, 2, false, true, 2, false))
        assertEquals(ProfileListPrefsChange.RELAYOUT, between(true, 2, false, true, 3, false))
        assertEquals(ProfileListPrefsChange.RELAYOUT, between(false, 2, false, true, 3, true))
    }

    @Test fun unchangedDisplaySettingsDoNothing() {
        assertEquals(ProfileListPrefsChange.NONE, between(false, 2, false, false, 2, false))
        assertEquals(ProfileListPrefsChange.NONE, between(true, 3, true, true, 3, true))
    }
}

package com.astra.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProfileSelectionRefreshPolicyTest {
    @Test fun keepsExactOrUniquelyRemappedSelection() {
        val fresh = "vless://rotated@node.example:443#A"
        assertEquals(fresh, ProfileSelectionRefreshPolicy.resolve("old", fresh, false))
    }

    @Test fun doesNotChooseAnUnrelatedFirstServerWhenSelectionDisappears() {
        assertNull(ProfileSelectionRefreshPolicy.resolve("removed", null, false))
    }

    @Test fun keepsCurrentlyActiveRemovedProfileUntilTunnelStops() {
        val active = "vless://old@node.example:443#A"
        assertEquals(active, ProfileSelectionRefreshPolicy.resolve(active, null, true))
    }

    @Test fun leavesNoSelectionWhenThereWasNoPreviousSelection() {
        assertNull(ProfileSelectionRefreshPolicy.resolve(null, "new", false))
    }
}

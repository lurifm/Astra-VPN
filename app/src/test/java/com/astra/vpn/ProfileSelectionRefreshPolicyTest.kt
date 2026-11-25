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
    @Test fun recognizesActiveProfileAfterSubscriptionCredentialRotation() {
        val active = "vless://old-token@node.example:443?security=tls#Germany"
        val removed = listOf("vless://new-token@node.example:443?security=reality#Germany")
        org.junit.Assert.assertTrue(ProfileSelectionRefreshPolicy.matchesRemovedProfile(active, removed))
    }

    @Test fun doesNotGuessWhenRemovedEndpointsAreAmbiguous() {
        val active = "vless://old@node.example:443#Germany"
        val removed = listOf("vless://one@node.example:443#Germany", "vless://two@node.example:443#Germany")
        org.junit.Assert.assertFalse(ProfileSelectionRefreshPolicy.matchesRemovedProfile(active, removed))
    }

    @Test fun doesNotTreatSameDisplayNameOnDifferentEndpointAsRemoved() {
        val active = "vless://token@active.example:443#Germany"
        val removed = listOf("vless://token@other.example:443#Germany")
        org.junit.Assert.assertFalse(ProfileSelectionRefreshPolicy.matchesRemovedProfile(active, removed))
    }

    @Test fun ignoresUnrelatedProfilesWhenDeletingSubscription() {
        val active = "vless://token@other.example:443#Sweden"
        val removed = listOf("vless://new@node.example:443#Germany")
        org.junit.Assert.assertFalse(ProfileSelectionRefreshPolicy.matchesRemovedProfile(active, removed))
    }
}

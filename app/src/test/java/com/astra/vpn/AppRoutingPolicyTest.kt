package com.astra.vpn

import org.junit.Assert.assertThrows
import org.junit.Test

class AppRoutingPolicyTest {
    @Test fun allowsIncludeModeWhenEveryPackageWasApplied() {
        AppRoutingPolicy.validate("include", selectedCount = 2, appliedCount = 2)
    }

    @Test fun allowsExcludeModeWithEmptySelection() {
        AppRoutingPolicy.validate("exclude", selectedCount = 0, appliedCount = 0)
    }

    @Test fun rejectsEmptyIncludeList() {
        assertThrows(IllegalArgumentException::class.java) {
            AppRoutingPolicy.validate("include", selectedCount = 0, appliedCount = 0)
        }
    }

    @Test fun rejectsIncludeListWhenNoPackageCouldBeApplied() {
        assertThrows(IllegalArgumentException::class.java) {
            AppRoutingPolicy.validate("include", selectedCount = 3, appliedCount = 0)
        }
    }

    @Test fun rejectsPartiallyAppliedIncludeListToPreventUnexpectedBypass() {
        assertThrows(IllegalArgumentException::class.java) {
            AppRoutingPolicy.validate("include", selectedCount = 3, appliedCount = 2)
        }
    }

    @Test fun rejectsUnknownRoutingMode() {
        assertThrows(IllegalArgumentException::class.java) {
            AppRoutingPolicy.validate("unexpected", selectedCount = 1, appliedCount = 1)
        }
    }

    @Test fun rejectsNegativeRuleCounts() {
        assertThrows(IllegalArgumentException::class.java) {
            AppRoutingPolicy.validate("exclude", selectedCount = -1, appliedCount = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AppRoutingPolicy.validate("exclude", selectedCount = 1, appliedCount = -1)
        }
    }

    @Test fun rejectsAppliedCountGreaterThanSelection() {
        assertThrows(IllegalArgumentException::class.java) {
            AppRoutingPolicy.validate("exclude", selectedCount = 1, appliedCount = 2)
        }
    }
}

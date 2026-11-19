package com.astra.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OnboardingSlidesTest {
    @Test fun onboardingHasThreeOrderedSlides() {
        assertEquals(3, OnboardingSlides.all.size)
        assertEquals("Своя орбита. Своя скорость. Своя защита", OnboardingSlides.all.first().title)
    }

    @Test fun nextStopsAfterLastSlide() {
        assertEquals(1, OnboardingSlides.next(0))
        assertEquals(2, OnboardingSlides.next(1))
        assertNull(OnboardingSlides.next(2))
        assertNull(OnboardingSlides.next(-1))
    }
}

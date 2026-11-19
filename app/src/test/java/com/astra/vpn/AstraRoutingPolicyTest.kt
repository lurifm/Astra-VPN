package com.astra.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AstraRoutingPolicyTest {
    @Test fun localDirectListContainsExpectedLocalSuffixes() {
        val domains = AstraRoutingPolicy.localDirectSuffixes()
        assertTrue("ru" in domains)
        assertTrue("рф" in domains)
        assertTrue("gosuslugi.ru" in domains)
        assertTrue("sberbank.ru" in domains)
    }

    @Test fun jsonListMatchesPolicyAndIsFresh() {
        val first = AstraRoutingPolicy.localDirectSuffixesJson()
        val second = AstraRoutingPolicy.localDirectSuffixesJson()
        assertEquals(AstraRoutingPolicy.localDirectSuffixes().size, first.length())
        first.put("unexpected.example")
        assertEquals(AstraRoutingPolicy.localDirectSuffixes().size, second.length())
    }
}

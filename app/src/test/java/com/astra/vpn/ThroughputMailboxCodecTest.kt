package com.astra.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ThroughputMailboxCodecTest {
    @Test
    fun resultEventRoundTripsWithoutProfileIdentity() {
        val event = ThroughputMailboxEvent(
            sessionId = "session-123",
            action = AstraThroughputProbeService.ACTION_RESULT,
            resultJson = """{"downloadMbps":42.5,"uploadMbps":12.0,"networkHandle":7}"""
        )
        assertEquals(event, ThroughputMailboxCodec.decode(ThroughputMailboxCodec.encode(event)))
    }

    @Test
    fun errorEventRoundTrips() {
        val event = ThroughputMailboxEvent(
            sessionId = "session-456",
            action = AstraThroughputProbeService.ACTION_ERROR,
            errorMessage = "measurement failed"
        )
        assertEquals(event, ThroughputMailboxCodec.decode(ThroughputMailboxCodec.encode(event)))
    }

    @Test
    fun malformedAndUnscopedEventsAreRejected() {
        assertNull(ThroughputMailboxCodec.decode("not-json"))
        assertNull(ThroughputMailboxCodec.decode("""{"session":"","action":"bad","error":"x"}"""))
        assertNull(ThroughputMailboxCodec.decode("""{"session":"s","action":"unknown","result":"{}"}"""))
    }

    @Test
    fun terminalPayloadMustMatchItsAction() {
        assertNull(ThroughputMailboxCodec.decode(
            """{"session":"s","action":"${AstraThroughputProbeService.ACTION_RESULT}","error":"wrong field"}"""
        ))
        assertNull(ThroughputMailboxCodec.decode(
            """{"session":"s","action":"${AstraThroughputProbeService.ACTION_ERROR}","result":"{}"}"""
        ))
    }

    @Test
    fun oversizedMailboxPayloadIsRejected() {
        assertNull(ThroughputMailboxCodec.decode(" ".repeat(65 * 1024)))
    }
}

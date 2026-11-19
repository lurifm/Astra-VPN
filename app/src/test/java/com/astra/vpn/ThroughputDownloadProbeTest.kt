package com.astra.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.net.Proxy
import java.net.URL
import javax.net.ssl.HttpsURLConnection

class ThroughputDownloadProbeTest {
    @Test
    fun requestsOnlyFixedCloudflareEndpointThroughLoopbackProxy() {
        var seenUrl: URL? = null
        var seenProxy: Proxy? = null
        val sample = ThroughputDownloadProbe.measure(
            proxyPort = 19123,
            expectedBytes = 512L * 1024L,
            connectionFactory = { url, proxy ->
                seenUrl = url
                seenProxy = proxy
                FakeHttpsConnection(url, ByteArray(512 * 1024), 512L * 1024L)
            },
            clockNanos = tickingClock()
        )
        assertTrue(sample != null)
        assertEquals("speed.cloudflare.com", seenUrl!!.host)
        assertEquals("/__down", seenUrl!!.path)
        assertEquals("bytes=524288", seenUrl!!.query)
        assertEquals(Proxy.Type.HTTP, seenProxy!!.type())
        assertEquals("127.0.0.1", (seenProxy!!.address() as java.net.InetSocketAddress).hostString)
        assertEquals(19123, (seenProxy!!.address() as java.net.InetSocketAddress).port)
    }

    @Test
    fun rejectsNonSuccessHttpResponse() {
        val sample = ThroughputDownloadProbe.measure(
            19123,
            512L * 1024L,
            connectionFactory = { url, _ -> FakeHttpsConnection(url, ByteArray(0), 0L, 302) },
            clockNanos = tickingClock()
        )
        assertNull(sample)
    }

    @Test
    fun rejectsWrongContentLengthAndOversizedBody() {
        val wrongLength = ThroughputDownloadProbe.measure(
            19123, 512L * 1024L,
            connectionFactory = { url, _ -> FakeHttpsConnection(url, ByteArray(100), 100L) },
            clockNanos = tickingClock()
        )
        assertNull(wrongLength)

        val oversized = ThroughputDownloadProbe.measure(
            19123, 512L * 1024L,
            connectionFactory = { url, _ -> FakeHttpsConnection(url, ByteArray(512 * 1024 + 1), -1L) },
            clockNanos = tickingClock()
        )
        assertNull(oversized)
    }

    @Test
    fun rejectsInvalidProxyPortAndPayloadSizeBeforeConnecting() {
        assertNull(ThroughputDownloadProbe.measure(0))
        assertNull(ThroughputDownloadProbe.measure(19123, 100L))
    }

    private fun tickingClock(): () -> Long {
        var now = 0L
        return { now += 100_000_000L; now }
    }

    private class FakeHttpsConnection(
        url: URL,
        private val body: ByteArray,
        private val length: Long,
        private val status: Int = HTTP_OK
    ) : HttpsURLConnection(url) {
        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy(): Boolean = true
        override fun getResponseCode(): Int = status
        override fun getContentLengthLong(): Long = length
        override fun getInputStream() = ByteArrayInputStream(body)
        override fun getCipherSuite(): String = "TLS_FAKE"
        override fun getLocalCertificates(): Array<java.security.cert.Certificate>? = null
        override fun getServerCertificates(): Array<java.security.cert.Certificate> = emptyArray()
        override fun getPeerPrincipal(): java.security.Principal =
            javax.security.auth.x500.X500Principal("CN=speed.cloudflare.com")
        override fun getLocalPrincipal(): java.security.Principal? = null
    }
}

package com.astra.vpn

import org.junit.Test

class ProfileShareQrTest {
    @Test fun rejectsEmptyAndOversizedLinks() {
        runCatching { ProfileShareQr.create("") }.onSuccess { error("Empty profile accepted") }
        runCatching { ProfileShareQr.create("x".repeat(4097)) }.onSuccess { error("Oversized profile accepted") }
    }

    @Test fun rejectsInvalidBitmapSize() {
        runCatching { ProfileShareQr.create("vless://example", 128) }
            .onSuccess { error("Invalid QR size accepted") }
    }
}

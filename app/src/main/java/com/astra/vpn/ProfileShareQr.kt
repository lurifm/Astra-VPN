package com.astra.vpn

import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** QR helper for a single user-selected profile URI. */
internal object ProfileShareQr {
    fun create(link: String, size: Int = 720): Bitmap {
        require(link.isNotBlank() && link.length <= MAX_LINK_LENGTH) { "Ссылка профиля пуста или слишком длинная" }
        require(size in 256..1536) { "Недопустимый размер QR-кода" }
        val matrix = MultiFormatWriter().encode(
            link,
            BarcodeFormat.QR_CODE,
            size,
            size,
            mapOf(
                EncodeHintType.MARGIN to 2,
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M
            )
        )
        val pixels = IntArray(size * size)
        for (y in 0 until size) for (x in 0 until size) {
            pixels[y * size + x] = if (matrix[x, y]) 0xFF08111F.toInt() else 0xFFFFFFFF.toInt()
        }
        return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
    }

    private const val MAX_LINK_LENGTH = 4096
}

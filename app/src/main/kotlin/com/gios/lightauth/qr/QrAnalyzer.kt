package com.gios.lightauth.qr

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer

/**
 * CameraX analyzer that decodes QR codes off the luminance (Y) plane using ZXing core.
 * Lifted from LightQR, where it reads codes off a phone screen reliably enough to be the
 * whole app.
 *
 * Pure Java, no Google Play Services — which is the point, because LightOS ships without
 * GMS and ML Kit's barcode scanner is therefore not an option.
 *
 * Only QR is in the format hints: a 2FA enrolment page never shows a barcode of any other
 * kind, and every format left enabled is work done on every frame for nothing.
 */
class QrAnalyzer(private val onResult: (String) -> Unit) : ImageAnalysis.Analyzer {

    private val reader = MultiFormatReader().apply {
        setHints(
            mapOf(
                DecodeHintType.TRY_HARDER to true,
                DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
            ),
        )
    }

    override fun analyze(image: ImageProxy) {
        try {
            val buffer = image.planes[0].buffer
            val bytes = ByteArray(buffer.remaining()).also { buffer.get(it) }

            val source = PlanarYUVLuminanceSource(
                bytes,
                image.width,
                image.height,
                0, 0,
                image.width,
                image.height,
                false,
            )
            val bitmap = BinaryBitmap(HybridBinarizer(source))
            val result = reader.decodeWithState(bitmap)
            result?.text?.let { onResult(it) }
        } catch (_: Exception) {
            // No code found in this frame — normal, just keep scanning.
        } finally {
            reader.reset()
            image.close()
        }
    }
}

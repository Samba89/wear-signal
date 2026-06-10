package dev.sam.wearsignal.ui

import android.graphics.Bitmap
import android.graphics.Color
import androidx.core.graphics.createBitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

object QrCode {

  /** Renders [data] as a QR bitmap with [size] px sides and a quiet zone. */
  fun bitmap(data: String, size: Int): Bitmap {
    val hints = mapOf(
      EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L,
      EncodeHintType.MARGIN to 2
    )
    val matrix = QRCodeWriter().encode(data, BarcodeFormat.QR_CODE, size, size, hints)
    val bitmap = createBitmap(size, size)
    for (x in 0 until size) {
      for (y in 0 until size) {
        bitmap.setPixel(x, y, if (matrix.get(x, y)) Color.BLACK else Color.WHITE)
      }
    }
    return bitmap
  }
}

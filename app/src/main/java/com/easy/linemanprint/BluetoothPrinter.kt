package com.easy.linemanprint

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import java.util.UUID

// ส่งรูปใบเสร็จไปเครื่องพิมพ์บลูทูธ ESC/POS
object BluetoothPrinter {

    private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private val lock = Any()

    // แปลง Bitmap ขาวดำ -> คำสั่ง raster ESC/POS (GS v 0)
    private fun rasterize(bmp: Bitmap): ByteArray {
        val w = bmp.width
        val h = bmp.height
        val bytesPerRow = (w + 7) / 8
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x1D, 0x76, 0x30, 0x00))
        out.write(bytesPerRow and 0xFF)
        out.write((bytesPerRow shr 8) and 0xFF)
        out.write(h and 0xFF)
        out.write((h shr 8) and 0xFF)

        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)
        for (y in 0 until h) {
            for (xb in 0 until bytesPerRow) {
                var b = 0
                for (bit in 0 until 8) {
                    val x = xb * 8 + bit
                    if (x < w) {
                        val p = pixels[y * w + x]
                        val r = (p shr 16) and 0xFF
                        val g = (p shr 8) and 0xFF
                        val bl = p and 0xFF
                        val lum = (r * 299 + g * 587 + bl * 114) / 1000
                        if (lum < 160) b = b or (0x80 shr bit)  // เข้ม = จุดดำ
                    }
                }
                out.write(b)
            }
        }
        return out.toByteArray()
    }

    @SuppressLint("MissingPermission")
    fun printBitmap(context: Context, bmp: Bitmap) {
        val mac = Prefs.getPrinterMac(context)
            ?: throw IllegalStateException("ยังไม่ได้เลือกเครื่องพิมพ์")
        val adapter = BluetoothAdapter.getDefaultAdapter()
            ?: throw IllegalStateException("เครื่องไม่มีบลูทูธ")
        val device = adapter.getRemoteDevice(mac)

        synchronized(lock) {
            val socket = device.createRfcommSocketToServiceRecord(SPP_UUID)
            try {
                socket.connect()
                val os = socket.outputStream
                os.write(byteArrayOf(0x1B, 0x40))            // ESC @ init
                os.write(rasterize(bmp))                     // รูปใบเสร็จ
                os.write(byteArrayOf(0x0A, 0x0A, 0x0A, 0x0A)) // feed
                os.flush()
                Thread.sleep(400)                            // รอให้พิมพ์เสร็จก่อนปิด
            } finally {
                try { socket.close() } catch (_: Exception) {}
            }
        }
    }
}

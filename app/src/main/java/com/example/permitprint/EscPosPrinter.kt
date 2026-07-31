package com.example.permitprint

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.graphics.Bitmap
import android.graphics.Color
import java.io.OutputStream
import java.util.UUID

/**
 * Minimal ESC/POS driver for 58mm Bluetooth thermal printers.
 *
 * 58mm printers have a printable width of 48mm = 384 dots at 203 dpi.
 * Images are sent with the GS v 0 raster command in chunks so cheap
 * printers with tiny buffers don't drop data.
 */
class EscPosPrinter {

    companion object {
        const val PRINTER_WIDTH_DOTS = 384          // 58mm printer
        private val SPP_UUID: UUID =
            UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }

    private var socket: BluetoothSocket? = null
    private var out: OutputStream? = null

    val isConnected: Boolean
        get() = socket?.isConnected == true

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice) {
        disconnect()
        val s = device.createRfcommSocketToServiceRecord(SPP_UUID)
        s.connect()
        socket = s
        out = s.outputStream
        // Initialize printer: ESC @
        write(byteArrayOf(0x1B, 0x40))
    }

    fun disconnect() {
        try { out?.close() } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
        out = null
        socket = null
    }

    private fun write(bytes: ByteArray) {
        out?.write(bytes)
        out?.flush()
    }

    fun feedLines(n: Int) {
        write(byteArrayOf(0x1B, 0x64, n.toByte()))   // ESC d n
    }

    /**
     * Prints a bitmap. The bitmap is scaled to 384px wide (aspect kept),
     * converted to 1-bit using Floyd–Steinberg dithering, and streamed
     * in 128-row chunks.
     */
    fun printBitmap(source: Bitmap) {
        val scaled = if (source.width != PRINTER_WIDTH_DOTS) {
            val h = (source.height.toLong() * PRINTER_WIDTH_DOTS / source.width).toInt()
            Bitmap.createScaledBitmap(source, PRINTER_WIDTH_DOTS, h, true)
        } else source

        val w = scaled.width
        val h = scaled.height
        val bytesPerRow = w / 8

        val mono = ditherToMono(scaled)
        if (scaled !== source) scaled.recycle()

        val chunkRows = 128
        var row = 0
        while (row < h) {
            val rows = minOf(chunkRows, h - row)
            val header = byteArrayOf(
                0x1D, 0x76, 0x30, 0x00,                         // GS v 0, normal mode
                (bytesPerRow and 0xFF).toByte(),
                ((bytesPerRow shr 8) and 0xFF).toByte(),
                (rows and 0xFF).toByte(),
                ((rows shr 8) and 0xFF).toByte()
            )
            val data = ByteArray(bytesPerRow * rows)
            System.arraycopy(mono, row * bytesPerRow, data, 0, data.size)
            write(header)
            write(data)
            // Give slow printers time to drain their buffer
            Thread.sleep(60)
            row += rows
        }
    }

    /**
     * Floyd–Steinberg dithering -> packed 1-bit array, MSB first,
     * 1 = black dot (ESC/POS raster convention).
     */
    private fun ditherToMono(bmp: Bitmap): ByteArray {
        val w = bmp.width
        val h = bmp.height
        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)

        // Grayscale with white background for transparency
        val gray = FloatArray(w * h)
        for (i in pixels.indices) {
            val c = pixels[i]
            val a = Color.alpha(c) / 255f
            val r = Color.red(c) * a + 255 * (1 - a)
            val g = Color.green(c) * a + 255 * (1 - a)
            val b = Color.blue(c) * a + 255 * (1 - a)
            gray[i] = 0.299f * r + 0.587f * g + 0.114f * b
        }

        val bytesPerRow = w / 8
        val outBytes = ByteArray(bytesPerRow * h)

        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                val old = gray[i]
                val newVal = if (old < 128f) 0f else 255f
                val err = old - newVal
                gray[i] = newVal

                if (x + 1 < w) gray[i + 1] += err * 7 / 16
                if (y + 1 < h) {
                    if (x > 0) gray[i + w - 1] += err * 3 / 16
                    gray[i + w] += err * 5 / 16
                    if (x + 1 < w) gray[i + w + 1] += err * 1 / 16
                }

                if (newVal == 0f) { // black
                    outBytes[y * bytesPerRow + x / 8] =
                        (outBytes[y * bytesPerRow + x / 8].toInt() or (0x80 shr (x % 8))).toByte()
                }
            }
        }
        return outBytes
    }
}

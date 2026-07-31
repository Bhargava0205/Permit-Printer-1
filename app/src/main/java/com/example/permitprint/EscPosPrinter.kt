package com.example.permitprint

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.graphics.Bitmap
import android.graphics.Color
import java.io.OutputStream
import java.lang.reflect.Method
import java.util.UUID

/**
 * ESC/POS driver for 58mm Bluetooth thermal printers.
 *
 * QR-CRITICAL RULES FOLLOWED HERE:
 *  - never smooth/blur when resizing (nearest-neighbour only, and only if
 *    absolutely unavoidable; normally the image is already 384 dots wide)
 *  - never dither: the receipt is pure black/white, so a hard threshold keeps
 *    every QR module a perfect square. Dithering was what smeared the QR.
 */
class EscPosPrinter {

    companion object {
        const val PRINTER_WIDTH_DOTS = 384          // 58mm = 48mm printable
        private val SPP_UUID: UUID =
            UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }

    private var socket: BluetoothSocket? = null
    private var out: OutputStream? = null

    val isConnected: Boolean
        get() = socket?.isConnected == true

    /** Connects, with fallbacks for the many quirky clone printers. */
    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice) {
        disconnect()
        var lastError: Exception? = null

        // 1) standard secure SPP
        try {
            val s = device.createRfcommSocketToServiceRecord(SPP_UUID)
            s.connect()
            attach(s); return
        } catch (e: Exception) { lastError = e; }

        // 2) insecure SPP (many cheap printers have no pairing security)
        try {
            val s = device.createInsecureRfcommSocketToServiceRecord(SPP_UUID)
            s.connect()
            attach(s); return
        } catch (e: Exception) { lastError = e }

        // 3) hidden channel-1 fallback (older/no-SDP printers)
        try {
            val m: Method = device.javaClass
                .getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
            val s = m.invoke(device, 1) as BluetoothSocket
            s.connect()
            attach(s); return
        } catch (e: Exception) { lastError = e }

        throw Exception(
            "could not connect (" + (lastError?.message ?: "unknown") +
            "). Switch the printer off and on, then try again."
        )
    }

    private fun attach(s: BluetoothSocket) {
        socket = s
        out = s.outputStream
        write(byteArrayOf(0x1B, 0x40))          // ESC @  initialise
    }

    fun disconnect() {
        try { out?.close() } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
        out = null
        socket = null
    }

    private fun write(bytes: ByteArray) {
        val o = out ?: throw Exception("printer not connected")
        o.write(bytes)
        o.flush()
    }

    fun feedLines(n: Int) {
        write(byteArrayOf(0x1B, 0x64, n.toByte()))
    }

    /** Sets maximum print density so QR modules come out solid black. */
    fun setMaxDensity() {
        try {
            // GS ( K  <density>  (supported by most clones; ignored by others)
            write(byteArrayOf(0x1D, 0x28, 0x4B, 0x02, 0x00, 0x31, 0x08))
        } catch (_: Exception) { }
    }

    fun printBitmap(source: Bitmap) {
        // Fit to the printer width WITHOUT smoothing.
        val prepared: Bitmap = when {
            source.width == PRINTER_WIDTH_DOTS -> source
            source.width < PRINTER_WIDTH_DOTS -> padToWidth(source)
            else -> {
                val h = (source.height.toLong() * PRINTER_WIDTH_DOTS / source.width).toInt()
                Bitmap.createScaledBitmap(source, PRINTER_WIDTH_DOTS, h, false) // no filter
            }
        }

        val w = prepared.width
        val h = prepared.height
        val bytesPerRow = w / 8
        val mono = thresholdToMono(prepared)
        if (prepared !== source) prepared.recycle()

        val chunkRows = 128
        var row = 0
        while (row < h) {
            val rows = if (row + chunkRows <= h) chunkRows else h - row
            val header = byteArrayOf(
                0x1D, 0x76, 0x30, 0x00,
                (bytesPerRow and 0xFF).toByte(),
                ((bytesPerRow shr 8) and 0xFF).toByte(),
                (rows and 0xFF).toByte(),
                ((rows shr 8) and 0xFF).toByte()
            )
            val data = ByteArray(bytesPerRow * rows)
            System.arraycopy(mono, row * bytesPerRow, data, 0, data.size)
            write(header)
            write(data)
            Thread.sleep(60)                       // let slow printers drain
            row += rows
        }
    }

    /** Centres a narrower image on white paper - no scaling, no blur. */
    private fun padToWidth(src: Bitmap): Bitmap {
        val outBmp = Bitmap.createBitmap(PRINTER_WIDTH_DOTS, src.height, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(outBmp)
        canvas.drawColor(Color.WHITE)
        canvas.drawBitmap(src, ((PRINTER_WIDTH_DOTS - src.width) / 2).toFloat(), 0f, null)
        return outBmp
    }

    /**
     * Hard threshold -> packed 1-bit rows (MSB first, 1 = black dot).
     * No dithering: keeps QR modules perfectly square and scannable.
     */
    private fun thresholdToMono(bmp: Bitmap): ByteArray {
        val w = bmp.width
        val h = bmp.height
        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)

        val bytesPerRow = w / 8
        val outBytes = ByteArray(bytesPerRow * h)

        for (y in 0 until h) {
            val rowBase = y * bytesPerRow
            for (x in 0 until w) {
                val c = pixels[y * w + x]
                val a = Color.alpha(c) / 255f
                val r = Color.red(c) * a + 255 * (1 - a)
                val g = Color.green(c) * a + 255 * (1 - a)
                val b = Color.blue(c) * a + 255 * (1 - a)
                val lum = 0.299f * r + 0.587f * g + 0.114f * b
                if (lum < 160f) {                   // slightly generous = solid black
                    val idx = rowBase + (x shr 3)
                    outBytes[idx] = (outBytes[idx].toInt() or (0x80 shr (x and 7))).toByte()
                }
            }
        }
        return outBytes
    }
}

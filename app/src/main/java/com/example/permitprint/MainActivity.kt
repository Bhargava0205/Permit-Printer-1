package com.example.permitprint

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject

/**
 * Single-screen app: the verified permit engine runs in a WebView (assets/www),
 * printing goes out over Bluetooth ESC/POS from Kotlin.
 * Uses only the Android platform - no external libraries - so it always builds.
 */
class MainActivity : Activity() {

    private lateinit var web: WebView
    private val printer = EscPosPrinter()
    private val prefs by lazy { getSharedPreferences("printer", Context.MODE_PRIVATE) }
    private var pageReady = false
    private var pendingShare: Uri? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        web = WebView(this)
        setContentView(web)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.allowFileAccess = true
        web.addJavascriptInterface(Bridge(), "AndroidPrinter")
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                pageReady = true
                val p = pendingShare
                if (p != null) { pendingShare = null; deliverShared(p) }
            }
        }
        web.loadUrl("file:///android_asset/www/index.html")

        requestBtPermissions()
        handleIncoming(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handleIncoming(intent)
    }

    // ---------------- shared permits (WhatsApp / Files) ----------------

    private fun handleIncoming(intent: Intent?) {
        val uri: Uri? = when (intent?.action) {
            Intent.ACTION_SEND ->
                @Suppress("DEPRECATION") intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            Intent.ACTION_VIEW -> intent.data
            else -> null
        }
        if (uri == null) return
        if (pageReady) deliverShared(uri) else pendingShare = uri
    }

    private fun deliverShared(uri: Uri) {
        Thread {
            try {
                val mime = contentResolver.getType(uri) ?: "application/pdf"
                val stream = contentResolver.openInputStream(uri)
                val bytes = stream!!.readBytes()
                stream.close()
                val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                runOnUiThread {
                    web.evaluateJavascript("shareBegin()", null)
                    var i = 0
                    val step = 200000
                    while (i < b64.length) {
                        val end = if (i + step < b64.length) i + step else b64.length
                        val chunk = b64.substring(i, end)
                        web.evaluateJavascript("shareChunk('" + chunk + "')", null)
                        i += step
                    }
                    web.evaluateJavascript("shareEnd('" + mime + "','permit')", null)
                }
            } catch (e: Exception) {
                status("Could not open the shared file: " + e.message, false)
            }
        }.start()
    }

    // ---------------- printing bridge ----------------

    inner class Bridge {
        @JavascriptInterface
        fun print(dataUrl: String) {
            val b64 = dataUrl.substringAfter("base64,")
            val bytes = Base64.decode(b64, Base64.DEFAULT)
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            Thread {
                try {
                    if (!printer.isConnected) connectSaved()
                    printer.printBitmap(bmp)
                    printer.feedLines(4)
                    status("Printed \u2714", true)
                } catch (e: Exception) {
                    status("Print failed: " + e.message, false)
                    runOnUiThread { choosePrinter() }
                }
            }.start()
        }
    }

    @SuppressLint("MissingPermission")
    private fun bondedDevices(): List<BluetoothDevice> {
        val manager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter: BluetoothAdapter? = manager?.adapter
        val bonded = adapter?.bondedDevices ?: return emptyList()
        return bonded.toList()
    }

    @SuppressLint("MissingPermission")
    private fun connectSaved() {
        val devices = bondedDevices()
        if (devices.isEmpty())
            throw Exception("pair the printer in Bluetooth settings first")
        val saved = prefs.getString("mac", null)
        var device = devices.firstOrNull { it.address == saved }
        if (device == null) {
            device = devices.firstOrNull {
                val n = (it.name ?: "").uppercase()
                n.contains("PRINT") || n.contains("POS") || n.contains("58") || n.contains("MTP")
            }
        }
        if (device == null) throw Exception("select the printer")
        printer.connect(device)
        prefs.edit().putString("mac", device.address).apply()
    }

    @SuppressLint("MissingPermission")
    private fun choosePrinter() {
        val devices = bondedDevices()
        if (devices.isEmpty()) return
        val names = devices.map { (it.name ?: "Unknown") + " (" + it.address + ")" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Select printer")
            .setItems(names) { _, which ->
                prefs.edit().putString("mac", devices[which].address).apply()
                printer.disconnect()
                status("Printer saved. Tap Print again.", true)
            }
            .show()
    }

    private fun requestBtPermissions() {
        val need = if (Build.VERSION.SDK_INT >= 31)
            arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        else
            arrayOf(Manifest.permission.BLUETOOTH, Manifest.permission.BLUETOOTH_ADMIN)
        val missing = need.filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 1)
    }

    private fun status(msg: String, ok: Boolean) {
        runOnUiThread {
            web.evaluateJavascript("appStatus(" + JSONObject.quote(msg) + ", " + ok + ")", null)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        printer.disconnect()
    }
}

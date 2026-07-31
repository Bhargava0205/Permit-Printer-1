package com.example.permitprint

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private val printer = EscPosPrinter()
    private val prefs by lazy { getSharedPreferences("printer", MODE_PRIVATE) }
    private var pageReady = false
    private var pendingShare: Uri? = null

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this)
        setContentView(web)
        with(web.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
        }
        web.addJavascriptInterface(Bridge(), "AndroidPrinter")
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                pageReady = true
                pendingShare?.let { deliverShared(it); pendingShare = null }
            }
        }
        web.loadUrl("file:///android_asset/www/index.html")
        ensureBtPermissions()
        handleIncoming(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncoming(intent)
    }

    // ---------- Shared permits from WhatsApp / Files ----------
    private fun handleIncoming(intent: Intent?) {
        val uri: Uri? = when (intent?.action) {
            Intent.ACTION_SEND ->
                if (Build.VERSION.SDK_INT >= 33)
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
            Intent.ACTION_VIEW -> intent.data
            else -> null
        } ?: return
        if (pageReady) deliverShared(uri) else pendingShare = uri
    }

    private fun deliverShared(uri: Uri) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val mime = contentResolver.getType(uri) ?: "application/pdf"
                val bytes = contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                withContext(Dispatchers.Main) {
                    web.evaluateJavascript("shareBegin()", null)
                    var i = 0
                    while (i < b64.length) {
                        val chunk = b64.substring(i, minOf(i + 200_000, b64.length))
                        web.evaluateJavascript("shareChunk('$chunk')", null)
                        i += 200_000
                    }
                    web.evaluateJavascript("shareEnd('$mime','permit')", null)
                }
            } catch (e: Exception) {
                status("Could not open shared file: ${e.message}", false)
            }
        }
    }

    // ---------- Bluetooth printing bridge ----------
    inner class Bridge {
        @JavascriptInterface
        fun print(dataUrl: String) {
            val b64 = dataUrl.substringAfter("base64,")
            val bytes = Base64.decode(b64, Base64.DEFAULT)
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    if (!printer.isConnected) connectSaved()
                    printer.printBitmap(bmp)
                    printer.feedLines(4)
                    status("Printed \u2714", true)
                } catch (e: Exception) {
                    status("Print failed: ${e.message}. Tap Print again to pick a printer.", false)
                    withContext(Dispatchers.Main) { choosePrinter() }
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun bonded(): List<BluetoothDevice> =
        (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager)
            .adapter?.bondedDevices?.toList() ?: emptyList()

    @SuppressLint("MissingPermission")
    private fun connectSaved() {
        val devices = bonded()
        if (devices.isEmpty()) throw Exception("pair the printer in Bluetooth settings first")
        val saved = prefs.getString("mac", null)
        val device = devices.firstOrNull { it.address == saved }
            ?: devices.firstOrNull {
                val n = (it.name ?: "").uppercase()
                "PRINT" in n || "POS" in n || "58" in n || "MTP" in n
            } ?: throw Exception("no printer selected")
        printer.connect(device)
        prefs.edit().putString("mac", device.address).apply()
    }

    @SuppressLint("MissingPermission")
    private fun choosePrinter() {
        val devices = bonded()
        if (devices.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle("Select printer")
            .setItems(devices.map { "${it.name ?: "Unknown"} (${it.address})" }
                .toTypedArray()) { _, which ->
                prefs.edit().putString("mac", devices[which].address).apply()
                printer.disconnect()
                status("Printer saved. Tap Print again.", true)
            }.show()
    }

    private fun ensureBtPermissions() {
        val need = if (Build.VERSION.SDK_INT >= 31)
            arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        else arrayOf(Manifest.permission.BLUETOOTH, Manifest.permission.BLUETOOTH_ADMIN)
        val missing = need.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray())
    }

    private fun status(msg: String, ok: Boolean) {
        runOnUiThread {
            web.evaluateJavascript(
                "appStatus(${org.json.JSONObject.quote(msg)}, $ok)", null)
        }
    }

    override fun onDestroy() { super.onDestroy(); printer.disconnect() }
}

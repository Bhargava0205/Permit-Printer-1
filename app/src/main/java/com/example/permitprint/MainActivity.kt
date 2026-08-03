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
import java.net.HttpURLConnection
import java.net.URL

/**
 * Single-screen app: the verified permit engine runs in a WebView (assets/www),
 * printing goes out over Bluetooth ESC/POS from Kotlin.
 * Uses only the Android platform - no external libraries - so it always builds.
 */
class MainActivity : Activity() {

    companion object {
        /** Latest-release info straight from GitHub - no file to edit. */
        const val RELEASES_API =
            "https://api.github.com/repos/Bhargava0205/Permit-Printer-1/releases/latest"
        const val RELEASES_PAGE =
            "https://github.com/Bhargava0205/Permit-Printer-1/releases/latest"
    }

    /** The version baked in by the build - never edited by hand. */
    private val appVersion: String by lazy {
        try { packageManager.getPackageInfo(packageName, 0).versionName ?: "0.0" }
        catch (e: Exception) { "0.0" }
    }

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
        checkForUpdate(false)
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
        /** Opens the printer chooser from the app's Printer button. */
        @JavascriptInterface
        fun selectPrinter() { runOnUiThread { choosePrinter() } }

        /** Name of the currently saved printer (shown on the button). */
        @JavascriptInterface
        fun savedPrinterName(): String {
            val mac = prefs.getString("mac", null) ?: return ""
            return try {
                val d = bondedDevices().firstOrNull { it.address == mac }
                if (d != null) {
                    val fresh = displayName(d)          // reflects Bluetooth renames
                    prefs.edit().putString("name", fresh).apply()
                    fresh
                } else prefs.getString("name", "") ?: ""
            } catch (e: Exception) { prefs.getString("name", "") ?: "" }
        }

        @JavascriptInterface
        fun appVersion(): String = this@MainActivity.appVersion

        /** Project facts for the hidden info screen (long-press the version). */
        @JavascriptInterface
        fun projectInfo(): String =
            "AMC PERMIT PRINT - PROJECT INFORMATION\n" +
            "======================================\n\n" +
            "Repository:\n  github.com/Bhargava0205/Permit-Printer-1\n\n" +
            "APK link (always newest):\n  github.com/Bhargava0205/Permit-Printer-1/releases/latest/download/app-permit-print.apk\n\n" +
            "How a release happens:\n  Any push to the repo builds automatically.\n  Version = build number (run 15 = v2.5, run 16 = v2.6...).\n  Nothing to edit for versioning.\n\n" +
            "Signing (needed for updates to install):\n  4 GitHub secrets: KEYSTORE_B64, KEYSTORE_PASSWORD,\n  KEY_ALIAS, KEY_PASSWORD.\n  Key file backup: PROJECT_VAULT.zip (kept privately\n  by the developer - NOT in this app or the repo).\n\n" +
            "If everything is lost:\n  Give the PROJECT_VAULT.zip to Claude (claude.ai)\n  and say: restore the AMC Permit Print project.\n  It contains the full source, keys and instructions.\n\n" +
            "Developed for AMC, Palamaner, Chittoor District."

        @JavascriptInterface
        fun checkUpdateNow() { checkForUpdate(true) }

        @JavascriptInterface
        fun testPrint() {
            if (!hasBtPermission()) {
                runOnUiThread { requestBtPermissions() }
                status("Allow the Bluetooth permission, then try again.", false); return
            }
            Thread {
                try {
                    printer.disconnect()
                    Thread.sleep(150)
                    connectSaved()
                    printer.testPage(prefs.getString("name", "printer") ?: "printer")
                    Thread.sleep(200)
                    printer.disconnect()
                    status("Test page sent \u2714", true)
                } catch (e: Exception) {
                    status("Test print failed: " + e.message, false)
                    runOnUiThread { choosePrinter() }
                }
            }.start()
        }

        /** Fallback used if the page calls print() without a paper width. */
        @JavascriptInterface
        fun print(dataUrl: String) = print(dataUrl, EscPosPrinter.WIDTH_58MM)

        @JavascriptInterface
        fun print(dataUrl: String, widthDots: Int) {
            val b64 = dataUrl.substringAfter("base64,")
            val bytes = Base64.decode(b64, Base64.DEFAULT)
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            if (!hasBtPermission()) {
                runOnUiThread { requestBtPermissions() }
                status("Allow the Bluetooth permission, then tap Print again.", false)
                return
            }
            Thread {
                try {
                    printer.disconnect()          // always start from a clean link
                    Thread.sleep(150)
                    connectSaved()
                    printer.printBitmap(bmp, widthDots)
                    printer.feedLines(4)
                    Thread.sleep(200)
                    printer.disconnect()
                    status("Printed \u2714", true)
                } catch (e: Exception) {
                    try { printer.disconnect() } catch (_: Exception) {}
                    status("Print failed: " + e.message, false)
                    runOnUiThread { choosePrinter() }
                }
            }.start()
        }
    }

    /** The name the user sees in Android Bluetooth settings (rename-aware). */
    @SuppressLint("MissingPermission")
    private fun displayName(d: BluetoothDevice): String {
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                val alias = d.alias
                if (!alias.isNullOrBlank()) return alias
            } else {
                val m = d.javaClass.getMethod("getAliasName")
                val alias = m.invoke(d) as? String
                if (!alias.isNullOrBlank()) return alias
            }
        } catch (_: Exception) { }
        return d.name ?: "Unknown"
    }

    @SuppressLint("MissingPermission")
    @Throws(Exception::class)
    private fun bondedDevices(): List<BluetoothDevice> {
        val manager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter: BluetoothAdapter? = manager?.adapter
        if (adapter == null) throw Exception("this phone has no Bluetooth")
        if (!adapter.isEnabled) throw Exception("switch Bluetooth on, then tap Print again")
        val bonded = adapter.bondedDevices ?: return emptyList()
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

    /** True for devices that look like printers (class or name). */
    @SuppressLint("MissingPermission")
    private fun looksLikePrinter(d: BluetoothDevice): Boolean {
        try {
            val major = d.bluetoothClass?.majorDeviceClass ?: -1
            if (major == android.bluetooth.BluetoothClass.Device.Major.IMAGING) return true
        } catch (_: Exception) { }
        val n = (displayName(d) + " " + (d.name ?: "")).uppercase()
        for (k in listOf("PRINT", "POS", "THERMAL", "RECEIPT", "58", "80",
                         "MTP", "RPP", "PTP", "GOOJPRT", "PERIPAGE", "CP"))
            if (n.contains(k)) return true
        return false
    }

    /** Quick reachability probe: can we open a socket right now? */
    @SuppressLint("MissingPermission")
    private fun isReachable(d: BluetoothDevice, timeoutMs: Long): Boolean {
        var ok = false
        var socket: android.bluetooth.BluetoothSocket? = null
        val t = Thread {
            try {
                socket = d.createInsecureRfcommSocketToServiceRecord(
                    java.util.UUID.fromString("00001101-0000-1000-8000-00805F9B34FB"))
                socket?.connect()
                ok = true
            } catch (_: Exception) { }
        }
        t.start()
        t.join(timeoutMs)
        try { socket?.close() } catch (_: Exception) { }
        return ok
    }

    @SuppressLint("MissingPermission")
    private fun choosePrinter(showAll: Boolean = false) {
        if (!hasBtPermission()) { requestBtPermissions(); return }
        val all = try { bondedDevices() } catch (e: Exception) {
            AlertDialog.Builder(this).setTitle("Printer")
                .setMessage(e.message ?: "Bluetooth problem")
                .setPositiveButton("OK", null).show()
            return
        }
        if (all.isEmpty()) {
            AlertDialog.Builder(this).setTitle("No paired printers")
                .setMessage("Pair the thermal printer in Android Settings > Bluetooth first " +
                            "(PIN 0000 or 1234), then open this list again.")
                .setPositiveButton("Open Bluetooth settings") { _, _ ->
                    try { startActivity(Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS)) }
                    catch (_: Exception) {}
                }
                .setNegativeButton("Close", null).show()
            return
        }
        val saved = prefs.getString("mac", null)
        val candidates =
            if (showAll) all
            else all.filter { looksLikePrinter(it) || it.address == saved }
                    .ifEmpty { all }

        val checking = AlertDialog.Builder(this)
            .setTitle("Searching for printers")
            .setMessage("Checking which printers are switched on\u2026")
            .setCancelable(false)
            .create()
        checking.show()

        Thread {
            // probe all candidates in parallel (about 3 seconds total)
            val online = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
            val threads = candidates.map { d ->
                Thread { online[d.address] = isReachable(d, 3000) }.also { it.start() }
            }
            threads.forEach { it.join(3500) }

            runOnUiThread {
                try { checking.dismiss() } catch (_: Exception) { }
                // available printers first
                val sorted = candidates.sortedByDescending { online[it.address] == true }
                val labels = sorted.map {
                    val tick = if (it.address == saved) "\u2714 " else ""
                    val state = if (online[it.address] == true) "\u25CF available"
                                else "\u25CB off / out of range"
                    tick + displayName(it) + "  (" + state + ")\n" + it.address
                }.toMutableList()
                if (!showAll) labels.add("Show all Bluetooth devices\u2026")
                AlertDialog.Builder(this)
                    .setTitle("Select printer")
                    .setItems(labels.toTypedArray()) { _, which ->
                        if (!showAll && which == labels.size - 1) {
                            choosePrinter(showAll = true); return@setItems
                        }
                        val d = sorted[which]
                        prefs.edit().putString("mac", d.address)
                            .putString("name", displayName(d)).apply()
                        printer.disconnect()
                        status("Printer set: " + displayName(d), true)
                        web.evaluateJavascript("if(window.refreshPrinterName)refreshPrinterName()", null)
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }.start()
    }

    private fun isNewer(remote: String, local: String): Boolean {
        fun parts(v: String) = v.trim().split(".").map { it.filter { c -> c.isDigit() } }
            .map { if (it.isEmpty()) 0 else it.toInt() }
        val r = parts(remote); val l = parts(local)
        for (i in 0 until maxOf(r.size, l.size)) {
            val a = r.getOrElse(i) { 0 }; val b = l.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    /** Checks version.json on GitHub; offers to install a newer APK. */
    private fun checkForUpdate(manual: Boolean) {
        Thread {
            try {
                val conn = URL(RELEASES_API).openConnection() as HttpURLConnection
                conn.connectTimeout = 8000; conn.readTimeout = 8000
                conn.setRequestProperty("Accept", "application/vnd.github+json")
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()
                val j = JSONObject(body)
                val latest = j.optString("tag_name", "").removePrefix("v")
                var url = RELEASES_PAGE
                val assets = j.optJSONArray("assets")
                if (assets != null) for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    if (a.optString("name") == "app-permit-print.apk")
                        url = a.optString("browser_download_url", url)
                }
                val notes = j.optString("body", "")
                runOnUiThread {
                    if (latest.isNotEmpty() && url.isNotEmpty() && isNewer(latest, appVersion)) {
                        AlertDialog.Builder(this)
                            .setTitle("Update available: " + latest)
                            .setMessage("You have " + appVersion + ".\n\n" +
                                        (if (notes.isEmpty()) "A newer version is ready."
                                         else notes))
                            .setPositiveButton("Update now") { _, _ ->
                                try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                                catch (_: Exception) {}
                            }
                            .setNegativeButton("Later", null)
                            .show()
                    } else if (manual) {
                        status("You are on the latest version (" + appVersion + ").", true)
                    }
                }
            } catch (e: Exception) {
                if (manual) status("Could not check for updates: " + e.message, false)
            }
        }.start()
    }

    private fun hasBtPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= 31)
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED
        else true
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

    override fun onResume() {
        super.onResume()
        if (pageReady) web.evaluateJavascript(
            "if(window.refreshPrinterName)refreshPrinterName()", null)
    }

    override fun onDestroy() {
        super.onDestroy()
        printer.disconnect()
    }
}

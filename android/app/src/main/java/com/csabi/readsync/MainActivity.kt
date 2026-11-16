package com.csabi.readsync

import android.app.Activity
import android.content.Context
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.format.Formatter
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.security.SecureRandom

class MainActivity : Activity() {

    /** Set when the user taps "set home" before location permission exists. */
    private var pendingSet = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences("readsync", Context.MODE_PRIVATE)
        if (prefs.getString("token", "").isNullOrEmpty()) {
            prefs.edit().putString("token", newToken()).apply()
        }

        buildUi(prefs)
    }

    private fun newToken(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun buildUi(prefs: android.content.SharedPreferences) {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        fun text(s: String, small: Boolean = false) = TextView(this).apply {
            this.text = s
            textSize = if (small) 14f else 17f
            setPadding(0, pad / 2, 0, pad / 2)
            setTextIsSelectable(true)
        }

        val ip = wifiIp() ?: "not on Wi-Fi"
        val pm = getSystemService(PowerManager::class.java)
        val ignoring = pm.isIgnoringBatteryOptimizations(packageName)

        root.addView(text("Open this URL in the extension options:"))
        root.addView(text("http://$ip:8765/read"))
        root.addView(text("Token (paste into extension options):"))
        root.addView(text(prefs.getString("token", "") ?: "", small = true))

        val status = text(listenerStatus(), small = true)
        root.addView(status)

        val accessBtn = Button(this).apply {
            text = "Grant notification access"
            setOnClickListener {
                startActivity(android.content.Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
        }
        root.addView(accessBtn)

        val dozeBtn = Button(this).apply {
            text = if (ignoring) "Battery optimization: exempt ✓" else "Allow background (battery optimization)"
            setOnClickListener {
                if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                    @Suppress("BatteryLife")
                    startActivity(
                        android.content.Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                            .setData(android.net.Uri.parse("package:$packageName"))
                    )
                }
            }
        }
        root.addView(dozeBtn)

        val cur = NotificationSyncService.bssid(this)
        val home = prefs.getString("homeBssid", "") ?: ""
        val homeBtn = Button(this).apply {
            text = when {
                cur == null -> "Set current Wi-Fi as home (needs location permission)"
                home.isEmpty() -> "Set current Wi-Fi as home network"
                home == cur -> "Home network set ✓"
                else -> "Not on home network — tap to re-pair"
            }
            setOnClickListener {
                val now = NotificationSyncService.bssid(this@MainActivity)
                if (now == null) {
                    pendingSet = true
                    requestPermissions(
                        arrayOf("android.permission.ACCESS_FINE_LOCATION"), 1
                    )
                } else {
                    val bg = NotificationSyncService.gateway(this@MainActivity)
                    prefs.edit()
                        .putString("homeBssid", now)
                        .putString("homeGateway", bg)
                        .apply()
                    // Wake the service so the gate re-evaluates immediately.
                    startService(android.content.Intent(this@MainActivity, NotificationSyncService::class.java))
                    buildUi(prefs)
                }
            }
        }
        root.addView(homeBtn)
        if (home.isNotEmpty() && home != cur) {
            root.addView(text("Not on home network right now — server stopped.", small = true))
        }

        root.addView(text(
            "Server runs only on the home network set above. " +
            "Requests without the correct token are rejected.",
            small = true
        ))

        setContentView(root)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != 1) return
        val prefs = getSharedPreferences("readsync", MODE_PRIVATE)
        if (pendingSet && grantResults.firstOrNull() == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            NotificationSyncService.bssid(this)?.let {
                prefs.edit().putString("homeBssid", it).apply()
            }
        }
        pendingSet = false
        buildUi(prefs)
    }

    override fun onResume() {
        super.onResume()
        // Rebuild: IP / listener status may have changed while paused.
        buildUi(getSharedPreferences("readsync", Context.MODE_PRIVATE))
    }

    private fun wifiIp(): String? {
        val wm = applicationContext.getSystemService(WifiManager::class.java) ?: return null
        val ip = wm.connectionInfo?.ipAddress ?: return null
        if (ip == 0) return null
        return Formatter.formatIpAddress(ip)
    }

    private fun listenerStatus(): String {
        val connected = NotificationSyncService.isRunning
        return if (connected) "Notification listener: connected, server running"
        else "Notification listener: not connected — grant access, then reopen this app"
    }
}

package com.csabi.readsync

import android.service.notification.NotificationListenerService
import android.content.Context
import android.net.ConnectivityManager
import android.service.notification.StatusBarNotification

/**
 * Listens for Messenger notifications and dismisses the ones matching a
 * chat the user just read on the PC. Also hosts the HTTP endpoint.
 */
class NotificationSyncService : NotificationListenerService() {

    companion object {
        /** Messenger (com.facebook.orca). Add more if you use FB Lite etc. */
        const val TARGET_PACKAGE = "com.facebook.orca"
        const val PORT = 8765

        @Volatile
        var isRunning = false
            private set

        /**
         * Default-route gateway of the active network, or null. Weak
         * identifier (many routers use 192.168.1.1) but readable in the
         * background without location permission — used as fallback when
         * Android masks the BSSID for background services.
         */
        fun gateway(context: Context): String? {
            val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
            val lp = cm.getLinkProperties(cm.activeNetwork) ?: return null
            return lp.routes.firstOrNull {
                it.isDefaultRoute &&
                    it.gateway is java.net.Inet4Address &&
                    !(it.gateway?.hostAddress ?: "").startsWith("0.")
            }?.gateway?.hostAddress
        }

        /**
         * BSSID (MAC of the connected access point) or null. Identifies
         * your exact home router. Android only reports this with fine
         * location permission granted and location services enabled, and
         * only for foreground reads — background services get null.
         */
        fun bssid(context: Context): String? {
            val wm = context.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
                ?: return null
            val b = wm.connectionInfo?.bssid ?: return null
            if (b.isEmpty() || b == "02:00:00:00:00:00" || b == "<unknown ssid>") return null
            return b.lowercase()
        }
    }

    private var server: ReadServer? = null
    private var netRegistered = false
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    /** BSSID can lag right after a network change — recheck shortly after. */
    private fun reconcileSoon() {
        reconcile()
        handler.postDelayed({ reconcile() }, 3000)
    }

    private val netCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: android.net.Network) { reconcileSoon() }
        override fun onLost(network: android.net.Network) { reconcile() }
        override fun onLinkPropertiesChanged(
            network: android.net.Network,
            lp: android.net.LinkProperties,
        ) { reconcileSoon() }
    }

    override fun onListenerConnected() {
        isRunning = true
        val cm = getSystemService(ConnectivityManager::class.java)
        if (!netRegistered && cm != null) {
            cm.registerDefaultNetworkCallback(netCallback)
            netRegistered = true
        }
        reconcile()
    }

    /** MainActivity pokes the service after settings change. */
    override fun onStartCommand(
        intent: android.content.Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        reconcile()
        return START_STICKY
    }

    override fun onListenerDisconnected() {
        // Ask the system to rebinding so the service comes back.
        requestRebind(android.content.ComponentName(this, javaClass))
        isRunning = false
        stopServer()
    }

    override fun onDestroy() {
        isRunning = false
        if (netRegistered) {
            getSystemService(ConnectivityManager::class.java)
                ?.unregisterNetworkCallback(netCallback)
            netRegistered = false
        }
        stopServer()
        super.onDestroy()
    }

    /** Runs the endpoint only while connected to the home access point.
     *  BSSID is the strict check but Android masks it for background
     *  reads, so gateway is the fallback there. */
    private fun reconcile() {
        val prefs = getSharedPreferences("readsync", Context.MODE_PRIVATE)
        val homeB = prefs.getString("homeBssid", "") ?: ""
        val homeG = prefs.getString("homeGateway", "") ?: ""
        val b = bssid(this)
        val g = gateway(this)
        val match = (b != null && homeB.isNotEmpty() && b == homeB) ||
            (b == null && g != null && homeG.isNotEmpty() && g == homeG)
        if (match) {
            startServer()
        } else {
            stopServer()
        }
    }

    private fun startServer() {
        if (server?.wasStarted() == true) return
        try {
            val s = ReadServer(null, PORT, applicationContext) { thread -> dismissMatching(thread) }
            s.start(5000, false)
            server = s
        } catch (e: Exception) {
            android.util.Log.e("ReadSync", "server start failed", e)
        }
    }

    private fun stopServer() {
        server?.stop()
        server = null
    }

    /**
     * Dismisses active Messenger notifications whose title or text matches
     * [thread] (the chat name sent by the browser extension).
     */
    fun dismissMatching(thread: String): Int {
        val wanted = normalize(thread)
        if (wanted.isEmpty()) return 0
        var dismissed = 0
        for (sbn in activeNotifications ?: emptyArray<StatusBarNotification>()) {
            if (sbn.packageName != TARGET_PACKAGE) continue
            val (title, text) = extract(sbn)
            val nTitle = normalize(title)
            val nText = normalize(text)
            val match = nTitle.contains(wanted) || wanted.contains(nTitle) ||
                (nTitle.isEmpty() && nText.contains(wanted))
            if (match) {
                try {
                    cancelNotification(sbn.key)
                    dismissed++
                } catch (_: SecurityException) {
                    // Notification already gone or not ours to cancel.
                }
            }
        }
        return dismissed
    }

    private fun extract(sbn: StatusBarNotification): Pair<String, String> {
        val ex = sbn.notification.extras
        return Pair(
            ex.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString() ?: "",
            ex.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString() ?: ""
        )
    }

    private fun normalize(s: String): String =
        s.lowercase().replace(Regex("\\s+"), " ").trim()
}

package dev.konraditurbe.storiesclient.wifi

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pManager
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresPermission
import androidx.core.content.ContextCompat

/**
 * Hosts a WiFi-Direct (P2P) group so the glasses can join it as a station and expose their HTTPS webserver.
 *
 * Full-res media download (RE'd from the official app): the phone becomes the P2P group owner (fixed IP
 * [GO_IP]); its auto-generated SSID (`DIRECT-FB-xxxx`) + passphrase are pushed to the glasses via the BLE
 * `stella:soc:stationmode_connect` RPC; the glasses join at [CLIENT_IP] and serve
 * `https://192.168.49.2/stella-webserver/…`. Same-subnet traffic routes over `p2p-wlan0` automatically.
 */
class WifiDirectHost(ctx: Context, private val log: (String) -> Unit = {}) {

    class Group(
        /** `WifiP2pGroup.networkName`, e.g. "DIRECT-FB-awkh". */
        val ssid: String,
        val passphrase: String,
        /** MHz, e.g. 5180. */
        val frequency: Int,
    )

    interface Callback {
        fun onGroupReady(g: Group)
        fun onError(message: String)
    }

    private val appContext = ctx.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var mgr: WifiP2pManager? = null
    private var channel: WifiP2pManager.Channel? = null
    private var receiver: BroadcastReceiver? = null
    @Volatile private var joinFired = false

    @RequiresPermission(allOf = [Manifest.permission.NEARBY_WIFI_DEVICES, Manifest.permission.ACCESS_FINE_LOCATION])
    fun createGroup(cb: Callback) {
        val m = appContext.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
            ?: run { cb.onError("no WifiP2pManager"); return }
        mgr = m
        val ch = m.initialize(appContext, Looper.getMainLooper(), null)
        channel = ch
        // Remove any stale group first, then create a fresh one.
        try {
            m.removeGroup(ch, object : WifiP2pManager.ActionListener {
                override fun onSuccess() = doCreate(m, ch, cb)
                override fun onFailure(reason: Int) = doCreate(m, ch, cb)   // none existed; fine
            })
        } catch (e: SecurityException) {
            cb.onError("removeGroup sec: ${e.message}")
        }
    }

    @RequiresPermission(allOf = [Manifest.permission.NEARBY_WIFI_DEVICES, Manifest.permission.ACCESS_FINE_LOCATION])
    private fun doCreate(m: WifiP2pManager, ch: WifiP2pManager.Channel, cb: Callback) {
        try {
            m.createGroup(ch, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    log("[wifi] P2P group created; reading group info…")
                    fetchGroupInfo(m, ch, cb, 0)
                }
                override fun onFailure(reason: Int) = cb.onError("createGroup failed reason=$reason")
            })
        } catch (e: SecurityException) {
            cb.onError("createGroup sec: ${e.message}")
        }
    }

    /** Group info (SSID/passphrase) can lag the createGroup callback; poll a few times. */
    @RequiresPermission(allOf = [Manifest.permission.NEARBY_WIFI_DEVICES, Manifest.permission.ACCESS_FINE_LOCATION])
    private fun fetchGroupInfo(m: WifiP2pManager, ch: WifiP2pManager.Channel, cb: Callback, attempt: Int) {
        try {
            m.requestGroupInfo(ch) { wg: WifiP2pGroup? ->
                val ssid = wg?.networkName
                val pass = wg?.passphrase
                when {
                    ssid != null && pass != null -> {
                        val g = Group(ssid, pass, wg.frequency)
                        log("[wifi] group ready ssid=${g.ssid} freq=${g.frequency} (GO $GO_IP)")
                        cb.onGroupReady(g)
                    }
                    attempt < 8 -> main.postDelayed({
                        runCatching { fetchGroupInfo(m, ch, cb, attempt + 1) }
                    }, 400)
                    else -> cb.onError("group info unavailable")
                }
            }
        } catch (e: SecurityException) {
            cb.onError("requestGroupInfo sec: ${e.message}")
        }
    }

    /**
     * Event-driven "the glasses joined our P2P group": a client connecting fires
     * `WIFI_P2P_CONNECTION_CHANGED_ACTION`; the group's client list is also polled because the broadcast can
     * fire before the list populates and then never again. [onReady] runs exactly once, on join or after
     * [timeoutMs] so the flow never hangs.
     */
    @RequiresPermission(Manifest.permission.NEARBY_WIFI_DEVICES)
    fun awaitClientJoin(timeoutMs: Long, onReady: () -> Unit) {
        joinFired = false
        val fireOnce = {
            if (!joinFired) {
                joinFired = true
                unregisterReceiver()
                onReady()
            }
        }
        receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                if (i.action == WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION) checkClients(fireOnce)
            }
        }.also {
            ContextCompat.registerReceiver(
                appContext, it, IntentFilter(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }
        val poll = object : Runnable {
            override fun run() {
                if (joinFired) return
                checkClients(fireOnce)
                main.postDelayed(this, 1500)
            }
        }
        checkClients(fireOnce)          // a client may already be connected
        main.postDelayed(poll, 1500)
        main.postDelayed({
            if (!joinFired) {
                log("[wifi] client-join wait timed out (${timeoutMs}ms) — proceeding")
                fireOnce()
            }
        }, timeoutMs)
    }

    @RequiresPermission(Manifest.permission.NEARBY_WIFI_DEVICES)
    private fun checkClients(onJoined: () -> Unit) {
        val m = mgr ?: return
        val ch = channel ?: return
        try {
            m.requestGroupInfo(ch) { wg: WifiP2pGroup? ->
                val n = wg?.clientList?.size ?: 0
                if (n > 0) {
                    log("[wifi] glasses joined the P2P group ($n client)")
                    onJoined()
                }
            }
        } catch (_: SecurityException) {
        }
    }

    private fun unregisterReceiver() {
        receiver?.let { runCatching { appContext.unregisterReceiver(it) } }
        receiver = null
    }

    @RequiresPermission(Manifest.permission.NEARBY_WIFI_DEVICES)
    fun close() {
        unregisterReceiver()
        val m = mgr; val ch = channel
        if (m != null && ch != null) runCatching { m.removeGroup(ch, null) }
    }

    companion object {
        /** Standard WiFi-Direct group-owner address (the phone). */
        const val GO_IP = "192.168.49.1"
        /** First-client address (the glasses). */
        const val CLIENT_IP = "192.168.49.2"
    }
}

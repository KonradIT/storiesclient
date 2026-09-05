package dev.konraditurbe.storiesclient.wifi

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.util.Log

/**
 * Joins the glasses' own WiFi SoftAp (the alternative media transport to [WifiDirectHost]) so bulk media
 * can be pulled from the glasses-hosted webserver at `https://192.168.1.1/stella-webserver/…`.
 *
 * The glasses run a 5 GHz WPA2-PSK hostapd whose SSID/passphrase arrive in `StartSoftApResponse`. The AP has
 * no upstream internet, so the request drops `NET_CAPABILITY_INTERNET` and the process is bound to the network
 * once available so every socket (including HTTPS to 192.168.1.1) routes over wlan.
 */
class SoftApJoiner(context: Context) {

    private val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private var callback: ConnectivityManager.NetworkCallback? = null
    private var boundNetwork: Network? = null

    /**
     * Request and join the SoftAp, then bind this process to it. [onAvailable] fires (binder thread) with the
     * joined [Network]; [onUnavailable] if the request fails or times out. A new join tears down the previous one.
     */
    @SuppressLint("MissingPermission") // CHANGE_NETWORK_STATE + nearby-wifi/location declared in the manifest.
    fun join(ssid: String, pass: String, onAvailable: (Network) -> Unit, onUnavailable: () -> Unit) {
        require(ssid.isNotEmpty()) { "ssid is required (from StartSoftApResponse)" }
        require(pass.isNotEmpty()) { "pass is required (from StartSoftApResponse)" }
        unbind()

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(WifiNetworkSpecifier.Builder().setSsid(ssid).setWpa2Passphrase(pass).build())
            .build()

        callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.i(TAG, "SoftAp joined: $ssid -> $network")
                boundNetwork = network
                if (!cm.bindProcessToNetwork(network)) Log.w(TAG, "bindProcessToNetwork returned false; HTTP may not route to 192.168.1.1")
                onAvailable(network)
            }
            override fun onUnavailable() {
                Log.w(TAG, "SoftAp join unavailable for SSID: $ssid")
                onUnavailable()
            }
            override fun onLost(network: Network) {
                Log.w(TAG, "SoftAp lost: $network")
                if (network == boundNetwork) {
                    cm.bindProcessToNetwork(null)
                    boundNetwork = null
                }
            }
        }.also { cm.requestNetwork(request, it) }
    }

    /** Unbind the process from the SoftAp and release the network request. Safe to call repeatedly. */
    fun unbind() {
        cm.bindProcessToNetwork(null)
        boundNetwork = null
        callback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        callback = null
    }

    private companion object {
        const val TAG = "SoftApJoiner"
    }
}

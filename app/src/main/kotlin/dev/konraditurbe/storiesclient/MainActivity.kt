package dev.konraditurbe.storiesclient

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.method.ScrollingMovementMethod
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.view.WindowCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import dev.konraditurbe.storiesclient.auth.IdentityStore
import dev.konraditurbe.storiesclient.auth.PhaseB
import dev.konraditurbe.storiesclient.ble.GattTransport
import dev.konraditurbe.storiesclient.control.Capture
import dev.konraditurbe.storiesclient.control.ControlChannel
import dev.konraditurbe.storiesclient.control.DeviceInfo
import dev.konraditurbe.storiesclient.media.MediaSaver
import dev.konraditurbe.storiesclient.media.PhotoUndistort
import dev.konraditurbe.storiesclient.media.SyncedIndex
import dev.konraditurbe.storiesclient.ui.ConnectProgressBar
import dev.konraditurbe.storiesclient.ui.DetailView
import dev.konraditurbe.storiesclient.ui.GalleryAdapter
import dev.konraditurbe.storiesclient.ui.Palette
import dev.konraditurbe.storiesclient.ui.StatusPill
import dev.konraditurbe.storiesclient.ui.dp
import dev.konraditurbe.storiesclient.wifi.WifiDirectHost
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

/**
 * Stories Client: standalone Ray-Ban Stories session driver + media gallery.
 *
 * bonded "RB Stories" -> GATT/DataX link -> Phase-B RSA handshake -> session (sharedKey + auth_token)
 * -> control channel (get_capture_info) -> squircle grid. The status pill shows device name, battery,
 * connection + firmware; the Connect button doubles as Disconnect while connected.
 */
class MainActivity : AppCompatActivity(), DetailView.Actions {

    /**
     * The connection lifecycle as an explicit state machine. [enterState] is the single place that maps a state
     * onto the button / pill / progress bar / status caption.
     */
    private enum class Conn(val pct: Float, val label: String) {
        DISCONNECTED(0f, "Tap Connect to list media on the glasses"),
        CONNECTING(6f, "Connecting to glasses…"),
        LINK_READY(20f, "Link ready…"),
        AUTHENTICATING(33f, "Authenticating…"),
        SESSION(45f, "Session established…"),
        LOADING_MEDIA(85f, "Loading media…"),
        READY(100f, "Ready"),
        ASLEEP(100f, "No media yet — glasses may be asleep. Take a photo to wake them, then pull to refresh ↓"),
        ERROR(0f, "Disconnected");

        /** In an active connect flow or connected (not fully down). */
        val active get() = this != DISCONNECTED && this != ERROR
        /** Session established: the control channel is usable (or coming up). */
        val connected get() = this == SESSION || this == LOADING_MEDIA || this == READY || this == ASLEEP
    }

    private lateinit var logView: TextView
    private lateinit var logPanel: View
    private lateinit var detailView: DetailView
    private lateinit var recycler: RecyclerView
    private lateinit var adapter: GalleryAdapter
    private lateinit var swipe: SwipeRefreshLayout
    private lateinit var status: TextView
    private lateinit var pill: StatusPill
    private lateinit var connectBtn: Button
    private lateinit var loadKeysBtn: Button
    private lateinit var progressBar: ConnectProgressBar

    private val main = Handler(Looper.getMainLooper())

    // touched from UI, PhaseB worker, and BLE binder-callback threads
    @Volatile private var link: GattTransport? = null
    @Volatile private var wifiHost: WifiDirectHost? = null
    @Volatile private var authToken: String? = null
    @Volatile private var cc: ControlChannel? = null
    @Volatile private var connState = Conn.DISCONNECTED

    private var connectPct = 0f              // forward-only guard for the bar (UI thread)
    private var connectBarDone = false       // one-shot: the bar finishes once per connect (UI thread)
    private var deviceName: String? = null

    @Volatile private var downloading = false   // one full-res download at a time
    private var downloadingId: String? = null   // captureId of the in-flight download
    @Volatile private var dlProgress = 0f       // last reported download fraction (for detail re-entry)
    private var webStarted = false              // WiFi-Direct + webserver brought up once per session

    // device-info pill data
    private var fwVersion: String? = null
    private var glassesBatteryPct = -1
    private var pillRequested = false

    private val synced by lazy { SyncedIndex(this) }

    /** SAF document picker for the glasses identity JSON. */
    private val pickIdentity = registerForActivityResult(ActivityResultContracts.OpenDocument(), ::onIdentityPicked)

    // ------------------------------------------------------------------------------------------ setup

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ensureAllFilesAccess()

        val pad = dp(14f)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Palette.BG_CREAM)
            setPadding(pad, pad, pad, pad)
        }

        // status pill in a clip-disabled wrapper so its elevation shadow renders
        pill = StatusPill(this).apply {
            settingsListener = object : StatusPill.SettingsListener {
                override fun onVideoDurationPick(ms: Int) = writeSetting(ControlChannel.SETTING_VIDEO_DURATION_MS, ms, "video=${ms / 1000}s")
                override fun onSystemSoundsPick(level: Int) = writeSetting(ControlChannel.SETTING_USER_EARCON_VOLUME, level, "sounds=$level")
            }
            setOnLongClickListener { toggleLogDrawer(); true }   // long-press reveals the debug log
        }
        val sh = dp(10f)
        root.addView(FrameLayout(this).apply {
            clipChildren = false; clipToPadding = false
            setPadding(sh, sh / 2, sh, sh)
            addView(pill, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(6f) })

        connectBtn = Button(this).apply {
            isAllCaps = false
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setOnClickListener { onConnectButton() }
        }
        root.addView(connectBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        loadKeysBtn = Button(this).apply {
            isAllCaps = false
            text = "Load keys…"
            setTextColor(Palette.TEXT_MUTED)
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { pickIdentity.launch(arrayOf("application/json", "text/*", "*/*")) }
        }
        root.addView(loadKeysBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        progressBar = ConnectProgressBar(this).apply { visibility = View.GONE }
        root.addView(progressBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8f) })

        status = TextView(this).apply {
            text = Conn.DISCONNECTED.label
            setTextColor(Palette.TEXT_MUTED)
            setPadding(0, pad / 2, 0, pad / 2)
        }
        root.addView(status)

        // gallery: 3-column lazy grid + pull-to-refresh
        adapter = GalleryAdapter(isSynced = ::isSynced, onCellClick = ::openDetail)
        recycler = RecyclerView(this).apply {
            layoutManager = GridLayoutManager(this@MainActivity, 3)
            addItemDecoration(GalleryAdapter.GridSpacing(3, dp(10f)))
            clipToPadding = false
            adapter = this@MainActivity.adapter
        }
        swipe = SwipeRefreshLayout(this).apply {
            addView(recycler)
            setOnRefreshListener {
                val ch = cc
                if (ch == null || !connState.connected) { isRefreshing = false; return@setOnRefreshListener }
                thread { synced.refresh(::log) }
                ch.requestCaptureList()
            }
        }
        root.addView(swipe, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // overlay stack: [gallery root] under [hidden debug drawer] under [detail overlay]
        detailView = DetailView(this, this)
        val fill = { FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT) }
        setContentView(FrameLayout(this).apply {
            addView(root, fill())
            addView(buildLogDrawer(), fill())
            addView(detailView, fill())
        })
        updateSystemBars()

        enterState(Conn.DISCONNECTED)
        prefillDeviceName()
        refreshIdentityUi()
        log("Stories Client 1.0 — gallery + detail")
        requestPerms()
    }

    /** Show the "Load keys" button (and a hint) only while no owner identity is imported. */
    private fun refreshIdentityUi() {
        val configured = IdentityStore.isConfigured(this)
        loadKeysBtn.visibility = if (configured) View.GONE else View.VISIBLE
        if (!configured) status.text = "No glasses identity loaded — tap “Load keys…”"
    }

    /** SAF callback: parse the glasses identity JSON, persist it, refresh the UI. */
    private fun onIdentityPicked(uri: Uri?) {
        if (uri == null) return   // cancelled
        try {
            val raw = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: throw IllegalStateException("cannot open file")
            val j = JSONObject(String(raw, Charsets.UTF_8))
            val rsa = j.optString("app_rsa_priv_pkcs8_b64", "")
            val ticket = j.optString("bootstrap_ticket_hex", "")
            val serial = j.optString("serial", "")
            val label = j.optString("device_label", "")
            IdentityStore.save(this, rsa, ticket, serial, label)   // validates + clears the stale resume ticket
            val who = label.ifEmpty { serial.ifEmpty { "identity" } }
            log("[id] loaded $who (RSA ${rsa.length} b64 chars, ticket set, resume-ticket cleared)")
            toast("Identity loaded: $who")
            refreshIdentityUi()
        } catch (e: Exception) {
            log("[id] load failed: $e")
            toast("Couldn't load identity: ${e.message}")
        }
    }

    /** The hidden debug drawer holding the scrolling log (revealed by long-pressing the pill). */
    private fun buildLogDrawer(): View {
        val p = dp(14f)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Palette.SCRIM)
            setPadding(p, p, p, p)
            visibility = View.GONE
            isClickable = true   // swallow taps so they don't reach the gallery
        }
        panel.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(TextView(this@MainActivity).apply {
                text = "Debug log"; setTextColor(Palette.TXT_LIGHT); textSize = 15f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(TextView(this@MainActivity).apply {
                text = "✕  Close"; setTextColor(Palette.CYAN); textSize = 15f
                setPadding(dp(10f), dp(6f), 0, dp(6f))
                setOnClickListener { panel.visibility = View.GONE; updateSystemBars() }
            })
        })
        logView = TextView(this).apply {
            movementMethod = ScrollingMovementMethod()
            setTextIsSelectable(true)
            textSize = 10f
            setTextColor(0xFFB9B3A9.toInt())
        }
        panel.addView(ScrollView(this).apply { addView(logView) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        logPanel = panel
        return panel
    }

    private fun toggleLogDrawer() {
        logPanel.visibility = if (logPanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        updateSystemBars()
    }

    /** Match the OS status + nav bars to the surface on top: cream gallery vs a dark detail/log overlay. */
    private fun updateSystemBars() {
        val dark = detailView.visibility == View.VISIBLE || logPanel.visibility == View.VISIBLE
        val color = if (dark) Palette.DARK_BG else Palette.BG_CREAM
        window.statusBarColor = color
        window.navigationBarColor = color
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
    }

    // ---------------------------------------------------------------------------- runtime permissions

    private var afterPermsGranted: (() -> Unit)? = null
    private var pendingPerms: Array<String>? = null

    /** Bluetooth perms needed at runtime: API 31+ only (legacy install-time perms cover API <= 30). */
    private fun btPerms(): Array<String> =
        if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN) else emptyArray()

    /** Wi-Fi-Direct perms for full-res download: NEARBY_WIFI_DEVICES on 33+, FINE_LOCATION before. */
    private fun wifiPerms(): Array<String> =
        if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES) else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    private fun hasPerms(perms: Array<String>) =
        perms.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }

    /** Ensure [perms] are granted (showing [rationale] on a re-ask); run [onGranted] when they are. */
    private fun ensurePerms(perms: Array<String>, rationale: String, onGranted: (() -> Unit)?) {
        if (hasPerms(perms)) { onGranted?.invoke(); return }
        afterPermsGranted = onGranted
        pendingPerms = perms
        if (perms.any { ActivityCompat.shouldShowRequestPermissionRationale(this, it) }) {
            AlertDialog.Builder(this)
                .setTitle("Permission needed")
                .setMessage(rationale)
                .setPositiveButton("Continue") { _, _ -> ActivityCompat.requestPermissions(this, perms, REQ_PERMS) }
                .setNegativeButton("Not now") { _, _ -> afterPermsGranted = null; pendingPerms = null }
                .show()
        } else {
            ActivityCompat.requestPermissions(this, perms, REQ_PERMS)
        }
    }

    /** Up-front request on launch (Bluetooth only; Wi-Fi is asked in context at first download). */
    private fun requestPerms() { if (!hasPerms(btPerms())) ensurePerms(btPerms(), RATIONALE_BT, null) }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_PERMS) return
        val requested = pendingPerms; pendingPerms = null
        val act = afterPermsGranted; afterPermsGranted = null
        if (requested != null && hasPerms(requested)) {
            prefillDeviceName()
            act?.invoke()
            return
        }
        val permanentlyDenied = requested?.any {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED &&
                !ActivityCompat.shouldShowRequestPermissionRationale(this, it)
        } ?: false
        if (permanentlyDenied) {
            AlertDialog.Builder(this)
                .setTitle("Permission required")
                .setMessage("This permission was denied. Enable it in Settings → Permissions to continue.")
                .setPositiveButton("Open Settings") { _, _ ->
                    runCatching { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }
                }
                .setNegativeButton("Cancel", null)
                .show()
        } else {
            toast("Permission declined")
        }
    }

    /**
     * Scoped storage forbids non-media files (the .bin IMU sidecar) in DCIM via MediaStore. Request All-Files-Access
     * once so the sidecar can land next to the MP4; if declined it still saves, just under Download.
     */
    private fun ensureAllFilesAccess() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()) return
        try {
            toast("Grant All files access so the .bin gyro file saves into ${MediaSaver.LOCATION_LABEL}")
            startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
        } catch (e: Exception) {
            runCatching { startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
        }
    }

    // -------------------------------------------------------------------------- connection lifecycle

    private fun onConnectButton() {
        when {
            connState.connected -> disconnect()
            !connState.active -> onConnect()   // DISCONNECTED or ERROR: (re)connect
            // mid-connect: button disabled, ignore
        }
    }

    /**
     * The single state -> UI mapping. Sets [connState] synchronously (so guards see it at once) and updates the
     * button, pill, progress bar and status caption on the UI thread. [detail] overrides the default caption.
     */
    private fun enterState(s: Conn, detail: String? = null) {
        connState = s
        val label = detail ?: s.label
        runOnUiThread {
            if (connState != s) return@runOnUiThread   // a newer transition superseded this post
            when (s) {
                Conn.DISCONNECTED, Conn.ERROR -> {
                    connectBtn.text = "Connect"; connectBtn.isEnabled = true; pill.setDisconnected()
                    connectPct = 0f; connectBarDone = false
                    progressBar.setProgressImmediate(0f); progressBar.visibility = View.GONE
                    status.text = label
                }
                Conn.CONNECTING, Conn.LINK_READY, Conn.AUTHENTICATING -> {
                    connectBtn.text = "Connecting…"; connectBtn.isEnabled = false; pill.setConnecting()
                    progressBar.visibility = View.VISIBLE
                    setConnectProgress(s.pct, label)
                }
                Conn.SESSION, Conn.LOADING_MEDIA -> {
                    connectBtn.text = "Disconnect"; connectBtn.isEnabled = true; pill.setConnected()
                    progressBar.visibility = View.VISIBLE
                    setConnectProgress(s.pct, label)
                }
                Conn.READY, Conn.ASLEEP -> {
                    connectBtn.text = "Disconnect"; connectBtn.isEnabled = true; pill.setConnected()
                    setConnectProgress(100f, label)   // completes + auto-hides the bar
                }
            }
            styleConnectButton()
        }
    }

    private fun styleConnectButton() {
        val (bg, fg) = when {
            connState.connected -> 0xFFE7E0D6.toInt() to Palette.TEXT_DARK      // light "Disconnect"
            connState.active -> Palette.DOT_GRAY to Palette.TEXT_MUTED           // muted "Connecting…"
            else -> Palette.TEXT_DARK to Color.WHITE                             // dark "Connect"
        }
        connectBtn.background = GradientDrawable().apply { setColor(bg); cornerRadius = dp(16f).toFloat() }
        connectBtn.setTextColor(fg)
    }

    /**
     * Drive the connection progress bar + caption. Forward-only and gated on the live connection so late/stale
     * phase callbacks can't move it backward or revive it after a disconnect. Safe from any thread.
     */
    private fun setConnectProgress(pct: Float, label: String?) {
        runOnUiThread {
            if (!connState.active) return@runOnUiThread
            // One-shot: once complete, later get_capture_info round-trips (refresh, notify) use the swipe spinner.
            if (connectBarDone || pct < connectPct) { label?.let { status.text = it }; return@runOnUiThread }
            connectPct = pct
            progressBar.visibility = View.VISIBLE
            progressBar.setProgress(pct / 100f)
            label?.let { status.text = it }
            if (pct >= 100f) {
                connectBarDone = true
                main.postDelayed({ if (connState.connected) progressBar.visibility = View.GONE }, 800)
            }
        }
    }

    /** All bonded devices whose name matches "RB Stories" (gen-1 only), sorted by MAC for a stable order. */
    @SuppressLint("MissingPermission")
    private fun findAllGlasses(): List<BluetoothDevice> {
        val adapter = (getSystemService(BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter ?: return emptyList()
        return try {
            adapter.bondedDevices.filter { d -> d.name?.let(STORIES::matches) == true }.sortedBy { it.address }
        } catch (e: SecurityException) {
            emptyList()
        }
    }

    /** Show the bonded device's name in the pill before connecting (if BT permission is already granted). */
    @SuppressLint("MissingPermission")
    private fun prefillDeviceName() {
        if (!hasPerms(btPerms())) return
        findAllGlasses().firstOrNull()?.let { deviceName = it.name; pill.setDeviceName(deviceName) }
    }

    @SuppressLint("MissingPermission")
    private fun onConnect() {
        if (!IdentityStore.isConfigured(this)) {
            log("[!] no glasses identity loaded — tap “Load keys…” first")
            toast("Load your glasses identity first.")
            refreshIdentityUi()
            return
        }
        if (!hasPerms(btPerms())) { ensurePerms(btPerms(), RATIONALE_BT, ::onConnect); return }
        val adapter = (getSystemService(BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        if (adapter == null || !adapter.isEnabled) {   // BT off looks like "no pair" (empty bonded set): disambiguate
            log("[!] Bluetooth is off — enable it to connect")
            toast("Bluetooth is off.\nTurn it on to connect.")
            return
        }
        val glasses = findAllGlasses()
        when {
            glasses.isEmpty() -> {
                log("[!] no bonded 'RB Stories' device — pair it in the Meta app first")
                toast("No Ray-Ban Stories paired.\nPair it in the Meta app first.")
            }
            glasses.size > 1 -> promptGlassesChoice(glasses)
            else -> connectTo(glasses[0])
        }
    }

    /** More than one bonded "RB Stories": let the user pick, with the last-used pair floated to the top. */
    @SuppressLint("MissingPermission")
    private fun promptGlassesChoice(found: List<BluetoothDevice>) {
        val lastMac = getPreferences(MODE_PRIVATE).getString(PREF_LAST_MAC, null)
        val devs = found.sortedByDescending { it.address == lastMac }
        val labels = devs.map { d ->
            val tail = d.address.takeLast(5)
            (d.name ?: "RB Stories") + "  ·  " + tail + if (d.address == lastMac) "   (last used)" else ""
        }.toTypedArray<CharSequence>()
        AlertDialog.Builder(this)
            .setTitle("${devs.size} Ray-Ban Stories paired — choose one")
            .setItems(labels) { _, which -> connectTo(devs[which]) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Connect to one specific bonded glasses device. */
    @SuppressLint("MissingPermission")
    private fun connectTo(glasses: BluetoothDevice) {
        getPreferences(MODE_PRIVATE).edit { putString(PREF_LAST_MAC, glasses.address) }
        deviceName = glasses.name
        pill.setDeviceName(deviceName)
        enterState(Conn.CONNECTING)

        link?.let { runCatching { it.close() } }
        link = null
        log("[ble] connecting to ${glasses.name} ...")

        // Per-connection identity: callbacks from a stale transport (after a reconnect/teardown) are ignored.
        lateinit var transport: GattTransport
        transport = GattTransport.connect(this, glasses, object : GattTransport.ConnectCallback {
            fun stale() = link !== transport

            override fun onReady(link: GattTransport) {
                if (stale()) return
                // A short fixed settle lets BLE stabilize post service-discovery before Phase-B (~1.7 s observed
                // link bring-up + margin).
                log("[ble] DataX link ready — settling briefly, then Phase-B")
                enterState(Conn.LINK_READY)
                main.postDelayed({ if (!stale() && connState.active) runPhaseB(link, ::stale) }, 2500)
            }

            override fun onError(status: Int, message: String) {
                if (stale()) return
                log("[ble] connect error $status: $message")
                handleDisconnect("connect error $status", "Connection failed")
            }

            override fun onDisconnected(status: Int, message: String) {
                if (!stale()) handleDisconnect(message)
            }
        })
        link = transport
    }

    private fun runPhaseB(t: GattTransport, stale: () -> Boolean) {
        try {
            enterState(Conn.AUTHENTICATING)
            PhaseB.fromStore(this, t, ::log).run(object : PhaseB.Callback {
                override fun onSession(r: PhaseB.Result) {
                    if (stale() || !connState.active) { log("[ble] stale/late session ignored"); return }
                    log("\n===== SESSION ESTABLISHED =====")
                    log("auth_token: ${r.authToken}")
                    log("expiry:     ${r.expirationTimeSec}")
                    authToken = r.authToken
                    enterState(Conn.SESSION)
                    // If no capture list arrives soon the SOC is likely asleep (battery still works via the MCU).
                    main.postDelayed({
                        if (!stale() && connState.connected && adapter.itemCount == 0) enterState(Conn.ASLEEP)
                    }, 12000)
                    try {
                        cc = ControlChannel(t, r.crypto, this@MainActivity, ::log).also {
                            it.listener = controlListener
                            adapter.thumbSource = thumbSource(it)
                            it.start()
                        }
                    } catch (e: Exception) {
                        log("[ctrl] init error: $e")
                        handleDisconnect("ctrl init error", "Control channel error")
                    }
                }

                override fun onError(stage: String, t: Throwable) {
                    log("[phaseB] FAILED @$stage: $t")
                    if (!stale()) handleDisconnect("phaseB failed @$stage", "Handshake failed @$stage")
                }
            })
        } catch (e: Exception) {
            log("[phaseB] init error: $e")
            if (!stale()) handleDisconnect("phaseB init error", "Handshake error")
        }
    }

    private val controlListener = object : ControlChannel.Listener {
        override fun onProgress(stage: String, done: Int, total: Int) {
            when (stage) {
                "register" -> setConnectProgress(45f + 33f * done / maxOf(1, total), "Registering services… ($done/$total)")
                "capture_request" -> enterState(Conn.LOADING_MEDIA)
            }
        }

        override fun onCaptures(captures: List<Capture>) {
            enterState(Conn.READY)          // media list loaded = done (completes the bar)
            synced.refresh(::log)           // ctrl thread: off-UI MediaStore read
            runOnUiThread { renderGallery(captures) }
            if (!pillRequested) {           // channel settled: safe to query device info
                pillRequested = true
                main.postDelayed({ cc?.apply { requestSystemInfo(); requestDeviceState(); requestSettings() } }, 1500)
            }
        }

        override fun onNotify(info: String) {
            log("[notify] new media — refreshing")
            cc?.requestCaptureList()
        }

        override fun onDeviceInfo(d: DeviceInfo) {
            runOnUiThread {
                d.socBuildInfo?.let { fwVersion = fwBuild(it) }
                // prefer the human-readable cloud-OTA version string when the device reports one
                d.lastOtaVersion?.takeIf { it.isNotEmpty() }?.let { fwVersion = it }
                if (d.glassesBatteryPct >= 0) glassesBatteryPct = d.glassesBatteryPct
                if (glassesBatteryPct >= 0) pill.setBattery(glassesBatteryPct, d.isCharging)
                fwVersion?.let(pill::setFw)
                pill.setCase(d.caseBatteryPct)
                pill.setStorage(d.lowStorage, d.zeroStorage)
                if (d.videoDurationMs >= 0) pill.setVideoDuration(d.videoDurationMs)
                if (d.earconVolume >= 0) pill.setSystemSounds(d.earconVolume)
            }
        }
    }

    /** BLE thumbnail provider for the gallery, bound to one session. */
    private fun thumbSource(session: ControlChannel) = GalleryAdapter.ThumbSource { capture, onBitmap ->
        val thumbId = capture.thumbnailAssetId ?: return@ThumbSource onBitmap(null)
        session.fetchAssetBytes(thumbId) { r ->
            onBitmap(r.getOrNull()?.let { BitmapFactory.decodeByteArray(it, 0, it.size) })
        }
    }

    /** Write an MCU setting from a pill radio pick, then read it back to confirm (or revert the toggle). */
    private fun writeSetting(settingEnum: Int, value: Int, desc: String) {
        val ch = cc ?: run { log("[settings] not connected — ignoring $desc"); return }
        log("[settings] set $desc")
        ch.writeSetting(settingEnum, value.toLong(), object : ControlChannel.SettingCallback {
            override fun onValue(settingEnum: Int, value: Long, result: Int) {
                log("[settings] $desc ${if (result == 0) "OK" else "FAILED result=$result"}; reading back")
                ch.readSetting(settingEnum, null)
            }
            override fun onError(msg: String) {
                log("[settings] $desc error: $msg; reading back")
                ch.readSetting(settingEnum, null)
            }
        })
    }

    /** Unexpected drop / setup failure. Idempotent once already down. Shows [caption] in the ERROR state. */
    private fun handleDisconnect(why: String, caption: String = "Disconnected") {
        if (!connState.active) return
        log("[ble] $why")
        enterState(Conn.ERROR, caption)   // set the guard first so the reentrant onDisconnected is ignored
        teardown()
    }

    /** User-initiated disconnect. */
    private fun disconnect() {
        log("[ble] disconnecting (user)…")
        enterState(Conn.DISCONNECTED)
        teardown()
    }

    @SuppressLint("MissingPermission")
    private fun teardown() {
        runCatching { cc?.close() }
        runCatching { link?.close() }
        runCatching { wifiHost?.close() }
        cc = null; link = null; wifiHost = null
        adapter.thumbSource = null
        authToken = null; fwVersion = null; glassesBatteryPct = -1
        webStarted = false; downloading = false; pillRequested = false
    }

    override fun onDestroy() {
        if (connState.active) connState = Conn.DISCONNECTED
        teardown()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            detailView.visibility == View.VISIBLE -> closeDetail()
            logPanel.visibility == View.VISIBLE -> { logPanel.visibility = View.GONE; updateSystemBars() }
            else -> @Suppress("DEPRECATION") super.onBackPressed()
        }
    }

    fun log(s: String) {
        Log.i(TAG, s)
        runOnUiThread { logView.append(s + "\n") }
    }

    // ------------------------------------------------------------------------------------------ gallery

    private fun renderGallery(caps: List<Capture>) {
        swipe.isRefreshing = false
        adapter.setItems(caps)
        if (caps.isEmpty()) {
            status.text = "No media — if you have captures, the glasses may be asleep; take a photo to wake them, then pull to refresh ↓"
            return
        }
        val videos = caps.count { it.isVideo }
        status.text = "${caps.size} captures (${caps.size - videos} photo, $videos video)"
    }

    private fun isSynced(c: Capture) = synced.contains(c.key8)

    /** Reveal the full-screen detail overlay for a capture. */
    private fun openDetail(c: Capture) {
        val isSynced = isSynced(c)
        detailView.show(c, DetailView.Presentation(
            thumb = adapter.cachedThumb(c.captureId),
            savedUri = if (isSynced) synced[c.key8] else null,
            synced = isSynced,
            downloadable = c.fullFrames.isNotEmpty(),
            canDelete = connState.connected && cc != null,
            hasBurst = c.extraRightFrames().isNotEmpty(),
            hasLeft = c.stereoAssetId != null,
            bracket = c.isBracket,
        ))
        // keep the live progress if this capture is downloading right now
        if (downloading && c.captureId == downloadingId) detailView.setDownloadProgress(dlProgress)
        updateSystemBars()
    }

    private fun closeDetail() { detailView.hide(); updateSystemBars() }

    // ---- DetailView.Actions ----

    override fun onClose() = closeDetail()
    override fun onOpen(c: Capture) = openMedia(c)

    override fun onDownload(c: Capture) {
        val mode = detailView.selectedMode
        if (mode == DetailView.Mode.MAIN && isSynced(c)) { openMedia(c); return }   // main already saved: view it
        if (!connState.connected || cc == null) { toast("connect to download"); return }
        if (downloading) { toast("a download is already running"); return }
        if (c.fullFrames.isEmpty()) { toast("no full-res available (already imported?)"); return }
        // Ask for Nearby Wi-Fi here, in context, before the single-flight flag so a denial can't wedge it.
        if (!hasPerms(wifiPerms())) { ensurePerms(wifiPerms(), RATIONALE_WIFI) { onDownload(c) }; return }
        startDownload(c, mode)
    }

    override fun onDelete(c: Capture) {
        if (!connState.connected || cc == null) { toast("connect to delete"); return }
        promptDelete(c)
    }

    /** Fisheye -> rectilinear the saved main photo, writing `stories_<cid8>_rect.jpg` next to it. */
    override fun onUndistort(c: Capture) {
        val src = synced[c.key8] ?: run { toast("save the photo first (0 · Download)"); return }
        toast("Undistorting…"); status.text = "Undistorting photo…"
        val fname = MediaSaver.fileName(c.captureId, "_rect", "jpg")
        thread(name = "undistort") {
            val msg = try {
                val bmp = contentResolver.openInputStream(src)?.use { BitmapFactory.decodeStream(it) }
                when {
                    bmp == null -> "couldn't read the saved photo"
                    !PhotoUndistort.supports(bmp) -> "not a ${PhotoUndistort.SRC_W}×${PhotoUndistort.SRC_H} photo (${bmp.width}×${bmp.height})".also { bmp.recycle() }
                    else -> {
                        val rect = PhotoUndistort.undistort(bmp); bmp.recycle()
                        val bos = ByteArrayOutputStream()
                        rect.compress(Bitmap.CompressFormat.JPEG, 95, bos); rect.recycle()
                        if (MediaSaver.insertMedia(this, fname, "image/jpeg", bos.toByteArray()) != null) "Saved ✓ $fname" else "save failed"
                    }
                }
            } catch (e: Throwable) {
                "undistort failed: $e"
            }
            runOnUiThread { toast(msg); status.text = msg }
        }
    }

    // ------------------------------------------------------------------------------------- downloads

    /** One frame to fetch: assetId + the filename suffix it saves under (shared `stories_<cid8>` prefix). */
    private class DlFrame(val assetId: String, val suffix: String)

    /** The frame(s) a mode downloads: MAIN/video = the primary asset; BURST = the extra right frames; LEFT = stereo. */
    private fun framesForMode(c: Capture, mode: DetailView.Mode): List<DlFrame> {
        if (c.isVideo) return listOfNotNull(c.mainAssetId?.let { DlFrame(it, "") })
        return when (mode) {
            DetailView.Mode.BURST -> c.extraRightFrames().map { (id, suffix) -> DlFrame(id, suffix) }
            DetailView.Mode.LEFT -> listOfNotNull(c.stereoAssetId?.let { DlFrame(it, "_left") })
            DetailView.Mode.MAIN -> listOfNotNull(c.mainAssetId?.let { DlFrame(it, "") })
        }
    }

    private fun modeLabel(mode: DetailView.Mode, c: Capture) = when (mode) {
        DetailView.Mode.BURST -> if (c.isBracket) "exposures" else "burst frames"
        DetailView.Mode.LEFT -> "left lens"
        DetailView.Mode.MAIN -> "photo"
    }

    /** Clear the single-flight download flag and let the paused thumbnail worker resume. */
    private fun endDownload() {
        downloading = false
        cc?.pauseAssets(false)
    }

    /** Progress + completion sink for a download, updating the detail overlay only while it shows [captureId]. */
    private inner class DetailTarget(private val captureId: String) {
        fun progress(p: Float) { if (detailView.isShowing(captureId)) detailView.setDownloadProgress(p) }
        /** The MAIN frame is saved: flip to the synced presentation. */
        fun complete(u: Uri?) { if (detailView.isShowing(captureId)) detailView.markSynced(u) }
        /** Finished without the main (variants only) or failed: just clear the progress affordance. */
        fun done() { if (detailView.isShowing(captureId)) detailView.hideProgress() }
    }

    private fun startDownload(c: Capture, mode: DetailView.Mode) {
        val session = cc ?: run { toast("not connected"); return }
        val frames = framesForMode(c, mode)
        if (frames.isEmpty()) { toast("nothing to download for this option"); return }
        val target = DetailTarget(c.captureId)
        downloading = true
        session.pauseAssets(true)   // hold BLE thumbnail fetches while WiFi-Direct owns the radio
        downloadingId = c.captureId
        dlProgress = 0f
        status.text = "Downloading " + (if (c.isVideo) "video" else modeLabel(mode, c) + if (frames.size > 1) " (${frames.size})" else "") + "…"
        target.progress(0f)
        ensureWebserver(
            onReady = { downloadFrame(session, c, authToken, target, frames, 0) },
            onFail = { endDownload(); runOnUiThread { target.done() } },
        )
    }

    /**
     * Bring up WiFi-Direct + webserver once; run [onReady] when GETs can be issued (immediately if already up),
     * or [onFail] if the group can't be created so the caller can clear its in-flight state.
     */
    @SuppressLint("MissingPermission")
    private fun ensureWebserver(onReady: () -> Unit, onFail: () -> Unit) {
        if (webStarted) { onReady(); return }
        val session = cc ?: run { onFail(); return }
        webStarted = true
        val host = WifiDirectHost(this, ::log)
        wifiHost = host
        host.createGroup(object : WifiDirectHost.Callback {
            override fun onGroupReady(g: WifiDirectHost.Group) {
                if (cc !== session) return
                // Listen for the glasses joining BEFORE stationmode_connect goes out so the broadcast isn't missed.
                val joined = CountDownLatch(1)
                host.awaitClientJoin(12000) { joined.countDown() }
                session.prepareWebserver(g.ssid, g.passphrase, g.frequency, joined,
                    onReady = { runOnUiThread(onReady) },
                    onFail = { webStarted = false; onFail() })   // a BLE send threw mid-setup: allow a retry
            }

            override fun onError(message: String) {
                webStarted = false
                runOnUiThread { status.text = "WiFi error: $message" }
                onFail()
            }
        })
    }

    /** Download `frames[i]`, then chain to `i+1`; after the last (+ the video gyro sidecar) finalize. */
    private fun downloadFrame(session: ControlChannel, c: Capture, token: String?, target: DetailTarget, frames: List<DlFrame>, i: Int) {
        val total = frames.size
        val fr = frames[i]
        session.downloadOne(fr.assetId, c.captureId, fr.suffix, token, object : ControlChannel.DownloadCallback {
            override fun onProgress(got: Long, total: Long) {
                if (cc !== session) return
                val frac = if (total > 0) minOf(1f, got / total.toFloat()) else 0f
                val overall = (i + frac) / frames.size
                dlProgress = overall
                runOnUiThread { if (cc === session) target.progress(overall) }
            }

            override fun onSaved(saved: MediaSaver.Saved, mime: String) {
                if (cc !== session) return
                synced.refresh(::log)
                if (i + 1 < total) { downloadFrame(session, c, token, target, frames, i + 1); return }
                val savedUri = synced[c.key8]
                if (c.isVideo && c.imuAssetId != null) {
                    // grab the gyro/IMU sidecar as <same base>.bin before clearing "downloading" / offering delete
                    runOnUiThread {
                        if (cc !== session) return@runOnUiThread
                        target.complete(savedUri); adapter.notifyDataSetChanged()
                        status.text = "Saved ✓ — fetching gyro data…"
                    }
                    session.downloadSidecar(c.imuAssetId, c.captureId, token) { r ->
                        if (cc !== session) return@downloadSidecar
                        endDownload()   // the video is safe either way
                        runOnUiThread {
                            if (cc !== session) return@runOnUiThread
                            status.text = r.fold(
                                onSuccess = { "Saved ✓ video + gyro (${it.name})" },
                                onFailure = { "Saved ✓ video (gyro failed: ${it.message})" },
                            )
                            promptDelete(c)
                        }
                    }
                    return
                }
                endDownload()
                runOnUiThread {
                    if (cc !== session) return@runOnUiThread
                    adapter.notifyDataSetChanged()
                    val plural = if (total > 1) "s" else ""
                    if (savedUri != null) {   // the MAIN frame is on the phone: safe to offer delete
                        target.complete(savedUri)
                        status.text = "Saved ✓ $total frame$plural to ${MediaSaver.LOCATION_LABEL}"
                        promptDelete(c)
                    } else {                  // variants only: no ✓, no delete offer
                        target.done()
                        status.text = "Saved $total frame$plural — main photo not on phone (pick 0 to get it)"
                    }
                }
            }

            override fun onError(msg: String) {
                if (cc !== session) return
                endDownload()
                runOnUiThread { if (cc === session) { target.done(); status.text = "Download failed: $msg" } }
            }
        })
    }

    private fun promptDelete(c: Capture) {
        val isSynced = isSynced(c)
        val msg = (if (isSynced) "Saved to your phone. " else "⚠ NOT saved to your phone yet. ") +
            "Delete the ${if (c.isVideo) "video" else "photo"} off the glasses" +
            (if (isSynced) " to free space?" else "?") + "\n\nThis is permanent."
        AlertDialog.Builder(this)
            .setTitle("Delete from glasses?")
            .setMessage(msg)
            .setNegativeButton("Keep", null)
            .setPositiveButton("Delete") { _, _ -> doDelete(c) }
            .show()
    }

    private fun doDelete(c: Capture) {
        val session = cc ?: run { toast("not connected"); return }
        status.text = "Deleting from glasses…"
        session.deleteCapture(c.captureId) { r ->
            if (cc !== session) return@deleteCapture
            runOnUiThread {
                r.fold(
                    onSuccess = { adapter.remove(c); closeDetail(); status.text = "Deleted from glasses ✓"; toast("Deleted from glasses") },
                    onFailure = { status.text = "Delete failed: ${it.message}" },
                )
            }
        }
    }

    private fun openMedia(c: Capture) {
        thread {
            val uri = synced[c.key8] ?: run { synced.refresh(::log); synced[c.key8] }
            runOnUiThread {
                if (uri == null) { toast("saved file not found"); return@runOnUiThread }
                val i = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, if (c.isVideo) "video/*" else "image/*")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                try { startActivity(Intent.createChooser(i, "Open with")) } catch (e: Exception) { toast("no app to open this media") }
            }
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    companion object {
        private const val TAG = "STORIESCLIENT"
        private val STORIES = Regex("RB Stories.*")
        private const val REQ_PERMS = 1
        /** Persisted MAC of the last glasses we connected to (floats it to the top of the multi-pair chooser). */
        private const val PREF_LAST_MAC = "last_glasses_mac"

        private const val RATIONALE_BT =
            "Stories Client uses Bluetooth to find and connect to your Ray-Ban glasses.\n\nIt never uses your location."
        private const val RATIONALE_WIFI =
            "Downloading full-resolution photos & videos uses a direct Wi-Fi link to the glasses — Android calls this " +
                "the “Nearby Wi-Fi devices” permission.\n\nIt never uses your location."

        /**
         * Glasses SoC firmware build = the "incremental" of the Android build fingerprint
         * `facebook/stella/stella:8.1.0/OPM1.171019.026/<incremental>:user/release-keys`.
         */
        private fun fwBuild(soc: String): String = try {
            val p = soc.split("/")
            p[p.size - 2].substringBefore(':')
        } catch (e: Exception) {
            soc
        }
    }
}

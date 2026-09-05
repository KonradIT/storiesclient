package dev.konraditurbe.storiesclient.ble

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresPermission
import dev.konraditurbe.storiesclient.datax.DataXCodec
import dev.konraditurbe.storiesclient.datax.Frame
import dev.konraditurbe.storiesclient.datax.Reassembler
import dev.konraditurbe.storiesclient.util.toHex
import java.util.ArrayDeque
import java.util.UUID

/**
 * BLE GATT implementation of [DataXLink] for Ray-Ban Stories ("stella") glasses.
 *
 * DataX rides a single write+notify GATT characteristic (value handle 0x0051, MTU negotiated ~247-256).
 * This class connects, discovers services, locates that characteristic, enables notifications via the CCCD,
 * writes outbound frames chunked by [DataXCodec.encodeFrame], and feeds inbound notify bytes to a
 * [Reassembler], surfacing completed [Frame]s via [onFrame].
 *
 * [DEFAULT_DATAX_CHAR] is the HW-confirmed characteristic; an explicit UUID can be passed, and a legacy
 * service scan for the first write+notify characteristic remains as a fallback.
 */
class GattTransport(context: Context, private val configuredCharUuid: UUID? = null) : BluetoothGattCallback(), DataXLink {

    /** Callback for the async [connect] API. Invoked on the Bluetooth binder thread. */
    interface ConnectCallback {
        /** Link is up: connected, services discovered, DataX char found, notifications enabled. */
        fun onReady(link: GattTransport)
        /** Setup failed (service discovery / CCCD / write stage). [status] is the GATT status. */
        fun onError(status: Int, message: String)
        /** The GATT link transitioned to DISCONNECTED (drop, remote close, or our own close()). */
        fun onDisconnected(status: Int, message: String)
    }

    private val appContext = context.applicationContext
    private val reassembler = Reassembler()

    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var dataXChar: BluetoothGattCharacteristic? = null
    @Volatile private var ready = false

    /** MTU negotiated with the glasses (ATT default 23 until negotiation completes). */
    @Volatile var mtu: Int = DEFAULT_MTU
        private set

    private var connectCallback: ConnectCallback? = null
    @Volatile override var onFrame: ((Frame) -> Unit)? = null

    /** Android allows one outstanding GATT write per connection: serialize them. */
    private val writeQueue = ArrayDeque<ByteArray>()
    private var writeInFlight = false

    // ----------------------------------------------------------------------------- async connect API

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun connect(device: BluetoothDevice, cb: ConnectCallback) {
        connectCallback = cb
        ready = false
        gatt = device.connectGatt(appContext, false, this, BluetoothDevice.TRANSPORT_LE)
        if (gatt == null) fail(BluetoothGatt.GATT_FAILURE, "connectGatt returned null")
    }

    /** Close the GATT connection and release resources. Safe to call multiple times. */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun close() {
        ready = false
        gatt?.let { g ->
            runCatching { g.disconnect() }
            runCatching { g.close() }
        }
        gatt = null
        dataXChar = null
    }

    // ------------------------------------------------------------------------------------ DataXLink

    override val isConnected: Boolean get() = ready && gatt != null && dataXChar != null

    /**
     * Chunk [frame] (`[type:1][totalSize:2 LE][payload]`) to the negotiated MTU and queue each chunk as a
     * GATT write. A buffer that does not parse as a whole frame is queued verbatim as one raw write.
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun send(frame: ByteArray) {
        if (frame.isEmpty()) return
        synchronized(writeQueue) {
            writeQueue.addAll(toChunks(frame))
            pumpWrites()
        }
    }

    private fun toChunks(frame: ByteArray): List<ByteArray> {
        if (frame.size >= DataXCodec.FRAME_HEADER_SIZE) {
            val type = frame[0].toInt() and 0xff
            val totalSize = (frame[1].toInt() and 0xff) or ((frame[2].toInt() and 0xff) shl 8)
            if (totalSize == frame.size - DataXCodec.FRAME_HEADER_SIZE) {
                return DataXCodec.encodeFrame(type, frame.copyOfRange(DataXCodec.FRAME_HEADER_SIZE, frame.size), mtu)
            }
        }
        return listOf(frame)
    }

    /** Drain the write queue one GATT write at a time. Caller holds the [writeQueue] lock. */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun pumpWrites() {
        val g = gatt ?: return
        val ch = dataXChar ?: return
        if (writeInFlight) return
        val next = writeQueue.pollFirst() ?: return
        writeInFlight = true
        writeChunk(g, ch, next)
    }

    @Suppress("DEPRECATION")
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun writeChunk(g: BluetoothGatt, ch: BluetoothGattCharacteristic, data: ByteArray) {
        // Prefer write-with-response so onCharacteristicWrite advances the queue.
        val withResponse = ch.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0
        val writeType = if (withResponse) BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        Log.i(TAG, "TX(${if (withResponse) "rsp" else "noRsp"}) ${data.toHex()}")
        try {
            val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeCharacteristic(ch, data, writeType) == BluetoothGatt.GATT_SUCCESS
            } else {
                ch.writeType = writeType
                ch.value = data
                g.writeCharacteristic(ch)
            }
            if (!ok) {
                Log.w(TAG, "writeCharacteristic rejected the write")
                onWriteFinished(false)
            }
        } catch (e: SecurityException) {
            fail(BluetoothGatt.GATT_INSUFFICIENT_AUTHORIZATION, "write SecurityException: ${e.message}")
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun onWriteFinished(ok: Boolean) {
        synchronized(writeQueue) {
            writeInFlight = false
            if (!ok) {
                writeQueue.clear()   // drop the rest of this frame's chunks rather than send a half frame
                return
            }
            pumpWrites()
        }
    }

    // -------------------------------------------------------------------------------- GATT callbacks

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
        when (newState) {
            BluetoothProfile.STATE_CONNECTED -> try {
                g.requestMtu(REQUESTED_MTU)   // discoverServices runs in onMtuChanged
            } catch (e: SecurityException) {
                fail(status, "requestMtu SecurityException: ${e.message}")
            }
            BluetoothProfile.STATE_DISCONNECTED -> {
                ready = false
                dataXChar = null
                Log.w(TAG, "disconnected (status=$status)")
                connectCallback?.onDisconnected(status, "GATT disconnected (status=$status)")
            }
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun onMtuChanged(g: BluetoothGatt, newMtu: Int, status: Int) {
        if (status == BluetoothGatt.GATT_SUCCESS && newMtu > 0) mtu = newMtu
        try {
            g.discoverServices()
        } catch (e: SecurityException) {
            fail(status, "discoverServices SecurityException: ${e.message}")
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
        if (status != BluetoothGatt.GATT_SUCCESS) {
            fail(status, "service discovery failed"); return
        }
        for (svc in g.services) for (c in svc.characteristics) {
            Log.i(TAG, "GATTDB svc=${svc.uuid} char=${c.uuid} handle=0x%04x props=0x%02x".format(c.instanceId, c.properties))
        }
        val ch = findDataXChar(g)
        if (ch == null) {
            fail(BluetoothGatt.GATT_FAILURE, "DataX characteristic not found"); return
        }
        dataXChar = ch
        enableNotifications(g, ch)
    }

    /**
     * Locate the DataX write+notify characteristic: the configured UUID if given, then [DEFAULT_DATAX_CHAR],
     * then the first write+notify characteristic of the legacy service [SERVICE_E73E0001], then any.
     */
    private fun findDataXChar(g: BluetoothGatt): BluetoothGattCharacteristic? {
        configuredCharUuid?.let { uuid -> findByUuid(g, uuid)?.let { return it } }
        findByUuid(g, DEFAULT_DATAX_CHAR)?.takeIf { it.isWriteAndNotify() }?.let { return it }
        g.getService(SERVICE_E73E0001)?.characteristics?.firstOrNull { it.isWriteAndNotify() }?.let { return it }
        return g.services.asSequence().flatMap { it.characteristics.asSequence() }.firstOrNull { it.isWriteAndNotify() }
    }

    private fun findByUuid(g: BluetoothGatt, uuid: UUID): BluetoothGattCharacteristic? =
        g.services.firstNotNullOfOrNull { it.getCharacteristic(uuid) }

    private fun BluetoothGattCharacteristic.isWriteAndNotify(): Boolean {
        val writable = properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
        val notifiable = properties and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
        return writable && notifiable
    }

    /** Enable notifications locally and write the CCCD so the glasses start notifying. */
    @Suppress("DEPRECATION")
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun enableNotifications(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
        try {
            if (!g.setCharacteristicNotification(ch, true)) {
                fail(BluetoothGatt.GATT_FAILURE, "setCharacteristicNotification failed"); return
            }
            val cccd = ch.getDescriptor(CCCD)
            if (cccd == null) {
                Log.w(TAG, "DataX char has no CCCD; proceeding without remote subscribe")
                markReady(); return
            }
            val indicate = ch.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY == 0
            val value = if (indicate) BluetoothGattDescriptor.ENABLE_INDICATION_VALUE else BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val rc = g.writeDescriptor(cccd, value)
                if (rc != BluetoothGatt.GATT_SUCCESS) fail(rc, "writeDescriptor(CCCD) returned $rc")
            } else {
                cccd.value = value
                if (!g.writeDescriptor(cccd)) fail(BluetoothGatt.GATT_FAILURE, "writeDescriptor(CCCD) returned false")
            }
            // markReady() fires from onDescriptorWrite.
        } catch (e: SecurityException) {
            fail(BluetoothGatt.GATT_INSUFFICIENT_AUTHORIZATION, "enableNotifications SecurityException: ${e.message}")
        }
    }

    override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
        if (descriptor.uuid != CCCD) return
        if (status == BluetoothGatt.GATT_SUCCESS) markReady() else fail(status, "CCCD write failed (status=$status)")
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun onCharacteristicWrite(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
        onWriteFinished(status == BluetoothGatt.GATT_SUCCESS)
    }

    /** Pre-Android 13 notify callback (value carried on the characteristic). */
    @Deprecated("Android 13+ delivers the value explicitly")
    @Suppress("DEPRECATION")
    override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
        if (ch !== dataXChar) return
        ch.value?.let(::deliverNotify)
    }

    /** Android 13+ notify callback. */
    override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) {
        if (ch !== dataXChar) return
        deliverNotify(value)
    }

    private fun deliverNotify(chunk: ByteArray) {
        Log.i(TAG, "RX ${chunk.toHex()}")
        try {
            reassembler.push(chunk)?.let { f -> onFrame?.invoke(f) }
        } catch (e: RuntimeException) {
            Log.e(TAG, "reassembly error", e)
        }
    }

    // --------------------------------------------------------------------------------------- helpers

    private fun markReady() {
        ready = true
        connectCallback?.onReady(this)
    }

    private fun fail(status: Int, message: String) {
        Log.w(TAG, "fail: $message")
        connectCallback?.onError(status, message)
    }

    companion object {
        private const val TAG = "GattTransport"

        /** Standard Client Characteristic Configuration Descriptor. */
        private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        /** ATT default MTU (20 usable) until negotiation completes. */
        private const val DEFAULT_MTU = 23

        /** MTU we request; the glasses negotiate ~247-256. */
        private const val REQUESTED_MTU = 512

        /**
         * The glasses' DataX write+notify characteristic (svc fd5f, handle 0x0051), recovered from the live
         * GATT DB and HW-confirmed end-to-end. On real glasses this is the only match.
         */
        val DEFAULT_DATAX_CHAR: UUID = UUID.fromString("24154476-0e45-11ab-6441-e8be42459478")

        /**
         * Legacy fallback custom service that may carry the DataX characteristic. (The capture's other custom
         * service `10000000-328e-…` was proven to belong to a co-connected Pebble watch, not the glasses.)
         */
        val SERVICE_E73E0001: UUID = UUID.fromString("e73e0001-ef1b-4e74-8291-2e4f3164f3b5")

        /** Connect to [device] and bring the DataX link up; [cb] fires on ready / error / disconnect. */
        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        fun connect(context: Context, device: BluetoothDevice, cb: ConnectCallback): GattTransport =
            GattTransport(context).also { it.connect(device, cb) }
    }
}

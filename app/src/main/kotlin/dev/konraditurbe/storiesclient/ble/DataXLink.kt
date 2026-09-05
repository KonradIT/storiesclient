package dev.konraditurbe.storiesclient.ble

import dev.konraditurbe.storiesclient.datax.Frame

/**
 * Transport-agnostic DataX link to the glasses. Outbound whole frames (`[type:1][totalSize:2 LE][payload]`)
 * are chunked to the negotiated MTU by the implementation; inbound bytes are reassembled and surfaced as
 * [Frame]s via [onFrame]. [GattTransport] is the BLE implementation; auth/control layers talk only to this.
 */
interface DataXLink {
    /** Send one whole DataX frame (`[type:1][totalSize:2 LE][payload]`). */
    fun send(frame: ByteArray)

    /** Callback for each fully reassembled inbound frame. Setting it replaces the previous callback. */
    var onFrame: ((Frame) -> Unit)?

    /** True once the link is connected, services discovered and notifications enabled. */
    val isConnected: Boolean
}

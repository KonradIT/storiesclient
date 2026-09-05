package dev.konraditurbe.storiesclient.datax

/**
 * A fully reassembled DataX frame from the GATT data characteristic (handle 0x0051).
 *
 * On the wire a frame is `[type:1][totalSize:2 LE][payload]`; the first BLE notify/write carries the
 * 3-byte header and continuation packets carry raw payload bytes until `totalSize` is reached. After
 * reassembly only [type] (a [MessageTypes] value) and the inner [payload] FlatBuffer remain.
 */
class Frame(type: Int, val payload: ByteArray) {
    val type: Int = type and 0xff

    override fun toString(): String = "Frame{type=0x${type.toString(16)}, len=${payload.size}}"
}

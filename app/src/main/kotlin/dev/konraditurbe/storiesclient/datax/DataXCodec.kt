package dev.konraditurbe.storiesclient.datax

/**
 * DataX-over-GATT framing (STORIES_TRANSPORT_SPEC §4):
 * ```
 *   first chunk : [type:1][totalSize:2 LE][payload slice...]
 *   continuation: [payload slice...]                          (raw, no header)
 * ```
 * Each chunk is at most `mtu` bytes; `totalSize` is the inner payload length (excludes the 3-byte header).
 */
object DataXCodec {
    /** Header carried only on the first chunk: type(1) + totalSize(2 LE). */
    const val FRAME_HEADER_SIZE = 3

    /** Max payload size encodable in the 16-bit totalSize field. */
    const val MAX_FRAME_PAYLOAD = 0xffff

    /** Build a whole (unchunked) frame `[type:1][totalSize:2 LE][payload]`. */
    fun frame(type: Int, payload: ByteArray): ByteArray {
        require(payload.size <= MAX_FRAME_PAYLOAD) { "payload too large: ${payload.size}" }
        val out = ByteArray(FRAME_HEADER_SIZE + payload.size)
        out[0] = type.toByte()
        out[1] = payload.size.toByte()
        out[2] = (payload.size ushr 8).toByte()
        payload.copyInto(out, FRAME_HEADER_SIZE)
        return out
    }

    /**
     * Split a logical frame into MTU-sized GATT chunks. The first chunk carries the header (and is emitted
     * even for an empty payload so the receiver learns totalSize).
     */
    fun encodeFrame(type: Int, payload: ByteArray, mtu: Int): List<ByteArray> {
        require(type and 0xff.inv() == 0) { "type must be 0..255: $type" }
        require(payload.size <= MAX_FRAME_PAYLOAD) { "payload too large for 16-bit totalSize: ${payload.size}" }
        require(mtu > FRAME_HEADER_SIZE) { "mtu must be > $FRAME_HEADER_SIZE: $mtu" }

        val total = payload.size
        val chunks = ArrayList<ByteArray>()
        val firstBody = minOf(total, mtu - FRAME_HEADER_SIZE)
        chunks += ByteArray(FRAME_HEADER_SIZE + firstBody).also {
            it[0] = type.toByte()
            it[1] = total.toByte()
            it[2] = (total ushr 8).toByte()
            payload.copyInto(it, FRAME_HEADER_SIZE, 0, firstBody)
        }
        var pos = firstBody
        while (pos < total) {
            val body = minOf(total - pos, mtu)
            chunks += payload.copyOfRange(pos, pos + body)
            pos += body
        }
        return chunks
    }
}

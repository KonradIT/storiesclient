package dev.konraditurbe.storiesclient.datax

/**
 * Reassembles DataX-over-GATT chunks back into [Frame]s (inverse of [DataXCodec.encodeFrame]).
 *
 * Feed every received notify value to [push]; it returns `null` while a frame is incomplete and the
 * completed [Frame] on the chunk that finishes it. Stateful, single-threaded (call from the transport
 * callback thread). One outstanding frame at a time, matching the wire.
 */
class Reassembler {
    private var inFrame = false
    private var type = -1
    private var totalSize = 0
    private var buffer = ByteArray(0)
    private var filled = 0

    /** True while a frame is partially received. */
    val isAssembling: Boolean get() = inFrame

    fun push(chunk: ByteArray?): Frame? {
        if (chunk == null || chunk.isEmpty()) return null

        var off = 0
        if (!inFrame) {
            if (chunk.size < DataXCodec.FRAME_HEADER_SIZE) return null   // leading garbage: drop
            type = chunk[0].toInt() and 0xff
            totalSize = (chunk[1].toInt() and 0xff) or ((chunk[2].toInt() and 0xff) shl 8)
            buffer = ByteArray(totalSize)
            filled = 0
            inFrame = true
            off = DataXCodec.FRAME_HEADER_SIZE
        }

        val avail = chunk.size - off
        if (avail > 0) {
            // Bytes beyond `need` would belong to a following frame; the wire never packs two frames in one
            // chunk, so the (impossible) overflow is ignored rather than mis-attributed.
            val copy = minOf(totalSize - filled, avail)
            chunk.copyInto(buffer, filled, off, off + copy)
            filled += copy
        }

        if (filled < totalSize) return null
        val f = Frame(type, buffer)
        reset()
        return f
    }

    /** Discard any in-progress frame (e.g. on transport reconnect). */
    fun reset() {
        inFrame = false; type = -1; totalSize = 0; buffer = ByteArray(0); filled = 0
    }

    override fun toString(): String =
        "Reassembler{" + (if (inFrame) "type=0x${type.toString(16)}, $filled/$totalSize" else "idle") + "}"
}

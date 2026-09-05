package dev.konraditurbe.storiesclient.util

/** Little-endian byte helpers shared by the wire codecs. */

fun ByteArray.toHex(max: Int = size): String {
    val n = minOf(size, max)
    val sb = StringBuilder(n * 2)
    for (i in 0 until n) {
        val v = this[i].toInt() and 0xff
        sb.append(Character.forDigit(v ushr 4, 16)).append(Character.forDigit(v and 0xf, 16))
    }
    return sb.toString()
}

fun String.hexToBytes(): ByteArray {
    val s = replace(" ", "")
    require(s.length % 2 == 0) { "odd hex length" }
    return ByteArray(s.length / 2) { i -> s.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
}

/** True if [need] bytes starting at [off] lie inside this array. */
fun ByteArray.inBounds(off: Int, need: Int): Boolean = off >= 0 && off + need <= size

fun ByteArray.u8(off: Int): Int = this[off].toInt() and 0xff

fun ByteArray.le16(off: Int): Int =
    if (inBounds(off, 2)) u8(off) or (u8(off + 1) shl 8) else 0

fun ByteArray.le32(off: Int): Int =
    if (inBounds(off, 4)) u8(off) or (u8(off + 1) shl 8) or (u8(off + 2) shl 16) or (u8(off + 3) shl 24) else 0

fun ByteArray.le64(off: Int): Long {
    if (!inBounds(off, 8)) return 0
    var v = 0L
    for (i in 7 downTo 0) v = (v shl 8) or (this[off + i].toLong() and 0xff)
    return v
}

fun ByteArray.putLe16(off: Int, v: Int) {
    this[off] = v.toByte(); this[off + 1] = (v shr 8).toByte()
}

fun ByteArray.putLe32(off: Int, v: Int) {
    this[off] = v.toByte(); this[off + 1] = (v shr 8).toByte()
    this[off + 2] = (v shr 16).toByte(); this[off + 3] = (v shr 24).toByte()
}

/** Index of the first occurrence of [needle] (ASCII) in this array, or -1. */
fun ByteArray.indexOfAscii(needle: String): Int {
    val n = needle.toByteArray(Charsets.US_ASCII)
    outer@ for (i in 0..size - n.size) {
        for (j in n.indices) if (this[i + j] != n[j]) continue@outer
        return i
    }
    return -1
}

/** Printable-ASCII rendering (non-printables as '.'), capped at [max] chars. */
fun ByteArray.toAscii(max: Int = 120): String {
    val sb = StringBuilder(minOf(size, max))
    for (i in 0 until minOf(size, max)) {
        val c = u8(i)
        sb.append(if (c in 32..126) c.toChar() else '.')
    }
    return sb.toString()
}

fun ByteArray.isJpeg(): Boolean = size > 3 && u8(0) == 0xff && u8(1) == 0xd8
fun ByteArray.isMp4(): Boolean = size > 8 && this[4] == 'f'.code.toByte() && this[5] == 't'.code.toByte() && this[6] == 'y'.code.toByte()

/** First 8 chars of an id for compact logs. */
fun String?.abbr(): String = this?.take(8) ?: "-"

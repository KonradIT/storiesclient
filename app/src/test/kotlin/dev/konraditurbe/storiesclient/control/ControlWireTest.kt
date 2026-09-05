package dev.konraditurbe.storiesclient.control

import dev.konraditurbe.storiesclient.util.toHex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Byte-exactness of every control-channel frame against golden vectors generated from the original Java
 * implementation (which was itself validated against the official app's captured frames). The firmware
 * rejects registration frames with any other byte layout, so these are the regression guard for the port.
 */
class ControlWireTest {
    private val id = "0123456789abcdef0123456789abcdef"

    /** `golden_wire.txt`: one `KEY hex` per line, emitted by the Java reference harness. */
    private val golden: Map<String, String> by lazy {
        javaClass.getResourceAsStream("/golden_wire.txt")!!.bufferedReader().readLines()
            .filter { it.isNotBlank() }
            .associate { line ->
                val parts = line.trim().split(" ")
                if (parts[0] == "REG") "REG ${parts[1]}" to parts[2] else parts[0] to parts[1]
            }
    }

    @Test
    fun all45RegistrationFramesMatchGolden() {
        val regs = ServiceRegistry.registrations()
        assertEquals(45, regs.size)
        for (r in regs) {
            assertEquals("registration idx ${r.idx} (${r.name})", golden.getValue("REG ${r.idx}"), r.frame.toHex())
        }
        assertEquals(regs.map { it.idx }, regs.map { it.idx }.sorted())
    }

    @Test
    fun invokeEnvelope() {
        assertEquals(golden.getValue("INVOKE"), Requests.invoke(0x49, 0x60, Requests.EMPTY_TABLE).toHex())
        assertEquals(golden.getValue("EMPTY_TABLE"), Requests.EMPTY_TABLE.toHex())
    }

    @Test
    fun getAssetContentV2() {
        assertEquals(golden.getValue("GETASSET"), Requests.getAssetContentV2(id).toHex())
    }

    @Test
    fun deleteCaptureIsByteExactAndGuarded() {
        assertEquals(golden.getValue("DELETE"), Requests.deleteCapture(id).toHex())
        assertThrows(IllegalArgumentException::class.java) { Requests.deleteCapture("short") }
        assertThrows(IllegalArgumentException::class.java) { Requests.deleteCapture("") }
    }

    @Test
    fun staModeConnect() {
        assertEquals(
            golden.getValue("STAMODE"),
            Requests.staModeConnect("DIRECT-FB-awkh", "pAssw0rd", 5180, 0xffffffee.toInt(), 0x0024c29f, 415).toHex(),
        )
    }

    @Test
    fun startWebserver() {
        assertEquals(golden.getValue("STARTWEB"), Requests.startWebserver(0x0024c29f, 415).toHex())
    }

    @Test
    fun mcuSettingReadAndWrite() {
        assertEquals(golden.getValue("MCU_READ"), Requests.mcuSetting(Requests.OP_READ, 0x8004, 0L).toHex())
        assertEquals(golden.getValue("MCU_WRITE"), Requests.mcuSetting(Requests.OP_WRITE, 0x8036, 90L).toHex())
    }
}

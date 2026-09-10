package com.spop.poverlay.sim

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SimulationParametersTest {

    /** Bytes taken from dircon-probe.py's encoder, so both ends agree. */
    private fun bytes(vararg values: Int) =
        ByteArray(values.size) { values[it].toByte() }

    @Test
    fun `decodes a positive gradient`() {
        // 11 00 00 c2 01 28 33 -- grade 4.5%, no wind, Crr 0.0040, Cw 0.51
        val parsed = SimulationParameters.parse(bytes(0x11, 0x00, 0x00, 0xC2, 0x01, 0x28, 0x33))!!

        assertEquals(0.0, parsed.windSpeedMps, 1e-9)
        assertEquals(4.5, parsed.gradePercent, 1e-9)
        assertEquals(0.0040, parsed.crr, 1e-9)
        assertEquals(0.51, parsed.cw, 1e-9)
    }

    @Test
    fun `decodes negative wind and gradient`() {
        // Both signed fields negative: a descent with a tail wind. Getting the
        // sign wrong here would read a -8% descent as a +647% climb.
        val parsed = SimulationParameters.parse(bytes(0x11, 0x3C, 0xF6, 0xC7, 0xFC, 0x21, 0x1C))!!

        assertEquals(-2.5, parsed.windSpeedMps, 1e-9)
        assertEquals(-8.25, parsed.gradePercent, 1e-9)
        assertEquals(0.0033, parsed.crr, 1e-9)
        assertEquals(0.28, parsed.cw, 1e-9)
    }

    @Test
    fun `reads the coefficients as unsigned`() {
        // Crr and Cw are uint8. A signed read turns the top half of each range
        // into a negative coefficient, which would invert the drag term.
        val parsed = SimulationParameters.parse(bytes(0x11, 0x00, 0x00, 0x00, 0x00, 0xFF, 0xFF))!!

        assertEquals(0.0255, parsed.crr, 1e-9)
        assertEquals(2.55, parsed.cw, 1e-9)
    }

    @Test
    fun `rejects a short payload`() {
        assertNull(SimulationParameters.parse(null))
        assertNull(SimulationParameters.parse(bytes(0x11)))
        // Six bytes is one short of a complete write.
        assertNull(SimulationParameters.parse(bytes(0x11, 0x00, 0x00, 0xC2, 0x01, 0x28)))
    }

    @Test
    fun `rejects trailing bytes and the wrong opcode`() {
        assertNull(SimulationParameters.parse(bytes(17, 0, 0, 0, 0, 0, 0, 0)))
        assertNull(SimulationParameters.parse(bytes(5, 0, 0, 0, 0, 0, 0)))
    }
}

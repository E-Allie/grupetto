package com.spop.poverlay.sensor.v2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Built against the offsets `send_topaz_packet` writes, so a change to either
 * side shows up here rather than as silently wrong watts.
 */
class TitanStatusPacketTest {

    @Test
    fun `parses the fields the erg path depends on`() {
        val packet = TitanStatusPacket.parse(
            titanPacket(
                firmwareMajor = 5,
                firmwareMinor = 8,
                cadence = 87,
                currentResistance = 43,
                targetResistance = 45,
                centiwatts = 15_320,
                pzafMode = 1,
                pzafSetpoint = 150,
                pzafTargetResistance = 44.5f,
                pzafStatus = PzafStatus.ENABLED_ACTIVE,
                rampUp = 50,
                rampDown = 8,
                maxResistance = 100,
                minUpdateRpm = 40
            )
        )!!

        assertEquals(5, packet.firmwareMajor)
        assertEquals(8, packet.firmwareMinor)
        assertEquals(87, packet.cadenceRpm)
        assertEquals(43, packet.currentResistance)
        assertEquals(45, packet.targetResistance)
        assertEquals(153.2f, packet.powerWatts, 0.001f)
        assertEquals(1, packet.pzafMode)
        assertEquals(150, packet.pzafSetpointWatts)
        assertEquals(44.5f, packet.pzafTargetResistance, 0.001f)
        assertEquals(PzafStatus.ENABLED_ACTIVE, packet.pzafStatus)
        assertEquals(50, packet.pzafRampUpRate)
        assertEquals(8, packet.pzafRampDownRate)
        assertEquals(100, packet.pzafMaxResistance)
        assertEquals(40, packet.pzafMinUpdateRpm)
        assertTrue(packet.pzafActive)
        assertFalse(packet.isLegacyPzafMarker)
    }

    @Test
    fun `rejects anything that is not a status record`() {
        assertNull("null", TitanStatusPacket.parse(null))
        assertNull("short", TitanStatusPacket.parse(ByteArray(128)))
        assertNull("all zero", TitanStatusPacket.parse(ByteArray(256)))

        val badMagic = titanPacket().also { it[0] = 0 }
        assertNull("bad magic", TitanStatusPacket.parse(badMagic))

        val badFooter = titanPacket().also { it[255] = 0 }
        assertNull("bad footer", TitanStatusPacket.parse(badFooter))
    }

    /**
     * Titan 1.88 and 1.94 write a fixed marker across the four bytes 5.08 uses
     * for live PZAF state. Recognising it is how a bike with no watt-target loop
     * is identified without trusting the version string's formatting.
     */
    @Test
    fun `recognises the legacy marker that stands where PZAF state goes`() {
        val legacy = titanPacket(firmwareMajor = 1, firmwareMinor = 94).also {
            it[240] = 0x00
            it[241] = 0x11
            it[242] = 0x22
            it[243] = 0x33
        }
        val packet = TitanStatusPacket.parse(legacy)!!
        assertTrue(packet.isLegacyPzafMarker)
        assertEquals(1, packet.firmwareMajor)
        assertEquals(94, packet.firmwareMinor)
    }

    /**
     * Peloton's enum names 0..6 and 20..23 but the firmware also passes 7, from
     * `pzaf_no_usage_timeout`. Nothing may switch on the named disable values.
     */
    @Test
    fun `treats every status below twenty as disabled, named or not`() {
        for (status in 0..19) {
            assertFalse("status $status", PzafStatus.isEnabled(status))
        }
        for (status in 20..23) {
            assertTrue("status $status", PzafStatus.isEnabled(status))
        }
        assertEquals("disabled by no-usage timeout", PzafStatus.name(7))
        assertTrue(PzafStatus.name(99).startsWith("unknown"))
    }

    @Test
    fun `reads unsigned bytes rather than sign extending them`() {
        val packet = TitanStatusPacket.parse(
            titanPacket(pzafStatus = 200, minUpdateRpm = 250, pzafSetpoint = 60_000)
        )!!
        assertEquals(200, packet.pzafStatus)
        assertEquals(250, packet.pzafMinUpdateRpm)
        assertEquals(60_000, packet.pzafSetpointWatts)
    }
}

/**
 * A well-formed 256-byte record, big-endian, laid out the way the firmware does.
 */
fun titanPacket(
    firmwareMajor: Int = 5,
    firmwareMinor: Int = 8,
    cadence: Int = 0,
    currentResistance: Int = 0,
    targetResistance: Int = 0,
    centiwatts: Int = 0,
    pzafMode: Int = 0,
    pzafSetpoint: Int = 15,
    pzafTargetResistance: Float = 0f,
    pzafStatus: Int = PzafStatus.DISABLED_BY_COMMAND,
    rampUp: Int = 50,
    rampDown: Int = 8,
    maxResistance: Int = 100,
    minUpdateRpm: Int = 40
): ByteArray {
    val bytes = ByteArray(TitanStatusPacket.LENGTH)
    val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
    buffer.putInt(0, 0xDEADBEEF.toInt())
    buffer.putShort(12, firmwareMajor.toShort())
    buffer.putShort(14, firmwareMinor.toShort())
    buffer.putInt(32, cadence)
    buffer.putInt(36, currentResistance)
    buffer.putInt(40, targetResistance)
    buffer.putInt(68, centiwatts)
    buffer.put(240, pzafMode.toByte())
    buffer.putShort(241, pzafSetpoint.toShort())
    buffer.putFloat(243, pzafTargetResistance)
    buffer.put(247, pzafStatus.toByte())
    buffer.put(248, rampUp.toByte())
    buffer.put(249, rampDown.toByte())
    buffer.put(250, maxResistance.toByte())
    buffer.put(251, minUpdateRpm.toByte())
    buffer.putInt(252, 0xCCDDEEFF.toInt())
    return bytes
}

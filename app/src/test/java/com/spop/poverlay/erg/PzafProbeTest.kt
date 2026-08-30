package com.spop.poverlay.erg

import com.spop.poverlay.sensor.v2.TitanControl
import com.spop.poverlay.sensor.v2.TitanStatusPacket
import com.spop.poverlay.sensor.v2.titanPacket
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The probe exists because nothing else will say no. `BikeServiceHelper` guards
 * the PZAF transactions with a platform check and returns true as soon as its own
 * range check passes, without ever hearing from the controller -- so these tests
 * are mostly about the probe refusing to be satisfied by that.
 *
 * Only paths that resolve without waiting are covered. The two timeout branches
 * would cost three and ten seconds of real time apiece and would be testing
 * `withTimeoutOrNull`.
 */
class PzafProbeTest {

    @Test
    fun `a bike with no titan controller is unsupported`() = runBlocking {
        val result = PzafProbe(null).run()
        assertTrue(result is PzafCapability.Unsupported)
        assertEquals("no Titan controller on this bike", (result as PzafCapability.Unsupported).reason)
    }

    @Test
    fun `legacy firmware is rejected on its marker, before the version`() = runBlocking {
        val control = FakeTitanControl(
            titanPacket(firmwareMajor = 1, firmwareMinor = 94).also {
                it[240] = 0x00; it[241] = 0x11; it[242] = 0x22; it[243] = 0x33
            }
        )
        val result = PzafProbe(control).run()
        assertTrue(result is PzafCapability.Unsupported)
        assertTrue((result as PzafCapability.Unsupported).reason.contains("legacy 00 11 22 33"))
        assertEquals("nothing should have been sent", 0, control.writes.size)
    }

    @Test
    fun `firmware below five is rejected without writing anything`() = runBlocking {
        val control = FakeTitanControl(titanPacket(firmwareMajor = 4, firmwareMinor = 99))
        val result = PzafProbe(control).run()
        assertTrue(result is PzafCapability.Unsupported)
        assertTrue((result as PzafCapability.Unsupported).reason.contains("PZAF needs 5.0"))
        assertEquals(0, control.writes.size)
    }

    @Test
    fun `an echoed configuration value is what makes a bike supported`() = runBlocking {
        val control = FakeTitanControl(titanPacket(firmwareMajor = 5, firmwareMinor = 8, minUpdateRpm = 30))
        val result = PzafProbe(control).run()
        assertEquals(PzafCapability.Supported("5.8"), result)
        assertEquals(listOf(PzafProbe.PROBE_RPM_PRIMARY), control.writes)
    }

    /**
     * Writing back the value already in the packet would make "the controller
     * answered" indistinguishable from "nothing happened at all".
     */
    @Test
    fun `probes with a value the controller is not already holding`() {
        assertEquals(PzafProbe.PROBE_RPM_PRIMARY, PzafProbe.probeValueFor(30))
        assertEquals(PzafProbe.PROBE_RPM_ALTERNATE, PzafProbe.probeValueFor(PzafProbe.PROBE_RPM_PRIMARY))
    }

    @Test
    fun `probes with the alternate when the primary is already set`() = runBlocking {
        val control = FakeTitanControl(
            titanPacket(firmwareMajor = 5, firmwareMinor = 8, minUpdateRpm = PzafProbe.PROBE_RPM_PRIMARY)
        )
        assertEquals(PzafCapability.Supported("5.8"), PzafProbe(control).run())
        assertEquals(listOf(PzafProbe.PROBE_RPM_ALTERNATE), control.writes)
    }

    @Test
    fun `a value the service refuses to send is not a supported bike`() = runBlocking {
        val control = FakeTitanControl(titanPacket(firmwareMajor = 5, firmwareMinor = 8), accepts = false)
        val result = PzafProbe(control).run()
        assertTrue(result is PzafCapability.Unsupported)
        assertTrue((result as PzafCapability.Unsupported).reason.contains("would not send"))
    }

    /** The transient flag is what lets the coordinator retry a startup race. */
    @Test
    fun `only a missing packet is worth retrying`() {
        assertTrue(PzafCapability.Unsupported("no valid status packet", transient = true).transient)
        assertEquals(false, PzafCapability.Unsupported("firmware 1.94").transient)
    }
}

/**
 * Echoes a write straight back into the status flow, which is what a controller
 * that implements PZAF does within a frame or two. [accepts] false is
 * AffernetService dropping the value on its own range check.
 */
private class FakeTitanControl(
    initial: ByteArray,
    private val accepts: Boolean = true
) : TitanControl {

    val writes = mutableListOf<Int>()

    private val packets = MutableSharedFlow<TitanStatusPacket>(
        replay = 1,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    private var current = initial.copyOf()

    init {
        packets.tryEmit(TitanStatusPacket.parse(current)!!)
    }

    override val status = packets

    override val latestStatus: TitanStatusPacket?
        get() = TitanStatusPacket.parse(current)

    override fun setPzafMinUpdateRpm(rpm: Int): Boolean {
        if (!accepts) return false
        writes += rpm
        current = current.copyOf().also { it[251] = rpm.toByte() }
        packets.tryEmit(TitanStatusPacket.parse(current)!!)
        return true
    }

    override fun setPowerZoneAutoFollow(watts: Int) = error("not part of the probe")
    override fun disablePowerZoneAutoFollow() = error("not part of the probe")
    override fun setPzafRampUpRate(rate: Int) = error("not part of the probe")
    override fun setPzafRampDownRate(rate: Int) = error("not part of the probe")
    override fun setPzafMaxResistance(percent: Int) = error("not part of the probe")
}

package com.spop.poverlay.erg

import com.spop.poverlay.sensor.v2.PzafStatus
import com.spop.poverlay.sensor.v2.TitanControl
import com.spop.poverlay.sensor.v2.TitanStatusPacket
import com.spop.poverlay.sensor.v2.TitanStatusReading
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer

class ErgCoordinatorTest {
    private class Native : TitanControl {
        override val status: Flow<TitanStatusPacket> = emptyFlow()
        override var latestStatusReading: TitanStatusReading? = null
        val commands = mutableListOf<String>()
        var accept = true
        override fun setPowerZoneAutoFollow(watts: Int) = command("enable:$watts")
        override fun disablePowerZoneAutoFollow() = command("disable")
        override fun setPzafRampUpRate(rate: Int) = command("up:$rate")
        override fun setPzafRampDownRate(rate: Int) = command("down:$rate")
        override fun setPzafMaxResistance(percent: Int) = command("max:$percent")
        override fun setPzafMinUpdateRpm(rpm: Int) = command("rpm:$rpm")
        private fun command(value: String): Boolean { commands += value; return accept }
        fun packet(time: Long, minRpm: Int = 30, status: Int = 0, firmware: Int = 5) {
            val bytes = ByteBuffer.allocate(256)
            bytes.putInt(0, 0xDEADBEEF.toInt()); bytes.putInt(252, 0xCCDDEEFF.toInt())
            bytes.putShort(12, firmware.toShort()); bytes.putShort(14, 8)
            bytes.put(247, status.toByte()); bytes.put(251, minRpm.toByte())
            latestStatusReading = TitanStatusReading(TitanStatusPacket.parse(bytes.array())!!, time)
        }
    }
    private class Rig(val native: Native? = Native()) {
        var now = 0L
        var failure: String? = null
        val writes = mutableListOf<Int>()
        val job = SupervisorJob()
        val erg = ErgCoordinator(native, { writes += it; true }, { now }, Any(),
            CoroutineScope(job + Dispatchers.Unconfined), { failure = it })
        init { native?.packet(now); sample() }
        fun sample() = erg.sample(100f, 80f, writes.lastOrNull() ?: 20, now)
        fun resolve(mode: ErgMode = ErgMode.Auto) {
            assertTrue(erg.enable(150, mode))
            erg.tick() // Configuration probe; never arms here.
            now += 100
            native!!.packet(now, minRpm = 40)
            sample(); erg.tick()
        }
    }

    @Test fun `auto probes round trip then selects native and only configures once`() {
        val r = Rig()
        r.resolve()
        assertTrue(r.erg.state.value.active)
        assertEquals(ErgPath.Native, r.erg.state.value.path)
        assertTrue(r.erg.state.value.nativeAvailable)
        assertEquals(listOf("disable", "rpm:40", "up:50", "down:8", "max:100", "rpm:30", "enable:150"), r.native!!.commands)
        assertTrue(r.erg.setTarget(200))
        assertEquals("enable:200", r.native.commands.last())
        assertEquals(1, r.native.commands.count { it == "up:50" })
    }

    @Test fun `late probe echo cannot rearm after stop`() {
        val r = Rig()
        r.erg.enable(150, ErgMode.Auto); r.erg.tick(); r.erg.disable()
        r.now = 100; r.native!!.packet(r.now, minRpm = 40); r.sample(); r.erg.tick()
        assertFalse(r.erg.state.value.active)
        assertTrue(r.native.commands.none { it.startsWith("enable:") })
        assertFalse(r.erg.setTarget(200))
        // A new deliberate request can probe again using the alternate setting.
        assertTrue(r.erg.enable(200, ErgMode.Auto)); r.erg.tick()
        r.now = 200; r.native.packet(r.now, minRpm = 45); r.sample(); r.erg.tick()
        assertEquals("enable:200", r.native.commands.last())
    }

    @Test fun `external meter forces software even when native was requested`() {
        val r = Rig()
        assertTrue(r.erg.enable(150, ErgMode.Native, forceHost = true))
        r.erg.tick()
        assertEquals(ErgPath.Host, r.erg.state.value.path)
        assertEquals(listOf(45), r.writes)
        assertTrue(r.native!!.commands.none { it.startsWith("enable:") || it.startsWith("rpm:") })
    }

    @Test fun `unsupported native falls back in auto but strict native fails`() {
        val auto = Rig()
        auto.native!!.packet(0, firmware = 1)
        auto.erg.enable(150, ErgMode.Auto); auto.erg.tick()
        assertEquals(ErgPath.Host, auto.erg.state.value.path)
        val strict = Rig()
        strict.native!!.packet(0, firmware = 1)
        strict.erg.enable(150, ErgMode.Native); strict.erg.tick()
        assertFalse(strict.erg.state.value.active)
        assertTrue(strict.erg.state.value.suspended)
        assertTrue(strict.failure!!.contains("unavailable"))
        assertTrue(strict.writes.isEmpty())
    }

    @Test fun `missing native controller uses software immediately`() {
        val r = Rig(null)
        assertTrue(r.erg.enable(150, ErgMode.Auto)); r.erg.tick()
        assertEquals(ErgPath.Host, r.erg.state.value.path)
    }

    @Test fun `configuration failure never sends enable and reports stand down`() {
        val r = Rig()
        r.erg.enable(150, ErgMode.Native); r.erg.tick()
        r.now = 100; r.native!!.packet(r.now, minRpm = 40)
        r.native.accept = false
        r.sample(); r.erg.tick()
        assertFalse(r.erg.state.value.active)
        assertTrue(r.native.commands.none { it.startsWith("enable:") })
        assertEquals("Native Auto Follow command failed", r.failure)
    }

    @Test fun `native knob status and stale status both stand down without rearming`() {
        val r = Rig(); r.resolve()
        r.now = 200; r.native!!.packet(r.now, status = PzafStatus.ENABLED_ACTIVE); r.erg.tick()
        r.now = 300; r.native.packet(r.now, status = PzafStatus.DISABLED_BY_KNOB); r.erg.tick()
        assertFalse(r.erg.state.value.active)
        assertTrue(r.failure!!.contains("knob"))
        assertFalse(r.erg.setTarget(200))
        val stale = Rig(); stale.resolve()
        stale.now += 1501; stale.erg.tick()
        assertEquals("Native status data lost", stale.failure)
        assertFalse(stale.erg.state.value.active)
    }

    @Test fun `native enable requires a new enabled echo within grace period`() {
        val r = Rig(); r.resolve()
        repeat(30) {
            r.now += 100; r.native!!.packet(r.now); r.sample(); r.erg.tick()
        }
        assertEquals("Native Auto Follow did not arm", r.failure)
        assertFalse(r.erg.state.value.active)
    }

    @Test fun `cancelling scope disables native and cancels unresolved activation`() {
        val active = Rig(); active.resolve(); active.job.cancel()
        assertFalse(active.erg.state.value.active)
        assertEquals("disable", active.native!!.commands.last())
        assertFalse(active.erg.enable(150, ErgMode.Host))
        val pending = Rig(); pending.erg.enable(150, ErgMode.Auto); pending.erg.tick(); pending.job.cancel()
        pending.now = 100; pending.native!!.packet(pending.now, minRpm = 40); pending.erg.tick()
        assertTrue(pending.native.commands.none { it.startsWith("enable:") })
    }

    @Test fun `native release rejection prevents software activation`() {
        val r = Rig(); r.native!!.accept = false
        assertFalse(r.erg.enable(150, ErgMode.Host))
        assertFalse(r.erg.state.value.active)
        assertTrue(r.writes.isEmpty())
        assertEquals("Could not release native Auto Follow", r.failure)
    }
    @Test fun `native release failure remains visible on repeated disable attempts`() {
        val r = Rig(); r.resolve(); r.native!!.accept = false
        assertFalse(r.erg.disable())
        assertFalse(r.erg.disable())
        assertTrue(r.erg.state.value.suspended)
        r.native.accept = true
        assertTrue(r.erg.disable())
    }

    @Test fun `legacy controller rejecting unsupported commands still permits software fallback`() {
        val r = Rig()
        r.native!!.packet(0, firmware = 1)
        r.native.accept = false
        assertTrue(r.erg.enable(150, ErgMode.Auto))
        r.erg.tick()
        assertEquals(ErgPath.Host, r.erg.state.value.path)
        assertEquals(listOf(45), r.writes)
        assertTrue(r.erg.disable())
    }

    @Test fun `native stale target cannot write before the next tick`() {
        val r = Rig(); r.resolve()
        r.now += 1501
        assertFalse(r.erg.setTarget(200))
        assertFalse(r.erg.state.value.active)
        assertFalse(r.native!!.commands.contains("enable:200"))
    }

    @Test fun `probe waits for previous native loop to release`() {
        val r = Rig()
        r.native!!.packet(0, status = PzafStatus.ENABLED_ACTIVE)
        assertTrue(r.erg.enable(150, ErgMode.Native))
        r.erg.tick()
        assertNull(r.failure)
        assertTrue(r.native.commands.none { it.startsWith("rpm:") })
        r.now = 100; r.native.packet(r.now); r.sample(); r.erg.tick()
        r.now = 200; r.native.packet(r.now, minRpm = 40); r.sample(); r.erg.tick()
        assertEquals(ErgPath.Native, r.erg.state.value.path)
    }

    @Test fun `owner can synchronously disable on failure without recursive reporting`() {
        val native = Native().apply { packet(0); accept = false }
        var reports = 0
        lateinit var erg: ErgCoordinator
        erg = ErgCoordinator(native, { true }, { 0 }, Any(),
            CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)) {
            reports++
            assertFalse(erg.disable())
        }
        assertFalse(erg.enable(150, ErgMode.Host))
        assertEquals(1, reports)
    }

    @Test fun `already cancelled scope cannot accept any activation`() {
        val job = SupervisorJob().apply { cancel() }
        val erg = ErgCoordinator(null, { true }, { 0 }, Any(), CoroutineScope(job), {})
        assertFalse(erg.enable(150, ErgMode.Host))
    }

    @Test fun `read only stop and close never disable another apps native loop`() {
        val r = Rig()
        r.native!!.packet(0, status = PzafStatus.ENABLED_ACTIVE)
        assertTrue(r.erg.disable())
        r.erg.close()
        assertTrue(r.native.commands.isEmpty())
    }

    @Test fun `read only probe cancellation does not acquire native ownership`() {
        val r = Rig()
        r.native!!.packet(0, status = PzafStatus.ENABLED_ACTIVE)
        assertNull(r.erg.preferredPath(ErgMode.Auto))
        r.erg.tick()
        r.erg.close()
        assertTrue(r.native.commands.isEmpty())
    }

    @Test fun `explicit resistance takeover can force native release without prior ownership`() {
        val r = Rig()
        r.native!!.packet(0, status = PzafStatus.ENABLED_ACTIVE)
        assertTrue(r.erg.disable(forceNative = true))
        assertEquals(listOf("disable"), r.native.commands)
        r.erg.close()
        assertEquals(listOf("disable"), r.native.commands)
    }

    @Test fun `failed forced release stays pending across ordinary stop and close`() {
        val r = Rig()
        r.native!!.accept = false
        assertFalse(r.erg.disable(forceNative = true))
        val failedAttempts = r.native.commands.size
        assertFalse(r.erg.disable())
        assertTrue(r.native.commands.size > failedAttempts)
        r.native.accept = true
        assertTrue(r.erg.disable())
        val releasedAt = r.native.commands.size
        r.erg.close()
        assertEquals(releasedAt, r.native.commands.size)
    }

    @Test fun `owned native shutdown sends one final disable and then forgets ownership`() {
        val r = Rig(); r.resolve()
        val before = r.native!!.commands.size
        r.erg.close()
        assertEquals(before + 1, r.native.commands.size)
        assertEquals("disable", r.native.commands.last())
        assertTrue(r.erg.disable())
        assertEquals(before + 1, r.native.commands.size)
    }

    @Test fun `target arriving before next tick cannot override a known native knob stop`() {
        val r = Rig(); r.resolve()
        r.now = 200
        r.native!!.packet(r.now, status = PzafStatus.ENABLED_ACTIVE)
        r.erg.tick()
        r.now = 300
        r.native.packet(r.now, status = PzafStatus.DISABLED_BY_KNOB)
        // No coordinator tick here: a remote target races the periodic watcher.
        assertFalse(r.erg.setTarget(200))
        assertFalse(r.native.commands.contains("enable:200"))
        assertFalse(r.erg.state.value.active)
        assertTrue(r.erg.state.value.suspended)
        assertTrue(r.failure!!.contains("knob"))
    }

}
